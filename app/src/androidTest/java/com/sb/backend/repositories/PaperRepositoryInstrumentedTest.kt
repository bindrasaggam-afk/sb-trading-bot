package com.sb.backend.repositories

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sb.backend.database.PaperLegacyCleanup
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.models.OrderSide
import com.sb.backend.models.OrderStatus
import com.sb.backend.models.PaperExecutionKind
import com.sb.backend.trading.PaperSignalCandidate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaperRepositoryInstrumentedTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var helper: SBDatabaseHelper
    private lateinit var repository: PaperRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "paper_repo_test_${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        helper = SBDatabaseHelper(context, databaseName)
        repository = PaperRepository(helper)
        repository.ensureAccount(100_000.0)
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun pendingReservationFillAndExitReconcileAccount() {
        val saved = repository.saveSignalOrderDetailed(
            candidate(signalId = 1L),
            quantity = 10
        )

        assertTrue(saved.inserted)
        assertEquals(1_000.40004, saved.reservedCapital, 0.00001)
        var account = repository.getAccount()
        assertNotNull(account)
        assertEquals(1_000.40004, account!!.reservedMargin, 0.00001)
        assertEquals(98_999.59996, account.availableBalance, 0.00001)
        assertEquals(OrderStatus.PENDING.name, saved.order.status)

        val filled = repository.fillSignalOrder(
            orderId = saved.order.id,
            executionPrice = 100.0,
            executedAt = 2_000L
        )
        assertNotNull(filled)
        assertEquals(OrderStatus.OPEN.name, filled!!.status)
        assertEquals(0.2, filled.entryCharges, 0.000001)

        account = repository.getAccount()
        assertEquals(98_999.8, account!!.balance, 0.000001)
        assertEquals(0.0, account.reservedMargin, 0.000001)
        assertEquals(-0.2, account.realizedPnl, 0.000001)

        val entry = repository.getExecutions(saved.order.id)
            .first { it.kind == PaperExecutionKind.ENTRY }
        assertEquals(0.0, entry.netPnl, 0.000001)
        assertEquals(0.2, entry.charges, 0.000001)

        val exited = repository.exitSignalOrder(
            orderId = saved.order.id,
            kind = PaperExecutionKind.TARGET_1,
            quantity = 10,
            executionPrice = 105.0,
            executedAt = 3_000L
        )
        assertNotNull(exited)
        assertEquals(OrderStatus.CLOSED.name, exited!!.status)

        account = repository.getAccount()
        assertEquals(100_049.59, account!!.balance, 0.000001)
        assertEquals(0.0, account.reservedMargin, 0.000001)
        assertEquals(49.59, account.realizedPnl, 0.000001)

        val netExecutions = repository.getExecutions(saved.order.id)
            .sumOf { it.netPnl }
        assertEquals(49.59, netExecutions, 0.000001)
    }

    @Test
    fun multiplePendingOrdersReserveCapitalAndCancelReleasesIt() {
        val first = repository.saveSignalOrderDetailed(
            candidate(signalId = 10L),
            quantity = 10
        )
        val second = repository.saveSignalOrderDetailed(
            candidate(signalId = 11L),
            quantity = 10
        )

        var account = repository.getAccount()!!
        assertEquals(2_000.80008, account.reservedMargin, 0.00001)
        assertEquals(97_999.19992, account.availableBalance, 0.00001)

        repository.cancelSignalOrder(first.order.id, "cancelled in test")
        account = repository.getAccount()!!
        assertEquals(1_000.40004, account.reservedMargin, 0.00001)
        assertEquals(98_999.59996, account.availableBalance, 0.00001)
        assertEquals(
            OrderStatus.CANCELLED.name,
            repository.getOrder(first.order.id)!!.status
        )
        assertEquals(
            OrderStatus.PENDING.name,
            repository.getOrder(second.order.id)!!.status
        )
    }

    @Test
    fun sellEntryDebitsChargeAndKeepsShortMarginReserved() {
        val saved = repository.saveSignalOrderDetailed(
            candidate(
                signalId = 20L,
                side = OrderSide.SELL.name,
                stopLoss = 105.0,
                target1 = 95.0,
                target2 = 90.0,
                target3 = 85.0
            ),
            quantity = 10
        )
        assertEquals(200.15996, saved.reservedCapital, 0.00001)

        repository.fillSignalOrder(
            orderId = saved.order.id,
            executionPrice = 100.0,
            executedAt = 2_000L
        )

        val account = repository.getAccount()!!
        assertEquals(99_999.8, account.balance, 0.000001)
        assertEquals(200.0, account.reservedMargin, 0.000001)
        assertEquals(-0.2, account.realizedPnl, 0.000001)
    }

    @Test
    fun cleanupDeletesOnlyTestGeneratedRowsAndPreservesSignals() {
        val fake = repository.saveOrder(
            symbol = "FAKE",
            exchange = "NSE",
            side = OrderSide.BUY.name,
            quantity = 10,
            entryPrice = 100.0
        )
        val signal = repository.saveSignalOrderDetailed(
            candidate(signalId = 30L),
            quantity = 10
        ).order

        val result = PaperLegacyCleanup.cleanup(
            context = context,
            databaseHelper = helper,
            now = 5_000L
        )

        assertTrue(result.applied)
        assertEquals(1, result.deletedOrders)
        assertEquals(1, result.preservedOrders)
        assertNull(repository.getOrder(fake.id))
        assertNotNull(repository.getOrder(signal.id))
        assertEquals(
            OrderStatus.PENDING.name,
            repository.getOrder(signal.id)!!.status
        )

        val account = repository.getAccount()!!
        assertEquals(100_000.0, account.balance, 0.000001)
        assertEquals(1_000.40004, account.reservedMargin, 0.00001)
        assertEquals(0.0, account.realizedPnl, 0.000001)
    }

    private fun candidate(
        signalId: Long,
        side: String = OrderSide.BUY.name,
        stopLoss: Double = 95.0,
        target1: Double = 105.0,
        target2: Double = 110.0,
        target3: Double = 115.0
    ): PaperSignalCandidate {
        return PaperSignalCandidate(
            signalId = signalId,
            symbol = "TEST",
            exchange = "NSE",
            side = side,
            category = "INTRADAY",
            timeframe = "5m",
            strategyId = "test",
            strategyVersion = "1",
            referencePrice = 100.0,
            entryLow = 99.0,
            entryHigh = 101.0,
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            target3 = target3,
            generatedAt = 1_000L,
            expiresAt = 10_000_000L,
            costBpsPerSide = 2.0,
            slippageBpsPerSide = 2.0
        )
    }
}

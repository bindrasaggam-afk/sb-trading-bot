package com.sb.backend.trading

import com.sb.backend.models.OrderSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperSignalPolicyTest {

    @Test
    fun `sizing respects risk allocation and maximum quantity`() {
        val candidate = candidate()
        val rules = PaperSizingRules(
            riskPerTradePercent = 1.0,
            allocationPercent = 20.0,
            shortMarginRate = 1.0,
            maxQuantity = 100
        )

        assertEquals(
            100,
            PaperSignalPolicy.initialQuantity(
                candidate = candidate,
                availableBalance = 100_000.0,
                rules = rules
            )
        )
    }

    @Test
    fun `sizing uses actual expected entry price and entry costs`() {
        val candidate = candidate()
        val rules = PaperSizingRules(
            riskPerTradePercent = 1.0,
            allocationPercent = 100.0,
            shortMarginRate = 1.0,
            maxQuantity = 1_000,
            costBpsPerSide = 2.0
        )

        assertEquals(
            200,
            PaperSignalPolicy.initialQuantity(
                candidate = candidate,
                availableBalance = 100_000.0,
                rules = rules,
                expectedEntryPrice = 100.0
            )
        )
        assertEquals(
            66,
            PaperSignalPolicy.initialQuantity(
                candidate = candidate,
                availableBalance = 100_000.0,
                rules = rules,
                expectedEntryPrice = 110.0
            )
        )
    }

    @Test
    fun `expected entry price clamps reference into signal range`() {
        val candidate = candidate()

        assertEquals(
            99.0,
            PaperSignalPolicy.expectedEntryPrice(
                candidate.copy(referencePrice = 90.0)
            ),
            0.000001
        )
        assertEquals(
            101.0,
            PaperSignalPolicy.expectedEntryPrice(
                candidate.copy(referencePrice = 110.0)
            ),
            0.000001
        )
        assertEquals(
            100.0,
            PaperSignalPolicy.expectedEntryPrice(candidate),
            0.000001
        )
    }

    @Test
    fun `expected entry price applies adverse slippage within range`() {
        val candidate = candidate().copy(slippageBpsPerSide = 2.0)

        assertEquals(
            100.02,
            PaperSignalPolicy.expectedEntryPrice(
                candidate = candidate,
                applySlippage = true
            ),
            0.000001
        )
        assertEquals(
            101.0,
            PaperSignalPolicy.expectedEntryPrice(
                candidate = candidate.copy(referencePrice = 110.0),
                applySlippage = true
            ),
            0.000001
        )
    }

    @Test
    fun `capital requirement separates cash margin and entry charge`() {
        val buy = PaperSignalPolicy.capitalRequirement(
            side = OrderSide.BUY.name,
            price = 100.0,
            quantity = 10,
            costBpsPerSide = 2.0,
            shortMarginRate = 0.2
        )
        assertEquals(1_000.0, buy.cashDebit, 0.000001)
        assertEquals(0.0, buy.reservedMargin, 0.000001)
        assertEquals(0.2, buy.entryCharge, 0.000001)
        assertEquals(1_000.2, buy.totalReservation, 0.000001)

        val sell = PaperSignalPolicy.capitalRequirement(
            side = OrderSide.SELL.name,
            price = 100.0,
            quantity = 10,
            costBpsPerSide = 2.0,
            shortMarginRate = 0.2
        )
        assertEquals(0.0, sell.cashDebit, 0.000001)
        assertEquals(200.0, sell.reservedMargin, 0.000001)
        assertEquals(0.2, sell.entryCharge, 0.000001)
        assertEquals(200.2, sell.totalReservation, 0.000001)
    }

    @Test
    fun `sizing rejects invalid balance and price`() {
        val candidate = candidate()
        val rules = PaperSizingRules(1.0, 20.0, 1.0, 100)

        assertEquals(
            0,
            PaperSignalPolicy.initialQuantity(candidate, 0.0, rules)
        )
        assertEquals(
            0,
            PaperSignalPolicy.initialQuantity(
                candidate.copy(referencePrice = 0.0),
                100_000.0,
                rules
            )
        )
    }

    @Test
    fun `entry waits outside range and fills inside range`() {
        val candidate = candidate()
        val now = 1_000L

        val waiting = PaperSignalPolicy.entryDecision(
            candidate,
            quotePrice = 102.0,
            quoteFresh = true,
            now = now
        )
        assertEquals(PaperEntryDecisionType.WAIT, waiting.type)

        val fill = PaperSignalPolicy.entryDecision(
            candidate,
            quotePrice = 100.0,
            quoteFresh = true,
            now = now
        )
        assertEquals(PaperEntryDecisionType.FILL, fill.type)
        assertEquals(100.1, fill.price ?: 0.0, 0.000001)
    }

    @Test
    fun `entry cancels on expiry stop or target before fill`() {
        val candidate = candidate()

        val expired = PaperSignalPolicy.entryDecision(
            candidate,
            quotePrice = 100.0,
            quoteFresh = true,
            now = candidate.expiresAt
        )
        assertEquals(PaperEntryDecisionType.CANCEL, expired.type)

        val stop = PaperSignalPolicy.entryDecision(
            candidate,
            quotePrice = 95.0,
            quoteFresh = true,
            now = 1_000L
        )
        assertEquals(PaperEntryDecisionType.CANCEL, stop.type)

        val target = PaperSignalPolicy.entryDecision(
            candidate,
            quotePrice = 105.0,
            quoteFresh = true,
            now = 1_000L
        )
        assertEquals(PaperEntryDecisionType.CANCEL, target.type)
    }

    @Test
    fun `entry waits for stale or missing quotes`() {
        val candidate = candidate()

        assertEquals(
            PaperEntryDecisionType.WAIT,
            PaperSignalPolicy.entryDecision(
                candidate,
                quotePrice = 100.0,
                quoteFresh = false,
                now = 1_000L
            ).type
        )
        assertEquals(
            PaperEntryDecisionType.WAIT,
            PaperSignalPolicy.entryDecision(
                candidate,
                quotePrice = Double.NaN,
                quoteFresh = true,
                now = 1_000L
            ).type
        )
    }

    @Test
    fun `entry rejects non finite simulated fill after slippage`() {
        val extremeCandidate = candidate().copy(
            entryLow = 1.0,
            entryHigh = Double.MAX_VALUE,
            referencePrice = Double.MAX_VALUE,
            stopLoss = 0.5,
            target1 = Double.MAX_VALUE,
            target2 = Double.MAX_VALUE,
            target3 = Double.MAX_VALUE,
            slippageBpsPerSide = 9_999.0
        )

        val decision = PaperSignalPolicy.entryDecision(
            candidate = extremeCandidate,
            quotePrice = Double.MAX_VALUE,
            quoteFresh = true,
            now = 1_000L
        )

        assertEquals(PaperEntryDecisionType.WAIT, decision.type)
        assertEquals(null, decision.price)
    }

    @Test
    fun `partial quantities split forty thirty thirty`() {
        assertEquals(Triple(1, 0, 0), PaperSignalPolicy.partialQuantities(1))
        assertEquals(Triple(1, 0, 1), PaperSignalPolicy.partialQuantities(2))
        assertEquals(Triple(1, 1, 1), PaperSignalPolicy.partialQuantities(3))
        assertEquals(Triple(4, 3, 3), PaperSignalPolicy.partialQuantities(10))
    }

    @Test
    fun `pnl and charges use side and allocated entry charge`() {
        assertEquals(
            100.0,
            PaperSignalPolicy.grossPnl(
                OrderSide.BUY.name, 100.0, 110.0, 10
            ),
            0.000001
        )
        assertEquals(
            100.0,
            PaperSignalPolicy.grossPnl(
                OrderSide.SELL.name, 100.0, 90.0, 10
            ),
            0.000001
        )
        assertEquals(
            0.2,
            PaperSignalPolicy.entryCharge(100.0, 10, 2.0),
            0.000001
        )
        assertEquals(
            0.1,
            PaperSignalPolicy.allocatedEntryCharge(0.2, 10, 5),
            0.000001
        )
    }

    @Test
    fun `stop and market execution apply adverse slippage`() {
        assertEquals(
            94.0 * 0.998,
            PaperSignalPolicy.stopExecutionPrice(
                OrderSide.BUY.name,
                quotePrice = 94.0,
                stopLoss = 95.0,
                slippageBps = 20.0
            ),
            0.000001
        )
        assertEquals(
            106.0 * 1.002,
            PaperSignalPolicy.stopExecutionPrice(
                OrderSide.SELL.name,
                quotePrice = 106.0,
                stopLoss = 105.0,
                slippageBps = 20.0
            ),
            0.000001
        )
        assertTrue(
            PaperSignalPolicy.isTargetHit(
                OrderSide.BUY.name, 105.0, 105.0
            )
        )
    }

    private fun candidate(): PaperSignalCandidate {
        return PaperSignalCandidate(
            signalId = 7L,
            symbol = "RELIANCE",
            exchange = "NSE",
            side = OrderSide.BUY.name,
            category = "INTRADAY",
            timeframe = "5m",
            strategyId = "trend",
            strategyVersion = "1",
            referencePrice = 100.0,
            entryLow = 99.0,
            entryHigh = 101.0,
            stopLoss = 95.0,
            target1 = 105.0,
            target2 = 110.0,
            target3 = 115.0,
            generatedAt = 0L,
            expiresAt = 10_000L,
            costBpsPerSide = 2.0,
            slippageBpsPerSide = 10.0
        )
    }
}

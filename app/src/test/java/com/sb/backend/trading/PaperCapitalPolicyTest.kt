package com.sb.backend.trading

import com.sb.backend.models.OrderSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperCapitalPolicyTest {

    @Test
    fun `pending orders reserve capital cumulatively`() {
        var state = PaperCapitalState(
            balance = 100_000.0,
            reservedMargin = 0.0,
            realizedPnl = 0.0
        )

        state = PaperCapitalPolicy.reserve(state, 20_000.0)
        state = PaperCapitalPolicy.reserve(state, 20_000.0)

        assertEquals(40_000.0, state.reservedMargin, 0.000001)
        assertEquals(60_000.0, state.availableBalance, 0.000001)

        var rejected = false
        try {
            PaperCapitalPolicy.reserve(state, 70_000.0)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Reservation beyond available capital must fail", rejected)
    }

    @Test
    fun `cancel releases exactly the pending reservation`() {
        val state = PaperCapitalState(
            balance = 100_000.0,
            reservedMargin = 20_000.0,
            realizedPnl = 0.0
        )

        val released = PaperCapitalPolicy.release(state, 20_000.0)

        assertEquals(0.0, released.reservedMargin, 0.000001)
        assertEquals(100_000.0, released.availableBalance, 0.000001)
    }

    @Test
    fun `buy entry converts reservation into cash and one entry charge`() {
        val state = PaperCapitalState(
            balance = 100_000.0,
            reservedMargin = 20_040.0,
            realizedPnl = 0.0
        )
        val requirement = PaperCapitalRequirement(
            orderValue = 20_000.0,
            cashDebit = 20_000.0,
            reservedMargin = 0.0,
            entryCharge = 40.0
        )

        val next = PaperCapitalPolicy.applyEntry(
            state = state,
            previousReservation = 20_040.0,
            requirement = requirement
        )

        assertEquals(79_960.0, next.balance, 0.000001)
        assertEquals(0.0, next.reservedMargin, 0.000001)
        assertEquals(-40.0, next.realizedPnl, 0.000001)
        assertEquals(79_960.0, next.availableBalance, 0.000001)
    }

    @Test
    fun `actual fill is rejected when reservation cannot cover revised cost`() {
        val state = PaperCapitalState(
            balance = 100_000.0,
            reservedMargin = 20_040.0,
            realizedPnl = 0.0
        )
        val higherActualFill = PaperCapitalRequirement(
            orderValue = 99_000.0,
            cashDebit = 99_000.0,
            reservedMargin = 0.0,
            entryCharge = 1_000.0
        )

        var rejected = false
        try {
            PaperCapitalPolicy.applyEntry(
                state = state,
                previousReservation = 20_040.0,
                requirement = higherActualFill
            )
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(
            "Entry must not overdraw available capital after actual fill",
            rejected
        )
    }

    @Test
    fun `sell entry debits entry charge and keeps short margin reserved`() {
        val state = PaperCapitalState(
            balance = 100_000.0,
            reservedMargin = 4_040.0,
            realizedPnl = 0.0
        )
        val requirement = PaperCapitalRequirement(
            orderValue = 20_000.0,
            cashDebit = 0.0,
            reservedMargin = 4_000.0,
            entryCharge = 40.0
        )

        val next = PaperCapitalPolicy.applyEntry(
            state = state,
            previousReservation = 4_040.0,
            requirement = requirement
        )

        assertEquals(99_960.0, next.balance, 0.000001)
        assertEquals(4_000.0, next.reservedMargin, 0.000001)
        assertEquals(-40.0, next.realizedPnl, 0.000001)
        assertEquals(95_960.0, next.availableBalance, 0.000001)
    }

    @Test
    fun `partial buy exit returns proceeds and releases margin`() {
        val state = PaperCapitalState(
            balance = 79_960.0,
            reservedMargin = 4_000.0,
            realizedPnl = -40.0
        )

        val next = PaperCapitalPolicy.applyExit(
            state = state,
            side = OrderSide.BUY.name,
            exitPrice = 110.0,
            exitQuantity = 5,
            grossPnl = 50.0,
            exitCharge = 0.11,
            releaseReserved = 2_000.0
        )

        assertEquals(80_509.89, next.balance, 0.000001)
        assertEquals(2_000.0, next.reservedMargin, 0.000001)
        assertEquals(9.89, next.realizedPnl, 0.000001)
    }

    @Test
    fun `entry execution has zero net and exit net excludes entry charge once`() {
        assertEquals(
            0.0,
            PaperCapitalPolicy.entryExecutionNetPnl(),
            0.000001
        )
        assertEquals(
            98.5798,
            PaperCapitalPolicy.exitExecutionNetPnl(
                grossPnl = 99.0,
                allocatedEntryCharge = 0.2002,
                exitCharge = 0.22
            ),
            0.000001
        )
    }
}

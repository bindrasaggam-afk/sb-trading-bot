package com.sb.backend.trading

import com.sb.backend.models.OrderSide
import com.sb.backend.models.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperSignalExecutionHardeningTest {

    @Test
    fun `target fills apply adverse slippage for both sides`() {
        assertEquals(
            104.79,
            PaperSignalPolicy.targetExecutionPrice(
                side = OrderSide.BUY.name,
                targetPrice = 105.0,
                slippageBps = 20.0
            ),
            0.000001
        )
        assertEquals(
            94.188,
            PaperSignalPolicy.targetExecutionPrice(
                side = OrderSide.SELL.name,
                targetPrice = 94.0,
                slippageBps = 20.0
            ),
            0.000001
        )
    }

    @Test
    fun `non finite and excessive slippage are safely bounded`() {
        val bounded = PaperSignalPolicy.targetExecutionPrice(
            side = OrderSide.BUY.name,
            targetPrice = 100.0,
            slippageBps = Double.NaN
        )
        assertTrue(bounded.isFinite())
        assertTrue(bounded > 0.0)
        assertEquals(
            0.01,
            bounded,
            0.000001
        )
    }

    @Test
    fun `open position unrealized pnl subtracts estimated round trip charges`() {
        val long = Position(
            symbol = "TEST",
            side = OrderSide.BUY.name,
            quantity = 10,
            entryPrice = 100.0,
            lastPrice = 105.0,
            estimatedCharges = 2.5
        )
        val short = Position(
            symbol = "TEST",
            side = OrderSide.SELL.name,
            quantity = 10,
            entryPrice = 100.0,
            lastPrice = 95.0,
            estimatedCharges = 2.5
        )

        assertEquals(47.5, long.unrealizedPnl, 0.000001)
        assertEquals(47.5, short.unrealizedPnl, 0.000001)
    }
}

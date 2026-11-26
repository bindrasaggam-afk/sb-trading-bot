package com.sb.backend.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KotakEquityChargesTest {

    @Test
    fun `intraday buy excludes sell stt and applies buy stamp duty`() {
        val fees = KotakEquityCharges.calculate(
            side = "BUY",
            category = "INTRADAY",
            price = 1_000.0,
            quantity = 10
        )
        assertEquals(0.01, fees.brokerage, 0.000001)
        assertEquals(0.0, fees.stt, 0.000001)
        assertEquals(0.30, fees.stampDuty, 0.000001)
        assertTrue(fees.gst > 0.0)
        assertTrue(fees.total > fees.brokerage)
    }

    @Test
    fun `intraday sell applies stt and no stamp duty`() {
        val fees = KotakEquityCharges.calculate(
            side = "SELL",
            category = "INTRADAY",
            price = 1_000.0,
            quantity = 10
        )
        assertEquals(2.50, fees.stt, 0.000001)
        assertEquals(0.0, fees.stampDuty, 0.000001)
    }

    @Test
    fun `delivery applies minimum brokerage and both side stt`() {
        val buy = KotakEquityCharges.calculate(
            side = "BUY",
            category = "DELIVERY",
            price = 100.0,
            quantity = 1
        )
        val sell = KotakEquityCharges.calculate(
            side = "SELL",
            category = "DELIVERY",
            price = 100.0,
            quantity = 1
        )
        assertEquals(20.0, buy.brokerage, 0.000001)
        assertEquals(0.10, buy.stt, 0.000001)
        assertEquals(0.10, sell.stt, 0.000001)
        assertEquals(0.015, buy.stampDuty, 0.000001)
        assertEquals(0.0, sell.stampDuty, 0.000001)
    }

    @Test
    fun `delivery sell applies dp minimum and adds dp gst`() {
        val fees = KotakEquityCharges.calculate(
            side = "SELL",
            category = "DELIVERY",
            price = 100.0,
            quantity = 1
        )
        assertEquals(20.0, fees.dpCharge, 0.000001)
        assertEquals(
            (fees.brokerage + fees.exchangeTransaction +
                fees.sebiTurnover + fees.dpCharge) * 0.18,
            fees.gst,
            0.000001
        )
    }

    @Test
    fun `intraday sells do not incur delivery dp fee`() {
        val fees = KotakEquityCharges.calculate(
            side = "SELL",
            category = "INTRADAY",
            price = 100_000.0,
            quantity = 10
        )
        assertEquals(0.0, fees.dpCharge, 0.000001)
    }

    @Test
    fun `BSE cash fee uses published standard nonexclusive rate`() {
        val fees = KotakEquityCharges.calculate(
            side = "BUY",
            category = "DELIVERY",
            price = 10_000.0,
            quantity = 1,
            exchange = "BSE"
        )
        assertEquals(
            0.375,
            fees.exchangeTransaction,
            0.000001
        )
    }

    @Test
    fun `unsupported exchange and invalid side are rejected`() {
        var exchangeRejected = false
        try {
            KotakEquityCharges.calculate("BUY", "INTRADAY", 100.0, 1, "BSE")
        } catch (_: IllegalArgumentException) {
            exchangeRejected = true
        }
        assertTrue(exchangeRejected)

        var sideRejected = false
        try {
            KotakEquityCharges.calculate("HOLD", "INTRADAY", 100.0, 1)
        } catch (_: IllegalArgumentException) {
            sideRejected = true
        }
        assertTrue(sideRejected)
    }
}

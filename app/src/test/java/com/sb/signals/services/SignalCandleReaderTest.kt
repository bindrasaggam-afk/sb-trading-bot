package com.sb.signals.services

import com.sb.backend.market.model.MarketTimeframe
import com.sb.backend.models.Candle
import com.sb.signals.TestSignals
import com.sb.signals.domain.StrategyDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalCandleReaderTest {

    @Test
    fun intradayReaderRequestsConfiguredTimeframes() {
        val variants = StrategyDefaults.intradayVariants()
        val expected = mapOf(
            "1m" to listOf("1m", "5m", "15m"),
            "3m" to listOf("3m", "15m", "1h"),
            "5m" to listOf("5m", "15m", "1h"),
            "10m" to listOf("10m", "30m", "1h"),
            "15m" to listOf("15m", "1h", "4h"),
            "30m" to listOf("30m", "1h", "4h")
        )

        variants.forEach { config ->
            val requested = ArrayList<String>()
            val reader = SignalCandleReader(
                source = fakeSource(requested)
            )
            val series = reader.read(
                symbol = "RELIANCE",
                exchange = "NSE",
                category = config.category,
                config = config,
                now = TestSignals.testNow
            )

            assertEquals(
                expected.getValue(config.primaryTimeframe),
                requested
            )
            assertTrue(
                "${config.primaryTimeframe} series unusable",
                series.quality.isUsable
            )
            assertTrue(
                series.primary.closed.size >= config.minPrimaryCandles
            )
        }
    }

    private fun fakeSource(
        requested: MutableList<String>
    ): MarketHistorySource {
        return object : MarketHistorySource {
            override fun candles(
                symbol: String,
                exchange: String,
                timeframe: String,
                limit: Int
            ): List<Candle> {
                requested.add(timeframe)
                val frame = MarketTimeframe.fromLabel(timeframe)
                    ?: return emptyList()
                val interval = frame.seconds * 1_000L
                val count = limit
                val start = TestSignals.testNow - count * interval
                return TestSignals.backendCandles(
                    TestSignals.candles(
                        count = count,
                        start = start,
                        intervalMillis = interval
                    )
                )
            }
        }
    }
}

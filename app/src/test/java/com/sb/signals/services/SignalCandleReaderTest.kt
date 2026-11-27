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
        val expected = listOf("5m", "15m", "1h")

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
                expected,
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

    @Test
    fun swingAndDeliveryUseDistinctHigherTrendTimeframes() {
        val configs = listOf(
            StrategyDefaults.swing(),
            StrategyDefaults.delivery()
        )
        configs.forEach { config ->
            val requested = ArrayList<String>()
            val reader = SignalCandleReader(source = fakeSource(requested))
            val series = reader.read(
                symbol = "RELIANCE",
                exchange = "NSE",
                category = config.category,
                config = config,
                now = TestSignals.testNow
            )
            assertEquals(
                listOf(config.primaryTimeframe, config.confirmationTimeframe, config.trendTimeframe),
                requested
            )
            assertTrue(series.primary.quality.isUsable)
            assertTrue(series.confirmation.quality.isUsable)
            assertTrue(series.trend.quality.isUsable)
            assertTrue(series.trend.closed.size >= config.minTrendCandles)
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
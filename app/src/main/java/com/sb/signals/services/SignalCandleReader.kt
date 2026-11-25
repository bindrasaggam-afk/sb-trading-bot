package com.sb.signals.services

import com.sb.backend.core.BackendManager
import com.sb.backend.models.Candle
import com.sb.backend.repositories.MarketRepository
import com.sb.signals.domain.SignalCategory
import com.sb.signals.domain.StrategyConfig
import com.sb.signals.domain.market.CandleSeriesTools
import com.sb.signals.domain.market.PreparedSeries
import com.sb.signals.domain.strategies.SignalQualityCombiner

interface MarketHistorySource {
    fun candles(
        symbol: String,
        exchange: String,
        timeframe: String,
        limit: Int
    ): List<Candle>
}

class BackendMarketHistorySource : MarketHistorySource {
    override fun candles(
        symbol: String,
        exchange: String,
        timeframe: String,
        limit: Int
    ): List<Candle> {
        if (!BackendManager.isInitialized()) return emptyList()
        return try {
            BackendManager.market().getCandles(
                symbol = symbol,
                timeframe = timeframe,
                exchange = exchange,
                limit = limit
            )
        } catch (_: Exception) {
            emptyList()
        }
    }
}

data class CategoryCandleSeries(
    val primary: PreparedSeries,
    val confirmation: PreparedSeries,
    val trend: PreparedSeries,
    val quality: com.sb.signals.domain.DataQuality
)

class SignalCandleReader(
    private val source: MarketHistorySource = BackendMarketHistorySource()
) {

    fun futureCandles(
        symbol: String,
        exchange: String,
        timeframe: String,
        fromTimestamp: Long,
        toTimestamp: Long
    ): List<com.sb.backend.market.model.MarketCandle> {
        return source.candles(symbol, exchange, timeframe, 5000)
            .asSequence()
            .filter {
                it.timestamp > fromTimestamp &&
                    it.timestamp <= toTimestamp
            }
            .mapNotNull { CandleSeriesTools.toMarketCandle(it) }
            .sortedBy { it.timestamp }
            .toList()
    }

    fun read(
        symbol: String,
        exchange: String,
        category: SignalCategory,
        config: StrategyConfig,
        now: Long
    ): CategoryCandleSeries {
        return when (category) {
            SignalCategory.INTRADAY -> intraday(
                symbol,
                exchange,
                config,
                now
            )
            SignalCategory.SWING -> swing(symbol, exchange, config, now)
            SignalCategory.DELIVERY -> delivery(
                symbol,
                exchange,
                config,
                now
            )
        }
    }

    private fun intraday(
        symbol: String,
        exchange: String,
        config: StrategyConfig,
        now: Long
    ): CategoryCandleSeries {
        val primaryRaw = source.candles(
            symbol,
            exchange,
            config.primaryTimeframe,
            500
        )
        val primary = CandleSeriesTools.prepare(
            primaryRaw,
            config.primaryTimeframe,
            exchange,
            now,
            config.minPrimaryCandles
        )

        val confirmationRaw = source.candles(
            symbol,
            exchange,
            config.confirmationTimeframe,
            400
        )
        val confirmation = if (confirmationRaw.size >=
            config.minConfirmationCandles
        ) {
            CandleSeriesTools.prepare(
                confirmationRaw,
                config.confirmationTimeframe,
                exchange,
                now,
                config.minConfirmationCandles
            )
        } else {
            val aggregated = CandleSeriesTools.aggregate(
                primary.all,
                config.confirmationTimeframe,
                exchange
            )
            CandleSeriesTools.prepareMarket(
                aggregated,
                config.confirmationTimeframe,
                exchange,
                now,
                config.minConfirmationCandles
            )
        }

        val trendRaw = source.candles(
            symbol,
            exchange,
            config.trendTimeframe,
            300
        )
        val trend = if (trendRaw.size >= config.minTrendCandles) {
            CandleSeriesTools.prepare(
                trendRaw,
                config.trendTimeframe,
                exchange,
                now,
                config.minTrendCandles
            )
        } else {
            val aggregated = CandleSeriesTools.aggregate(
                confirmation.all,
                config.trendTimeframe,
                exchange
            )
            CandleSeriesTools.prepareMarket(
                aggregated,
                config.trendTimeframe,
                exchange,
                now,
                config.minTrendCandles
            )
        }

        return CategoryCandleSeries(
            primary,
            confirmation,
            trend,
            SignalQualityCombiner.combine(
                primary.quality,
                confirmation.quality,
                trend.quality
            )
        )
    }

    private fun swing(
        symbol: String,
        exchange: String,
        config: StrategyConfig,
        now: Long
    ): CategoryCandleSeries {
        val primaryRaw = source.candles(
            symbol,
            exchange,
            config.primaryTimeframe,
            800
        )
        val primary = CandleSeriesTools.prepare(
            primaryRaw,
            config.primaryTimeframe,
            exchange,
            now,
            config.minPrimaryCandles
        )

        val confirmationRaw = source.candles(
            symbol,
            exchange,
            config.confirmationTimeframe,
            300
        )
        val confirmation = if (confirmationRaw.size >=
            config.minConfirmationCandles
        ) {
            CandleSeriesTools.prepare(
                confirmationRaw,
                config.confirmationTimeframe,
                exchange,
                now,
                config.minConfirmationCandles
            )
        } else {
            val aggregated = CandleSeriesTools.aggregate(
                primary.all,
                config.confirmationTimeframe,
                exchange
            )
            CandleSeriesTools.prepareMarket(
                aggregated,
                config.confirmationTimeframe,
                exchange,
                now,
                config.minConfirmationCandles
            )
        }

        val trendRaw = source.candles(
            symbol,
            exchange,
            config.trendTimeframe,
            120
        )
        val trend = if (trendRaw.size >= config.minTrendCandles) {
            CandleSeriesTools.prepare(
                trendRaw,
                config.trendTimeframe,
                exchange,
                now,
                config.minTrendCandles
            )
        } else {
            val aggregated = CandleSeriesTools.aggregate(
                confirmation.all,
                config.trendTimeframe,
                exchange
            )
            CandleSeriesTools.prepareMarket(
                aggregated,
                config.trendTimeframe,
                exchange,
                now,
                config.minTrendCandles
            )
        }

        return CategoryCandleSeries(
            primary,
            confirmation,
            trend,
            SignalQualityCombiner.combine(
                primary.quality,
                confirmation.quality,
                trend.quality
            )
        )
    }

    private fun delivery(
        symbol: String,
        exchange: String,
        config: StrategyConfig,
        now: Long
    ): CategoryCandleSeries {
        val primaryRaw = source.candles(
            symbol,
            exchange,
            config.primaryTimeframe,
            1200
        )
        val primary = CandleSeriesTools.prepare(
            primaryRaw,
            config.primaryTimeframe,
            exchange,
            now,
            config.minPrimaryCandles
        )

        val confirmationRaw = source.candles(
            symbol,
            exchange,
            config.confirmationTimeframe,
            300
        )
        val confirmation = if (confirmationRaw.size >=
            config.minConfirmationCandles
        ) {
            CandleSeriesTools.prepare(
                confirmationRaw,
                config.confirmationTimeframe,
                exchange,
                now,
                config.minConfirmationCandles
            )
        } else {
            CandleSeriesTools.prepareMarket(
                CandleSeriesTools.aggregate(
                    primary.all,
                    config.confirmationTimeframe,
                    exchange
                ),
                config.confirmationTimeframe,
                exchange,
                now,
                config.minConfirmationCandles
            )
        }

        val trendRaw = source.candles(
            symbol,
            exchange,
            config.trendTimeframe,
            120
        )
        val trend = if (trendRaw.size >= config.minTrendCandles) {
            CandleSeriesTools.prepare(
                trendRaw,
                config.trendTimeframe,
                exchange,
                now,
                config.minTrendCandles
            )
        } else {
            CandleSeriesTools.prepareMarket(
                CandleSeriesTools.aggregate(
                    primary.all,
                    config.trendTimeframe,
                    exchange
                ),
                config.trendTimeframe,
                exchange,
                now,
                config.minTrendCandles
            )
        }

        return CategoryCandleSeries(
            primary,
            confirmation,
            trend,
            SignalQualityCombiner.combine(
                primary.quality,
                confirmation.quality,
                trend.quality
            )
        )
    }
}
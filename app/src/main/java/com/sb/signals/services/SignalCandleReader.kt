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
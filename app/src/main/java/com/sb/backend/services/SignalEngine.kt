package com.sb.backend.services

import com.sb.backend.core.AppConfig
import com.sb.backend.models.SignalStatus
import com.sb.backend.models.SignalType
import com.sb.backend.models.TradingSignal
import com.sb.backend.models.TradingStyle
import com.sb.backend.repositories.MarketRepository
import com.sb.backend.repositories.SignalRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class SignalEngine(
    private val marketRepository: MarketRepository,
    private val signalRepository: SignalRepository
) {

    companion object {
        private const val MIN_CANDLES = 200

        private const val PRIMARY_TIMEFRAME = "5M"
        private const val CONFIRMATION_TIMEFRAME = "15M"
        private const val TREND_TIMEFRAME = "1H"

        private const val ATR_MULTIPLIER = 1.5
        private const val MIN_SIGNAL_SCORE = 65.0
        private const val MIN_SCORE_DIFFERENCE = 10.0

        private const val VOLUME_PERIOD = 20
        private const val VOLUME_MULTIPLIER = 1.10
    }

    fun generate(
        symbol: String,
        exchange: String = AppConfig.get().defaultExchange,
        timeframe: String = AppConfig.get().defaultTimeframe,
        persist: Boolean = true
    ): TradingSignal? {

        val normalizedSymbol =
            symbol.trim().toUpperCase(Locale.ROOT)

        val normalizedExchange =
            exchange.trim().toUpperCase(Locale.ROOT)

        val normalizedTimeframe =
            timeframe.trim().toUpperCase(Locale.ROOT)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank()
        ) return null

        if (normalizedTimeframe != PRIMARY_TIMEFRAME) {
            return null
        }

        // --------------------------------------------------------
        // 1. LOAD 5M PRIMARY CANDLES
        // --------------------------------------------------------

        val candles5m = marketRepository.getCandles(
            symbol = normalizedSymbol,
            timeframe = PRIMARY_TIMEFRAME,
            exchange = normalizedExchange,
            limit = 300
        )

        if (candles5m.size < MIN_CANDLES) return null

        // --------------------------------------------------------
        // 2. LOAD 15M CONFIRMATION CANDLES
        // --------------------------------------------------------

        val candles15m = marketRepository.getCandles(
            symbol = normalizedSymbol,
            timeframe = CONFIRMATION_TIMEFRAME,
            exchange = normalizedExchange,
            limit = 300
        )

        if (candles15m.size < MIN_CANDLES) return null

        // --------------------------------------------------------
        // 3. LOAD 1H TREND CANDLES
        // --------------------------------------------------------

        val candles1h = marketRepository.getCandles(
            symbol = normalizedSymbol,
            timeframe = TREND_TIMEFRAME,
            exchange = normalizedExchange,
            limit = 300
        )

        if (candles1h.size < MIN_CANDLES) return null

        // --------------------------------------------------------
        // 4. 5M INDICATORS
        // --------------------------------------------------------

        val closes5m = candles5m.map { it.close }

        val ema50_5m =
            IndicatorService.ema(closes5m, 50)

        val ema200_5m =
            IndicatorService.ema(closes5m, 200)

        val rsi5m =
            IndicatorService.rsi(closes5m, 14)

        val stoch5m =
            IndicatorService.stochRsi(
                values = closes5m,
                rsiPeriod = 14,
                stochPeriod = 14,
                kPeriod = 3,
                dPeriod = 3
            )

        val macd5m =
            IndicatorService.macd(closes5m)

        val supertrend5m =
            IndicatorService.supertrend(
                candles = candles5m,
                period = 10,
                multiplier = 3.0
            )

        val atr5m =
            IndicatorService.atr(candles5m, 14)

        val vwap5m =
            IndicatorService.vwap(candles5m)

        val heikinAshi5m =
            IndicatorService.heikinAshi(candles5m)

        val lastIndex5m = candles5m.lastIndex
        val lastCandle = candles5m[lastIndex5m]

        val price = lastCandle.close

        val ema50 =
            ema50_5m[lastIndex5m] ?: return null

        val ema200 =
            ema200_5m[lastIndex5m] ?: return null

        val rsi =
            rsi5m[lastIndex5m] ?: return null

        val stochK =
            stoch5m.k[lastIndex5m] ?: return null

        val stochD =
            stoch5m.d[lastIndex5m] ?: return null

        val macdHistogram =
            macd5m.histogram[lastIndex5m] ?: return null

        val supertrendDirection =
            supertrend5m.direction[lastIndex5m]

        val atr =
            atr5m[lastIndex5m] ?: return null

        val vwap =
            vwap5m[lastIndex5m] ?: return null

        val ha =
            heikinAshi5m[lastIndex5m]

        if (!price.isFinite() ||
            price <= 0.0 ||
            !atr.isFinite() ||
            atr <= 0.0 ||
            !vwap.isFinite()
        ) return null

        // --------------------------------------------------------
        // 5. 15M CONFIRMATION
        // --------------------------------------------------------

        val closes15m = candles15m.map { it.close }

        val ema50_15m =
            IndicatorService.ema(closes15m, 50)

        val ema200_15m =
            IndicatorService.ema(closes15m, 200)

        val macd15m =
            IndicatorService.macd(closes15m)

        val supertrend15m =
            IndicatorService.supertrend(
                candles = candles15m,
                period = 10,
                multiplier = 3.0
            )

        val lastIndex15m = candles15m.lastIndex

        val trend50_15m =
            ema50_15m[lastIndex15m] ?: return null

        val trend200_15m =
            ema200_15m[lastIndex15m] ?: return null

        val macdHistogram15m =
            macd15m.histogram[lastIndex15m] ?: return null

        val direction15m =
            supertrend15m.direction[lastIndex15m]

        val confirmed15mBuy =
            trend50_15m > trend200_15m &&
                macdHistogram15m > 0.0 &&
                direction15m == 1

        val confirmed15mSell =
            trend50_15m < trend200_15m &&
                macdHistogram15m < 0.0 &&
                direction15m == -1

        // --------------------------------------------------------
        // 6. 1H TREND CONFIRMATION
        // --------------------------------------------------------

        val closes1h = candles1h.map { it.close }

        val ema50_1h =
            IndicatorService.ema(closes1h, 50)

        val ema200_1h =
            IndicatorService.ema(closes1h, 200)

        val macd1h =
            IndicatorService.macd(closes1h)

        val supertrend1h =
            IndicatorService.supertrend(
                candles = candles1h,
                period = 10,
                multiplier = 3.0
            )

        val lastIndex1h = candles1h.lastIndex

        val trend50_1h =
            ema50_1h[lastIndex1h] ?: return null

        val trend200_1h =
            ema200_1h[lastIndex1h] ?: return null

        val macdHistogram1h =
            macd1h.histogram[lastIndex1h] ?: return null

        val direction1h =
            supertrend1h.direction[lastIndex1h]

        val confirmed1hBuy =
            trend50_1h > trend200_1h &&
                macdHistogram1h > 0.0 &&
                direction1h == 1

        val confirmed1hSell =
            trend50_1h < trend200_1h &&
                macdHistogram1h < 0.0 &&
                direction1h == -1

        // --------------------------------------------------------
        // 7. VOLUME CONFIRMATION
        // --------------------------------------------------------

        val volumeAverage = calculateAverageVolume(
            candles5m,
            VOLUME_PERIOD
        )

        val currentVolume =
            lastCandle.volume.toDouble()

        val volumeConfirmed =
            volumeAverage > 0.0 &&
                currentVolume >=
                volumeAverage * VOLUME_MULTIPLIER

        // --------------------------------------------------------
        // 8. SUPPORT / RESISTANCE
        // --------------------------------------------------------

        val previousCandles =
            candles5m.dropLast(1)

        val supportResistance =
            IndicatorService.supportResistance(
                candles = previousCandles,
                lookback = 50
            )

        val support =
            supportResistance?.first

        val resistance =
            supportResistance?.second

        // --------------------------------------------------------
        // 9. PRICE ACTION
        // --------------------------------------------------------

        val previousCandle =
            candles5m[candles5m.lastIndex - 1]

        val bullishEngulfing =
            previousCandle.close < previousCandle.open &&
                lastCandle.close > lastCandle.open &&
                lastCandle.open <= previousCandle.close &&
                lastCandle.close >= previousCandle.open

        val bearishEngulfing =
            previousCandle.close > previousCandle.open &&
                lastCandle.close < lastCandle.open &&
                lastCandle.open >= previousCandle.close &&
                lastCandle.close <= previousCandle.open

        val bullishBreakout =
            resistance != null &&
                price > resistance

        val bearishBreakdown =
            support != null &&
                price < support

        val bullishPriceAction =
            bullishEngulfing || bullishBreakout

        val bearishPriceAction =
            bearishEngulfing || bearishBreakdown

        // --------------------------------------------------------
        // 10. DIRECTIONAL INDICATOR CONDITIONS
        // --------------------------------------------------------

        val bullishTrend =
            ema50 > ema200

        val bearishTrend =
            ema50 < ema200

        val bullishRsi =
            rsi >= 50.0 && rsi <= 70.0

        val bearishRsi =
            rsi >= 30.0 && rsi <= 50.0

        val bullishStoch =
            stochK > stochD &&
                stochK < 85.0

        val bearishStoch =
            stochK < stochD &&
                stochK > 15.0

        val bullishMacd =
            macdHistogram > 0.0

        val bearishMacd =
            macdHistogram < 0.0

        val bullishSupertrend =
            supertrendDirection == 1

        val bearishSupertrend =
            supertrendDirection == -1

        val bullishVwap =
            price > vwap

        val bearishVwap =
            price < vwap

        val bullishHeikinAshi =
            ha.close > ha.open

        val bearishHeikinAshi =
            ha.close < ha.open

        // --------------------------------------------------------
        // 11. WEIGHTED BUY SCORE
        // --------------------------------------------------------

        val buyScore = calculateScore(
            trend = bullishTrend,
            rsi = bullishRsi,
            stoch = bullishStoch,
            macd = bullishMacd,
            supertrend = bullishSupertrend,
            vwap = bullishVwap,
            heikinAshi = bullishHeikinAshi,
            volume = volumeConfirmed,
            priceAction = bullishPriceAction,
            confirmation15m = confirmed15mBuy,
            confirmation1h = confirmed1hBuy
        )

        // --------------------------------------------------------
        // 12. WEIGHTED SELL SCORE
        // --------------------------------------------------------

        val sellScore = calculateScore(
            trend = bearishTrend,
            rsi = bearishRsi,
            stoch = bearishStoch,
            macd = bearishMacd,
            supertrend = bearishSupertrend,
            vwap = bearishVwap,
            heikinAshi = bearishHeikinAshi,
            volume = volumeConfirmed,
            priceAction = bearishPriceAction,
            confirmation15m = confirmed15mSell,
            confirmation1h = confirmed1hSell
        )

        // --------------------------------------------------------
        // 13. FINAL SIGNAL DECISION
        // --------------------------------------------------------

        val candidate = when {
            buyScore >= MIN_SIGNAL_SCORE &&
                buyScore - sellScore >= MIN_SCORE_DIFFERENCE ->
                SignalType.BUY

            sellScore >= MIN_SIGNAL_SCORE &&
                sellScore - buyScore >= MIN_SCORE_DIFFERENCE ->
                SignalType.SELL

            else -> SignalType.WAIT
        }

        val isConfirmed = when (candidate) {
            SignalType.BUY ->
                confirmed15mBuy && confirmed1hBuy

            SignalType.SELL ->
                confirmed15mSell && confirmed1hSell

            SignalType.WAIT -> false
        }

        val action =
            if (isConfirmed) candidate else SignalType.WAIT

        // --------------------------------------------------------
        // 14. STOP LOSS AND TARGETS
        // --------------------------------------------------------

        val stopLoss: Double?
        val target1: Double?
        val target2: Double?
        val target3: Double?

        when (action) {

            SignalType.BUY -> {
                stopLoss = max(
                    0.01,
                    price - atr * ATR_MULTIPLIER
                )

                target1 = price + atr * 1.5
                target2 = price + atr * 3.0
                target3 = price + atr * 4.5
            }

            SignalType.SELL -> {
                stopLoss = price + atr * ATR_MULTIPLIER

                target1 = max(0.01, price - atr * 1.5)
                target2 = max(0.01, price - atr * 3.0)
                target3 = max(0.01, price - atr * 4.5)
            }

            SignalType.WAIT -> {
                stopLoss = null
                target1 = null
                target2 = null
                target3 = null
            }
        }

        // --------------------------------------------------------
        // 15. CONFIDENCE
        // --------------------------------------------------------

        val confidence = when (action) {
            SignalType.BUY -> buyScore
            SignalType.SELL -> sellScore
            SignalType.WAIT -> 0.0
        }

        // --------------------------------------------------------
        // 16. EXPIRY
        // --------------------------------------------------------

        val now = System.currentTimeMillis()

        val expiryMinutes =
            AppConfig.get().signalExpiryMinutes.coerceAtLeast(1)

        val expiryDuration = max(
            5L * 60_000L,
            expiryMinutes.toLong() * 60_000L
        )

        val expiresAt = now + expiryDuration

        // --------------------------------------------------------
        // 17. REASON
        // --------------------------------------------------------

        val reason = buildString {

            append("5M Indicators: ")

            append("EMA50=")
            append(format(ema50))

            append(", EMA200=")
            append(format(ema200))

            append(", RSI=")
            append(format(rsi))

            append(", Stoch K=")
            append(format(stochK))

            append(", Stoch D=")
            append(format(stochD))

            append(", MACD=")
            append(format(macdHistogram))

            append(", Supertrend=")
            append(supertrendDirection)

            append(", VWAP=")
            append(format(vwap))

            append(", ATR=")
            append(format(atr))

            append("; Volume confirmed=")
            append(volumeConfirmed)

            append("; Bullish engulfing=")
            append(bullishEngulfing)

            append("; Bearish engulfing=")
            append(bearishEngulfing)

            append("; Breakout=")
            append(bullishBreakout)

            append("; Breakdown=")
            append(bearishBreakdown)

            append("; Support=")
            append(support?.let { format(it) } ?: "N/A")

            append("; Resistance=")
            append(resistance?.let { format(it) } ?: "N/A")

            append("; 15M Buy=")
            append(confirmed15mBuy)

            append(", 15M Sell=")
            append(confirmed15mSell)

            append("; 1H Buy=")
            append(confirmed1hBuy)

            append(", 1H Sell=")
            append(confirmed1hSell)

            append("; Buy Score=")
            append(format(buyScore))

            append("; Sell Score=")
            append(format(sellScore))

            append("; Final Confirmation=")
            append(isConfirmed)
        }

        // --------------------------------------------------------
        // 18. CREATE TRADING SIGNAL
        // --------------------------------------------------------

        val tradingSignal = TradingSignal(
            symbol = normalizedSymbol,
            exchange = normalizedExchange,
            tradingStyle = TradingStyle.INTRADAY,

            signal = action,

            entryPrice = if (action == SignalType.WAIT) {
                null
            } else {
                price
            },

            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            target3 = target3,

            confidence = confidence,

            timeframe = PRIMARY_TIMEFRAME,

            status = if (isConfirmed) {
                SignalStatus.CONFIRMED
            } else {
                SignalStatus.ACTIVE
            },

            reason = reason,

            createdAt = now,
            expiresAt = expiresAt,

            confirmationCount = if (isConfirmed) 2 else 0,
            confirmationRequired = 2,

            isStable = isConfirmed
        )

        // --------------------------------------------------------
        // 19. PREVIEW MODE
        // --------------------------------------------------------

        if (!persist) {
            return tradingSignal
        }

        // Do not save WAIT signals.
        if (!isConfirmed) {
            return null
        }

        // --------------------------------------------------------
        // 20. DUPLICATE PROTECTION
        // --------------------------------------------------------

        val duplicateWindow = 5L * 60_000L

        if (signalRepository.hasRecent(
                symbol = normalizedSymbol,
                timeframe = PRIMARY_TIMEFRAME,
                signalType = action,
                sinceTimestamp = now - duplicateWindow
            )
        ) {
            return null
        }

        val id = signalRepository.save(tradingSignal)

        return if (id > 0L) {
            tradingSignal.copy(id = id)
        } else {
            null
        }
    }

    // ------------------------------------------------------------
    // GENERATE FOR MULTIPLE SYMBOLS
    // ------------------------------------------------------------

    fun generateForSymbols(
        symbols: List<String>,
        exchange: String = AppConfig.get().defaultExchange,
        timeframe: String = AppConfig.get().defaultTimeframe
    ): List<TradingSignal> {

        val result = ArrayList<TradingSignal>()

        symbols.forEach { symbol ->

            try {

                val signal = generate(
                    symbol = symbol,
                    exchange = exchange,
                    timeframe = timeframe
                )

                if (signal != null) {
                    result.add(signal)
                }

            } catch (e: Exception) {
                // Continue scanning other symbols.
            }
        }

        return result
    }

    // ------------------------------------------------------------
    // EXPIRE OLD SIGNALS
    // ------------------------------------------------------------

    fun expireOldSignals(): Int {
        return signalRepository.expireOld()
    }

    // ------------------------------------------------------------
    // WEIGHTED CONFIDENCE SCORE
    // Maximum score = 100
    // ------------------------------------------------------------

    private fun calculateScore(
        trend: Boolean,
        rsi: Boolean,
        stoch: Boolean,
        macd: Boolean,
        supertrend: Boolean,
        vwap: Boolean,
        heikinAshi: Boolean,
        volume: Boolean,
        priceAction: Boolean,
        confirmation15m: Boolean,
        confirmation1h: Boolean
    ): Double {

        var score = 0.0

        if (trend) score += 12.0
        if (rsi) score += 8.0
        if (stoch) score += 8.0
        if (macd) score += 10.0
        if (supertrend) score += 10.0
        if (vwap) score += 8.0
        if (heikinAshi) score += 6.0
        if (volume) score += 10.0
        if (priceAction) score += 10.0
        if (confirmation15m) score += 10.0
        if (confirmation1h) score += 8.0

        return score.coerceIn(0.0, 100.0)
    }

    // ------------------------------------------------------------
    // AVERAGE VOLUME
    // Uses candles before the current candle.
    // ------------------------------------------------------------

    private fun calculateAverageVolume(
        candles: List<com.sb.backend.models.Candle>,
        period: Int
    ): Double {

        if (period <= 0 || candles.size <= period) {
            return 0.0
        }

        val endIndex = candles.lastIndex
        val startIndex = endIndex - period

        var totalVolume = 0.0

        for (index in startIndex until endIndex) {

            val volume =
                candles[index].volume.toDouble()

            if (!volume.isFinite() || volume < 0.0) {
                return 0.0
            }

            totalVolume += volume
        }

        return totalVolume / period
    }

    // ------------------------------------------------------------
    // NUMBER FORMAT
    // ------------------------------------------------------------

    private fun format(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }
}

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
        private const val ATR_MULTIPLIER = 1.5
    }

    fun generate(
        symbol: String,
        exchange: String = AppConfig.get().defaultExchange,
        timeframe: String = AppConfig.get().defaultTimeframe,
        persist: Boolean = true
    ): TradingSignal? {

        val normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT)
        val normalizedExchange = exchange.trim().toUpperCase(Locale.ROOT)
        val normalizedTimeframe = timeframe.trim().toUpperCase(Locale.ROOT)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank()
        ) return null

        // This engine uses 5-minute candles as its primary timeframe.
        if (normalizedTimeframe != PRIMARY_TIMEFRAME) {
            return null
        }

        // --------------------------------------------------------
        // 1. LOAD PRIMARY 5-MINUTE CANDLES
        // --------------------------------------------------------

        val candles5m = marketRepository.getCandles(
            symbol = normalizedSymbol,
            timeframe = PRIMARY_TIMEFRAME,
            exchange = normalizedExchange,
            limit = 300
        )

        if (candles5m.size < MIN_CANDLES) return null

        // --------------------------------------------------------
        // 2. LOAD 15-MINUTE CONFIRMATION CANDLES
        // --------------------------------------------------------

        val candles15m = marketRepository.getCandles(
            symbol = normalizedSymbol,
            timeframe = CONFIRMATION_TIMEFRAME,
            exchange = normalizedExchange,
            limit = 300
        )

        // Never declare a signal stable without 15m confirmation.
        if (candles15m.size < MIN_CANDLES) return null

        // --------------------------------------------------------
        // 3. PRIMARY 5-MINUTE INDICATORS
        // --------------------------------------------------------

        val closes5m = candles5m.map { it.close }

        val ema50_5m = IndicatorService.ema(closes5m, 50)
        val ema200_5m = IndicatorService.ema(closes5m, 200)
        val rsi5m = IndicatorService.rsi(closes5m, 14)

        val stoch5m = IndicatorService.stochRsi(
            values = closes5m,
            rsiPeriod = 14,
            stochPeriod = 14,
            kPeriod = 3,
            dPeriod = 3
        )

        val macd5m = IndicatorService.macd(closes5m)
        val supertrend5m = IndicatorService.supertrend(
            candles = candles5m,
            period = 10,
            multiplier = 3.0
        )

        val atr5m = IndicatorService.atr(candles5m, 14)
        val vwap5m = IndicatorService.vwap(candles5m)
        val ha5m = IndicatorService.heikinAshi(candles5m)

        val lastIndex5m = candles5m.lastIndex
        val lastCandle = candles5m[lastIndex5m]
        val price = lastCandle.close

        val ema50 = ema50_5m[lastIndex5m] ?: return null
        val ema200 = ema200_5m[lastIndex5m] ?: return null
        val rsi = rsi5m[lastIndex5m] ?: return null

        val stochK = stoch5m.k[lastIndex5m] ?: return null
        val stochD = stoch5m.d[lastIndex5m] ?: return null

        val macdHistogram =
            macd5m.histogram[lastIndex5m] ?: return null

        val supertrendDirection =
            supertrend5m.direction[lastIndex5m]

        val atr = atr5m[lastIndex5m] ?: return null
        val vwap = vwap5m[lastIndex5m] ?: return null
        val ha = ha5m[lastIndex5m]

        if (!price.isFinite() || price <= 0.0 ||
            !atr.isFinite() || atr <= 0.0 ||
            !vwap.isFinite()
        ) return null

        // --------------------------------------------------------
        // 4. 15-MINUTE CONFIRMATION INDICATORS
        // --------------------------------------------------------

        val closes15m = candles15m.map { it.close }

        val ema50_15m = IndicatorService.ema(closes15m, 50)
        val ema200_15m = IndicatorService.ema(closes15m, 200)
        val macd15m = IndicatorService.macd(closes15m)

        val supertrend15m = IndicatorService.supertrend(
            candles = candles15m,
            period = 10,
            multiplier = 3.0
        )

        val lastIndex15m = candles15m.lastIndex

        val trend50_15m = ema50_15m[lastIndex15m] ?: return null
        val trend200_15m = ema200_15m[lastIndex15m] ?: return null

        val macdHistogram15m =
            macd15m.histogram[lastIndex15m] ?: return null

        val direction15m =
            supertrend15m.direction[lastIndex15m]

        // --------------------------------------------------------
        // 5. PRIMARY BUY / SELL CONDITIONS
        // --------------------------------------------------------

        val bullishPrimary =
            ema50 > ema200 &&
                rsi >= 50.0 &&
                rsi < 75.0 &&
                stochK > stochD &&
                stochK < 85.0 &&
                macdHistogram > 0.0 &&
                supertrendDirection == 1 &&
                price > vwap &&
                ha.close > ha.open

        val bearishPrimary =
            ema50 < ema200 &&
                rsi > 25.0 &&
                rsi <= 50.0 &&
                stochK < stochD &&
                stochK > 15.0 &&
                macdHistogram < 0.0 &&
                supertrendDirection == -1 &&
                price < vwap &&
                ha.close < ha.open

        val primaryAction = when {
            bullishPrimary -> SignalType.BUY
            bearishPrimary -> SignalType.SELL
            else -> SignalType.WAIT
        }

        // --------------------------------------------------------
        // 6. 15-MINUTE TREND CONFIRMATION
        // --------------------------------------------------------

        val confirmedBuy =
            primaryAction == SignalType.BUY &&
                trend50_15m > trend200_15m &&
                macdHistogram15m > 0.0 &&
                direction15m == 1

        val confirmedSell =
            primaryAction == SignalType.SELL &&
                trend50_15m < trend200_15m &&
                macdHistogram15m < 0.0 &&
                direction15m == -1

        val isConfirmed = confirmedBuy || confirmedSell

        // A primary signal without 15m confirmation is not stable.
        val action = if (isConfirmed) {
            primaryAction
        } else {
            SignalType.WAIT
        }

        // --------------------------------------------------------
        // 7. STOP LOSS AND TARGETS
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
        // 8. CONFIDENCE
        // --------------------------------------------------------

        val confidence = if (action == SignalType.WAIT) {
            0.0
        } else {
            calculateConfidence(
                rsi = rsi,
                stochK = stochK,
                stochD = stochD,
                macdHistogram = macdHistogram,
                primaryTrend = true,
                confirmation = isConfirmed
            )
        }

        // --------------------------------------------------------
        // 9. SIGNAL EXPIRY AND REASON
        // --------------------------------------------------------

        val now = System.currentTimeMillis()
        val expiryMinutes = AppConfig.get().signalExpiryMinutes
            .coerceAtLeast(1)

        val expiryDuration = max(
            5L * 60_000L,
            expiryMinutes.toLong() * 60_000L
        )

        val expiresAt = now + expiryDuration

        val reason = buildString {
            append("5m EMA50=")
            append(format(ema50))
            append(", EMA200=")
            append(format(ema200))
            append(", RSI=")
            append(format(rsi))
            append(", Stoch K=")
            append(format(stochK))
            append(", D=")
            append(format(stochD))
            append(", MACD=")
            append(format(macdHistogram))
            append(", Supertrend=")
            append(supertrendDirection)
            append(", VWAP=")
            append(format(vwap))
            append(", ATR=")
            append(format(atr))
            append("; 15m EMA50=")
            append(format(trend50_15m))
            append(", EMA200=")
            append(format(trend200_15m))
            append(", MACD=")
            append(format(macdHistogram15m))
            append(", Supertrend=")
            append(direction15m)
            append("; 15m confirmation=")
            append(if (isConfirmed) "PASSED" else "NOT PASSED")
        }

        val tradingSignal = TradingSignal(
            symbol = normalizedSymbol,
            exchange = normalizedExchange,
            tradingStyle = TradingStyle.INTRADAY,
            signal = action,
            entryPrice = if (action == SignalType.WAIT) null else price,
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

        // Preview mode: return WAIT or confirmed signal without saving.
        if (!persist) return tradingSignal

        // Never save WAIT or unconfirmed signals.
        if (!isConfirmed) return null

        // --------------------------------------------------------
        // 10. DUPLICATE PROTECTION
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
                // One bad symbol must not stop the remaining scan.
            }
        }

        return result
    }

    fun expireOldSignals(): Int {
        return signalRepository.expireOld()
    }

    // ------------------------------------------------------------
    // CONFIDENCE CALCULATION
    // ------------------------------------------------------------

    private fun calculateConfidence(
        rsi: Double,
        stochK: Double,
        stochD: Double,
        macdHistogram: Double,
        primaryTrend: Boolean,
        confirmation: Boolean
    ): Double {
        var score = 50.0

        if (primaryTrend) score += 10.0
        if (confirmation) score += 20.0
        if (abs(stochK - stochD) > 2.0) score += 5.0
        if (abs(macdHistogram) > 0.0) score += 5.0

        if (rsi in 55.0..70.0 || rsi in 30.0..45.0) {
            score += 5.0
        }

        return score.coerceIn(0.0, 95.0)
    }

    private fun format(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }
}


package com.sb.backend.market.model

import com.sb.backend.market.MarketDataManager
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class DepthLevel(
    val price: Double = 0.0,
    val quantity: Long = 0L,
    val orders: Int = 0
) {
    val isValid: Boolean
        get() = price.isFinite() &&
            price >= 0.0 &&
            quantity >= 0L &&
            orders >= 0
}

data class InstrumentKey(
    val exchange: String,
    val symbol: String
) {
    init {
        require(exchange.isNotBlank()) {
            "Exchange cannot be empty"
        }
        require(symbol.isNotBlank()) {
            "Symbol cannot be empty"
        }
    }

    fun key(): String =
        "${exchange.trim().uppercase(Locale.ROOT)}|" +
            symbol.trim().uppercase(Locale.ROOT)
}

data class MarketTick(
    val key: InstrumentKey,
    val ltp: Double = 0.0,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val close: Double? = null,
    val previousClose: Double? = null,
    val volume: Long = 0L,
    val change: Double? = null,
    val changePercent: Double? = null,
    val bidPrice: Double? = null,
    val bidQty: Long? = null,
    val askPrice: Double? = null,
    val askQty: Long? = null,
    val bids: List<DepthLevel> = emptyList(),
    val asks: List<DepthLevel> = emptyList(),
    val totalBuyQty: Long = 0L,
    val totalSellQty: Long = 0L,
    val oi: Long = 0L,
    val lastTradeQty: Long = 0L,
    /** Exchange trade time, normalized to epoch milliseconds. */
    val lastTradeTime: Long? = null,
    /** Exchange update time, normalized to epoch milliseconds. */
    val exchangeTime: Long? = null,
    /** Local receive time, in epoch milliseconds. */
    val receivedAt: Long = System.currentTimeMillis(),
    val isIndex: Boolean = false
) {
    val isValid: Boolean
        get() =
            ltp.isFinite() &&
                ltp >= 0.0 &&
                isValidOptionalPrice(open) &&
                isValidOptionalPrice(high) &&
                isValidOptionalPrice(low) &&
                isValidOptionalPrice(close) &&
                isValidOptionalPrice(previousClose) &&
                isValidOptionalPrice(bidPrice) &&
                isValidOptionalPrice(askPrice) &&
                isValidOptionalNumber(change) &&
                isValidOptionalNumber(changePercent) &&
                volume >= 0L &&
                bidQty.isValidQuantity() &&
                askQty.isValidQuantity() &&
                totalBuyQty >= 0L &&
                totalSellQty >= 0L &&
                oi >= 0L &&
                lastTradeQty >= 0L &&
                (lastTradeTime == null || lastTradeTime > 0L) &&
                (exchangeTime == null || exchangeTime > 0L) &&
                receivedAt > 0L &&
                bids.all { it.isValid } &&
                asks.all { it.isValid }

    companion object {
        private const val MILLIS_THRESHOLD = 10_000_000_000L

        private fun isValidOptionalPrice(value: Double?): Boolean =
            value == null || (value.isFinite() && value >= 0.0)

        private fun isValidOptionalNumber(value: Double?): Boolean =
            value == null || value.isFinite()

        private fun Long?.isValidQuantity(): Boolean =
            this == null || this >= 0L

        /**
         * Converts exchange epoch seconds or milliseconds to milliseconds.
         *
         * Values below MILLIS_THRESHOLD are treated as seconds.
         * Invalid values and overflowing conversions return null.
         */
        fun normalizeMillis(value: Long?): Long? {
            if (value == null || value <= 0L) return null

            return if (value < MILLIS_THRESHOLD) {
                if (value > Long.MAX_VALUE / 1_000L) {
                    null
                } else {
                    value * 1_000L
                }
            } else {
                value
            }
        }
    }
}

data class MarketCandle(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long = 0L
) {
    val isValid: Boolean
        get() = isValid(
            timestamp = timestamp,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume
        )

    companion object {
        fun isValid(
            timestamp: Long,
            open: Double,
            high: Double,
            low: Double,
            close: Double,
            volume: Long
        ): Boolean {
            return timestamp > 0L &&
                open.isFinite() &&
                high.isFinite() &&
                low.isFinite() &&
                close.isFinite() &&
                open > 0.0 &&
                high > 0.0 &&
                low > 0.0 &&
                close > 0.0 &&
                high >= low &&
                high >= max(open, close) &&
                low <= min(open, close) &&
                volume >= 0L
        }

        fun create(
            timestamp: Long,
            open: Double,
            high: Double,
            low: Double,
            close: Double,
            volume: Long = 0L
        ): MarketCandle? {
            if (!isValid(timestamp, open, high, low, close, volume)) {
                return null
            }

            return MarketCandle(
                timestamp = timestamp,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume
            )
        }
    }
}

/**
 * Supported chart timeframes.
 *
 * Intraday seconds represent exact fixed durations.
 * Calendar timeframe seconds are nominal only.
 * Always use TimeframeBuckets for candle alignment.
 */
enum class MarketTimeframe(
    val label: String,
    val seconds: Long
) {
    MIN_1("1m", 60L),
    MIN_2("2m", 120L),
    MIN_3("3m", 180L),
    MIN_5("5m", 300L),
    MIN_10("10m", 600L),
    MIN_15("15m", 900L),
    MIN_30("30m", 1_800L),
    HOUR_1("1h", 3_600L),
    HOUR_2("2h", 7_200L),
    HOUR_4("4h", 14_400L),
    DAY_1("1D", 86_400L),
    WEEK_1("1W", 604_800L),
    WEEK_2("2W", 1_209_600L),
    WEEK_4("4W", 2_419_200L),
    MONTH_1("1M", 2_592_000L),
    MONTH_3("3M", 7_776_000L),
    YEAR_1("1Y", 31_536_000L),
    YEAR_5("5Y", 157_680_000L);

    val isIntraday: Boolean
        get() = when (this) {
            MIN_1, MIN_2, MIN_3, MIN_5, MIN_10, MIN_15, MIN_30,
            HOUR_1, HOUR_2, HOUR_4 -> true
            else -> false
        }

    val usesCalendarBuckets: Boolean
        get() = !isIntraday

    companion object {
        fun fromLabel(label: String): MarketTimeframe? {
            val normalized = label.trim()

            return values().firstOrNull {
                it.label == normalized
            }
        }
    }
}

data class ChartStatus(
    val state: String = "IDLE",
    val message: String = "",
    val candleCount: Int = 0,
    val historicalLoaded: Boolean = false,
    val liveActive: Boolean = false
) {
    init {
        require(candleCount >= 0) {
            "Candle count cannot be negative"
        }
    }
}

data class MarketUiState(
    val key: InstrumentKey? = null,
    val tick: MarketTick? = null,
    val isSubscribed: Boolean = false,
    val connectionState: MarketDataManager.ConnectionState =
        MarketDataManager.ConnectionState.DISCONNECTED,
    val timeframe: String = "1m",
    val status: String = "IDLE",
    val lastUpdated: Long = 0L,
    val chartStatus: ChartStatus = ChartStatus()
) {
    init {
        require(timeframe.isNotBlank()) {
            "Timeframe cannot be empty"
        }
        require(lastUpdated >= 0L) {
            "Last updated timestamp cannot be negative"
        }
    }
}

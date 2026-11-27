package com.sb.signals.domain.market

import com.sb.backend.market.TimeframeBuckets
import java.util.Calendar
import java.util.Locale

object MarketSession {

    private const val MINUTE_MILLIS = 60_000L

    fun sessionClose(timestamp: Long, exchange: String): Long {
        val calendar = Calendar.getInstance(TimeframeBuckets.IST).apply {
            timeInMillis = timestamp
        }
        val normalizedExchange = exchange.trim().uppercase(Locale.ROOT)
        val closeMinute = if (normalizedExchange.contains("MCX")) {
            23 * 60 + 30
        } else if (normalizedExchange.contains("CDS")) {
            17 * 60
        } else {
            15 * 60 + 30
        }

        calendar.set(Calendar.HOUR_OF_DAY, closeMinute / 60)
        calendar.set(Calendar.MINUTE, closeMinute % 60)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun sessionOpen(timestamp: Long, exchange: String): Long {
        val calendar = Calendar.getInstance(TimeframeBuckets.IST).apply {
            timeInMillis = timestamp
        }
        val normalizedExchange = exchange.trim().uppercase(Locale.ROOT)
        val openMinute = if (normalizedExchange.contains("MCX")) {
            9 * 60
        } else if (normalizedExchange.contains("CDS")) {
            9 * 60
        } else {
            9 * 60 + 15
        }

        calendar.set(Calendar.HOUR_OF_DAY, openMinute / 60)
        calendar.set(Calendar.MINUTE, openMinute % 60)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun minutesToClose(timestamp: Long, exchange: String): Long =
        (sessionClose(timestamp, exchange) - timestamp) / MINUTE_MILLIS

    fun isWeekend(timestamp: Long): Boolean {
        val calendar = Calendar.getInstance(TimeframeBuckets.IST).apply {
            timeInMillis = timestamp
        }
        return calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
            calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
    }

    fun isEntryWindow(timestamp: Long, exchange: String): Boolean {
        if (isWeekend(timestamp)) return false
        val open = sessionOpen(timestamp, exchange)
        val close = sessionClose(timestamp, exchange)
        return timestamp in open until (close - 15 * MINUTE_MILLIS)
    }

    fun addDays(timestamp: Long, days: Int): Long {
        return Calendar.getInstance(TimeframeBuckets.IST).apply {
            timeInMillis = timestamp
            add(Calendar.DAY_OF_YEAR, days)
        }.timeInMillis
    }

    fun supportsOvernightShort(exchange: String): Boolean {
        val normalized = exchange.trim().uppercase(Locale.ROOT)
        return normalized.contains("MCX") ||
            normalized.contains("NFO") ||
            normalized.contains("BFO") ||
            normalized.contains("CDS")
    }

    fun isTradeableInstrument(
        symbol: String,
        exchange: String,
        instrumentType: String?,
        category: String?
    ): Boolean {
        val normalizedType = instrumentType.orEmpty().uppercase(Locale.ROOT)
        val normalizedCategory = category.orEmpty().uppercase(Locale.ROOT)
        if (normalizedType.contains("INDEX") ||
            normalizedCategory.contains("INDEX")
        ) {
            return false
        }

        val normalizedSymbol = symbol.trim().uppercase(Locale.ROOT)
        return normalizedSymbol !in NON_TRADEABLE_INDEXES &&
            normalizedSymbol !in NON_TRADEABLE_INDEX_ALIASES
    }

    private val NON_TRADEABLE_INDEXES = setOf(
        "NIFTY",
        "NIFTY 50",
        "NIFTY50",
        "BANKNIFTY",
        "NIFTY BANK",
        "FINNIFTY",
        "MIDCPNIFTY",
        "SENSEX",
        "BANKEX",
        "INDIA VIX",
        "INDIAVIX"
    )

    private val NON_TRADEABLE_INDEX_ALIASES = setOf(
        "NIFTY 50",
        "NIFTY BANK",
        "NIFTY FIN SERVICE",
        "SENSEX 50"
    )
}

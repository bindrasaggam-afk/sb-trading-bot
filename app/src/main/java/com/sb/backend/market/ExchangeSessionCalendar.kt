package com.sb.backend.market

import com.sb.backend.market.TimeframeBuckets.IST
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object ExchangeSessionCalendar {

    private data class Session(val openMinute: Int, val closeMinute: Int)

    private val sessions = mapOf(
        "NSE" to Session(9 * 60 + 15, 15 * 60 + 30),
        "BSE" to Session(9 * 60 + 15, 15 * 60 + 30),
        "MCX" to Session(9 * 60, 23 * 60 + 30),
        "CDS" to Session(9 * 60, 17 * 60)
    )

    private val defaultSession = sessions.getValue("NSE")

    private val holidayKeys = ConcurrentHashMap.newKeySet<String>()

    private val marketOpenOverrides = ConcurrentHashMap<String, Boolean>()

    fun clear() {
        holidayKeys.clear()
        marketOpenOverrides.clear()
    }

    fun setHolidays(exchange: String, dates: Collection<Long>) {
        val normalized = normalizeExchange(exchange)
        holidayKeys.removeAll { key -> key.startsWith("$normalized|") }
        dates.forEach { timestamp ->
            holidayKeys.add("$normalized|${dayKey(timestamp)}")
        }
    }

    fun setMarketOpen(exchange: String, isOpen: Boolean) {
        marketOpenOverrides[normalizeExchange(exchange)] = isOpen
    }

    fun updateFromStatus(exchange: String, status: String) {
        val normalized = status.trim().uppercase(Locale.ROOT)

        val isOpen = when {
            normalized.contains("OPEN") && !normalized.contains("NOT") -> true
            normalized.contains("START") -> true
            normalized.contains("LIVE") -> true
            normalized.contains("CLOSE") -> false
            normalized.contains("END") -> false
            normalized.contains("HOLIDAY") -> false
            else -> return
        }

        setMarketOpen(exchange, isOpen)
    }

    fun isTradingTime(timestamp: Long, exchange: String): Boolean {
        if (timestamp <= 0L) return false

        val normalizedExchange = normalizeExchange(exchange)
        if (marketOpenOverrides[normalizedExchange] == false) return false
        if (holidayKeys.contains("$normalizedExchange|${dayKey(timestamp)}")) {
            return false
        }

        val calendar = Calendar.getInstance(IST).apply { timeInMillis = timestamp }
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            return false
        }

        val session = sessions[normalizedExchange] ?: defaultSession
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 +
            calendar.get(Calendar.MINUTE)

        return minuteOfDay in session.openMinute until session.closeMinute
    }

    private fun normalizeExchange(exchange: String): String {
        return exchange.trim().uppercase(Locale.ROOT)
    }

    private fun dayKey(timestamp: Long): String {
        val calendar = Calendar.getInstance(IST).apply { timeInMillis = timestamp }
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }
}

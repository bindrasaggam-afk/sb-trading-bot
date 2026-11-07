
package com.sb.backend.market

import com.sb.backend.market.model.MarketTimeframe
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Single source of truth for candle bucket alignment.
 *
 * Intraday buckets are anchored to the exchange session open.
 * NSE/BSE: 09:15
 * MCX: 09:00
 *
 * Higher timeframes use calendar boundaries.
 */
object TimeframeBuckets {

    private const val MILLIS_THRESHOLD = 10_000_000_000L
    private const val DAY_MILLIS = 86_400_000L

    private const val NSE_OPEN_MILLIS =
        (9L * 60L + 15L) * 60L * 1_000L

    private const val MCX_OPEN_MILLIS =
        9L * 60L * 60L * 1_000L

    val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    fun normalizeTimestamp(value: Long): Long {
        if (value <= 0L) return 0L

        return if (value < MILLIS_THRESHOLD) {
            value * 1_000L
        } else {
            value
        }
    }

    fun sessionOpenMillis(exchange: String): Long {
        val name = exchange.trim().uppercase(Locale.ROOT)

        return if (name.contains("MCX")) {
            MCX_OPEN_MILLIS
        } else {
            NSE_OPEN_MILLIS
        }
    }

    fun bucketStart(
        timestamp: Long,
        label: String,
        exchange: String
    ): Long {
        val timeframe = MarketTimeframe.fromLabel(label)
            ?: return timestamp

        return bucketStart(timestamp, timeframe, exchange)
    }

    fun bucketStart(
        timestamp: Long,
        timeframe: MarketTimeframe,
        exchange: String
    ): Long {
        if (timestamp <= 0L) return 0L

        val calendar = Calendar.getInstance(IST).apply {
            timeInMillis = timestamp
        }

        when (timeframe.label) {

            "1D" -> return startOfDay(calendar)

            "1W" -> return startOfWeek(calendar)

            "2W" -> return multiWeekBucket(calendar, 2)

            "4W" -> return multiWeekBucket(calendar, 4)

            "1M" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                return startOfDay(calendar)
            }

            "3M" -> {
                // Set day first to prevent month overflow.
                val month = calendar.get(Calendar.MONTH)
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.MONTH, (month / 3) * 3)

                return startOfDay(calendar)
            }

            "1Y" -> {
                calendar.set(Calendar.MONTH, Calendar.JANUARY)
                calendar.set(Calendar.DAY_OF_MONTH, 1)

                return startOfDay(calendar)
            }

            "5Y" -> {
                val year = calendar.get(Calendar.YEAR)

                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.MONTH, Calendar.JANUARY)
                calendar.set(Calendar.YEAR, (year / 5) * 5)

                return startOfDay(calendar)
            }
        }

        val interval = timeframe.seconds * 1_000L
        if (interval <= 0L) return timestamp

        val dayStart = startOfDay(calendar)
        val millisOfDay = timestamp - dayStart
        val sessionOpen = sessionOpenMillis(exchange)

        return if (millisOfDay < sessionOpen) {
            dayStart +
                Math.floorDiv(millisOfDay, interval) * interval
        } else {
            dayStart +
                sessionOpen +
                Math.floorDiv(
                    millisOfDay - sessionOpen,
                    interval
                ) * interval
        }
    }

    fun sessionKey(timestamp: Long): String {
        val calendar = Calendar.getInstance(IST).apply {
            timeInMillis = timestamp
        }

        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun startOfDay(source: Calendar): Long {
        val calendar = source.clone() as Calendar

        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        return calendar.timeInMillis
    }

    private fun startOfWeek(source: Calendar): Long {
        val calendar = source.clone() as Calendar

        val day = calendar.get(Calendar.DAY_OF_WEEK)
        val daysSinceMonday = (day + 5) % 7

        calendar.add(Calendar.DAY_OF_MONTH, -daysSinceMonday)

        return startOfDay(calendar)
    }

    private fun multiWeekBucket(
        source: Calendar,
        weeks: Int
    ): Long {
        val calendar = source.clone() as Calendar
        val currentDay = startOfDay(calendar)

        val anchor = Calendar.getInstance(IST).apply {
            clear()
            set(1970, Calendar.JANUARY, 5, 0, 0, 0)
        }.timeInMillis

        val days = Math.floorDiv(
            currentDay - anchor,
            DAY_MILLIS
        )

        val periodDays = weeks.toLong() * 7L
        val bucketDays =
            Math.floorDiv(days, periodDays) * periodDays

        return anchor + bucketDays * DAY_MILLIS
    }
}

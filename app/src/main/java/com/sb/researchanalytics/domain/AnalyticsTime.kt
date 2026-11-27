package com.sb.researchanalytics.domain

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AnalyticsTime {

    private val dateFormat = SimpleDateFormat(
        "dd MMM yyyy",
        Locale.US
    )
    private val dateTimeFormat = SimpleDateFormat(
        "dd MMM yyyy HH:mm:ss",
        Locale.US
    )
    private val numberFormat = DecimalFormat("0.00")
    private val percentFormat = DecimalFormat("0.00'%'")

    init {
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        dateFormat.timeZone = ist
        dateTimeFormat.timeZone = ist
    }

    fun formatDate(millis: Long): String =
        synchronized(dateFormat) { dateFormat.format(Date(millis)) }

    fun formatDateTime(millis: Long): String =
        synchronized(dateTimeFormat) {
            dateTimeFormat.format(Date(millis))
        }

    fun number(value: Double?): String =
        if (value == null || !value.isFinite()) {
            NOT_AVAILABLE
        } else {
            synchronized(numberFormat) { numberFormat.format(value) }
        }

    fun percent(value: Double?): String =
        if (value == null || !value.isFinite()) {
            NOT_AVAILABLE
        } else {
            synchronized(percentFormat) { percentFormat.format(value) }
        }

    fun durationHours(
        start: Long?,
        end: Long?
    ): Double? {
        if (start == null || end == null || end < start) return null
        return (end - start).toDouble() / 3_600_000.0
    }

    const val NOT_AVAILABLE = "NOT_AVAILABLE"
}

package com.sb.backend.services

import com.sb.backend.models.Candle
import com.sb.backend.repositories.MarketRepository
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Imports daily MCX futures bhavcopy candles from the public MTF Analytics
 * archive, which mirrors MCX daily bhavcopy files. Attribution: mtf.trading.
 *
 * Only futures (FUTCOM/FUTIDX) are imported; option contracts are excluded.
 * A missing date (weekend, holiday, or unavailable archive day) is skipped.
 */
class McxBhavcopyHistoricalService(
    private val marketRepository: MarketRepository
) {
    data class ImportResult(
        val datesChecked: Int,
        val datesAvailable: Int,
        val candlesFetched: Int,
        val candlesSaved: Int,
        val skippedDates: Int,
        val errors: Int,
        val lastError: String
    )

    @Volatile private var running = false
    @Volatile private var status = "not_started"
    @Volatile private var datesChecked = 0
    @Volatile private var datesAvailable = 0
    @Volatile private var candlesFetched = 0
    @Volatile private var candlesSaved = 0
    @Volatile private var skippedDates = 0
    @Volatile private var errors = 0
    @Volatile private var lastError = ""

    fun snapshot(): JSONObject = JSONObject()
        .put("status", status)
        .put("running", running)
        .put("source", "mtf.trading MCX bhavcopy archive (MCX daily bhavcopy mirror)")
        .put("attribution", "mtf.trading")
        .put("timeframe", "1D")
        .put("from_date", "2026-08-09")
        .put("to_date", "2026-11-09")
        .put("dates_checked", datesChecked)
        .put("dates_available", datesAvailable)
        .put("candles_fetched", candlesFetched)
        .put("candles_saved", candlesSaved)
        .put("skipped_dates", skippedDates)
        .put("errors", errors)
        .put("last_error", lastError)

    @Synchronized
    fun importThreeMonths(): JSONObject {
        if (running) return snapshot().put("already_running", true)
        running = true
        status = "running"
        datesChecked = 0
        datesAvailable = 0
        candlesFetched = 0
        candlesSaved = 0
        skippedDates = 0
        errors = 0
        lastError = ""

        try {
            val tz = TimeZone.getTimeZone("Asia/Kolkata")
            val calendar = Calendar.getInstance(tz).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MONTH, -3)
            }
            val end = Calendar.getInstance(tz).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = tz
            }

            while (calendar.timeInMillis <= end.timeInMillis) {
                val date = dateFormat.format(calendar.time)
                datesChecked++
                try {
                    val rows = fetchDate(date)
                    if (rows.isEmpty()) {
                        skippedDates++
                    } else {
                        datesAvailable++
                        val timestamp = calendar.timeInMillis
                        val grouped = rows.groupBy { it.first }
                        grouped.forEach { (symbol, records) ->
                            val batch = records.map { (_, values) ->
                                Candle(
                                    symbol = symbol,
                                    timeframe = "1D",
                                    timestamp = timestamp,
                                    open = values[0],
                                    high = values[1],
                                    low = values[2],
                                    close = values[3],
                                    volume = values[4].toLong().coerceAtLeast(0L),
                                    isComplete = true
                                )
                            }
                            candlesFetched += batch.size
                            val saved = marketRepository.saveCandles(
                                symbol = symbol,
                                exchange = "MCX",
                                timeframe = "1D",
                                candles = batch
                            )
                            candlesSaved += saved
                            if (saved != batch.size) {
                                throw IllegalStateException(
                                    "Partial save for $symbol on $date: saved=$saved fetched=${batch.size}"
                                )
                            }
                        }
                    }
                } catch (error: Exception) {
                    // 404 is expected for non-trading days and dates not yet
                    // captured in the archive. Other failures are reported.
                    val message = error.message.orEmpty()
                    if (message.contains("HTTP 404")) {
                        skippedDates++
                    } else {
                        errors++
                        lastError = "$date: ${message.take(400)}"
                    }
                }
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
            status = if (errors == 0) "completed" else "completed_with_errors"
        } catch (error: Exception) {
            errors++
            lastError = error.message ?: "MCX import failed"
            status = "failed"
        } finally {
            running = false
        }
        return snapshot()
    }

    private fun fetchDate(date: String): List<Pair<String, List<Double>>> {
        val encodedDate = URLEncoder.encode(date, "UTF-8")
        val url = URL(
            "https://mtf.trading/api/v1/mcx?date=$encodedDate&limit=100000"
        )
        val connection = url.openConnection() as HttpURLConnection
        val body: String
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "SBTradingBot/1.0")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("MCX archive HTTP $code")
        } finally {
            connection.disconnect()
        }

        val rows = when {
            body.trim().startsWith("[") -> JSONArray(body)
            body.trim().startsWith("{") -> {
                val root = JSONObject(body)
                root.optJSONArray("data")
                    ?: root.optJSONArray("rows")
                    ?: root.optJSONArray("results")
                    ?: throw IllegalStateException("MCX archive response has no rows array")
            }
            else -> throw IllegalStateException("MCX archive returned invalid JSON")
        }

        val result = ArrayList<Pair<String, List<Double>>>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val instrument = field(row, "Instrument", "InstrumentName", "FinInstrmTp")
                .uppercase(Locale.ROOT)
            if (instrument !in setOf("FUTCOM", "FUTIDX")) continue

            val symbol = field(row, "Symbol", "TradingSymbol", "TckrSymb").trim()
            if (symbol.isBlank()) continue

            val open = number(row, "OpenPrice", "Open", "OpnPric")
            val high = number(row, "HighPrice", "High", "HghPric")
            val low = number(row, "LowPrice", "Low", "LwPric")
            val close = number(row, "ClosePrice", "Close", "ClsPric", "SettlementPrice")
            val volume = number(row, "Volume", "TotalTradedVolume", "TtlTradgVol") ?: 0.0
            if (open == null || high == null || low == null || close == null) continue
            if (open <= 0.0 || high < maxOf(open, close, low) ||
                low > minOf(open, close) || close <= 0.0 || volume < 0.0
            ) continue

            result.add(symbol to listOf(open, high, low, close, volume))
        }
        return result
    }

    private fun field(row: JSONObject, vararg names: String): String {
        for (name in names) {
            if (row.has(name) && !row.isNull(name)) {
                return row.optString(name, "")
            }
        }
        return ""
    }

    private fun number(row: JSONObject, vararg names: String): Double? {
        val raw = field(row, *names).replace(",", "").trim()
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}

package com.sb.backend.services

import com.sb.backend.models.Candle
import com.sb.backend.models.Instrument
import com.sb.backend.repositories.InstrumentRepository
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
 * Imports daily MCX futures bhavcopy rows published by MTF Analytics.
 * The provider states that its MCX dataset is captured from MCX bhavcopy;
 * this is a third-party mirror, not the official MCX API.
 */
class McxBhavcopyImportService(
    private val instruments: InstrumentRepository,
    private val market: MarketRepository
) {
    data class Result(
        val tradingDaysChecked: Int,
        val sourceDaysAvailable: Int,
        val rowsReceived: Int,
        val candlesSaved: Int,
        val unmatchedContracts: Int,
        val invalidRows: Int,
        val errors: Int,
        val latestSourceDate: String,
        val lastError: String
    )

    @Volatile private var running = false
    @Volatile private var status = "not_started"
    @Volatile private var progressDays = 0
    @Volatile private var totalDays = 0
    @Volatile private var sourceDays = 0
    @Volatile private var rowsReceived = 0
    @Volatile private var candlesSaved = 0
    @Volatile private var unmatched = 0
    @Volatile private var invalid = 0
    @Volatile private var errors = 0
    @Volatile private var latestSourceDate = ""
    @Volatile private var lastError = ""

    fun snapshot(): JSONObject = JSONObject()
        .put("status", status)
        .put("running", running)
        .put("source", "MTF Analytics MCX bhavcopy mirror")
        .put("source_url", "https://mtf.trading/api/v1/mcx")
        .put("source_provenance", "Third-party mirror of MCX daily bhavcopy; not an official MCX API")
        .put("timeframe", "1D")
        .put("from_date", "2026-08-09")
        .put("to_date", "2026-11-09")
        .put("trading_days_checked", progressDays)
        .put("total_calendar_days", totalDays)
        .put("source_days_available", sourceDays)
        .put("rows_received", rowsReceived)
        .put("candles_saved", candlesSaved)
        .put("unmatched_contract_rows", unmatched)
        .put("invalid_rows", invalid)
        .put("errors", errors)
        .put("latest_source_date", latestSourceDate)
        .put("last_error", lastError)

    @Synchronized
    fun start(): JSONObject {
        if (running) return snapshot().put("already_running", true)
        val active = instruments.getActiveMcxFutures()
        if (active.isEmpty()) {
            return JSONObject().put("status", "blocked")
                .put("message", "No active MCX futures contracts found. Sync MCX scrip master first.")
        }
        running = true
        status = "running"
        progressDays = 0
        totalDays = 93
        sourceDays = 0
        rowsReceived = 0
        candlesSaved = 0
        unmatched = 0
        invalid = 0
        errors = 0
        latestSourceDate = ""
        lastError = ""
        Thread({
            try {
                runImport(active)
                status = if (errors == 0) "completed" else "completed_with_errors"
            } catch (e: Exception) {
                errors++
                lastError = (e.message ?: "MCX import failed").take(500)
                status = "failed"
            } finally {
                running = false
            }
        }, "mcx-bhavcopy-backfill").start()
        return snapshot().put("started", true)
    }

    private fun runImport(active: List<Instrument>) {
        val byContract = active.associateBy { it.symbol.uppercase(Locale.ROOT) }
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -92)
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
        repeat(totalDays) {
            val date = dateFormat.format(calendar.time)
            try {
                val rows = fetchDay(date)
                if (rows.isNotEmpty()) {
                    sourceDays++
                    latestSourceDate = date
                    processRows(date, rows, byContract)
                }
            } catch (e: HttpNotFoundException) {
                // Weekends, exchange holidays and dates outside mirror coverage.
            } catch (e: Exception) {
                errors++
                lastError = "$date: ${e.message ?: "source error"}".take(500)
            } finally {
                progressDays++
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    private fun fetchDay(date: String): List<JSONObject> {
        val encodedDate = URLEncoder.encode(date, "UTF-8")
        val url = URL("https://mtf.trading/api/v1/mcx?date=$encodedDate&instr=FUTCOM&limit=100000")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SB-Trading-Bot/1.0")
        }
        try {
            val code = connection.responseCode
            if (code == 404) throw HttpNotFoundException()
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("MCX mirror HTTP $code: ${body.take(250)}")
            return extractRows(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRows(body: String): List<JSONObject> {
        val root = body.trim()
        if (root.startsWith("[")) {
            val array = JSONArray(root)
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }
        val obj = JSONObject(root)
        if (obj.optString("status").equals("error", true)) {
            throw Exception(obj.optString("message", "MCX source returned error"))
        }
        val arrays = listOf("data", "rows", "results", "records")
        for (key in arrays) {
            val array = obj.optJSONArray(key)
            if (array != null) return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            val nested = obj.optJSONObject(key)
            if (nested != null) {
                for (nestedKey in arrays) {
                    val nestedArray = nested.optJSONArray(nestedKey)
                    if (nestedArray != null) return (0 until nestedArray.length()).mapNotNull { nestedArray.optJSONObject(it) }
                }
            }
        }
        throw Exception("MCX response did not contain a recognized rows array")
    }

    private fun field(row: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = row.opt(key)
            if (value != null && value != JSONObject.NULL) {
                val text = value.toString().trim()
                if (text.isNotEmpty() && !text.equals("null", true)) return text
            }
        }
        return ""
    }

    private fun number(row: JSONObject, vararg keys: String): Double? =
        field(row, *keys).replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun parseExpiry(value: String): String {
        val formats = listOf("ddMMMyyyy", "dd-MMM-yyyy", "yyyy-MM-dd", "dd/MM/yyyy")
        for (pattern in formats) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                }
                val date = parser.parse(value) ?: continue
                return SimpleDateFormat("ddMMMyy", Locale.US).format(date).uppercase(Locale.ROOT)
            } catch (_: Exception) { }
        }
        return ""
    }

    private fun timestamp(date: String): Long {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
        return parser.parse(date)?.time ?: throw Exception("Invalid trade date: $date")
    }

    private fun processRows(date: String, rows: List<JSONObject>, contracts: Map<String, Instrument>) {
        rowsReceived += rows.size
        val batch = ArrayList<Candle>()
        rows.forEach { row ->
            val symbol = field(row, "Symbol", "symbol", "TckrSymb", "TickerSymbol").uppercase(Locale.ROOT)
            val expiry = parseExpiry(field(row, "ExpiryDate", "expiry", "XpryDt", "Expiry"))
            if (symbol.isBlank() || expiry.isBlank()) {
                unmatched++
                return@forEach
            }
            val instrument = contracts.values.firstOrNull { candidate ->
                val key = candidate.symbol.uppercase(Locale.ROOT)
                key.startsWith(symbol) && key.contains(expiry)
            } ?: run {
                unmatched++
                return@forEach
            }
            val open = number(row, "Open", "open", "OpnPric")
            val high = number(row, "High", "high", "HghPric")
            val low = number(row, "Low", "low", "LwPric")
            val close = number(row, "Close", "close", "ClsPric", "SettlementPrice")
            val volume = number(row, "Volume", "volume", "TtlTradgVol", "TotalVolume")?.toLong() ?: 0L
            if (open == null || high == null || low == null || close == null ||
                open <= 0.0 || high < maxOf(open, close, low) ||
                low > minOf(open, close) || volume < 0L
            ) {
                invalid++
                return@forEach
            }
            val tradeDate = field(row, "TradeDate", "Date", "date", "TradDt", "BizDt").ifBlank { date }
            val ts = try { timestamp(tradeDate.take(10)) } catch (_: Exception) {
                invalid++
                return@forEach
            }
            batch.add(
                Candle(
                    symbol = instrument.symbol,
                    timeframe = "1D",
                    timestamp = ts,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume,
                    isComplete = true
                )
            )
        }
        if (batch.isNotEmpty()) {
            val saved = market.saveCandles(
                symbol = batch.first().symbol,
                exchange = "MCX",
                timeframe = "1D",
                candles = batch
            )
            candlesSaved += saved
            if (saved != batch.size) {
                errors++
                lastError = "$date: saved $saved of ${batch.size} candles"
            }
        }
    }

    private class HttpNotFoundException : Exception()
}

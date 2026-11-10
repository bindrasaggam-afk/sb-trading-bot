package com.sb.backend.services

import com.sb.backend.market.TimeframeBuckets
import com.sb.backend.market.model.MarketTimeframe
import com.sb.backend.models.Candle
import com.sb.backend.models.Instrument
import com.sb.backend.models.Quote
import com.sb.backend.repositories.MarketRepository
import com.sb.backend.security.KotakNeoSecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class KotakNeoMarketDataService(
    private val secureStore: KotakNeoSecureStore,
    private val marketRepository: MarketRepository
) {

    private fun request(url: String): String {
        val token = secureStore.getAccessToken()
        require(token.isNotBlank()) { "Kotak Access Token missing" }

        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", token)
            connection.setRequestProperty("Content-Type", "application/json")

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code == 429) {
                val retry = connection.getHeaderField("Retry-After")
                throw Exception("Kotak rate limit reached. Retry-After: ${retry ?: "not provided"}")
            }

            if (code !in 200..299) {
                throw Exception("Kotak API HTTP $code: $body")
            }

            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun baseUrl(): String {
        return secureStore.getTradeBaseUrl().trimEnd('/').also {
            require(it.isNotBlank()) {
                "Kotak trade Base URL missing. Authenticate again."
            }
        }
    }

    private fun number(obj: JSONObject, key: String): Double? {
        val raw = obj.optString(key, "")
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun parseItems(body: String): List<JSONObject> {
        val root = body.trim()

        if (root.startsWith("[")) {
            val array = JSONArray(root)
            return (0 until array.length()).mapNotNull {
                array.optJSONObject(it)
            }
        }

        if (!root.startsWith("{")) {
            throw Exception("Invalid Kotak response")
        }

        val obj = JSONObject(root)
        val data = obj.optJSONArray("data")

        if (data != null) {
            return (0 until data.length()).mapNotNull {
                data.optJSONObject(it)
            }
        }

        val nested = obj.optJSONObject("data")
        if (nested != null) return listOf(nested)

        if (obj.has("ltp")) return listOf(obj)

        throw Exception(
            obj.optString("emsg", "Kotak quote data missing")
        )
    }

    private fun segmentFor(exchange: String): String {
        return when (exchange.toUpperCase(Locale.ROOT)) {
            "NSE" -> "nse_cm"
            "BSE" -> "bse_cm"
            "MCX" -> "mcx_fo"
            else -> throw IllegalArgumentException(
                "Unsupported quote exchange: $exchange"
            )
        }
    }

    private fun parseExchangeTime(item: JSONObject): Long? {
        val raw = item.optString("lstup_time", "")
        val value = raw.toLongOrNull() ?: return null

        // Kotak quote response uses Unix seconds.
        return if (value in 1_000_000_000L..9_999_999_999L) {
            value * 1000L
        } else {
            value
        }
    }

    fun fetchQuotes(instruments: List<Instrument>): List<Quote> {
        require(instruments.isNotEmpty()) {
            "No instruments supplied"
        }

        val eligible = instruments.filter {
            it.isActive &&
                it.symbol.isNotBlank() &&
                it.token?.isNotBlank() == true
        }

        require(eligible.isNotEmpty()) {
            "No instruments have verified Kotak tokens"
        }

        // Keep requests in small batches to avoid oversized URLs.
        val result = ArrayList<Quote>()

        eligible.chunked(20).forEach { batch ->
            val queries = batch.map { instrument ->
                val segment = segmentFor(instrument.exchange)
                "$segment|${instrument.token!!.trim()}"
            }

            val encoded = URLEncoder.encode(
                queries.joinToString(","),
                "UTF-8"
            )

            val endpoint =
                "${baseUrl()}/script-details/1.0/quotes/neosymbol/$encoded/all"

            val items = parseItems(request(endpoint))

            items.forEach { item ->
                val token = item.optString("exchange_token", "")
                val display = item.optString("display_symbol", "")

                val instrument = batch.firstOrNull {
                    it.token == token ||
                        it.tradingSymbol.equals(display, true) ||
                        it.symbol.equals(display, true)
                } ?: return@forEach

                val ltp = number(item, "ltp") ?: return@forEach
                val ohlc = item.optJSONObject("ohlc") ?: JSONObject()
                val received = System.currentTimeMillis()

                val quote = Quote(
                    symbol = instrument.symbol,
                    exchange = instrument.exchange,
                    ltp = ltp,
                    open = number(ohlc, "open"),
                    high = number(ohlc, "high"),
                    low = number(ohlc, "low"),
                    previousClose = number(ohlc, "close"),
                    volume = number(item, "last_volume")?.toLong() ?: 0L,
                    change = number(item, "change"),
                    changePercent = number(item, "per_change"),
                    timestamp = received,
                    isLive = true,
                    exchangeTimestamp = parseExchangeTime(item),
                    receivedAt = received
                )

                if (!marketRepository.saveQuote(quote)) {
                    throw Exception("Database save failed: ${instrument.symbol}")
                }

                val saved = marketRepository.getQuote(
                    instrument.symbol,
                    instrument.exchange
                ) ?: throw Exception("Database read-back failed: ${instrument.symbol}")

                if (saved.ltp != quote.ltp ||
                    saved.receivedAt != quote.receivedAt
                ) {
                    throw Exception("Quote verification failed: ${instrument.symbol}")
                }

                result.add(saved)
            }
        }

        return result
    }

    fun fetchRelianceQuote(): Quote {
        val instrument = Instrument(
            symbol = "RELIANCE",
            name = "Reliance Industries",
            exchange = "NSE",
            segment = "EQ",
            instrumentType = "EQ",
            token = "2885"
        )

        return fetchQuotes(listOf(instrument)).firstOrNull()
            ?: throw Exception("RELIANCE quote not returned by Kotak")
    }

    private fun parseCandleTime(value: String): Long {
        val format = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ssZ",
            Locale.US
        )
        format.timeZone = TimeZone.getTimeZone("Asia/Kolkata")

        return format.parse(value)?.time
            ?: throw Exception("Invalid candle timestamp: $value")
    }

    private data class HistoricalRange(
        val interval: String,
        val maxDays: Int,
        val aggregation: Int,
        val baseSeconds: Long,
        val lookbackDays: Int
    )

    private fun historicalRange(timeframe: String): HistoricalRange {
        val label = timeframe.trim()
        val lower = label.lowercase(Locale.ROOT)

        return when {
            label.endsWith("M") || label.endsWith("Y") ->
                HistoricalRange("D", 180, 1, 86_400L, THREE_YEARS_DAYS)

            label.endsWith("D") ->
                HistoricalRange("D", 180, 1, 86_400L, THREE_YEARS_DAYS)

            label.endsWith("W") ->
                HistoricalRange("W", 180, 1, 604_800L, THREE_YEARS_DAYS)

            lower == "1m" || lower == "1min" ->
                HistoricalRange("1min", 30, 1, 60L, 30)

            lower == "2m" || lower == "2min" ->
                HistoricalRange("1min", 30, 1, 60L, 30)

            lower == "3m" || lower == "3min" ->
                HistoricalRange("3min", 30, 1, 180L, 30)

            lower == "5m" || lower == "5min" ->
                HistoricalRange("5min", 30, 1, 300L, 30)

            lower == "10m" || lower == "10min" ->
                HistoricalRange("10min", 60, 1, 600L, 60)

            lower == "15m" || lower == "15min" ->
                HistoricalRange("15min", 60, 1, 900L, 60)

            lower == "30m" || lower == "30min" ->
                HistoricalRange("30min", 90, 1, 1_800L, 90)

            lower == "1h" || lower == "60m" || lower == "60min" ->
                HistoricalRange("60min", 90, 1, 3_600L, 90)

            lower == "2h" ->
                HistoricalRange("60min", 90, 1, 3_600L, 90)

            lower == "4h" ->
                HistoricalRange("60min", 90, 1, 3_600L, 90)

            else -> throw IllegalArgumentException(
                "Unsupported historical timeframe: $timeframe"
            )
        }
    }

    fun fetchHistoricalCandles(
        instrument: Instrument,
        timeframe: String,
        years: Int = 3
    ): List<Candle> {
        require(instrument.isActive) {
            "Instrument is inactive: ${instrument.symbol}"
        }

        val exchange = instrument.exchange.trim().uppercase(Locale.ROOT)
        // Kotak Neo historical endpoint supports NSE/BSE, not MCX.
        // MCX daily history must come from the MCX Bhav Copy source.
        require(exchange == "NSE" || exchange == "BSE") {
            "Kotak historical candles are supported for NSE/BSE only; MCX requires MCX Bhav Copy"
        }

        val token = instrument.token?.trim()
        require(!token.isNullOrBlank()) {
            "Verified instrument token missing: ${instrument.symbol}"
        }

        val range = historicalRange(timeframe)
        val segment = segmentFor(instrument.exchange)
        val neoSymbol = URLEncoder.encode(
            "$segment|$token",
            "UTF-8"
        )

        val timezone = TimeZone.getTimeZone("Asia/Kolkata")
        val lookback = if (range.lookbackDays >= THREE_YEARS_DAYS) {
            val end = Calendar.getInstance(timezone)
            val start = (end.clone() as Calendar).apply {
                add(Calendar.YEAR, -years.coerceIn(1, 5))
            }
            ((end.timeInMillis - start.timeInMillis) / 86_400_000L)
                .toInt() + 1
        } else {
            range.lookbackDays
        }
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            this.timeZone = timezone
        }

        val anchor = Calendar.getInstance(timezone).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val collected = ArrayList<Candle>()
        // The API is queried newest-to-oldest. Empty chunks are only valid
        // at the oldest end of history (before listing). Once an empty chunk
        // is seen, any candles in an older chunk prove there was a data gap.
        var emptyOlderChunkSeen = false
        var remainingDays = lookback
        while (remainingDays > 0) {
            val chunkDays = minOf(range.maxDays, remainingDays)
            val chunkEnd = anchor.time
            anchor.add(Calendar.DAY_OF_YEAR, -(chunkDays - 1))
            val chunkStart = anchor.time

            val fromDate = formatter.format(chunkStart)
            val toDate = formatter.format(chunkEnd)

            val endpoint =
                "${baseUrl()}/market-data/1.0/historical/details" +
                    "?neosymbol=$neoSymbol" +
                    "&fromdate=$fromDate" +
                    "&todate=$toDate" +
                    "&interval=${range.interval}"

            try {
                val chunkCandles = parseHistoricalRows(
                    request(endpoint),
                    instrument,
                    timeframe
                )
                if (chunkCandles.isEmpty()) {
                    emptyOlderChunkSeen = true
                } else {
                    if (emptyOlderChunkSeen) {
                        throw Exception(
                            "Historical data gap: candles returned in an older chunk after an empty newer chunk"
                        )
                    }
                    collected.addAll(chunkCandles)
                }
            } catch (e: Exception) {
                throw Exception(
                    "Historical chunk failed for ${instrument.exchange}:${instrument.symbol} " +
                        "[$fromDate to $toDate]: ${e.message ?: e.javaClass.simpleName}",
                    e
                )
            }

            remainingDays -= chunkDays
            anchor.add(Calendar.DAY_OF_YEAR, -1)
        }

        if (collected.isEmpty()) {
            throw Exception(
                "No historical candles returned for ${instrument.exchange}:${instrument.symbol}; nothing was saved"
            )
        }

        val ordered = collected
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }

        val candles = aggregateCandles(
            ordered,
            timeframe.trim(),
            instrument.exchange
        )

        val saved = marketRepository.saveCandles(
            symbol = instrument.symbol,
            exchange = instrument.exchange,
            timeframe = timeframe.trim(),
            candles = candles
        )
        require(saved == candles.size) {
            "Historical candle save was incomplete for ${instrument.symbol}: saved=$saved, fetched=${candles.size}"
        }

        val persisted = marketRepository.countCandles(
            symbol = instrument.symbol,
            exchange = instrument.exchange,
            timeframe = timeframe.trim()
        )
        require(persisted >= candles.size) {
            "Historical candle read-back verification failed for ${instrument.symbol}: saved=$saved, persisted=$persisted, fetched=${candles.size}"
        }

        return candles
    }

    private fun parseHistoricalRows(
        body: String,
        instrument: Instrument,
        timeframe: String
    ): List<Candle> {
        val root = JSONObject(body)

        if (root.optString("status", "")
                .equals("error", ignoreCase = true)
        ) {
            throw Exception(
                root.optString("message", "Kotak historical data error")
            )
        }

        val data = root.optJSONObject("data")
        val rows = data?.optJSONArray("candles")
            ?: root.optJSONArray("candles")
            ?: root.optJSONArray("data")
            ?: throw Exception("Historical candles array missing")

        val result = ArrayList<Candle>(rows.length())

        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i)
                ?: throw Exception("Malformed historical candle row at index $i")
            if (row.length() < 6) {
                throw Exception("Incomplete historical candle row at index $i")
            }

            val open = row.optDouble(1, Double.NaN)
            val high = row.optDouble(2, Double.NaN)
            val low = row.optDouble(3, Double.NaN)
            val close = row.optDouble(4, Double.NaN)
            val volume = row.optDouble(5, Double.NaN)

            if (!open.isFinite() || !high.isFinite() ||
                !low.isFinite() || !close.isFinite()
            ) {
                throw Exception("Non-numeric OHLC in historical candle row $i")
            }

            // Reject malformed rows instead of silently accepting a partial range.
            if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) {
                throw Exception("Non-positive OHLC in historical candle row $i")
            }

            if (high < maxOf(open, close, low) ||
                low > minOf(open, close, high)
            ) {
                throw Exception("Invalid OHLC relationship in historical candle row $i")
            }
            if (!volume.isFinite() || volume < 0.0) {
                throw Exception("Invalid volume in historical candle row $i")
            }

            val timestamp = try {
                parseCandleTime(row.getString(0))
            } catch (error: Exception) {
                throw Exception("Invalid timestamp in historical candle row $i", error)
            }

            result.add(
                Candle(
                    symbol = instrument.symbol,
                    timeframe = timeframe.trim(),
                    timestamp = timestamp,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = if (volume.isFinite()) {
                        volume.toLong().coerceAtLeast(0L)
                    } else {
                        0L
                    },
                    isComplete = true
                )
            )
        }

        return result
    }

    private fun aggregateCandles(
        candles: List<Candle>,
        timeframe: String,
        exchange: String
    ): List<Candle> {
        if (candles.isEmpty()) return candles

        val parsed = MarketTimeframe.fromLabel(timeframe) ?: return candles
        val result = ArrayList<Candle>()

        for (candle in candles) {
            val bucket = TimeframeBuckets.bucketStart(
                timestamp = candle.timestamp,
                timeframe = parsed,
                exchange = exchange
            )
            if (bucket <= 0L) continue

            val last = result.lastOrNull()

            if (last == null || last.timestamp != bucket) {
                result.add(candle.copy(timestamp = bucket))
            } else {
                result[result.lastIndex] = last.copy(
                    high = maxOf(last.high, candle.high),
                    low = minOf(last.low, candle.low),
                    close = candle.close,
                    volume = safeAdd(last.volume, candle.volume)
                )
            }
        }

        return result
    }

    private fun safeAdd(first: Long, second: Long): Long {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE
        return (first + second).coerceAtLeast(0L)
    }

    private companion object {
        private const val THREE_YEARS_DAYS = 1095
    }
}

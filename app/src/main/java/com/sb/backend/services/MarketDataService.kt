
package com.sb.backend.services

import android.os.Handler
import android.os.Looper
import com.sb.backend.core.AppConfig
import com.sb.backend.models.Candle
import com.sb.backend.models.Instrument
import com.sb.backend.models.Quote
import com.sb.backend.repositories.InstrumentRepository
import com.sb.backend.repositories.MarketRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

data class RemoteResult<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val fromCache: Boolean = false
)

class MarketDataService(
    private val marketRepository: MarketRepository,
    private val instrumentRepository: InstrumentRepository,
    private val deviceId: String? = null
) {

    private val executor: ExecutorService =
        Executors.newFixedThreadPool(4)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isShutdown = false

    fun refreshQuotes(
        symbols: List<String>,
        exchange: String = AppConfig.get().defaultExchange,
        callback: (RemoteResult<List<Quote>>) -> Unit
    ) {
        val cleaned = symbols
            .map { it.trim().toUpperCase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .distinct()

        val normalizedExchange = normalizeExchange(exchange)

        if (cleaned.isEmpty()) {
            deliver(callback, RemoteResult(true, emptyList()))
            return
        }

        if (normalizedExchange.isBlank()) {
            deliver(
                callback,
                RemoteResult(false, error = "Invalid exchange.")
            )
            return
        }

        if (!AppConfig.get().liveMarketDataEnabled) {
            deliver(
                callback,
                cachedQuotes(
                    cleaned,
                    normalizedExchange,
                    "Live market data is disabled."
                )
            )
            return
        }

        executeRequest(callback) {
            try {
                val encodedSymbols = URLEncoder.encode(
                    cleaned.joinToString(","),
                    "UTF-8"
                )

                val encodedExchange = URLEncoder.encode(
                    normalizedExchange,
                    "UTF-8"
                )

                val body = fetchBody(
                    "/api/quotes?symbols=$encodedSymbols" +
                        "&exchange=$encodedExchange"
                )

                val array = parseArray(body, "quotes", "data")
                val quotes = ArrayList<Quote>()

                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val quote = parseQuote(item) ?: continue

                    if (quote.exchange == normalizedExchange &&
                        cleaned.contains(quote.symbol)
                    ) {
                        quotes.add(quote)
                    }
                }

                if (quotes.isEmpty()) {
                    return@executeRequest cachedQuotes(
                        cleaned,
                        normalizedExchange,
                        "Backend returned no valid quotes."
                    )
                }

                marketRepository.saveQuotes(quotes)

                RemoteResult(
                    success = true,
                    data = quotes,
                    fromCache = false
                )
            } catch (e: Exception) {
                cachedQuotes(
                    cleaned,
                    normalizedExchange,
                    e.message ?: "Quote request failed."
                )
            }
        }
    }

    fun loadCandles(
        symbol: String,
        timeframe: String,
        exchange: String = AppConfig.get().defaultExchange,
        limit: Int = AppConfig.get().candlePageSize,
        callback: (RemoteResult<List<Candle>>) -> Unit
    ) {
        val normalizedSymbol = normalizeSymbol(symbol)
        val normalizedTimeframe = normalizeTimeframe(timeframe)
        val normalizedExchange = normalizeExchange(exchange)
        val safeLimit = limit.coerceIn(1, 5000)

        if (normalizedSymbol.isBlank()) {
            deliver(
                callback,
                RemoteResult(false, error = "Symbol is empty.")
            )
            return
        }

        if (normalizedTimeframe.isBlank()) {
            deliver(
                callback,
                RemoteResult(false, error = "Timeframe is empty.")
            )
            return
        }

        if (normalizedExchange.isBlank()) {
            deliver(
                callback,
                RemoteResult(false, error = "Invalid exchange.")
            )
            return
        }

        if (!AppConfig.get().liveMarketDataEnabled) {
            deliver(
                callback,
                cachedCandles(
                    normalizedSymbol,
                    normalizedTimeframe,
                    normalizedExchange,
                    safeLimit,
                    "Live market data is disabled."
                )
            )
            return
        }

        executeRequest(callback) {
            try {
                val path = "/api/candles?symbol=" +
                    URLEncoder.encode(normalizedSymbol, "UTF-8") +
                    "&timeframe=" +
                    URLEncoder.encode(normalizedTimeframe, "UTF-8") +
                    "&exchange=" +
                    URLEncoder.encode(normalizedExchange, "UTF-8") +
                    "&limit=$safeLimit"

                val body = fetchBody(path)
                val array = parseArray(body, "candles", "data")
                val candles = ArrayList<Candle>()

                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue

                    val candle = parseCandle(
                        item,
                        normalizedSymbol,
                        normalizedTimeframe
                    ) ?: continue

                    candles.add(candle)
                }

                if (candles.isEmpty()) {
                    return@executeRequest cachedCandles(
                        normalizedSymbol,
                        normalizedTimeframe,
                        normalizedExchange,
                        safeLimit,
                        "Backend returned no valid candles."
                    )
                }

                val sortedCandles = candles
                    .distinctBy { it.timestamp }
                    .sortedBy { it.timestamp }
                    .takeLast(safeLimit)

                marketRepository.saveCandles(
                    normalizedSymbol,
                    normalizedExchange,
                    normalizedTimeframe,
                    sortedCandles
                )

                RemoteResult(
                    success = true,
                    data = sortedCandles,
                    fromCache = false
                )
            } catch (e: Exception) {
                cachedCandles(
                    normalizedSymbol,
                    normalizedTimeframe,
                    normalizedExchange,
                    safeLimit,
                    e.message ?: "Candle request failed."
                )
            }
        }
    }

    fun syncInstruments(
        exchange: String? = null,
        callback: (RemoteResult<Int>) -> Unit
    ) {
        val normalizedExchange = exchange
            ?.trim()
            ?.toUpperCase(Locale.ROOT)

        if (normalizedExchange != null &&
            normalizedExchange.isBlank()
        ) {
            deliver(
                callback,
                RemoteResult(false, error = "Invalid exchange.")
            )
            return
        }

        if (!AppConfig.get().liveMarketDataEnabled) {
            deliver(
                callback,
                RemoteResult(
                    false,
                    error = "Live market data is disabled."
                )
            )
            return
        }

        executeRequest(callback) {
            try {
                val path = if (normalizedExchange == null) {
                    "/api/instruments?limit=5000"
                } else {
                    "/api/instruments?exchange=" +
                        URLEncoder.encode(
                            normalizedExchange,
                            "UTF-8"
                        ) +
                        "&limit=5000"
                }

                val body = fetchBody(path)
                val array = parseArray(body, "instruments", "data")
                val instruments = ArrayList<Instrument>()

                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val instrument = parseInstrument(item) ?: continue

                    if (normalizedExchange == null ||
                        instrument.exchange == normalizedExchange
                    ) {
                        instruments.add(instrument)
                    }
                }

                if (instruments.isEmpty()) {
                    return@executeRequest RemoteResult(
                        success = false,
                        data = 0,
                        error = "No valid instruments received."
                    )
                }

                val saved = instrumentRepository.saveAll(instruments)

                RemoteResult(
                    success = true,
                    data = saved,
                    fromCache = false
                )
            } catch (e: Exception) {
                RemoteResult<Int>(
                    success = false,
                    error = e.message ?: "Instrument sync failed."
                )
            }
        }
    }

    fun checkHealth(
        callback: (RemoteResult<String>) -> Unit
    ) {
        executeRequest(callback) {
            try {
                val body = fetchBody("/api/health")
                RemoteResult(
                    success = true,
                    data = body
                )
            } catch (e: Exception) {
                RemoteResult<String>(
                    success = false,
                    error = e.message ?: "Health check failed."
                )
            }
        }
    }

    fun loadCachedQuotes(
        symbols: List<String>,
        exchange: String = AppConfig.get().defaultExchange
    ): List<Quote> {
        val cleaned = symbols
            .map { normalizeSymbol(it) }
            .filter { it.isNotBlank() }
            .distinct()

        val normalizedExchange = normalizeExchange(exchange)

        if (cleaned.isEmpty() || normalizedExchange.isBlank()) {
            return emptyList()
        }

        return marketRepository.getQuotes(
            cleaned,
            normalizedExchange
        )
    }

    fun loadCachedCandles(
        symbol: String,
        timeframe: String,
        exchange: String = AppConfig.get().defaultExchange,
        limit: Int = AppConfig.get().candlePageSize
    ): List<Candle> {
        val normalizedSymbol = normalizeSymbol(symbol)
        val normalizedTimeframe = normalizeTimeframe(timeframe)
        val normalizedExchange = normalizeExchange(exchange)

        if (normalizedSymbol.isBlank() ||
            normalizedTimeframe.isBlank() ||
            normalizedExchange.isBlank() ||
            limit <= 0
        ) {
            return emptyList()
        }

        return marketRepository.getCandles(
            normalizedSymbol,
            normalizedTimeframe,
            normalizedExchange,
            limit.coerceAtMost(5000)
        )
    }

    fun shutdown() {
        isShutdown = true
        executor.shutdown()
    }

    private fun <T> executeRequest(
        callback: (RemoteResult<T>) -> Unit,
        task: () -> RemoteResult<T>
    ) {
        if (isShutdown) {
            deliver(
                callback,
                RemoteResult(
                    success = false,
                    error = "MarketDataService is shut down."
                )
            )
            return
        }

        try {
            executor.execute {
                val result = try {
                    task()
                } catch (e: Exception) {
                    RemoteResult<T>(
                        success = false,
                        error = e.message ?: "Request failed."
                    )
                }

                deliver(callback, result)
            }
        } catch (e: RejectedExecutionException) {
            deliver(
                callback,
                RemoteResult(
                    success = false,
                    error = "Market request could not be scheduled."
                )
            )
        }
    }

    private fun <T> deliver(
        callback: (RemoteResult<T>) -> Unit,
        result: RemoteResult<T>
    ) {
        mainHandler.post {
            callback(result)
        }
    }

    private fun cachedQuotes(
        symbols: List<String>,
        exchange: String,
        error: String
    ): RemoteResult<List<Quote>> {
        val cached = marketRepository.getQuotes(symbols, exchange)

        return RemoteResult(
            success = cached.isNotEmpty(),
            data = cached,
            error = error,
            fromCache = true
        )
    }

    private fun cachedCandles(
        symbol: String,
        timeframe: String,
        exchange: String,
        limit: Int,
        error: String
    ): RemoteResult<List<Candle>> {
        val cached = marketRepository.getCandles(
            symbol,
            timeframe,
            exchange,
            limit
        )

        return RemoteResult(
            success = cached.isNotEmpty(),
            data = cached,
            error = error,
            fromCache = true
        )
    }

    private fun fetchBody(path: String): String {
        val config = AppConfig.get()
        val root = config.baseUrl.trim().trimEnd('/')

        require(
            root.startsWith("http://") ||
                root.startsWith("https://")
        ) {
            "Invalid backend URL."
        }

        require(path.startsWith("/") && !path.startsWith("//")) {
            "Invalid API path."
        }

        var connection: HttpURLConnection? = null

        try {
            connection = (URL(root + path).openConnection()
                as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = config.requestTimeoutMs
                readTimeout = config.readTimeoutMs
                setRequestProperty("Accept", "application/json")

                if (!deviceId.isNullOrBlank()) {
                    setRequestProperty(
                        config.deviceHeaderName,
                        deviceId
                    )
                }
            }

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (code !in 200..299) {
                throw IOException(
                    "API returned HTTP $code" +
                        if (body.isNotBlank()) ": $body" else ""
                )
            }

            return body
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseArray(
        body: String,
        vararg keys: String
    ): JSONArray {
        val trimmed = body.trim()

        if (trimmed.startsWith("[")) {
            return JSONArray(trimmed)
        }

        if (trimmed.isBlank()) {
            return JSONArray()
        }

        val root = JSONObject(trimmed)

        for (key in keys) {
            if (root.isNull(key)) continue

            val value = root.opt(key)

            if (value is JSONArray) return value

            if (value is JSONObject) {
                val nested = nestedArray(value)
                if (nested != null) return nested
            }
        }

        return nestedArray(root) ?: JSONArray()
    }

    private fun nestedArray(obj: JSONObject): JSONArray? {
        val keys = obj.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)

            if (value is JSONArray) return value
        }

        return null
    }

    private fun parseQuote(item: JSONObject): Quote? {
        val symbol = normalizeSymbol(
            jsonString(item, "symbol", "tradingsymbol", "ticker")
        )

        if (symbol.isBlank()) return null

        val ltp = jsonDouble(
            item,
            "ltp",
            "last_price",
            "lastPrice",
            "price",
            "close"
        )

        if (!ltp.isFinite() || ltp <= 0.0) return null

        val exchange = normalizeExchange(
            jsonString(item, "exchange", "exch")
                .ifBlank { AppConfig.get().defaultExchange }
        )

        if (exchange.isBlank()) return null

        val previousClose = jsonDouble(
            item,
            "previous_close",
            "previousClose",
            "prev_close",
            "close"
        ).takeIf { it.isFinite() && it > 0.0 }

        val rawChange = jsonDouble(
            item,
            "change",
            "net_change",
            "change_abs"
        )

        val rawPercent = jsonDouble(
            item,
            "change_percent",
            "changePercent",
            "pct_change"
        )

        if (!rawChange.isFinite() || !rawPercent.isFinite()) {
            return null
        }

        val calculatedChange = if (rawChange != 0.0) {
            rawChange
        } else if (previousClose != null) {
            ltp - previousClose
        } else {
            0.0
        }

        val calculatedPercent = if (rawPercent != 0.0) {
            rawPercent
        } else if (previousClose != null) {
            (ltp - previousClose) / previousClose * 100.0
        } else {
            0.0
        }

        if (!calculatedChange.isFinite() ||
            !calculatedPercent.isFinite()
        ) {
            return null
        }

        val rawTimestamp = jsonLong(
            item,
            "timestamp",
            "time",
            "ts",
            "last_update"
        )

        val timestamp = normalizeTimestamp(rawTimestamp)
            ?: return null

        val volume = jsonLong(item, "volume", "vol", "quantity")
        if (volume < 0L) return null

        return Quote(
            symbol = symbol,
            exchange = exchange,
            ltp = ltp,
            open = jsonDouble(item, "open", "open_price")
                .takeIf { it.isFinite() && it > 0.0 },
            high = jsonDouble(item, "high", "day_high")
                .takeIf { it.isFinite() && it > 0.0 },
            low = jsonDouble(item, "low", "day_low")
                .takeIf { it.isFinite() && it > 0.0 },
            previousClose = previousClose,
            volume = volume,
            change = calculatedChange,
            changePercent = calculatedPercent,
            timestamp = timestamp,
            isLive = true
        )
    }

    private fun parseCandle(
        item: JSONObject,
        symbol: String,
        timeframe: String
    ): Candle? {
        val open = jsonDouble(item, "open", "o")
        val high = jsonDouble(item, "high", "h")
        val low = jsonDouble(item, "low", "l")
        val close = jsonDouble(item, "close", "c")

        if (!open.isFinite() ||
            !high.isFinite() ||
            !low.isFinite() ||
            !close.isFinite()
        ) {
            return null
        }

        if (open <= 0.0 ||
            high <= 0.0 ||
            low <= 0.0 ||
            close <= 0.0
        ) {
            return null
        }

        if (high < low ||
            high < open ||
            high < close ||
            low > open ||
            low > close
        ) {
            return null
        }

        val rawTimestamp = jsonLong(
            item,
            "timestamp",
            "time",
            "t",
            "date"
        )

        val timestamp = normalizeTimestamp(rawTimestamp)
            ?: return null

        val volume = jsonLong(item, "volume", "v", "vol")
        if (volume < 0L) return null

        return Candle(
            symbol = symbol,
            timeframe = timeframe,
            timestamp = timestamp,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume
        )
    }

    private fun parseInstrument(item: JSONObject): Instrument? {
        val symbol = normalizeSymbol(
            jsonString(item, "symbol", "tradingsymbol", "ticker")
        )

        if (symbol.isBlank()) return null

        val name = jsonString(
            item,
            "name",
            "company_name"
        ).trim()

        val exchange = normalizeExchange(
            jsonString(item, "exchange", "exch")
                .ifBlank { AppConfig.get().defaultExchange }
        )

        if (exchange.isBlank()) return null

        val tradingSymbol = jsonString(
            item,
            "trading_symbol",
            "tradingSymbol",
            "tradingsymbol"
        ).trim().ifBlank { symbol }

        val rawLotSize = jsonLong(
            item,
            "lot_size",
            "lotSize",
            "lot"
        )

        if (rawLotSize > Int.MAX_VALUE) return null

        val lotSize = rawLotSize.toInt().coerceAtLeast(1)

        val rawTickSize = jsonDouble(
            item,
            "tick_size",
            "tickSize"
        )

        val tickSize = if (rawTickSize.isFinite() &&
            rawTickSize > 0.0
        ) {
            rawTickSize
        } else {
            0.05
        }

        val segment = jsonString(
            item,
            "segment",
            "category"
        ).ifBlank { "EQ" }
            .toUpperCase(Locale.ROOT)

        val instrumentType = jsonString(
            item,
            "instrument_type",
            "instrumentType",
            "type"
        ).ifBlank { "EQ" }
            .toUpperCase(Locale.ROOT)

        return Instrument(
            symbol = symbol,
            name = name.ifBlank { symbol },
            exchange = exchange,
            segment = segment,
            instrumentType = instrumentType,
            tradingSymbol = tradingSymbol,
            token = jsonString(
                item,
                "token",
                "instrument_token"
            ).ifBlank { null },
            lotSize = lotSize,
            tickSize = tickSize,
            isActive = true
        )
    }

    private fun jsonString(
        obj: JSONObject,
        vararg keys: String
    ): String {
        for (key in keys) {
            if (obj.isNull(key)) continue

            val value = obj.opt(key)

            if (value != null && value != JSONObject.NULL) {
                return value.toString()
            }
        }

        return ""
    }

    private fun jsonDouble(
        obj: JSONObject,
        vararg keys: String
    ): Double {
        for (key in keys) {
            if (obj.isNull(key)) continue

            val value = obj.opt(key)

            if (value is Number) {
                return value.toDouble()
            }

            if (value is String) {
                val parsed = value.toDoubleOrNull()
                if (parsed != null) return parsed
            }
        }

        return 0.0
    }

    private fun jsonLong(
        obj: JSONObject,
        vararg keys: String
    ): Long {
        for (key in keys) {
            if (obj.isNull(key)) continue

            val value = obj.opt(key)

            if (value is Number) {
                val number = value.toDouble()

                if (!number.isFinite() ||
                    number < Long.MIN_VALUE.toDouble() ||
                    number > Long.MAX_VALUE.toDouble()
                ) {
                    continue
                }

                return number.toLong()
            }

            if (value is String) {
                val parsed = value.toLongOrNull()
                if (parsed != null) return parsed
            }
        }

        return 0L
    }

    private fun normalizeTimestamp(value: Long): Long? {
        if (value <= 0L) return null

        // Unix seconds -> milliseconds.
        if (value < 100_000_000_000L) {
            if (value > Long.MAX_VALUE / 1000L) return null
            return value * 1000L
        }

        // Reject implausibly large timestamps.
        if (value > 100_000_000_000_000L) return null

        return value
    }

    private fun normalizeSymbol(value: String): String {
        return value.trim().toUpperCase(Locale.ROOT)
    }

    private fun normalizeTimeframe(value: String): String {
        return value.trim().toUpperCase(Locale.ROOT)
    }

    private fun normalizeExchange(value: String): String {
        return value.trim().toUpperCase(Locale.ROOT)
    }
}

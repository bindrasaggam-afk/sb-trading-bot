package com.sb.backend.services

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

    fun fetchHistoricalCandles(
        instrument: Instrument,
        timeframe: String,
        fromDate: String,
        toDate: String
    ): Int {
        require(instrument.exchange == "NSE" ||
            instrument.exchange == "BSE") {
            "Historical candles currently support NSE/BSE only"
        }

        val token = instrument.token
        require(!token.isNullOrBlank()) {
            "Verified instrument token missing: ${instrument.symbol}"
        }

        val interval = when (timeframe.toUpperCase(Locale.ROOT)) {
            "5M", "5MIN" -> "5min"
            "15M", "15MIN" -> "15min"
            else -> throw IllegalArgumentException(
                "Only 5m and 15m candles are supported"
            )
        }

        val segment = segmentFor(instrument.exchange)
        val neoSymbol = URLEncoder.encode(
            "$segment|$token",
            "UTF-8"
        )

        val endpoint =
            "${baseUrl()}/market-data/1.0/historical/details" +
                "?neosymbol=$neoSymbol" +
                "&fromdate=$fromDate" +
                "&todate=$toDate" +
                "&interval=$interval"

        val root = JSONObject(request(endpoint))
        val data = root.optJSONObject("data")
            ?: throw Exception("Historical candle data missing")

        val rows = data.optJSONArray("candles")
            ?: throw Exception("Historical candles array missing")

        val candles = ArrayList<Candle>()

        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            if (row.length() < 6) continue

            val timestamp = parseCandleTime(row.getString(0))
            val open = row.getDouble(1)
            val high = row.getDouble(2)
            val low = row.getDouble(3)
            val close = row.getDouble(4)
            val volume = row.getDouble(5).toLong()

            candles.add(
                Candle(
                    symbol = instrument.symbol,
                    timeframe = interval,
                    timestamp = timestamp,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume,
                    isComplete = true
                )
            )
        }

        return marketRepository.saveCandles(
            symbol = instrument.symbol,
            exchange = instrument.exchange,
            timeframe = interval,
            candles = candles
        )
    }
}

package com.sb.backend.services

import android.util.Log
import com.sb.backend.repositories.InstrumentRepository
import com.sb.backend.security.KotakNeoSecureStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

data class KotakScripToken(
    val symbol: String,
    val token: String,
    val exchange: String,
    val segments: List<String>
)

data class ScripMasterSyncResult(
    val downloadedSegments: Int = 0,
    val parsedRecords: Int = 0,
    val matchedRecords: Int = 0,
    val updatedRows: Int = 0,
    val errors: List<String> = emptyList()
)

class KotakScripMasterService(
    private val secureStore: KotakNeoSecureStore,
    private val instrumentRepository: InstrumentRepository
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private data class SegmentConfig(
        val name: String,
        val exchange: String,
        val databaseSegments: List<String>,
        val cash: Boolean
    )

    private data class ScripRow(
        val symbol: String,
        val token: String,
        val exchange: String,
        val feedSegment: String,
        val group: String,
        val instrumentType: String,
        val optionType: String,
        val expiryMillis: Long?
    )

    private val segments = listOf(
        SegmentConfig(
            name = "nse_cm",
            exchange = "NSE",
            databaseSegments = listOf("EQ", "ETF"),
            cash = true
        ),
        SegmentConfig(
            name = "bse_cm",
            exchange = "BSE",
            databaseSegments = listOf("EQ"),
            cash = true
        ),
        SegmentConfig(
            name = "mcx_fo",
            exchange = "MCX",
            databaseSegments = listOf("COMMODITY"),
            cash = false
        )
    )

    fun sync(): ScripMasterSyncResult {
        val accessToken = secureStore.getAccessToken()
        require(accessToken.isNotBlank()) {
            "Kotak Access Token missing"
        }

        val filePaths = fetchFilePaths(accessToken)
        val updates = LinkedHashMap<String, KotakScripToken>()
        val errors = ArrayList<String>()
        var downloaded = 0
        var parsed = 0

        segments.forEach { config ->
            val url = filePaths[config.name] ?: return@forEach

            try {
                val rows = downloadSegment(config, url)
                downloaded++
                parsed += rows.size

                val selected = if (config.cash) {
                    selectCashRows(rows)
                } else {
                    selectFrontMonthRows(config, rows)
                }

                selected.forEach { row ->
                    val key = buildString {
                        append(row.exchange)
                        append('|')
                        append(normalizeSymbol(row.symbol))
                        append('|')
                        append(config.databaseSegments.joinToString(","))
                    }

                    updates[key] = KotakScripToken(
                        symbol = row.symbol,
                        token = row.token,
                        exchange = row.exchange,
                        segments = config.databaseSegments
                    )
                }
            } catch (e: Exception) {
                val message = "${config.name}: ${e.message}"
                errors.add(message)
                Log.e(TAG, "Scrip master segment failed: $message", e)
            }
        }

        val updatedRows = instrumentRepository.applyKotakScripTokens(
            updates.values.toList()
        )

        val result = ScripMasterSyncResult(
            downloadedSegments = downloaded,
            parsedRecords = parsed,
            matchedRecords = updates.size,
            updatedRows = updatedRows,
            errors = errors
        )

        Log.d(
            TAG,
            "Scrip master sync: segments=${result.downloadedSegments}, " +
                "parsed=${result.parsedRecords}, " +
                "matched=${result.matchedRecords}, " +
                "updated=${result.updatedRows}, " +
                "errors=${result.errors.size}"
        )

        return result
    }

    private fun fetchFilePaths(accessToken: String): Map<String, String> {
        val endpoint = "${baseUrl()}/script-details/1.0/masterscrip/file-paths"
        val root = JSONObject(requestText(endpoint, accessToken))
        val data = root.optJSONObject("data") ?: root

        val files = data.optJSONArray("filesPaths")
            ?: root.optJSONArray("filesPaths")
            ?: throw Exception("Kotak scrip master filesPaths missing")

        val baseFolder = data.optString(
            "baseFolder",
            root.optString("baseFolder", "")
        )

        val chosen = LinkedHashMap<String, String>()

        for (index in 0 until files.length()) {
            val raw = files.optString(index, "").trim()
            if (raw.isBlank()) continue

            val url = if (raw.startsWith("http", true)) {
                raw
            } else {
                "${baseFolder.trimEnd('/')}/${raw.trimStart('/')}"
            }

            val segment = segmentName(url) ?: continue
            val existing = chosen[segment]

            if (existing == null || pathScore(url) > pathScore(existing)) {
                chosen[segment] = url
            }
        }

        if (chosen.isEmpty()) {
            throw Exception("Kotak scrip master has no supported segments")
        }

        return chosen
    }

    private fun requestText(url: String, accessToken: String): String {
        val connection = openConnection(url, accessToken)

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw Exception("Kotak scrip master HTTP $code: $body")
            }

            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadSegment(
        config: SegmentConfig,
        url: String
    ): List<ScripRow> {
        val request = Request.Builder()
            .url(url)
            .header("Date", awsDateHeader())
            .header("Accept", "text/csv,*/*")
            .header("User-Agent", "SBApp/1.0")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body()?.string().orEmpty()
                throw Exception("Kotak CSV HTTP ${response.code()}: $body")
            }

            val body = response.body()
                ?: throw Exception("Kotak CSV response body missing")

            return body.byteStream()
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> parseCsv(reader, config) }
        }
    }

    private fun awsDateHeader(): String {
        val formatter = java.text.SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
            java.util.Locale.US
        )
        formatter.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return formatter.format(java.util.Date())
    }

    private fun openConnection(
        url: String,
        accessToken: String
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection

        connection.requestMethod = "GET"
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Authorization", accessToken)
        connection.setRequestProperty("Accept", "application/json,text/csv,*/*")
        connection.setRequestProperty("User-Agent", "SBApp/1.0")

        return connection
    }

    private fun parseCsv(
        reader: java.io.BufferedReader,
        config: SegmentConfig
    ): List<ScripRow> {
        val headerLine = reader.readLine() ?: return emptyList()
        val headers = parseCsvLine(headerLine)
            .map { it.trim().removePrefix("\uFEFF").toLowerCase(Locale.ROOT) }
        val indexes = headers.withIndex().associate { it.value to it.index }

        val result = ArrayList<ScripRow>()

        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine

            val columns = parseCsvLine(line)

            fun value(name: String): String {
                val index = indexes[name] ?: return ""
                return columns.getOrNull(index)?.trim().orEmpty()
            }

            val token = value("psymbol")
            val symbol = value("psymbolname")
            val tradingSymbol = value("ptrdsymbol")
            val group = value("pgroup").toUpperCase(Locale.ROOT)
            val instrumentType = value("pinsttype").toUpperCase(Locale.ROOT)
            val optionType = value("poptiontype").toUpperCase(Locale.ROOT)

            if (token.isBlank() || symbol.isBlank()) return@forEachLine
            if (token.toLongOrNull() == null) return@forEachLine

            if (config.cash) {
                val validGroup = when (config.name) {
                    "nse_cm" -> group == "EQ" ||
                        tradingSymbol.endsWith("-EQ", ignoreCase = true)
                    "bse_cm" -> group in BSE_CASH_GROUPS
                    else -> true
                }

                if (!validGroup) return@forEachLine
            } else {
                if (instrumentType.contains("OPT")) return@forEachLine
                if (optionType == "CE" || optionType == "PE") {
                    return@forEachLine
                }
            }

            val exchange = value("pexchange").toUpperCase(Locale.ROOT)
                .ifBlank { config.exchange }

            if (!exchange.equals(config.exchange, ignoreCase = true)) {
                return@forEachLine
            }

            val expiry = parseExpiry(value("pexpirydate"))

            result.add(
                ScripRow(
                    symbol = symbol,
                    token = token,
                    exchange = exchange,
                    feedSegment = config.name,
                    group = group,
                    instrumentType = instrumentType,
                    optionType = optionType,
                    expiryMillis = expiry
                )
            )
        }

        return result
    }

    private fun selectCashRows(
        rows: List<ScripRow>
    ): List<ScripRow> {
        val selected = LinkedHashMap<String, ScripRow>()

        rows.forEach { row ->
            val key = "${row.exchange}|${normalizeSymbol(row.symbol)}"
            val existing = selected[key]

            if (existing == null ||
                groupRank(row.group) < groupRank(existing.group)
            ) {
                selected[key] = row
            }
        }

        return selected.values.toList()
    }

    private fun selectFrontMonthRows(
        config: SegmentConfig,
        rows: List<ScripRow>
    ): List<ScripRow> {
        val grouped = LinkedHashMap<String, MutableList<ScripRow>>()

        rows.forEach { row ->
            if (!row.exchange.equals(config.exchange, ignoreCase = true)) {
                return@forEach
            }

            val key = "${row.exchange}|${normalizeSymbol(row.symbol)}"
            grouped.getOrPut(key) { ArrayList() }.add(row)
        }

        val now = System.currentTimeMillis()
        val threshold = now - 3L * 24L * 60L * 60L * 1000L

        return grouped.values.mapNotNull { contracts ->
            var nearestFuture: ScripRow? = null
            var nearestExpiry = Long.MAX_VALUE
            var latest: ScripRow? = null
            var latestExpiry = Long.MIN_VALUE

            contracts.forEach { contract ->
                val expiry = contract.expiryMillis

                if (expiry != null) {
                    if (expiry >= threshold && expiry < nearestExpiry) {
                        nearestFuture = contract
                        nearestExpiry = expiry
                    }

                    if (expiry > latestExpiry) {
                        latest = contract
                        latestExpiry = expiry
                    }
                }
            }

            nearestFuture ?: latest ?: contracts.firstOrNull()
        }
    }

    private fun parseExpiry(raw: String): Long? {
        val seconds = raw.toLongOrNull() ?: return null
        if (seconds <= 0L) return null

        var millis = seconds * 1000L
        val now = System.currentTimeMillis()

        // Some Kotak transformed files encode expiry dates ten years in the past.
        if (millis < now - EIGHT_YEARS_MS) {
            millis += TEN_YEARS_MS
        }

        return millis
    }

    private fun groupRank(group: String): Int {
        return when (group.toUpperCase(Locale.ROOT)) {
            "EQ" -> 0
            "A" -> 1
            "B" -> 2
            "T" -> 3
            "Z" -> 4
            "M" -> 5
            "MT" -> 6
            "X" -> 7
            "XT" -> 8
            else -> 99
        }
    }

    private fun segmentName(url: String): String? {
        val file = url.substringBefore('?')
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .toLowerCase(Locale.ROOT)

        return segments.firstOrNull {
            file == it.name ||
                file.startsWith("${it.name}-")
        }?.name
    }

    private fun pathScore(url: String): Int {
        var score = 0
        if (url.contains("/transformed/")) score += 2
        if (url.contains("/transformed-v1/")) score += 1
        if (!url.contains("-v1.")) score += 4
        return score
    }

    private fun normalizeSymbol(value: String): String {
        return value.replace(" ", "")
            .trim()
            .toUpperCase(Locale.ROOT)
    }

    private fun baseUrl(): String {
        return secureStore.getTradeBaseUrl().trimEnd('/').also {
            require(it.isNotBlank()) {
                "Kotak trade Base URL missing. Authenticate again."
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = ArrayList<String>()
        val value = StringBuilder()
        var quoted = false
        var index = 0

        while (index < line.length) {
            val char = line[index]

            when {
                char == '"' && quoted &&
                    index + 1 < line.length &&
                    line[index + 1] == '"' -> {
                    value.append('"')
                    index++
                }

                char == '"' -> quoted = !quoted

                char == ',' && !quoted -> {
                    result.add(value.toString())
                    value.setLength(0)
                }

                else -> value.append(char)
            }

            index++
        }

        result.add(value.toString())
        return result
    }

    companion object {
        private const val TAG = "KotakScripMaster"

        private const val EIGHT_YEARS_MS = 8L * 365L * 24L * 60L * 60L * 1000L
        private const val TEN_YEARS_MS = 10L * 365L * 24L * 60L * 60L * 1000L

        private val BSE_CASH_GROUPS = setOf(
            "A", "B", "T", "Z", "M", "MT", "X", "XT"
        )
    }
}

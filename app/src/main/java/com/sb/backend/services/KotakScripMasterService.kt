package com.sb.backend.services

import android.util.Log
import com.sb.backend.models.Instrument
import com.sb.backend.repositories.InstrumentRepository
import com.sb.backend.security.KotakNeoSecureStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class KotakScripToken(
    val symbol: String,
    val token: String,
    val exchange: String,
    val segments: List<String>,
    val tradingSymbol: String = symbol,
    val expiryMillis: Long? = null
)

data class ScripMasterSyncResult(
    val downloadedSegments: Int = 0,
    val parsedRecords: Int = 0,
    val matchedRecords: Int = 0,
    val updatedRows: Int = 0,
    val mcxContractsSaved: Int = 0,
    val mcxCommodities: Int = 0,
    val mcxExpiredDeactivated: Int = 0,
    val mcxStaleDeactivated: Int = 0,
    val mcxActiveContracts: Int = 0,
    val errors: List<String> = emptyList()
)

class KotakScripMasterService(
    private val secureStore: KotakNeoSecureStore,
    private val instrumentRepository: InstrumentRepository
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    private data class SegmentConfig(
        val name: String,
        val exchange: String,
        val databaseSegments: List<String>,
        val cash: Boolean
    )

    private data class ScripRow(
        val symbol: String,
        val tradingSymbol: String,
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
            // ETF को EQ के साथ map नहीं करें।
            // ETF के लिए अलग master classification आवश्यक है।
            databaseSegments = listOf("EQ"),
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

    /**
     * Downloads and synchronizes Kotak scrip master.
     *
     * Important:
     * MCX stale contracts are deactivated only when the MCX master
     * was downloaded and valid futures contracts were parsed.
     */
    fun sync(): ScripMasterSyncResult {

        val accessToken = secureStore.getAccessToken()

        require(accessToken.isNotBlank()) {
            "Kotak Access Token missing"
        }

        val filePaths = fetchFilePaths(accessToken)

        val cashUpdates =
            LinkedHashMap<String, KotakScripToken>()

        val mcxContracts =
            LinkedHashMap<String, Instrument>()

        val errors = ArrayList<String>()

        var downloaded = 0
        var parsed = 0

        var mcxMasterDownloaded = false
        var mcxMasterHasValidContracts = false

        segments.forEach { config ->

            val url = filePaths[config.name]

            if (url.isNullOrBlank()) {
                val message =
                    "${config.name}: master file path missing"

                errors.add(message)

                Log.e(TAG, message)

                return@forEach
            }

            try {

                val rows = downloadSegment(config, url)

                downloaded++
                parsed += rows.size

                val selected = if (config.cash) {
                    selectCashRows(rows)
                } else {
                    selectFrontMonthRows(config, rows)
                }

                if (!config.cash) {

                    mcxMasterDownloaded = true

                    // Empty/invalid MCX data must never deactivate
                    // previously stored valid futures contracts.
                    mcxMasterHasValidContracts =
                        selected.isNotEmpty()
                }

                selected.forEach { row ->

                    if (config.cash) {

                        val identity =
                            normalizeSymbol(row.symbol)

                        val key = buildString {
                            append(row.exchange)
                            append('|')
                            append(identity)
                            append('|')
                            append(config.databaseSegments.joinToString(","))
                        }

                        cashUpdates[key] = KotakScripToken(
                            symbol = row.symbol,
                            token = row.token,
                            exchange = row.exchange,
                            segments = config.databaseSegments,
                            tradingSymbol = row.tradingSymbol,
                            expiryMillis = row.expiryMillis
                        )

                    } else {

                        val expiry = row.expiryMillis
                            ?: return@forEach

                        val tradingSymbol =
                            row.tradingSymbol.trim()

                        if (tradingSymbol.isBlank()) {
                            return@forEach
                        }

                        val key =
                            "${row.exchange}|${normalizeSymbol(tradingSymbol)}"

                        mcxContracts[key] = Instrument(
                            symbol = tradingSymbol,
                            name = row.symbol,
                            exchange = "MCX",
                            segment = "COMMODITY",
                            instrumentType = "FUT",
                            tradingSymbol = tradingSymbol,
                            token = row.token,
                            expiryMillis = expiry
                        )
                    }
                }

                Log.d(
                    TAG,
                    "${config.name}: " +
                        "parsed=${rows.size}, selected=${selected.size}"
                )

            } catch (e: Exception) {

                val message =
                    "${config.name}: ${e.message ?: "Unknown error"}"

                errors.add(message)

                if (!config.cash) {
                    mcxMasterDownloaded = false
                    mcxMasterHasValidContracts = false
                }

                Log.e(
                    TAG,
                    "Scrip master segment failed: $message",
                    e
                )
            }
        }

        // ------------------------------------------------------------
        // NSE / BSE CASH TOKEN UPDATE
        // ------------------------------------------------------------

        val cashUpdatedRows =
            if (cashUpdates.isNotEmpty()) {
                instrumentRepository.applyKotakScripTokens(
                    cashUpdates.values.toList()
                )
            } else {
                0
            }

        // ------------------------------------------------------------
        // MCX CONTRACT UPSERT
        // ------------------------------------------------------------

        val mcxUpdatedRows =
            if (mcxContracts.isNotEmpty()) {
                instrumentRepository.upsertMcxContracts(
                    mcxContracts.values.toList()
                )
            } else {
                0
            }

        // ------------------------------------------------------------
        // EXPIRED MCX CONTRACTS
        // ------------------------------------------------------------

        // Expired contracts can be deactivated independently of the
        // current master download because their stored expiry is known.
        val mcxExpiredDeactivated =
            instrumentRepository.deactivateExpiredMcxContracts()

        // ------------------------------------------------------------
        // MCX STALE CONTRACT PROTECTION
        // ------------------------------------------------------------

        val mcxStaleDeactivated = if (
            mcxMasterDownloaded &&
            mcxMasterHasValidContracts &&
            mcxContracts.isNotEmpty()
        ) {

            instrumentRepository.deactivateMcxFuturesNotIn(
                mcxContracts.values.map { it.symbol }
            )

        } else {

            Log.w(
                TAG,
                "MCX stale deactivation skipped. " +
                    "downloaded=$mcxMasterDownloaded, " +
                    "validContracts=$mcxMasterHasValidContracts, " +
                    "savedCandidates=${mcxContracts.size}"
            )

            0
        }

        // ------------------------------------------------------------
        // FINAL COUNTS
        // ------------------------------------------------------------

        val mcxActiveContracts =
            instrumentRepository.countActiveMcxFutures()

        val mcxCommodityCount = mcxContracts.values
            .map { normalizeSymbol(it.name) }
            .filter { it.isNotBlank() }
            .distinct()
            .size

        val result = ScripMasterSyncResult(
            downloadedSegments = downloaded,
            parsedRecords = parsed,
            matchedRecords = cashUpdates.size + mcxContracts.size,
            updatedRows = cashUpdatedRows + mcxUpdatedRows,
            mcxContractsSaved = mcxUpdatedRows,
            mcxCommodities = mcxCommodityCount,
            mcxExpiredDeactivated = mcxExpiredDeactivated,
            mcxStaleDeactivated = mcxStaleDeactivated,
            mcxActiveContracts = mcxActiveContracts,
            errors = errors
        )

        Log.d(
            TAG,
            "Scrip master sync: " +
                "segments=${result.downloadedSegments}, " +
                "parsed=${result.parsedRecords}, " +
                "matched=${result.matchedRecords}, " +
                "cashUpdated=$cashUpdatedRows, " +
                "mcxSaved=$mcxUpdatedRows, " +
                "mcxCommodities=$mcxCommodityCount, " +
                "mcxExpiredDeactivated=$mcxExpiredDeactivated, " +
                "mcxStaleDeactivated=$mcxStaleDeactivated, " +
                "mcxActiveContracts=$mcxActiveContracts, " +
                "totalUpdated=${result.updatedRows}, " +
                "errors=${result.errors.size}"
        )

        return result
    }

    // =================================================================
    // FILE PATH DISCOVERY
    // =================================================================

    private fun fetchFilePaths(
        accessToken: String
    ): Map<String, String> {

        val endpoint =
            "${baseUrl()}/script-details/1.0/masterscrip/file-paths"

        val root = JSONObject(
            requestText(endpoint, accessToken)
        )

        val data = root.optJSONObject("data") ?: root

        val files = data.optJSONArray("filesPaths")
            ?: root.optJSONArray("filesPaths")
            ?: throw Exception(
                "Kotak scrip master filesPaths missing"
            )

        val baseFolder = data.optString(
            "baseFolder",
            root.optString("baseFolder", "")
        ).trim()

        val chosen = LinkedHashMap<String, String>()

        for (index in 0 until files.length()) {

            val raw = files.optString(index, "").trim()

            if (raw.isBlank()) continue

            val resolvedUrl = if (
                raw.startsWith("https://", ignoreCase = true)
            ) {
                raw
            } else {

                if (baseFolder.isBlank()) {
                    Log.w(
                        TAG,
                        "Ignoring relative master path without baseFolder: $raw"
                    )
                    continue
                }

                "${baseFolder.trimEnd('/')}/${raw.trimStart('/')}"
            }

            validateHttpsUrl(resolvedUrl)

            val segment = segmentName(resolvedUrl)
                ?: continue

            val existing = chosen[segment]

            if (
                existing == null ||
                pathScore(resolvedUrl) > pathScore(existing)
            ) {
                chosen[segment] = resolvedUrl
            }
        }

        if (chosen.isEmpty()) {
            throw Exception(
                "Kotak scrip master has no supported HTTPS segments"
            )
        }

        return chosen
    }

    // =================================================================
    // HTTP REQUEST
    // =================================================================

    private fun requestText(
        url: String,
        accessToken: String
    ): String {

        validateHttpsUrl(url)

        val connection =
            openConnection(url, accessToken)

        try {

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                throw Exception(
                    "Kotak scrip master HTTP $code: $body"
                )
            }

            if (body.isBlank()) {
                throw Exception(
                    "Kotak scrip master returned an empty response"
                )
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

        validateHttpsUrl(url)

        val request = Request.Builder()
            .url(url)
            .header("Date", awsDateHeader())
            .header("Accept", "text/csv,*/*")
            .header("User-Agent", "SBApp/1.0")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {

                val body = response.body()
                    ?.string()
                    .orEmpty()

                throw Exception(
                    "Kotak CSV HTTP ${response.code()}: $body"
                )
            }

            val body = response.body()
                ?: throw Exception(
                    "Kotak CSV response body missing"
                )

            return body.byteStream()
                .bufferedReader(Charsets.UTF_8)
                .use { reader ->
                    parseCsv(reader, config)
                }
        }
    }

    private fun validateHttpsUrl(value: String) {

        val parsed = URL(value)

        require(
            parsed.protocol.equals("https", ignoreCase = true)
        ) {
            "Only HTTPS URLs are allowed: ${parsed.protocol}"
        }

        require(parsed.host.isNotBlank()) {
            "Invalid master URL host"
        }
    }

    private fun openConnection(
        url: String,
        accessToken: String
    ): HttpURLConnection {

        validateHttpsUrl(url)

        val connection =
            URL(url).openConnection() as HttpURLConnection

        connection.requestMethod = "GET"
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true

        connection.setRequestProperty(
            "Authorization",
            accessToken
        )

        connection.setRequestProperty(
            "Accept",
            "application/json,text/csv,*/*"
        )

        connection.setRequestProperty(
            "User-Agent",
            "SBApp/1.0"
        )

        return connection
    }

    // =================================================================
    // DATE HEADER
    // =================================================================

    private fun awsDateHeader(): String {

        val formatter = SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
            Locale.US
        )

        formatter.timeZone =
            TimeZone.getTimeZone("GMT")

        return formatter.format(Date())
    }

    // =================================================================
    // CSV PARSER
    // =================================================================

    private fun parseCsv(
        reader: BufferedReader,
        config: SegmentConfig
    ): List<ScripRow> {

        val headerLine = reader.readLine()
            ?: throw Exception(
                "${config.name}: CSV header missing"
            )

        val headers = parseCsvLine(headerLine)
            .map {
                it.trim()
                    .removePrefix("\uFEFF")
                    .lowercase(Locale.ROOT)
            }

        val indexes = headers
            .withIndex()
            .associate { it.value to it.index }

        val requiredHeaders = listOf(
            "psymbol",
            "psymbolname",
            "ptrdsymbol",
            "pexchange"
        )

        val missingHeaders = requiredHeaders.filter {
            it !in indexes
        }

        if (missingHeaders.isNotEmpty()) {
            throw Exception(
                "${config.name}: required CSV columns missing: " +
                    missingHeaders.joinToString()
            )
        }

        val result = ArrayList<ScripRow>()

        reader.forEachLine { line ->

            if (line.isBlank()) {
                return@forEachLine
            }

            val columns = parseCsvLine(line)

            fun value(name: String): String {

                val index = indexes[name]
                    ?: return ""

                return columns
                    .getOrNull(index)
                    ?.trim()
                    .orEmpty()
            }

            val token = value("psymbol")
            val symbol = value("psymbolname")
            val tradingSymbol = value("ptrdsymbol")

            val group = value("pgroup")
                .uppercase(Locale.ROOT)

            val instrumentType = value("pinsttype")
                .uppercase(Locale.ROOT)

            val optionType = value("poptiontype")
                .uppercase(Locale.ROOT)

            if (
                token.isBlank() ||
                symbol.isBlank()
            ) {
                return@forEachLine
            }

            if (token.toLongOrNull() == null) {
                return@forEachLine
            }

            if (token.toLong() <= 0L) {
                return@forEachLine
            }

            if (config.cash) {

                val validGroup = when (config.name) {

                    "nse_cm" -> {
                        group == "EQ" ||
                            tradingSymbol.endsWith(
                                "-EQ",
                                ignoreCase = true
                            )
                    }

                    "bse_cm" -> {
                        group in BSE_CASH_GROUPS
                    }

                    else -> true
                }

                if (!validGroup) {
                    return@forEachLine
                }

            } else {

                // Only actual commodity futures.
                // Options and index futures are excluded.
                if (instrumentType != "FUTCOM") {
                    return@forEachLine
                }

                if (
                    optionType == "CE" ||
                    optionType == "PE"
                ) {
                    return@forEachLine
                }
            }

            val exchange = value("pexchange")
                .uppercase(Locale.ROOT)
                .ifBlank { config.exchange }

            if (!exchange.equals(config.exchange, true)) {
                return@forEachLine
            }

            val rawExpiry = value("lexpirydate")
                .ifBlank {
                    value("pexpirydate")
                }

            val expiry = parseExpiry(
                raw = rawExpiry,
                config = config,
                symbol = symbol
            )

            val actualTradingSymbol =
                tradingSymbol.ifBlank { symbol }

            result.add(
                ScripRow(
                    symbol = symbol,
                    tradingSymbol = actualTradingSymbol,
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

    // =================================================================
    // CASH ROW SELECTION
    // =================================================================

    private fun selectCashRows(
        rows: List<ScripRow>
    ): List<ScripRow> {

        val selected =
            LinkedHashMap<String, ScripRow>()

        rows.forEach { row ->

            val key =
                "${row.exchange}|${normalizeSymbol(row.symbol)}"

            val existing = selected[key]

            if (
                existing == null ||
                groupRank(row.group) < groupRank(existing.group)
            ) {
                selected[key] = row
            }
        }

        return selected.values.toList()
    }

    // =================================================================
    // MCX FRONT THREE CONTRACT SELECTION
    // =================================================================

    /**
     * Selects the next three valid futures contracts for each
     * commodity using expiry and trading symbol.
     */
    private fun selectFrontMonthRows(
        config: SegmentConfig,
        rows: List<ScripRow>
    ): List<ScripRow> {

        val grouped =
            LinkedHashMap<String, MutableList<ScripRow>>()

        val now = System.currentTimeMillis()

        rows.forEach { row ->

            if (!row.exchange.equals(config.exchange, true)) {
                return@forEach
            }

            if (row.tradingSymbol.isBlank()) {
                return@forEach
            }

            if (row.token.isBlank()) {
                return@forEach
            }

            if (row.token.toLongOrNull() == null) {
                return@forEach
            }

            if (row.instrumentType != "FUTCOM") {
                return@forEach
            }

            if (
                row.optionType == "CE" ||
                row.optionType == "PE"
            ) {
                return@forEach
            }

            val expiry = row.expiryMillis
                ?: return@forEach

            if (expiry <= now) {
                return@forEach
            }

            val key =
                "${row.exchange}|${normalizeSymbol(row.symbol)}"

            grouped.getOrPut(key) {
                ArrayList()
            }.add(row)
        }

        val selected = ArrayList<ScripRow>()

        grouped.values.forEach { contracts ->

            val nextThree = contracts
                .distinctBy {
                    normalizeSymbol(it.tradingSymbol)
                }
                .sortedBy {
                    it.expiryMillis ?: Long.MAX_VALUE
                }
                .take(3)

            selected.addAll(nextThree)
        }

        return selected
    }

    // =================================================================
    // EXPIRY PARSER
    // =================================================================

    private fun parseExpiry(
        raw: String,
        config: SegmentConfig,
        symbol: String
    ): Long? {

        val value = raw.trim()

        if (
            value.isBlank() ||
            value == "0"
        ) {
            return null
        }

        val numeric = value.toLongOrNull()

        if (numeric == null) {

            Log.w(
                TAG,
                "Unrecognised expiry '$value' for " +
                    "${config.name}:$symbol"
            )

            return null
        }

        if (numeric <= 0L) {
            return null
        }

        val millis = try {

            when {
                numeric >= MILLIS_THRESHOLD -> numeric

                else -> Math.multiplyExact(
                    numeric,
                    1000L
                )
            }

        } catch (e: ArithmeticException) {

            Log.w(
                TAG,
                "Expiry overflow rejected: $value"
            )

            return null
        }

        val now = System.currentTimeMillis()

        val earliest = now - ONE_YEAR_MS
        val latest = now + FIVE_YEARS_MS

        if (
            millis < earliest ||
            millis > latest
        ) {

            Log.w(
                TAG,
                "Rejected implausible expiry '$value' " +
                    "($millis) for ${config.name}:$symbol"
            )

            return null
        }

        return millis
    }

    // =================================================================
    // GROUP PRIORITY
    // =================================================================

    private fun groupRank(group: String): Int {

        return when (
            group.uppercase(Locale.ROOT)
        ) {

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

    // =================================================================
    // FILE NAME / SEGMENT DETECTION
    // =================================================================

    private fun segmentName(url: String): String? {

        val file = url.substringBefore('?')
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .lowercase(Locale.ROOT)

        return segments.firstOrNull {
            file == it.name ||
                file.startsWith("${it.name}-")
        }?.name
    }

    private fun pathScore(url: String): Int {

        var score = 0

        if (url.contains("/transformed/")) {
            score += 2
        }

        if (url.contains("/transformed-v1/")) {
            score += 1
        }

        if (!url.contains("-v1.")) {
            score += 4
        }

        return score
    }

    // =================================================================
    // SYMBOL NORMALIZATION
    // =================================================================

    private fun normalizeSymbol(value: String): String {

        return value
            .trim()
            .replace(" ", "")
            .uppercase(Locale.ROOT)
    }

    // =================================================================
    // BASE URL
    // =================================================================

    private fun baseUrl(): String {

        val value = secureStore.getTradeBaseUrl()
            .trimEnd('/')

        require(value.isNotBlank()) {
            "Kotak trade Base URL missing. Authenticate again."
        }

        validateHttpsUrl(value)

        return value
    }

    // =================================================================
    // CSV LINE PARSER
    // =================================================================

    private fun parseCsvLine(
        line: String
    ): List<String> {

        val result = ArrayList<String>()
        val value = StringBuilder()

        var quoted = false
        var index = 0

        while (index < line.length) {

            val char = line[index]

            when {

                char == '"' &&
                    quoted &&
                    index + 1 < line.length &&
                    line[index + 1] == '"' -> {

                    value.append('"')
                    index++
                }

                char == '"' -> {
                    quoted = !quoted
                }

                char == ',' && !quoted -> {

                    result.add(
                        value.toString()
                    )

                    value.setLength(0)
                }

                else -> {
                    value.append(char)
                }
            }

            index++
        }

        if (quoted) {
            throw IllegalArgumentException(
                "Malformed CSV row: unterminated quoted field"
            )
        }

        result.add(
            value.toString()
        )

        return result
    }

    companion object {

        private const val TAG = "KotakScripMaster"

        private const val MILLIS_THRESHOLD =
            100_000_000_000L

        private const val ONE_YEAR_MS =
            365L * 24L * 60L * 60L * 1000L

        private const val FIVE_YEARS_MS =
            5L * 365L * 24L * 60L * 60L * 1000L

        private val BSE_CASH_GROUPS = setOf(
            "A",
            "B",
            "T",
            "Z",
            "M",
            "MT",
            "X",
            "XT"
        )
    }
}
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

data class SegmentSyncStats(
    val segment: String,
    val downloaded: Boolean = false,
    val parsedRows: Int = 0,
    val validRows: Int = 0,
    val invalidTokenRows: Int = 0,
    val invalidSymbolRows: Int = 0,
    val filteredRows: Int = 0,
    val selectedRows: Int = 0,
    val updatedRows: Int = 0,
    val error: String? = null
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
    val errors: List<String> = emptyList(),
    val segmentStats: List<SegmentSyncStats> = emptyList()
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

    private data class SegmentParseResult(
        val rows: List<ScripRow>,
        val parsedRows: Int,
        val invalidTokenRows: Int,
        val invalidSymbolRows: Int,
        val filteredRows: Int
    )

    private class MutableSegmentStats(val name: String) {
        var downloaded: Boolean = false
        var parsedRows: Int = 0
        var invalidTokenRows: Int = 0
        var invalidSymbolRows: Int = 0
        var filteredRows: Int = 0
        var selectedRows: Int = 0
        var updatedRows: Int = 0
        var error: String? = null

        fun snapshot(): SegmentSyncStats {
            val valid = (parsedRows - invalidTokenRows -
                invalidSymbolRows - filteredRows).coerceAtLeast(0)
            return SegmentSyncStats(
                segment = name,
                downloaded = downloaded,
                parsedRows = parsedRows,
                validRows = valid,
                invalidTokenRows = invalidTokenRows,
                invalidSymbolRows = invalidSymbolRows,
                filteredRows = filteredRows,
                selectedRows = selectedRows,
                updatedRows = updatedRows,
                error = error
            )
        }
    }

    private val segments = listOf(
        SegmentConfig(
            name = "nse_cm",
            exchange = "NSE",
            // NSE master contains equities and ETFs.
            // INDEX rows are subscribed by feed name, not by token.
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

        val errors = ArrayList<String>()
        val segmentStats = segments.associate { it.name to MutableSegmentStats(it.name) }
        val filePaths = try {
            fetchFilePaths(accessToken)
        } catch (e: Exception) {
            val message = "Master file-path discovery failed: ${e.message ?: "Unknown error"}"
            errors.add(message)
            Log.e(TAG, message, e)
            emptyMap()
        }

        val cashUpdatesBySegment = LinkedHashMap<String, LinkedHashMap<String, KotakScripToken>>()
        segments.filter { it.cash }.forEach {
            cashUpdatesBySegment[it.name] = LinkedHashMap()
        }
        val mcxContracts = LinkedHashMap<String, Instrument>()

        var downloaded = 0
        var parsed = 0
        var mcxMasterDownloaded = false
        var mcxMasterHasValidContracts = false

        segments.forEach { config ->
            val stats = segmentStats.getValue(config.name)
            val url = filePaths[config.name]

            if (url.isNullOrBlank()) {
                val message = "${config.name}: master file path missing"
                stats.error = message
                errors.add(message)
                Log.e(TAG, message)
                return@forEach
            }

            try {
                val parsedResult = downloadSegment(config, url)
                stats.downloaded = true
                downloaded++
                stats.parsedRows = parsedResult.parsedRows
                stats.invalidTokenRows = parsedResult.invalidTokenRows
                stats.invalidSymbolRows = parsedResult.invalidSymbolRows
                stats.filteredRows = parsedResult.filteredRows
                parsed += parsedResult.parsedRows

                val selected = if (config.cash) {
                    selectCashRows(parsedResult.rows)
                } else {
                    selectFrontMonthRows(config, parsedResult.rows)
                }
                stats.selectedRows = selected.size

                if (!config.cash) {
                    mcxMasterDownloaded = true
                    mcxMasterHasValidContracts = selected.isNotEmpty()
                }

                selected.forEach { row ->
                    if (config.cash) {
                        val identity = normalizeSymbol(row.symbol)
                        val key = buildString {
                            append(row.exchange)
                            append('|')
                            append(identity)
                            append('|')
                            append(config.databaseSegments.joinToString(","))
                        }
                        cashUpdatesBySegment.getValue(config.name)[key] =
                            KotakScripToken(
                                symbol = row.symbol,
                                token = row.token,
                                exchange = row.exchange,
                                segments = config.databaseSegments,
                                tradingSymbol = row.tradingSymbol,
                                expiryMillis = row.expiryMillis
                            )
                    } else {
                        val expiry = row.expiryMillis ?: return@forEach
                        val tradingSymbol = row.tradingSymbol.trim()
                        if (tradingSymbol.isBlank()) return@forEach
                        val key = "${row.exchange}|${normalizeSymbol(tradingSymbol)}"
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
                    "${config.name}: parsed=${stats.parsedRows}, " +
                        "valid=${stats.snapshot().validRows}, " +
                        "selected=${stats.selectedRows}, " +
                        "invalidToken=${stats.invalidTokenRows}, " +
                        "invalidSymbol=${stats.invalidSymbolRows}, " +
                        "filtered=${stats.filteredRows}"
                )
            } catch (e: Exception) {
                val message = "${config.name}: ${e.message ?: "Unknown error"}"
                stats.error = message
                errors.add(message)
                if (!config.cash) {
                    mcxMasterDownloaded = false
                    mcxMasterHasValidContracts = false
                }
                Log.e(TAG, "Scrip master segment failed: $message", e)
            }
        }

        var cashUpdatedRows = 0
        cashUpdatesBySegment.forEach { (segmentName, updates) ->
            if (updates.isEmpty()) return@forEach
            try {
                expandNseAliases(updates)
                val updated = instrumentRepository.applyKotakScripTokens(updates.values.toList())
                cashUpdatedRows += updated
                segmentStats.getValue(segmentName).updatedRows += updated
            } catch (e: Exception) {
                val message = "$segmentName: repository token update failed: ${e.message ?: "Unknown error"}"
                segmentStats.getValue(segmentName).error = message
                errors.add(message)
                Log.e(TAG, message, e)
            }
        }

        val mcxUpdatedRows = if (mcxContracts.isNotEmpty()) {
            try {
                val updated = instrumentRepository.upsertMcxContracts(mcxContracts.values.toList())
                segmentStats["mcx_fo"]?.updatedRows = updated
                updated
            } catch (e: Exception) {
                val message = "mcx_fo: repository contract update failed: ${e.message ?: "Unknown error"}"
                segmentStats["mcx_fo"]?.error = message
                errors.add(message)
                Log.e(TAG, message, e)
                0
            }
        } else {
            0
        }

        // Expired contracts are safe to deactivate from their stored expiry,
        // independently of whether today's MCX master download succeeded.
        val mcxExpiredDeactivated =
            instrumentRepository.deactivateExpiredMcxContracts()

        // Never deactivate current MCX contracts from an empty, invalid,
        // partially failed, or unusable master download.
        val mcxStaleDeactivated = if (
            mcxMasterDownloaded &&
            mcxMasterHasValidContracts &&
            mcxContracts.isNotEmpty() &&
            segmentStats["mcx_fo"]?.error == null
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

        val mcxActiveContracts = instrumentRepository.countActiveMcxFutures()
        val mcxCommodityCount = mcxContracts.values
            .map { normalizeSymbol(it.name) }
            .filter { it.isNotBlank() }
            .distinct()
            .size

        val cashMatched = cashUpdatesBySegment.values.fold(0) { total, updates -> total + updates.size }
        val result = ScripMasterSyncResult(
            downloadedSegments = downloaded,
            parsedRecords = parsed,
            matchedRecords = cashMatched + mcxContracts.size,
            updatedRows = cashUpdatedRows + mcxUpdatedRows,
            mcxContractsSaved = mcxUpdatedRows,
            mcxCommodities = mcxCommodityCount,
            mcxExpiredDeactivated = mcxExpiredDeactivated,
            mcxStaleDeactivated = mcxStaleDeactivated,
            mcxActiveContracts = mcxActiveContracts,
            errors = errors,
            segmentStats = segmentStats.values.map { it.snapshot() }
        )

        Log.d(
            TAG,
            "Scrip master sync: segments=${result.downloadedSegments}, " +
                "parsed=${result.parsedRecords}, matched=${result.matchedRecords}, " +
                "cashUpdated=$cashUpdatedRows, mcxSaved=$mcxUpdatedRows, " +
                "mcxCommodities=$mcxCommodityCount, " +
                "mcxExpiredDeactivated=$mcxExpiredDeactivated, " +
                "mcxStaleDeactivated=$mcxStaleDeactivated, " +
                "mcxActiveContracts=$mcxActiveContracts, " +
                "errors=${result.errors.size}, " +
                "segmentStats=${result.segmentStats}"
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
    ): SegmentParseResult {

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
    ): SegmentParseResult {

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
        var parsedRows = 0
        var invalidTokenRows = 0
        var invalidSymbolRows = 0
        var filteredRows = 0

        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            parsedRows++

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

            if (symbol.isBlank()) {
                invalidSymbolRows++
                return@forEachLine
            }

            val numericToken = token.toLongOrNull()
            if (numericToken == null || numericToken <= 0L) {
                invalidTokenRows++
                return@forEachLine
            }

            if (config.cash) {

                val validGroup = when (config.name) {

                    "nse_cm" -> {
                        group in NSE_CASH_GROUPS ||
                            NSE_CASH_GROUPS.any {
                                tradingSymbol.endsWith(
                                    "-$it",
                                    ignoreCase = true
                                )
                            }
                    }

                    "bse_cm" -> {
                        group in BSE_CASH_GROUPS
                    }

                    else -> true
                }

                if (!validGroup) {
                    filteredRows++
                    return@forEachLine
                }

            } else {

                // Only actual commodity futures.
                // Options and index futures are excluded.
                if (instrumentType != "FUTCOM") {
                    filteredRows++
                    return@forEachLine
                }

                if (optionType == "CE" || optionType == "PE") {
                    filteredRows++
                    return@forEachLine
                }
            }

            val exchange = value("pexchange")
                .uppercase(Locale.ROOT)
                .ifBlank { config.exchange }

            if (!exchange.equals(config.exchange, true)) {
                filteredRows++
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

        return SegmentParseResult(
            rows = result,
            parsedRows = parsedRows,
            invalidTokenRows = invalidTokenRows,
            invalidSymbolRows = invalidSymbolRows,
            filteredRows = filteredRows
        )
    }

    /**
     * Legacy NSE symbols (e.g. ZOMATO, CADILAHC) are not present in the
     * current master. Reuse the token of their renamed symbol so bundled
     * master rows can still receive live ticks.
     */
    private fun expandNseAliases(
        cashUpdates: LinkedHashMap<String, KotakScripToken>
    ) {
        if (cashUpdates.isEmpty()) return

        val nseBySymbol = LinkedHashMap<String, KotakScripToken>()

        cashUpdates.values.forEach { token ->
            if (token.exchange.equals("NSE", ignoreCase = true)) {
                val symbol = normalizeSymbol(token.symbol)
                if (!nseBySymbol.containsKey(symbol)) {
                    nseBySymbol[symbol] = token
                }
            }
        }

        if (nseBySymbol.isEmpty()) return

        KotakSymbolAliases.map.forEach { (legacy, current) ->

            val source = nseBySymbol[
                normalizeSymbol(current)
            ] ?: return@forEach

            val identity = normalizeSymbol(legacy)

            val key = buildString {
                append(source.exchange)
                append('|')
                append(identity)
                append('|')
                append(source.segments.joinToString(","))
            }

            if (!cashUpdates.containsKey(key)) {
                cashUpdates[key] = source.copy(symbol = legacy)
            }
        }
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

        private val NSE_CASH_GROUPS = setOf(
            "EQ",
            "BE",
            "BZ",
            "ST",
            "SM"
        )

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
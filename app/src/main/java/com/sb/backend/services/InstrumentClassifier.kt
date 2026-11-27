package com.sb.backend.services

import com.sb.backend.models.Instrument
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class MasterClassificationResult(
    val instruments: List<Instrument>,
    val errors: List<String>,
    val categoryCounts: Map<String, Int>
)

object InstrumentClassifier {

    fun classify(
        rows: List<InstrumentCsvRow>,
        importedAt: Long = System.currentTimeMillis()
    ): MasterClassificationResult {
        val duplicateRawSymbols = rows
            .groupingBy { normalizeSymbol(it.rawSymbol) }
            .eachCount()

        val instruments = ArrayList<Instrument>(rows.size)
        val errors = ArrayList<String>()

        rows.forEach { row ->
            val token = row.token.trim()

            val exchange = row.exchange
                .trim()
                .uppercase(Locale.ROOT)
                .ifBlank { exchangeFromSegment(row.exchangeSegment) }

            if (exchange.isBlank()) {
                errors.add("Line ${row.lineNumber}: exchange missing")
                return@forEach
            }

            val classification = classifyRow(row) ?: run {
                errors.add(
                    "Line ${row.lineNumber}: unsupported group '${row.group}'"
                )
                return@forEach
            }

            val numericToken = token.toLongOrNull()
            if (numericToken == null ||
                (numericToken <= 0L && classification.category != "INDEX")
            ) {
                errors.add("Line ${row.lineNumber}: invalid token '$token'")
                return@forEach
            }

            val storedToken = if (numericToken <= 0L) null else token

            val duplicate = (duplicateRawSymbols[
                normalizeSymbol(row.rawSymbol)
            ] ?: 0) > 1

            val canonicalSymbol = when {
                classification.category == "INDEX" -> {
                    KotakIndexCatalog.canonicalSymbol(row.rawSymbol)
                        ?: row.rawSymbol.trim()
                }
                duplicate && classification.category != "EQUITY" &&
                    classification.category != "ETF" -> {
                    row.tradingSymbol
                        .trim()
                        .ifBlank { "${row.rawSymbol.trim()}-$token" }
                }
                else -> row.rawSymbol.trim()
            }

            val rawTickSize = row.tickSizeRaw.toDoubleOrNull()
            val safeTickSize = if (rawTickSize != null && rawTickSize > 0.0) {
                rawTickSize / 100.0
            } else {
                0.05
            }

            val lotSize = row.lotSizeRaw
                .toIntOrNull()
                ?.takeIf { it > 0 }
                ?: 1

            val instrument = Instrument(
                symbol = canonicalSymbol,
                name = row.description.trim().ifBlank { row.rawSymbol.trim() },
                exchange = exchange,
                segment = classification.legacySegment,
                instrumentType = classification.instrumentType,
                tradingSymbol = row.tradingSymbol.trim().ifBlank {
                    row.rawSymbol.trim()
                },
                token = storedToken,
                lotSize = lotSize,
                tickSize = safeTickSize,
                isActive = true,
                expiryMillis = parseEpochMillis(row.expiryRaw),
                exchangeSegment = row.exchangeSegment.trim().ifBlank {
                    exchangeSegment(exchange, classification.instrumentType)
                },
                category = classification.category,
                subcategory = classification.subcategory,
                rawSymbol = row.rawSymbol.trim(),
                isin = row.isin.trim().ifBlank { null },
                description = row.description.trim().ifBlank { null },
                groupCode = row.group.trim().ifBlank { null },
                assetCode = row.assetCode.trim().ifBlank { null },
                amcCode = row.amcCode.trim().ifBlank { null },
                issueMillis = parseEpochMillis(row.issueRaw),
                maturityMillis = parseEpochMillis(row.maturityRaw),
                caseEligible = row.caseEligibleRaw.equals("true", true) ||
                    row.caseEligibleRaw == "1",
                sourceTickSize = rawTickSize,
                source = "SB_CSV",
                sourceUpdatedAt = importedAt,
                isAlias = false,
                aliasOf = null,
                feedName = if (classification.category == "INDEX") {
                    KotakIndexCatalog.feedName(canonicalSymbol)
                } else {
                    null
                }
            )

            instruments.add(instrument)
        }

        val categoryCounts = instruments
            .groupingBy { it.category }
            .eachCount()

        return MasterClassificationResult(
            instruments = instruments,
            errors = errors,
            categoryCounts = categoryCounts
        )
    }

    private data class Classification(
        val category: String,
        val subcategory: String,
        val legacySegment: String,
        val instrumentType: String
    )

    private fun classifyRow(row: InstrumentCsvRow): Classification? {
        val group = row.group.trim().uppercase(Locale.ROOT)
        val isin = row.isin.trim().uppercase(Locale.ROOT)

        if (KotakIndexCatalog.isIndexRawSymbol(row.rawSymbol) &&
            group.isBlank() &&
            isin.isBlank()
        ) {
            return Classification(
                category = "INDEX",
                subcategory = "INDEX",
                legacySegment = "INDEX",
                instrumentType = "INDEX"
            )
        }

        if (group == "EQ" && isin.startsWith("INF")) {
            return Classification(
                category = "ETF",
                subcategory = "NSE_ETF",
                legacySegment = "ETF",
                instrumentType = "ETF"
            )
        }

        if (group in EQUITY_GROUPS) {
            return Classification(
                category = "EQUITY",
                subcategory = equitySubcategory(group),
                legacySegment = "EQ",
                instrumentType = "EQ"
            )
        }

        if (group == "MF" || group == "SF") {
            return Classification(
                category = "MUTUAL_FUND",
                subcategory = if (group == "SF") "SIF" else "NSE_MF",
                legacySegment = "MF",
                instrumentType = "MF"
            )
        }

        if (group == "IV" || group == "RR" || group == "E1" || group == "W1") {
            return Classification(
                category = "OTHER",
                subcategory = otherSubcategory(group),
                legacySegment = "OTHER",
                instrumentType = "OTHER"
            )
        }

        if (group == "SG" || group == "GS" || group == "GB" ||
            group == "TB" || group == "D1"
        ) {
            return Classification(
                category = "BOND",
                subcategory = bondSubcategory(group),
                legacySegment = "BOND",
                instrumentType = "BOND"
            )
        }

        if (isNcdGroup(group)) {
            return Classification(
                category = "BOND",
                subcategory = "NCD",
                legacySegment = "BOND",
                instrumentType = "BOND"
            )
        }

        return null
    }

    private fun isNcdGroup(group: String): Boolean {
        if (group.isBlank()) return false
        return group.startsWith("N") ||
            group.startsWith("Y") ||
            group.startsWith("Z") ||
            (group.length == 2 && group[0] == 'A' && group[1] in 'K'..'Z') ||
            (group.length == 2 && group[0] == 'B' && group[1] in 'A'..'Z')
    }

    private fun equitySubcategory(group: String): String {
        return when (group) {
            "SM" -> "NSE_SME"
            "BE" -> "NSE_T2T"
            "ST" -> "NSE_SME_T2T"
            "BZ" -> "NSE_T2T_SURVEILLANCE"
            else -> "NSE_CASH"
        }
    }

    private fun bondSubcategory(group: String): String {
        return when (group) {
            "SG" -> "SDL"
            "GS" -> "GSEC"
            "GB" -> "SGB"
            "TB" -> "TBILL"
            "D1" -> "FCD"
            else -> "NCD"
        }
    }

    private fun otherSubcategory(group: String): String {
        return when (group) {
            "IV" -> "INVIT"
            "RR" -> "REIT"
            "E1" -> "RIGHTS"
            "W1" -> "WARRANT"
            else -> "OTHER"
        }
    }

    private fun exchangeFromSegment(segment: String): String {
        return when (segment.trim().lowercase(Locale.ROOT)) {
            "nse_cm", "nse_fo", "nse_com" -> "NSE"
            "bse_cm", "bse_fo", "bse_cd", "bse_co" -> "BSE"
            "mcx_fo" -> "MCX"
            else -> ""
        }
    }

    private fun exchangeSegment(exchange: String, instrumentType: String): String {
        val derivative = instrumentType.contains("FO") ||
            instrumentType.contains("FUT") ||
            instrumentType.contains("OPT")
        return when (exchange) {
            "NSE" -> if (derivative) "nse_fo" else "nse_cm"
            "BSE" -> if (derivative) "bse_fo" else "bse_cm"
            "MCX" -> "mcx_fo"
            else -> ""
        }
    }

    fun parseEpochMillis(raw: String): Long? {
        val value = raw.trim()
        if (value.isBlank() || value == "0" || value == "-1") return null

        val numeric = value.toLongOrNull()
        if (numeric != null) {
            if (numeric <= 0L) return null
            return if (numeric >= 100_000_000_000L) {
                numeric
            } else {
                numeric * 1_000L
            }
        }

        val patterns = listOf(
            "yyyy-MM-dd",
            "dd-MMM-yyyy",
            "dd/MM/yyyy",
            "yyyy/MM/dd"
        )
        for (pattern in patterns) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US)
                formatter.timeZone = TimeZone.getTimeZone("UTC")
                formatter.isLenient = false
                val parsed = formatter.parse(value) ?: continue
                return parsed.time
            } catch (_: Exception) {
                // Try the next accepted date format.
            }
        }
        return null
    }

    private fun normalizeSymbol(value: String): String =
        value.trim()
            .replace(" ", "")
            .uppercase(Locale.ROOT)

    private val EQUITY_GROUPS = setOf(
        "EQ",
        "SM",
        "BE",
        "ST",
        "BZ",
        "IT",
        "SZ"
    )
}

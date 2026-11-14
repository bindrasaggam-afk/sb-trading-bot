package com.sb.backend.services

import java.io.BufferedReader
import java.io.Reader
import java.util.Locale

data class InstrumentCsvRow(
    val lineNumber: Int,
    val token: String,
    val rawSymbol: String,
    val tradingSymbol: String,
    val exchange: String,
    val exchangeSegment: String,
    val group: String,
    val instrumentType: String,
    val optionType: String,
    val description: String,
    val isin: String,
    val assetCode: String,
    val amcCode: String,
    val segment: String,
    val tickSizeRaw: String,
    val lotSizeRaw: String,
    val expiryRaw: String,
    val issueRaw: String,
    val maturityRaw: String,
    val caseEligibleRaw: String
)

data class InstrumentCsvParseResult(
    val rows: List<InstrumentCsvRow>,
    val errors: List<String>
)

object InstrumentCsvParser {

    fun parse(reader: Reader): InstrumentCsvParseResult {
        val buffered = if (reader is BufferedReader) reader else BufferedReader(reader)
        val headerLine = buffered.readLine()
            ?: return InstrumentCsvParseResult(emptyList(), listOf("CSV header missing"))

        val headers = parseLine(headerLine)
            .map(::normalizeHeader)

        val indexes = headers
            .withIndex()
            .filter { it.value.isNotBlank() }
            .associate { it.value to it.index }

        val required = listOf("psymbol", "psymbolname", "ptrdsymbol", "pexchseg")
        val missing = required.filter { it !in indexes }
        if (missing.isNotEmpty()) {
            return InstrumentCsvParseResult(
                emptyList(),
                listOf("Required CSV columns missing: ${missing.joinToString()}")
            )
        }

        val rows = ArrayList<InstrumentCsvRow>(10_100)
        val errors = ArrayList<String>()

        var lineNumber = 1
        while (true) {
            val line = buffered.readLine() ?: break
            lineNumber++
            if (line.isBlank()) continue

            val columns = try {
                parseLine(line)
            } catch (error: Exception) {
                errors.add("Line $lineNumber: ${error.message}")
                continue
            }

            fun value(name: String): String {
                val index = indexes[name] ?: return ""
                return columns.getOrNull(index)?.trim().orEmpty()
            }

            val token = value("psymbol")
            val rawSymbol = value("psymbolname")
            val tradingSymbol = value("ptrdsymbol")
            val exchangeSegment = value("pexchseg")

            if (token.isBlank() || rawSymbol.isBlank()) {
                errors.add("Line $lineNumber: token or symbol blank")
                continue
            }

            rows.add(
                InstrumentCsvRow(
                    lineNumber = lineNumber,
                    token = token,
                    rawSymbol = rawSymbol,
                    tradingSymbol = tradingSymbol.ifBlank { rawSymbol },
                    exchange = value("pexchange"),
                    exchangeSegment = exchangeSegment,
                    group = value("pgroup").uppercase(Locale.ROOT),
                    instrumentType = value("pinsttype").uppercase(Locale.ROOT),
                    optionType = value("poptiontype").uppercase(Locale.ROOT),
                    description = value("pdesc"),
                    isin = value("pisin"),
                    assetCode = value("passetcode"),
                    amcCode = value("pamccode"),
                    segment = value("psegment"),
                    tickSizeRaw = value("dticksize"),
                    lotSizeRaw = value("llotsize"),
                    expiryRaw = value("lexpirydate").ifBlank { value("pexpirydate") },
                    issueRaw = value("pissuedate"),
                    maturityRaw = value("pmaturitydate"),
                    caseEligibleRaw = value("caseligible")
                )
            )
        }

        return InstrumentCsvParseResult(rows, errors)
    }

    private fun normalizeHeader(value: String): String {
        return value
            .trim()
            .removePrefix("\uFEFF")
            .lowercase(Locale.ROOT)
            .trim()
            .removeSuffix(";")
            .trim()
    }

    internal fun parseLine(line: String): List<String> {
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

                char == '"' -> quoted = !quoted

                char == ',' && !quoted -> {
                    result.add(value.toString())
                    value.setLength(0)
                }

                else -> value.append(char)
            }
            index++
        }

        if (quoted) {
            throw IllegalArgumentException("unterminated quoted field")
        }

        result.add(value.toString())
        return result
    }
}

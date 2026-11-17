package com.sb.overview

import com.sb.backend.models.Instrument
import java.util.Locale

/**
 * Pure filtering used by the All Instruments screen.
 *
 * Identity is always the complete [Instrument] row (exchange + symbol),
 * so the same symbol listed on NSE and BSE remains two separate entries.
 */
object InstrumentListFilter {

    val exchanges = listOf("All", "NSE", "BSE", "MCX")

    val categories = listOf(
        "All",
        "Equity",
        "Index",
        "F&O",
        "Commodity",
        "ETF",
        "Bond",
        "MF",
        "Other"
    )

    fun filter(
        instruments: List<Instrument>,
        query: String = "",
        exchange: String = "All",
        category: String = "All"
    ): List<Instrument> {
        val normalizedQuery = query.trim().uppercase(Locale.ROOT)

        return instruments.filter { instrument ->
            matchesExchange(instrument, exchange) &&
                matchesCategory(instrument, category) &&
                matchesQuery(instrument, normalizedQuery)
        }
    }

    fun categoryBucket(instrument: Instrument): String {
        val category = instrument.category.uppercase(Locale.ROOT)
        val segment = instrument.segment.uppercase(Locale.ROOT)
        val type = instrument.instrumentType.uppercase(Locale.ROOT)

        return when {
            category.contains("INDEX") || segment == "INDEX" -> "Index"
            category.contains("COMMODITY") ||
                segment == "COMMODITY" ||
                instrument.exchange.equals("MCX", ignoreCase = true) ->
                "Commodity"
            category.contains("ETF") || segment == "ETF" -> "ETF"
            category.contains("BOND") || segment == "BOND" -> "Bond"
            category.contains("MUTUAL") ||
                category == "MF" ||
                segment == "MF" -> "MF"
            type.contains("FUT") ||
                type.contains("OPT") ||
                segment.contains("FO") ||
                segment.contains("DERIV") -> "F&O"
            category.contains("EQUITY") || segment == "EQ" -> "Equity"
            else -> "Other"
        }
    }

    private fun matchesExchange(
        instrument: Instrument,
        exchange: String
    ): Boolean {
        return exchange == "All" ||
            instrument.exchange.equals(exchange, ignoreCase = true)
    }

    private fun matchesCategory(
        instrument: Instrument,
        category: String
    ): Boolean {
        return category == "All" ||
            categoryBucket(instrument) == category
    }

    private fun matchesQuery(
        instrument: Instrument,
        query: String
    ): Boolean {
        if (query.isBlank()) return true
        return instrument.symbol.uppercase(Locale.ROOT).contains(query) ||
            instrument.name.uppercase(Locale.ROOT).contains(query) ||
            instrument.tradingSymbol.uppercase(Locale.ROOT).contains(query) ||
            instrument.isin.orEmpty().uppercase(Locale.ROOT).contains(query) ||
            instrument.token.orEmpty().uppercase(Locale.ROOT).contains(query)
    }
}

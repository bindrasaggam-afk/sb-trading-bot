package com.sb.backend.services

import java.util.Locale

object KotakIndexCatalog {

    private val canonicalByRawSymbol = mapOf(
        "NIFTY" to "NIFTY 50",
        "NIFTY50" to "NIFTY 50",
        "BANKNIFTY" to "BANK NIFTY",
        "NIFTY BANK" to "BANK NIFTY",
        "FINNIFTY" to "FINNIFTY",
        "MIDCPNIFTY" to "MIDCPNIFTY",
        "NIFTY MIDCAP SELECT" to "MIDCPNIFTY",
        "SENSEX" to "SENSEX"
    )

    private val feedNameByCanonical = mapOf(
        "NIFTY 50" to "Nifty 50",
        "BANK NIFTY" to "Nifty Bank",
        "FINNIFTY" to "Nifty Fin Service",
        "MIDCPNIFTY" to "Nifty Midcap Select",
        "SENSEX" to "SENSEX",
        "NIFTY NEXT 50" to "Nifty Next 50",
        "NIFTY 100" to "Nifty 100",
        "NIFTY 200" to "Nifty 200",
        "NIFTY 500" to "Nifty 500",
        "NIFTY MIDCAP 50" to "Nifty Midcap 50",
        "NIFTY MIDCAP 100" to "Nifty Midcap 100",
        "NIFTY SMALLCAP 100" to "Nifty Smallcap 100",
        "NIFTY IT" to "Nifty IT",
        "NIFTY AUTO" to "Nifty Auto",
        "NIFTY PHARMA" to "Nifty Pharma",
        "NIFTY FMCG" to "Nifty FMCG",
        "NIFTY METAL" to "Nifty Metal",
        "NIFTY REALTY" to "Nifty Realty",
        "NIFTY ENERGY" to "Nifty Energy",
        "NIFTY PSU BANK" to "Nifty PSU Bank",
        "NIFTY PRIVATE BANK" to "Nifty Private Bank",
        "NIFTY MEDIA" to "Nifty Media",
        "INDIA VIX" to "India VIX"
    )

    val primaryFeedTokens: List<String> = listOf(
        "nse_cm|Nifty 50",
        "nse_cm|Nifty Bank",
        "nse_cm|Nifty Fin Service",
        "bse_cm|SENSEX"
    )

    fun canonicalSymbol(rawSymbol: String): String? {
        return canonicalByRawSymbol[normalize(rawSymbol)]
    }

    fun isIndexRawSymbol(rawSymbol: String): Boolean =
        canonicalByRawSymbol.containsKey(normalize(rawSymbol))

    fun feedName(symbol: String): String? =
        feedNameByCanonical[normalize(symbol)]

    private fun normalize(value: String): String =
        value.trim()
            .uppercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}

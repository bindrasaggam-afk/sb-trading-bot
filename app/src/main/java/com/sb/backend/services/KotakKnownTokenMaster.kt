package com.sb.backend.services

import java.util.Locale

data class KotakKnownToken(
    val symbol: String,
    val token: String,
    val exchange: String,
    val segment: String
)

object KotakKnownTokenMaster {

    val instruments = listOf(
        KotakKnownToken("RELIANCE", "2885", "NSE", "EQ"),
        KotakKnownToken("TATASTEEL", "3499", "NSE", "EQ"),
        KotakKnownToken("HDFCBANK", "1333", "NSE", "EQ"),
        KotakKnownToken("INFY", "1594", "NSE", "EQ"),
        KotakKnownToken("ICICIBANK", "4963", "NSE", "EQ"),
        KotakKnownToken("AXISBANK", "5900", "NSE", "EQ"),
        KotakKnownToken("WIPRO", "3787", "NSE", "EQ"),
        KotakKnownToken("ADANIENT", "25", "NSE", "EQ"),
        KotakKnownToken("LT", "11483", "NSE", "EQ"),
        KotakKnownToken("BAJFINANCE", "317", "NSE", "EQ"),
        KotakKnownToken("ONGC", "2475", "NSE", "EQ"),
        KotakKnownToken("APOLLOHOSP", "157", "NSE", "EQ"),
        KotakKnownToken("HDFCLIFE", "467", "NSE", "EQ"),
        KotakKnownToken("TITAN", "3506", "NSE", "EQ"),
        KotakKnownToken("JIOFIN", "18143", "NSE", "EQ"),

        KotakKnownToken("GOLD", "582365", "MCX", "COMMODITY"),
        KotakKnownToken("CRUDEOIL", "565899", "MCX", "COMMODITY"),
        KotakKnownToken("NATURALGAS", "568245", "MCX", "COMMODITY"),
        KotakKnownToken("SILVER", "495214", "MCX", "COMMODITY"),
        KotakKnownToken("COPPER", "584327", "MCX", "COMMODITY"),
        KotakKnownToken("ZINC", "578638", "MCX", "COMMODITY"),
        KotakKnownToken("ALUMINIUM", "584317", "MCX", "COMMODITY"),
        KotakKnownToken("LEAD", "584320", "MCX", "COMMODITY")
    )

    fun find(symbol: String): KotakKnownToken? {
        val normalized = normalize(symbol)

        return instruments.firstOrNull {
            normalize(it.symbol) == normalized
        }
    }

    private fun normalize(value: String): String {
        return value.replace(" ", "")
            .trim()
            .toUpperCase(Locale.ROOT)
    }
}

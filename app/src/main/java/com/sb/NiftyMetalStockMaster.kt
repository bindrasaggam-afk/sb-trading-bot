package com.sb

/**
 * SB App — NIFTY Metal Stock Master
 * Starter list of 15 metal-sector constituents.
 * Verify current index membership with the official NSE list.
 */
object NiftyMetalStockMaster {

    val allStocks: List<Pair<String, String>> = listOf(
        "ADANIENT" to "Adani Enterprises",
        "APLAPOLLO" to "APL Apollo Tubes",
        "HINDALCO" to "Hindalco Industries",
        "HINDCOPPER" to "Hindustan Copper",
        "JINDALSTEL" to "Jindal Steel & Power",
        "JSWSTEEL" to "JSW Steel",
        "JSL" to "Jindal Stainless",
        "NMDC" to "NMDC",
        "NALCO" to "National Aluminium Company",
        "SAIL" to "Steel Authority of India",
        "TATASTEEL" to "Tata Steel",
        "VEDL" to "Vedanta",
        "WELCORP" to "Welspun Corp",
        "RATNAMANI" to "Ratnamani Metals & Tubes",
        "HINDZINC" to "Hindustan Zinc"
    )
}

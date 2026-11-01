package com.sb

/**
 * SB App — NIFTY FMCG Stock Master
 * 15 constituents; verify against the latest NSE index file.
 */
object NiftyFMCGStockMaster {

    val allStocks: List<Pair<String, String>> = listOf(
        "BRITANNIA" to "Britannia Industries",
        "COLPAL" to "Colgate-Palmolive India",
        "DABUR" to "Dabur India",
        "GODFRYPHLP" to "Godfrey Phillips India",
        "GODREJCP" to "Godrej Consumer Products",
        "HINDUNILVR" to "Hindustan Unilever",
        "ITC" to "ITC",
        "MARICO" to "Marico",
        "NESTLEIND" to "Nestle India",
        "PATANJALI" to "Patanjali Foods",
        "RADICO" to "Radico Khaitan",
        "TATACONSUM" to "Tata Consumer Products",
        "UBL" to "United Breweries",
        "UNITDSPR" to "United Spirits",
        "VBL" to "Varun Beverages"
    )
}

package com.sb

/**
 * SB App — NIFTY Pharma Stock Master
 * Verify symbols against the latest official NSE constituent file.
 */
object NiftyPharmaStockMaster {

    val allStocks: List<Pair<String, String>> = listOf(
        "ABBOTINDIA" to "Abbott India",
        "AJANTPHARM" to "Ajanta Pharmaceuticals",
        "ALKEM" to "Alkem Laboratories",
        "AUROPHARMA" to "Aurobindo Pharma",
        "BIOCON" to "Biocon",
        "CIPLA" to "Cipla",
        "DIVISLAB" to "Divi's Laboratories",
        "DRREDDY" to "Dr. Reddy's Laboratories",
        "GLAND" to "Gland Pharma",
        "GLENMARK" to "Glenmark Pharmaceuticals",
        "IPCALAB" to "Ipca Laboratories",
        "LAURUSLABS" to "Laurus Labs",
        "LUPIN" to "Lupin",
        "MANKIND" to "Mankind Pharma",
        "PPLPHARMA" to "Piramal Pharma",
        "SAILIFE" to "Sai Life Sciences",
        "SUNPHARMA" to "Sun Pharmaceutical Industries",
        "TORNTPHARM" to "Torrent Pharmaceuticals",
        "WOCKPHARMA" to "Wockhardt",
        "ZYDUSLIFE" to "Zydus Lifesciences"
    )
}

package com.sb

/**
 * SB App — NIFTY IT Stock Master
 * NIFTY IT has 10 constituents.
 * Verify the latest official NSE list after index rebalancing.
 */
object NiftyITStockMaster {

    val allStocks: List<Pair<String, String>> = listOf(
        "COFORGE" to "Coforge",
        "HCLTECH" to "HCL Technologies",
        "INFY" to "Infosys",
        "LTM" to "LTM",
        "MPHASIS" to "Mphasis",
        "OFSS" to "Oracle Financial Services Software",
        "PERSISTENT" to "Persistent Systems",
        "TCS" to "Tata Consultancy Services",
        "TECHM" to "Tech Mahindra",
        "WIPRO" to "Wipro"
    )
}

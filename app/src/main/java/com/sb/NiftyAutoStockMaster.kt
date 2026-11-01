package com.sb

/**
 * SB App — NIFTY Auto Stock Master
 * 15 constituents; verify current NSE symbols before live use.
 */
object NiftyAutoStockMaster {

    val allStocks: List<Pair<String, String>> = listOf(
        "ASHOKLEY" to "Ashok Leyland",
        "BAJAJ-AUTO" to "Bajaj Auto",
        "BALKRISIND" to "Balkrishna Industries",
        "BHARATFORG" to "Bharat Forge",
        "BOSCHLTD" to "Bosch",
        "EICHERMOT" to "Eicher Motors",
        "ESCORTS" to "Escorts Kubota",
        "HEROMOTOCO" to "Hero MotoCorp",
        "M&M" to "Mahindra & Mahindra",
        "MARUTI" to "Maruti Suzuki",
        "MRF" to "MRF",
        "SONACOMS" to "Sona BLW Precision Forgings",
        "TATAMOTORS" to "Tata Motors",
        "TIINDIA" to "Tube Investments of India",
        "TVSMOTOR" to "TVS Motor"
    )
}

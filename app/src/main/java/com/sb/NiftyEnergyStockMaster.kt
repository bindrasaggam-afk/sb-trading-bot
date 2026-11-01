package com.sb

/**
 * SB App — NIFTY Energy Stock Master
 * 40 constituents based on the published list dated 21 Oct 2026.
 * Verify current NSE membership and broker symbols before live use.
 */
object NiftyEnergyStockMaster {

    val allStocks: List<Pair<String, String>> = listOf(
        "ABB" to "ABB India",
        "ADANIENSOL" to "Adani Energy Solutions",
        "ADANIGREEN" to "Adani Green Energy",
        "ADANIPOWER" to "Adani Power",
        "ATGL" to "Adani Total Gas",
        "AEGISLOG" to "Aegis Logistics",
        "BHEL" to "Bharat Heavy Electricals",
        "BPCL" to "Bharat Petroleum",
        "CESC" to "CESC",
        "CGPOWER" to "CG Power and Industrial Solutions",
        "CASTROLIND" to "Castrol India",
        "COALINDIA" to "Coal India",
        "GAIL" to "GAIL India",
        "GVT&D" to "GE Vernova T&D India",
        "HINDPETRO" to "Hindustan Petroleum",
        "POWERINDIA" to "Hitachi Energy India",
        "IOC" to "Indian Oil Corporation",
        "IGL" to "Indraprastha Gas",
        "INOXWIND" to "Inox Wind",
        "JSWENERGY" to "JSW Energy",
        "JPPOWER" to "Jaiprakash Power Ventures",
        "MGL" to "Mahanagar Gas",
        "NHPC" to "NHPC",
        "NLCINDIA" to "NLC India",
        "NTPCGREEN" to "NTPC Green Energy",
        "NTPC" to "NTPC",
        "NAVA" to "Nava",
        "ONGC" to "Oil and Natural Gas Corporation",
        "OIL" to "Oil India",
        "PETRONET" to "Petronet LNG",
        "POWERGRID" to "Power Grid Corporation of India",
        "RELIANCE" to "Reliance Industries",
        "RPOWER" to "Reliance Power",
        "ENRIN" to "Siemens Energy India",
        "SIEMENS" to "Siemens",
        "SUZLON" to "Suzlon Energy",
        "TDPOWERSYS" to "TD Power Systems",
        "TATAPOWER" to "Tata Power",
        "THERMAX" to "Thermax",
        "TORNTPOWER" to "Torrent Power"
    )
}

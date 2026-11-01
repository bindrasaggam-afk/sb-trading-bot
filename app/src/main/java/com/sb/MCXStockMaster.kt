package com.sb

object MCXStockMaster {

    data class Commodity(
        val symbol: String,
        val name: String,
        val category: String
    )

    val allCommodities = listOf(
        // Bullion
        Commodity("GOLD", "Gold", "Bullion"),
        Commodity("GOLD MINI", "Gold Mini", "Bullion"),
        Commodity("GOLD PETAL", "Gold Petal", "Bullion"),
        Commodity("SILVER", "Silver", "Bullion"),
        Commodity("SILVER MINI", "Silver Mini", "Bullion"),
        Commodity("SILVER MICRO", "Silver Micro", "Bullion"),

        // Energy
        Commodity("CRUDE OIL", "Crude Oil", "Energy"),
        Commodity("CRUDE OIL MINI", "Crude Oil Mini", "Energy"),
        Commodity("NATURAL GAS", "Natural Gas", "Energy"),
        Commodity("NATURAL GAS MINI", "Natural Gas Mini", "Energy"),

        // Base Metals
        Commodity("ZINC", "Zinc", "Base Metals"),
        Commodity("ZINC MINI", "Zinc Mini", "Base Metals"),
        Commodity("COPPER", "Copper", "Base Metals"),
        Commodity("ALUMINIUM", "Aluminium", "Base Metals"),
        Commodity("LEAD MINI", "Lead Mini", "Base Metals"),

        // Agriculture
        Commodity("MENTHA OIL", "Mentha Oil", "Agriculture")
    )

    val categories = listOf(
        "Bullion",
        "Energy",
        "Base Metals",
        "Agriculture"
    )

    fun getByCategory(category: String): List<Commodity> {
        return allCommodities.filter { commodity ->
            commodity.category == category
        }
    }
}

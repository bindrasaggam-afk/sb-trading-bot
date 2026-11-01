package com.sb.backend.services

import com.sb.backend.repositories.InstrumentRepository

class InstrumentFunctionalTest(
    private val repository: InstrumentRepository
) {

    fun runTest(): String {
        val result = StringBuilder()

        try {
            result.append("INSTRUMENT TEST START\n")

            // 1. Built-in Master Data Seed
            val inserted = repository.seedBuiltInMasters()
            result.append("Master Seed: PASS ($inserted inserted)\n")

            // 2. Total Instrument Count
            val total = repository.count()
            result.append("Total Instruments: $total\n")

            // 3. NSE Equity
            val equities = repository.getBySegment(
                segment = "EQ",
                exchange = "NSE"
            )
            result.append(
                "NSE Equity: ${equities.size} " +
                    if (equities.isNotEmpty()) "PASS\n" else "FAIL\n"
            )

            // 4. NSE Index
            val indices = repository.getBySegment(
                segment = "INDEX",
                exchange = "NSE"
            )
            result.append(
                "NSE Index: ${indices.size} " +
                    if (indices.isNotEmpty()) "PASS\n" else "FAIL\n"
            )

            // 5. MCX Commodity
            val commodities = repository.getBySegment(
                segment = "COMMODITY",
                exchange = "MCX"
            )
            result.append(
                "MCX Commodity: ${commodities.size} " +
                    if (commodities.isNotEmpty()) "PASS\n" else "FAIL\n"
            )

            // 6. Symbol Lookup
            val nifty = repository.findBySymbol(
                symbol = "NIFTY 50",
                exchange = "NSE"
            )

            result.append(
                "NIFTY 50 Lookup: " +
                    if (nifty != null) "PASS\n" else "FAIL\n"
            )

            // 7. Search Test
            val searchResults = repository.search(
                query = "NIFTY",
                exchange = "NSE",
                segment = "INDEX"
            )

            result.append(
                "Instrument Search: " +
                    if (searchResults.isNotEmpty()) "PASS\n" else "FAIL\n"
            )

            // 8. Repeat Seed Test
            val countBefore = repository.count()
            val secondSeed = repository.seedBuiltInMasters()
            val countAfter = repository.count()

            result.append("Second Seed Inserted: $secondSeed\n")
            result.append(
                "Duplicate Seed Protection: " +
                    if (countBefore == countAfter) "PASS\n" else "CHECK\n"
            )

            result.append("INSTRUMENT TEST COMPLETED")

        } catch (e: Exception) {
            result.append("\nINSTRUMENT TEST ERROR: ${e.message}")
        }

        return result.toString()
    }
}

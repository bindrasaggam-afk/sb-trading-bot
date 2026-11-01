package com.sb.backend.services

import com.sb.backend.models.Candle
import com.sb.backend.models.Quote
import com.sb.backend.repositories.MarketRepository

class MarketFunctionalTest(
    private val repository: MarketRepository
) {

    fun runTest(): String {
        val result = StringBuilder()
        val symbol = "SB_TEST_WIRING"
        val exchange = "NSE"
        val timeframe = "TEST"

        try {
            result.append("MARKET TEST START\n")

            val now = System.currentTimeMillis()

            // 1. Quote Save
            val quote = Quote(
                symbol = symbol,
                exchange = exchange,
                ltp = 125.50,
                open = 124.00,
                high = 126.00,
                low = 123.50,
                previousClose = 124.50,
                volume = 1000L,
                change = 1.00,
                changePercent = 0.80,
                timestamp = now,
                isLive = false
            )

            val quoteSaved = repository.saveQuote(quote)
            result.append("Quote Save: ${if (quoteSaved) "PASS" else "FAIL"}\n")

            // 2. Quote Read
            val savedQuote = repository.getQuote(symbol, exchange)

            val quoteRead = savedQuote != null &&
                savedQuote.ltp == 125.50

            result.append("Quote Read: ${if (quoteRead) "PASS" else "FAIL"}\n")

            // 3. Candle Save
            val candle1 = Candle(
                symbol = symbol,
                timeframe = timeframe,
                timestamp = now - 60000L,
                open = 120.0,
                high = 123.0,
                low = 119.0,
                close = 122.0,
                volume = 500L
            )

            val candle2 = Candle(
                symbol = symbol,
                timeframe = timeframe,
                timestamp = now,
                open = 122.0,
                high = 126.0,
                low = 121.0,
                close = 125.0,
                volume = 700L
            )

            val candleSaved = repository.saveCandles(
                symbol,
                exchange,
                timeframe,
                listOf(candle1, candle2)
            )

            result.append(
                "Candle Save: " +
                    if (candleSaved == 2) "PASS ($candleSaved)"
                    else "FAIL ($candleSaved)"
            )
            result.append("\n")

            // 4. Candle Read
            val candles = repository.getCandles(
                symbol,
                timeframe,
                exchange
            )

            result.append(
                "Candle Read: " +
                    if (candles.size == 2) "PASS" else "FAIL (${candles.size})"
            )
            result.append("\n")

            // 5. Latest Candle
            val latest = repository.latestCandle(
                symbol,
                timeframe,
                exchange
            )

            result.append(
                "Latest Candle: " +
                    if (latest?.timestamp == now) "PASS" else "FAIL"
            )
            result.append("\n")

            // 6. Date Range
            val between = repository.getCandlesBetween(
                symbol = symbol,
                timeframe = timeframe,
                fromTimestamp = now - 60000L,
                toTimestamp = now,
                exchange = exchange
            )

            result.append(
                "Candle Date Range: " +
                    if (between.size == 2) "PASS" else "FAIL"
            )
            result.append("\n")

            // 7. Candle Count
            val count = repository.countCandles(
                symbol,
                timeframe,
                exchange
            )

            result.append(
                "Candle Count: " +
                    if (count == 2) "PASS" else "FAIL ($count)"
            )
            result.append("\n")

            // 8. Cleanup only test candles
            val deleted = repository.deleteCandles(
                symbol,
                timeframe,
                exchange
            )

            result.append("Test Candles Cleanup: $deleted\n")
            result.append("MARKET TEST COMPLETED")

        } catch (e: Exception) {
            result.append("\nMARKET TEST ERROR: ${e.message}")
        }

        return result.toString()
    }
}

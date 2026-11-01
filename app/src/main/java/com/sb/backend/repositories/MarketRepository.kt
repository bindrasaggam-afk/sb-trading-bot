
package com.sb.backend.repositories

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.models.Candle
import com.sb.backend.models.Quote
import java.util.Locale

class MarketRepository(
    private val databaseHelper: SBDatabaseHelper
) {

    fun saveQuote(quote: Quote): Boolean {
        if (!isValidQuote(quote)) return false

        return databaseHelper.writableDatabase.insertWithOnConflict(
            SBDatabaseHelper.TABLE_QUOTES,
            null,
            quoteValues(quote),
            SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }

    fun saveQuotes(quotes: List<Quote>): Int {
        if (quotes.isEmpty()) return 0

        val db = databaseHelper.writableDatabase
        var saved = 0

        db.beginTransaction()
        try {
            quotes.forEach { quote ->
                if (isValidQuote(quote)) {
                    val id = db.insertWithOnConflict(
                        SBDatabaseHelper.TABLE_QUOTES,
                        null,
                        quoteValues(quote),
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                    if (id != -1L) saved++
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return saved
    }

    fun getQuote(
        symbol: String,
        exchange: String = "NSE"
    ): Quote? {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank()
        ) return null

        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_QUOTES,
            quoteProjection,
            "UPPER(symbol) = ? AND UPPER(exchange) = ?",
            arrayOf(normalizedSymbol, normalizedExchange),
            null,
            null,
            "timestamp DESC",
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) readQuote(cursor) else null
        }
    }

    fun getQuotes(
        symbols: List<String>,
        exchange: String? = null
    ): List<Quote> {
        val result = ArrayList<Quote>()

        symbols.forEach { symbol ->
            val quote = if (exchange.isNullOrBlank()) {
                getAnyExchangeQuote(symbol)
            } else {
                getQuote(symbol, exchange)
            }

            if (quote != null) result.add(quote)
        }

        return result
    }

    fun saveCandles(
        symbol: String,
        exchange: String,
        timeframe: String,
        candles: List<Candle>
    ): Int {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)
        val normalizedTimeframe = normalize(timeframe)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank() ||
            normalizedTimeframe.isBlank() ||
            candles.isEmpty()
        ) return 0

        val db = databaseHelper.writableDatabase
        var saved = 0

        db.beginTransaction()
        try {
            candles.forEach { candle ->
                if (!isValidCandle(candle)) return@forEach

                val values = ContentValues().apply {
                    put("symbol", normalizedSymbol)
                    put("exchange", normalizedExchange)
                    put("timeframe", normalizedTimeframe)
                    put("timestamp", candle.timestamp)
                    put("open", candle.open)
                    put("high", candle.high)
                    put("low", candle.low)
                    put("close", candle.close)
                    put("volume", candle.volume)
                }

                val id = db.insertWithOnConflict(
                    SBDatabaseHelper.TABLE_CANDLES,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )

                if (id != -1L) saved++
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return saved
    }

    fun getCandles(
        symbol: String,
        timeframe: String,
        exchange: String = "NSE",
        limit: Int = 500
    ): List<Candle> {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)
        val normalizedTimeframe = normalize(timeframe)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank() ||
            normalizedTimeframe.isBlank()
        ) return emptyList()

        val result = ArrayList<Candle>()
        val safeLimit = limit.coerceIn(1, 5000)

        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_CANDLES,
            candleProjection,
            "UPPER(symbol) = ? AND UPPER(exchange) = ? AND UPPER(timeframe) = ?",
            arrayOf(
                normalizedSymbol,
                normalizedExchange,
                normalizedTimeframe
            ),
            null,
            null,
            "timestamp DESC",
            safeLimit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(readCandle(cursor))
            }
        }

        return result.reversed()
    }

    fun getCandlesBetween(
        symbol: String,
        timeframe: String,
        fromTimestamp: Long,
        toTimestamp: Long,
        exchange: String = "NSE",
        limit: Int = 5000
    ): List<Candle> {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)
        val normalizedTimeframe = normalize(timeframe)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank() ||
            normalizedTimeframe.isBlank() ||
            fromTimestamp <= 0L ||
            toTimestamp < fromTimestamp
        ) return emptyList()

        val result = ArrayList<Candle>()

        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_CANDLES,
            candleProjection,
            "UPPER(symbol) = ? AND UPPER(exchange) = ? " +
                "AND UPPER(timeframe) = ? " +
                "AND timestamp >= ? AND timestamp <= ?",
            arrayOf(
                normalizedSymbol,
                normalizedExchange,
                normalizedTimeframe,
                fromTimestamp.toString(),
                toTimestamp.toString()
            ),
            null,
            null,
            "timestamp ASC",
            limit.coerceIn(1, 10000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(readCandle(cursor))
            }
        }

        return result
    }

    fun latestCandle(
        symbol: String,
        timeframe: String,
        exchange: String = "NSE"
    ): Candle? {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)
        val normalizedTimeframe = normalize(timeframe)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank() ||
            normalizedTimeframe.isBlank()
        ) return null

        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_CANDLES,
            candleProjection,
            "UPPER(symbol) = ? AND UPPER(exchange) = ? " +
                "AND UPPER(timeframe) = ?",
            arrayOf(
                normalizedSymbol,
                normalizedExchange,
                normalizedTimeframe
            ),
            null,
            null,
            "timestamp DESC",
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) readCandle(cursor) else null
        }
    }

    fun countCandles(
        symbol: String,
        timeframe: String,
        exchange: String = "NSE"
    ): Int {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)
        val normalizedTimeframe = normalize(timeframe)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank() ||
            normalizedTimeframe.isBlank()
        ) return 0

        databaseHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_CANDLES} " +
                "WHERE UPPER(symbol) = ? AND UPPER(exchange) = ? " +
                "AND UPPER(timeframe) = ?",
            arrayOf(
                normalizedSymbol,
                normalizedExchange,
                normalizedTimeframe
            )
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun pruneCandles(olderThanTimestamp: Long): Int {
        if (olderThanTimestamp <= 0L) return 0

        return databaseHelper.writableDatabase.delete(
            SBDatabaseHelper.TABLE_CANDLES,
            "timestamp < ?",
            arrayOf(olderThanTimestamp.toString())
        )
    }

    fun deleteCandles(
        symbol: String,
        timeframe: String,
        exchange: String = "NSE"
    ): Int {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)
        val normalizedTimeframe = normalize(timeframe)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank() ||
            normalizedTimeframe.isBlank()
        ) return 0

        return databaseHelper.writableDatabase.delete(
            SBDatabaseHelper.TABLE_CANDLES,
            "UPPER(symbol) = ? AND UPPER(exchange) = ? " +
                "AND UPPER(timeframe) = ?",
            arrayOf(
                normalizedSymbol,
                normalizedExchange,
                normalizedTimeframe
            )
        )
    }

    fun clearQuotes(): Int {
        return databaseHelper.writableDatabase.delete(
            SBDatabaseHelper.TABLE_QUOTES,
            null,
            null
        )
    }

    fun clearCandles(): Int {
        return databaseHelper.writableDatabase.delete(
            SBDatabaseHelper.TABLE_CANDLES,
            null,
            null
        )
    }

    private fun getAnyExchangeQuote(symbol: String): Quote? {
        val normalizedSymbol = normalize(symbol)
        if (normalizedSymbol.isBlank()) return null

        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_QUOTES,
            quoteProjection,
            "UPPER(symbol) = ?",
            arrayOf(normalizedSymbol),
            null,
            null,
            "timestamp DESC",
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) readQuote(cursor) else null
        }
    }

    private fun quoteValues(quote: Quote): ContentValues {
        return ContentValues().apply {
            put("symbol", normalize(quote.symbol))
            put("exchange", normalize(quote.exchange))
            put("ltp", quote.ltp)
            putNullable("open", quote.open)
            putNullable("high", quote.high)
            putNullable("low", quote.low)
            putNullable("previous_close", quote.previousClose)
            put("volume", quote.volume)
            putNullable("change", quote.change)
            putNullable("change_percent", quote.changePercent)
            put("timestamp", quote.timestamp)
            put("is_live", if (quote.isLive) 1 else 0)

            if (quote.exchangeTimestamp == null) {
                putNull("exchange_timestamp")
            } else {
                put("exchange_timestamp", quote.exchangeTimestamp)
            }

            put("received_at", quote.receivedAt)
        }
    }

    private fun readQuote(cursor: Cursor): Quote {
        return Quote(
            symbol = cursor.getString(0),
            exchange = cursor.getString(1),
            ltp = cursor.getDouble(2),
            open = cursor.nullableDouble(3),
            high = cursor.nullableDouble(4),
            low = cursor.nullableDouble(5),
            previousClose = cursor.nullableDouble(6),
            volume = cursor.getLong(7),
            change = cursor.nullableDouble(8),
            changePercent = cursor.nullableDouble(9),
            timestamp = cursor.getLong(10),
            isLive = cursor.getInt(11) == 1,
            exchangeTimestamp = if (cursor.isNull(12)) {
                null
            } else {
                cursor.getLong(12)
            },
            receivedAt = cursor.getLong(13)
        )
    }

    private fun readCandle(cursor: Cursor): Candle {
        return Candle(
            symbol = cursor.getString(0),
            timeframe = cursor.getString(1),
            timestamp = cursor.getLong(2),
            open = cursor.getDouble(3),
            high = cursor.getDouble(4),
            low = cursor.getDouble(5),
            close = cursor.getDouble(6),
            volume = cursor.getLong(7)
        )
    }

    private fun isValidQuote(quote: Quote): Boolean {
        return quote.symbol.isNotBlank() &&
            quote.exchange.isNotBlank() &&
            quote.ltp.isFinite() &&
            quote.ltp >= 0.0 &&
            quote.volume >= 0L &&
            quote.timestamp > 0L
    }

    private fun isValidCandle(candle: Candle): Boolean {
        if (candle.timestamp <= 0L) return false
        if (!candle.open.isFinite() ||
            !candle.high.isFinite() ||
            !candle.low.isFinite() ||
            !candle.close.isFinite()
        ) return false

        if (candle.open <= 0.0 ||
            candle.high <= 0.0 ||
            candle.low <= 0.0 ||
            candle.close <= 0.0
        ) return false

        return candle.high >= maxOf(
            candle.open,
            candle.close,
            candle.low
        ) &&
            candle.low <= minOf(
                candle.open,
                candle.close,
                candle.high
            ) &&
            candle.volume >= 0L
    }

    private fun normalize(value: String): String {
        return value.trim().toUpperCase(Locale.ROOT)
    }

    private fun ContentValues.putNullable(
        key: String,
        value: Double?
    ) {
        if (value == null || !value.isFinite()) {
            putNull(key)
        } else {
            put(key, value)
        }
    }

    private fun Cursor.nullableDouble(index: Int): Double? {
        return if (isNull(index)) null else getDouble(index)
    }

    companion object {
        private val quoteProjection = arrayOf(
            "symbol",
            "exchange",
            "ltp",
            "open",
            "high",
            "low",
            "previous_close",
            "volume",
            "change",
            "change_percent",
            "timestamp",
            "is_live",
            "exchange_timestamp",
            "received_at"
        )

        private val candleProjection = arrayOf(
            "symbol",
            "timeframe",
            "timestamp",
            "open",
            "high",
            "low",
            "close",
            "volume"
        )
    }
}

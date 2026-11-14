
package com.sb.backend.repositories

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.database.DatabaseMaintenanceGate
import com.sb.backend.market.model.MarketTick
import com.sb.backend.models.Candle
import com.sb.backend.models.Quote
import java.util.Locale

/**
 * Candle timeframe labels are case-sensitive: intraday "1m" and
 * calendar "1M" are different intervals. Do not uppercase this key.
 */
internal fun normalizeCandleTimeframe(value: String): String = value.trim()

class MarketRepository(
    private val databaseHelper: SBDatabaseHelper,
    private val maintenanceGate: DatabaseMaintenanceGate = DatabaseMaintenanceGate.shared
) {

    data class QuoteBatchSaveResult(
        val attempted: Int,
        val saved: Int,
        val rejected: Int,
        val failed: Int
    ) {
        val complete: Boolean
            get() = attempted == saved && rejected == 0 && failed == 0
    }

    data class TickAuditDelta(
        val exchange: String,
        val symbol: String,
        val received: Int = 0,
        val parsed: Int = 0,
        val mapped: Int = 0,
        val rejected: Int = 0,
        val rawSaved: Int = 0,
        val quoteSaved: Int = 0,
        val saveFailed: Int = 0
    )

    private val quotesTable = SBDatabaseHelper.TABLE_QUOTES
    private val candlesTable = SBDatabaseHelper.TABLE_CANDLES
    private val rawTicksTable = SBDatabaseHelper.TABLE_RAW_TICKS
    private val tickAuditTable = SBDatabaseHelper.TABLE_TICK_AUDIT
    private val retryTable =
        SBDatabaseHelper.TABLE_QUOTE_PERSISTENCE_RETRY

    // ---------------------------------------------------------
    // QUOTE SAVE
    // ---------------------------------------------------------

    fun saveQuote(quote: Quote): Boolean {
        return maintenanceGate.withOperation {
        if (!isValidQuote(quote)) return false

        return try {
            databaseHelper.writableDatabase.insertWithOnConflict(
                quotesTable,
                null,
                quoteValues(quote),
                SQLiteDatabase.CONFLICT_REPLACE
            ) != -1L
        } catch (e: Exception) {
            Log.e("MarketRepository", "Quote save failed", e)
            false
        }
    
        }
    }

    fun saveQuotes(quotes: List<Quote>): Int {
        return maintenanceGate.withOperation {
        if (quotes.isEmpty()) return 0

        val db = databaseHelper.writableDatabase
        var saved = 0

        db.beginTransaction()
        try {
            quotes.forEach { quote ->
                if (!isValidQuote(quote)) return@forEach

                try {
                    val result = db.insertWithOnConflict(
                        quotesTable,
                        null,
                        quoteValues(quote),
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                    if (result != -1L) saved++
                } catch (e: Exception) {
                    Log.e(
                        "MarketRepository",
                        "Batch quote save failed: ${quote.symbol}",
                        e
                    )
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return saved
    
        }
    }

    fun saveQuotesDetailed(
        quotes: List<Quote>
    ): QuoteBatchSaveResult {
        return maintenanceGate.withOperation {
        if (quotes.isEmpty()) {
            return QuoteBatchSaveResult(0, 0, 0, 0)
        }

        val db = databaseHelper.writableDatabase
        var saved = 0
        var rejected = 0
        var failed = 0

        db.beginTransaction()
        try {
            quotes.forEach { quote ->
                if (!isValidQuote(quote)) {
                    rejected++
                    return@forEach
                }

                try {
                    val result = db.insertWithOnConflict(
                        quotesTable,
                        null,
                        quoteValues(quote),
                        SQLiteDatabase.CONFLICT_REPLACE
                    )

                    if (result == -1L) failed++ else saved++
                } catch (e: Exception) {
                    failed++
                    Log.e(
                        "MarketRepository",
                        "Detailed quote save failed: ${quote.symbol}",
                        e
                    )
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return QuoteBatchSaveResult(
            attempted = quotes.size,
            saved = saved,
            rejected = rejected,
            failed = failed
        )
    
        }
    }

    // ---------------------------------------------------------
    // QUOTE RETRY QUEUE
    // ---------------------------------------------------------

    fun enqueueQuoteRetry(
        quote: Quote,
        error: String
    ): Boolean {
        return maintenanceGate.withOperation {
        if (!isValidQuote(quote)) return false

        val db = databaseHelper.writableDatabase
        val values = quoteValues(quote).apply {
            put("last_error", error.take(1000))
            put("queued_at", System.currentTimeMillis())
        }

        return try {
            db.beginTransaction()

            val updated = db.update(
                retryTable,
                values,
                "symbol = ? AND exchange = ?",
                arrayOf(
                    normalize(quote.symbol),
                    normalize(quote.exchange)
                )
            )

            if (updated == 0) {
                val insertValues = ContentValues(values).apply {
                    put("attempts", 0)
                }

                val inserted = db.insertWithOnConflict(
                    retryTable,
                    null,
                    insertValues,
                    SQLiteDatabase.CONFLICT_IGNORE
                )

                if (inserted == -1L) {
                    // A concurrent writer may have inserted the same key.
                    db.update(
                        retryTable,
                        values,
                        "symbol = ? AND exchange = ?",
                        arrayOf(
                            normalize(quote.symbol),
                            normalize(quote.exchange)
                        )
                    )
                }
            }

            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            Log.e(
                "MarketRepository",
                "Quote retry enqueue failed",
                e
            )
            false
        } finally {
            if (db.inTransaction()) db.endTransaction()
        }
    
        }
    }

    fun retryPendingQuotes(limit: Int = 100): Int {
        return maintenanceGate.withOperation {
        val db = databaseHelper.writableDatabase
        val safeLimit = limit.coerceIn(1, 500)

        data class RetryItem(
            val quote: Quote,
            val attempts: Int
        )

        val pending = ArrayList<RetryItem>()

        db.rawQuery(
            """
            SELECT
                symbol, exchange, ltp, open, high, low,
                previous_close, volume, change, change_percent,
                timestamp, is_live, exchange_timestamp,
                received_at, attempts
            FROM `$retryTable`
            ORDER BY queued_at ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(safeLimit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                pending.add(
                    RetryItem(
                        quote = readRetryQuote(cursor),
                        attempts = cursor.getInt(14)
                    )
                )
            }
        }

        var recovered = 0

        pending.forEach { item ->
            val quote = item.quote
            var saved = false
            var failed = false

            try {
                /*
                 * Save and delete are performed in one transaction.
                 * A newer retry row cannot be deleted by an older retry.
                 */
                db.beginTransaction()

                val currentTimestamp = db.rawQuery(
                    """
                    SELECT timestamp
                    FROM `$retryTable`
                    WHERE symbol = ? AND exchange = ?
                    """.trimIndent(),
                    arrayOf(
                        normalize(quote.symbol),
                        normalize(quote.exchange)
                    )
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }

                if (currentTimestamp == quote.timestamp) {
                    val result = db.insertWithOnConflict(
                        quotesTable,
                        null,
                        quoteValues(quote),
                        SQLiteDatabase.CONFLICT_REPLACE
                    )

                    if (result != -1L) {
                        val deleted = db.delete(
                            retryTable,
                            """
                            symbol = ? AND exchange = ? AND timestamp = ?
                            """.trimIndent(),
                            arrayOf(
                                normalize(quote.symbol),
                                normalize(quote.exchange),
                                quote.timestamp.toString()
                            )
                        )

                        saved = deleted > 0
                        if (saved) db.setTransactionSuccessful()
                    } else {
                        failed = true
                    }
                }

                db.endTransaction()

                if (saved) {
                    recovered++
                    recordTickAudit(
                        exchange = quote.exchange,
                        symbol = quote.symbol,
                        quoteSaved = 1
                    )
                } else if (failed) {
                    updateRetryFailure(
                        quote,
                        item.attempts,
                        "Quote retry insert returned false"
                    )
                    recordTickAudit(
                        exchange = quote.exchange,
                        symbol = quote.symbol,
                        saveFailed = 1
                    )
                }
            } catch (e: Exception) {
                if (db.inTransaction()) {
                    db.endTransaction()
                }

                updateRetryFailure(
                    quote,
                    item.attempts,
                    e.message ?: "Unknown retry error"
                )

                recordTickAudit(
                    exchange = quote.exchange,
                    symbol = quote.symbol,
                    saveFailed = 1
                )

                Log.e(
                    "MarketRepository",
                    "Quote retry failed: ${quote.symbol}",
                    e
                )
            }
        }

        return recovered
    
        }
    }

    private fun updateRetryFailure(
        quote: Quote,
        previousAttempts: Int,
        error: String
    ) {
        try {
            val values = ContentValues().apply {
                put("attempts", previousAttempts + 1)
                put("last_error", error.take(1000))
            }

            databaseHelper.writableDatabase.update(
                retryTable,
                values,
                "symbol = ? AND exchange = ? AND timestamp = ?",
                arrayOf(
                    normalize(quote.symbol),
                    normalize(quote.exchange),
                    quote.timestamp.toString()
                )
            )
        } catch (e: Exception) {
            Log.e(
                "MarketRepository",
                "Retry failure update failed",
                e
            )
        }
    }

    // ---------------------------------------------------------
    // RAW TICK PERSISTENCE
    // ---------------------------------------------------------

    fun saveRawTick(tick: MarketTick): Boolean {
        return maintenanceGate.withOperation {
        if (!tick.isValid ||
            !tick.ltp.isFinite() ||
            tick.ltp <= 0.0 ||
            tick.volume < 0L ||
            tick.lastTradeQty < 0L ||
            tick.receivedAt <= 0L
        ) {
            return false
        }

        return try {
            val values = ContentValues().apply {
                put("exchange", normalize(tick.key.exchange))
                put("symbol", normalize(tick.key.symbol))
                put("ltp", tick.ltp)
                put("volume", tick.volume)
                put("last_trade_qty", tick.lastTradeQty)

                if (tick.lastTradeTime == null) {
                    putNull("last_trade_time")
                } else {
                    put("last_trade_time", tick.lastTradeTime)
                }

                if (tick.exchangeTime == null) {
                    putNull("exchange_time")
                } else {
                    put("exchange_time", tick.exchangeTime)
                }

                put("received_at", tick.receivedAt)
            }

            databaseHelper.writableDatabase.insert(
                rawTicksTable,
                null,
                values
            ) != -1L
        } catch (e: Exception) {
            Log.e(
                "MarketRepository",
                "Raw tick save failed: ${tick.key.symbol}",
                e
            )
            false
        }
    
        }
    }

    fun pruneRawTicks(olderThanTimestamp: Long): Int {
        return maintenanceGate.withOperation {
        if (olderThanTimestamp <= 0L) return 0

        return try {
            databaseHelper.writableDatabase.delete(
                rawTicksTable,
                "received_at < ?",
                arrayOf(olderThanTimestamp.toString())
            )
        } catch (e: Exception) {
            Log.e("MarketRepository", "Raw tick prune failed", e)
            0
        }
    
        }
    }

    // ---------------------------------------------------------
    // TICK AUDIT
    // ---------------------------------------------------------

    /**
     * Applies multiple per-instrument audit deltas in one SQLite transaction.
     * Intended for the live-feed persistence worker, not the WebSocket callback.
     */
    fun recordTickAudits(deltas: List<TickAuditDelta>): Boolean {
        return maintenanceGate.withOperation {
            val valid = deltas.filter { delta ->
                normalize(delta.exchange).isNotBlank() &&
                    normalize(delta.symbol).isNotBlank() &&
                    listOf(
                        delta.received, delta.parsed, delta.mapped,
                        delta.rejected, delta.rawSaved, delta.quoteSaved,
                        delta.saveFailed
                    ).all { it >= 0 }
            }
            if (valid.isEmpty()) return@withOperation true

            val db = databaseHelper.writableDatabase
            val updatedAt = System.currentTimeMillis()
            try {
                db.beginTransaction()
                valid.forEach { delta ->
                    val exchange = normalize(delta.exchange)
                    val symbol = normalize(delta.symbol)
                    db.execSQL(
                        """
                        UPDATE `$tickAuditTable`
                        SET received = received + ?,
                            parsed = parsed + ?,
                            mapped = mapped + ?,
                            rejected = rejected + ?,
                            raw_saved = raw_saved + ?,
                            quote_saved = quote_saved + ?,
                            save_failed = save_failed + ?,
                            updated_at = ?
                        WHERE exchange = ? AND symbol = ?
                        """.trimIndent(),
                        arrayOf(
                            delta.received, delta.parsed, delta.mapped,
                            delta.rejected, delta.rawSaved, delta.quoteSaved,
                            delta.saveFailed, updatedAt, exchange, symbol
                        )
                    )
                    val rowsUpdated = db.rawQuery("SELECT changes()", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                    if (rowsUpdated == 0) {
                        val values = ContentValues().apply {
                            put("exchange", exchange)
                            put("symbol", symbol)
                            put("received", delta.received)
                            put("parsed", delta.parsed)
                            put("mapped", delta.mapped)
                            put("rejected", delta.rejected)
                            put("raw_saved", delta.rawSaved)
                            put("quote_saved", delta.quoteSaved)
                            put("save_failed", delta.saveFailed)
                            put("updated_at", updatedAt)
                        }
                        check(db.insert(tickAuditTable, null, values) != -1L) {
                            "Tick audit insert failed for $exchange:$symbol"
                        }
                    }
                }
                db.setTransactionSuccessful()
                true
            } catch (e: Exception) {
                Log.e("MarketRepository", "Batched tick audit save failed", e)
                false
            } finally {
                if (db.inTransaction()) db.endTransaction()
            }
        }
    }

    fun recordTickAudit(
        exchange: String,
        symbol: String,
        received: Int = 0,
        parsed: Int = 0,
        mapped: Int = 0,
        rejected: Int = 0,
        rawSaved: Int = 0,
        quoteSaved: Int = 0,
        saveFailed: Int = 0
    ): Boolean {
        return maintenanceGate.withOperation {
        val normalizedExchange = normalize(exchange)
        val normalizedSymbol = normalize(symbol)

        if (normalizedExchange.isBlank() ||
            normalizedSymbol.isBlank()
        ) return false

        if (listOf(
                received,
                parsed,
                mapped,
                rejected,
                rawSaved,
                quoteSaved,
                saveFailed
            ).any { it < 0 }
        ) return false

        val db = databaseHelper.writableDatabase
        val updatedAt = System.currentTimeMillis()

        return try {
            db.beginTransaction()

            db.execSQL(
                """
                UPDATE `$tickAuditTable`
                SET received = received + ?,
                    parsed = parsed + ?,
                    mapped = mapped + ?,
                    rejected = rejected + ?,
                    raw_saved = raw_saved + ?,
                    quote_saved = quote_saved + ?,
                    save_failed = save_failed + ?,
                    updated_at = ?
                WHERE exchange = ? AND symbol = ?
                """.trimIndent(),
                arrayOf(
                    received,
                    parsed,
                    mapped,
                    rejected,
                    rawSaved,
                    quoteSaved,
                    saveFailed,
                    updatedAt,
                    normalizedExchange,
                    normalizedSymbol
                )
            )

            val rowsUpdated = db.rawQuery(
                "SELECT changes()",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }

            if (rowsUpdated == 0) {
                val values = ContentValues().apply {
                    put("exchange", normalizedExchange)
                    put("symbol", normalizedSymbol)
                    put("received", received)
                    put("parsed", parsed)
                    put("mapped", mapped)
                    put("rejected", rejected)
                    put("raw_saved", rawSaved)
                    put("quote_saved", quoteSaved)
                    put("save_failed", saveFailed)
                    put("updated_at", updatedAt)
                }

                check(db.insert(tickAuditTable, null, values) != -1L) {
                    "Tick audit insert failed"
                }
            }

            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            Log.e("MarketRepository", "Tick audit save failed", e)
            false
        } finally {
            if (db.inTransaction()) db.endTransaction()
        }
    
        }
    }

    // ---------------------------------------------------------
    // QUOTE READ
    // ---------------------------------------------------------

    fun getQuote(
        symbol: String,
        exchange: String = "NSE"
    ): Quote? {
        return maintenanceGate.withOperation {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank()
        ) return null

        return try {
            databaseHelper.readableDatabase.query(
                quotesTable,
                quoteProjection,
                "UPPER(symbol) = ? AND UPPER(exchange) = ?",
                arrayOf(normalizedSymbol, normalizedExchange),
                null,
                null,
                "timestamp DESC",
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) readQuote(cursor) else null
            }
        } catch (e: Exception) {
            Log.e("MarketRepository", "Quote read failed", e)
            null
        }
    
        }
    }

    fun getQuotes(
        symbols: List<String>,
        exchange: String? = null
    ): List<Quote> {
        return maintenanceGate.withOperation {
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
    }

    private fun getAnyExchangeQuote(symbol: String): Quote? {
        val normalizedSymbol = normalize(symbol)
        if (normalizedSymbol.isBlank()) return null

        return try {
            databaseHelper.readableDatabase.query(
                quotesTable,
                quoteProjection,
                "UPPER(symbol) = ?",
                arrayOf(normalizedSymbol),
                null,
                null,
                "timestamp DESC",
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) readQuote(cursor) else null
            }
        } catch (e: Exception) {
            Log.e("MarketRepository", "Any-exchange quote read failed", e)
            null
        }
    }

    // ---------------------------------------------------------
    // CANDLE SAVE
    // ---------------------------------------------------------

    fun saveCandles(
        symbol: String,
        exchange: String,
        timeframe: String,
        candles: List<Candle>
    ): Int {
        return maintenanceGate.withOperation {
            val normalizedSymbol = normalize(symbol)
            val normalizedExchange = normalize(exchange)
            val normalizedTimeframe = normalizeCandleTimeframe(timeframe)

            if (normalizedSymbol.isBlank() ||
                normalizedExchange.isBlank() ||
                normalizedTimeframe.isBlank() ||
                candles.isEmpty()
            ) {
                return@withOperation 0
            }

            // Reject the whole batch before opening a transaction. Silently
            // skipping malformed rows would leave a plausible-looking but
            // incomplete candle series in the database.
            val timestamps = HashSet<Long>(candles.size)
            if (candles.any { !isValidCandle(it) || !timestamps.add(it.timestamp) }) {
                Log.e(
                    "MarketRepository",
                    "Candle batch rejected: invalid candle or duplicate timestamp for " +
                        "$normalizedExchange:$normalizedSymbol/$normalizedTimeframe"
                )
                return@withOperation 0
            }

            val db = databaseHelper.writableDatabase
            var saved = 0

            try {
                db.beginTransaction()

                candles.forEach { candle ->
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

                        // Candle model does not expose tick metadata.
                        // Do not invent source tick counts or tick times.
                        put("status", "FINAL")
                        put("source_tick_count", 0)
                        putNull("first_tick_timestamp")
                        putNull("last_tick_timestamp")
                        put("finalized_at", System.currentTimeMillis())
                    }

                    val result = db.insertWithOnConflict(
                        candlesTable,
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )

                    check(result != -1L) {
                        "Candle insert failed at timestamp ${candle.timestamp}"
                    }
                    saved++
                }

                db.setTransactionSuccessful()
                saved
            } catch (e: Exception) {
                Log.e(
                    "MarketRepository",
                    "Candle batch transaction rolled back: " +
                        "$normalizedExchange:$normalizedSymbol/$normalizedTimeframe",
                    e
                )
                0
            } finally {
                if (db.inTransaction()) {
                    db.endTransaction()
                }
            }
        }
    }

    // ---------------------------------------------------------
    // CANDLE READ
    // ---------------------------------------------------------

    fun getCandles(
        symbol: String,
        timeframe: String,
        exchange: String = "NSE",
        limit: Int = 500
    ): List<Candle> {
        return maintenanceGate.withOperation {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)
        val normalizedTimeframe = normalizeCandleTimeframe(timeframe)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank() ||
            normalizedTimeframe.isBlank()
        ) return emptyList()

        val result = ArrayList<Candle>()

        return try {
            databaseHelper.readableDatabase.query(
                candlesTable,
                candleProjection,
                """
                UPPER(symbol) = ? AND UPPER(exchange) = ?
                AND timeframe = ?
                """.trimIndent(),
                arrayOf(
                    normalizedSymbol,
                    normalizedExchange,
                    normalizedTimeframe
                ),
                null,
                null,
                "timestamp DESC",
                limit.coerceIn(1, 5000).toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(readCandle(cursor))
                }
                result.reversed()
            }
        } catch (e: Exception) {
            Log.e("MarketRepository", "Candle read failed", e)
            emptyList()
        }
    
        }
    }

    fun getCandlesBetween(
        symbol: String,
        timeframe: String,
        fromTimestamp: Long,
        toTimestamp: Long,
        exchange: String = "NSE",
        limit: Int = 5000
    ): List<Candle> {
        return maintenanceGate.withOperation {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)
        val normalizedTimeframe = normalizeCandleTimeframe(timeframe)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank() ||
            normalizedTimeframe.isBlank() ||
            fromTimestamp <= 0L ||
            toTimestamp < fromTimestamp
        ) return emptyList()

        val result = ArrayList<Candle>()

        return try {
            databaseHelper.readableDatabase.query(
                candlesTable,
                candleProjection,
                """
                UPPER(symbol) = ? AND UPPER(exchange) = ?
                AND timeframe = ?
                AND timestamp >= ? AND timestamp <= ?
                """.trimIndent(),
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
                result
            }
        } catch (e: Exception) {
            Log.e("MarketRepository", "Candle range read failed", e)
            emptyList()
        }
    
        }
    }

    fun latestCandle(
        symbol: String,
        timeframe: String,
        exchange: String = "NSE"
    ): Candle? {
        return maintenanceGate.withOperation {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)
        val normalizedTimeframe = normalizeCandleTimeframe(timeframe)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank() ||
            normalizedTimeframe.isBlank()
        ) return null

        return try {
            databaseHelper.readableDatabase.query(
                candlesTable,
                candleProjection,
                """
                UPPER(symbol) = ? AND UPPER(exchange) = ?
                AND timeframe = ?
                """.trimIndent(),
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
                if (cursor.moveToFirst()) readCandle(cursor) else null
            }
        } catch (e: Exception) {
            Log.e("MarketRepository", "Latest candle read failed", e)
            null
        }
    
        }
    }

    fun countCandles(
        symbol: String,
        timeframe: String,
        exchange: String = "NSE"
    ): Int {
        return maintenanceGate.withOperation {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)
        val normalizedTimeframe = normalizeCandleTimeframe(timeframe)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank() ||
            normalizedTimeframe.isBlank()
        ) return 0

        return try {
            databaseHelper.readableDatabase.rawQuery(
                """
                SELECT COUNT(*) FROM `$candlesTable`
                WHERE UPPER(symbol) = ? AND UPPER(exchange) = ?
                AND timeframe = ?
                """.trimIndent(),
                arrayOf(
                    normalizedSymbol,
                    normalizedExchange,
                    normalizedTimeframe
                )
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } catch (e: Exception) {
            Log.e("MarketRepository", "Candle count failed", e)
            0
        }
    
        }
    }

    fun pruneCandles(olderThanTimestamp: Long): Int {
        return maintenanceGate.withOperation {
        if (olderThanTimestamp <= 0L) return 0

        return try {
            databaseHelper.writableDatabase.delete(
                candlesTable,
                "timestamp < ?",
                arrayOf(olderThanTimestamp.toString())
            )
        } catch (e: Exception) {
            Log.e("MarketRepository", "Candle prune failed", e)
            0
        }
    
        }
    }

    fun deleteCandles(
        symbol: String,
        timeframe: String,
        exchange: String = "NSE"
    ): Int {
        return maintenanceGate.withOperation {
        val normalizedSymbol = normalize(symbol)
        val normalizedExchange = normalize(exchange)
        val normalizedTimeframe = normalizeCandleTimeframe(timeframe)

        if (normalizedSymbol.isBlank() ||
            normalizedExchange.isBlank() ||
            normalizedTimeframe.isBlank()
        ) return 0

        return try {
            databaseHelper.writableDatabase.delete(
                candlesTable,
                """
                UPPER(symbol) = ? AND UPPER(exchange) = ?
                AND timeframe = ?
                """.trimIndent(),
                arrayOf(
                    normalizedSymbol,
                    normalizedExchange,
                    normalizedTimeframe
                )
            )
        } catch (e: Exception) {
            Log.e("MarketRepository", "Candle delete failed", e)
            0
        }
    
        }
    }

    fun clearQuotes(): Int {
        return maintenanceGate.withOperation {
        return try {
            databaseHelper.writableDatabase.delete(
                quotesTable,
                null,
                null
            )
        } catch (e: Exception) {
            Log.e("MarketRepository", "Clear quotes failed", e)
            0
        }
    
        }
    }

    fun clearCandles(): Int {
        return maintenanceGate.withOperation {
        return try {
            databaseHelper.writableDatabase.delete(
                candlesTable,
                null,
                null
            )
        } catch (e: Exception) {
            Log.e("MarketRepository", "Clear candles failed", e)
            0
        }
    
        }
    }

    // ---------------------------------------------------------
    // CONTENT VALUES AND MAPPERS
    // ---------------------------------------------------------

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

    private fun readRetryQuote(cursor: Cursor): Quote {
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

    // ---------------------------------------------------------
    // VALIDATION
    // ---------------------------------------------------------

    private fun isValidQuote(quote: Quote): Boolean {
        return quote.symbol.isNotBlank() &&
            quote.exchange.isNotBlank() &&
            quote.ltp.isFinite() &&
            quote.ltp > 0.0 &&
            quote.volume >= 0L &&
            quote.timestamp > 0L &&
            quote.receivedAt >= 0L
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

        if (candle.volume < 0L) return false

        return candle.high >= maxOf(
            candle.open,
            candle.close,
            candle.low
        ) && candle.low <= minOf(
            candle.open,
            candle.close,
            candle.high
        )
    }

    // ---------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------

    private fun normalize(value: String): String {
        return value.trim().uppercase(Locale.ROOT)
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

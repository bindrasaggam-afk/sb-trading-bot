
package com.sb.backend.repositories

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.sb.BseEquityStockMaster
import com.sb.EquityStockMaster
import com.sb.MCXStockMaster
import com.sb.NseEtfStockMaster
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.models.Instrument
import com.sb.backend.services.KotakKnownTokenMaster
import com.sb.backend.services.KotakScripToken
import java.util.Locale

class InstrumentRepository(
    private val databaseHelper: SBDatabaseHelper
) {

    fun save(instrument: Instrument): Long {
        if (!isValid(instrument)) return -1L

        return databaseHelper.writableDatabase.insertWithOnConflict(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            null,
            valuesOf(instrument),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun saveAll(instruments: List<Instrument>): Int {
        if (instruments.isEmpty()) return 0

        val db = databaseHelper.writableDatabase
        var saved = 0

        db.beginTransaction()
        try {
            instruments.forEach { instrument ->
                if (!isValid(instrument)) return@forEach

                val id = db.insertWithOnConflict(
                    SBDatabaseHelper.TABLE_INSTRUMENTS,
                    null,
                    valuesOf(instrument),
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

    /**
     * Saves expiry-specific MCX futures as separate instrument rows.
     *
     * IMPORTANT:
     * - Each contract must use its actual trading symbol as Instrument.symbol.
     * - The original base commodity rows remain untouched.
     * - expiryMillis must contain the actual contract expiry timestamp.
     */
    fun upsertMcxContracts(contracts: List<Instrument>): Int {
        if (contracts.isEmpty()) return 0

        val db = databaseHelper.writableDatabase
        var saved = 0

        db.beginTransaction()
        try {
            contracts.forEach { contract ->

                if (!isValid(contract)) return@forEach

                if (!contract.exchange.equals("MCX", ignoreCase = true)) {
                    return@forEach
                }

                if (!contract.segment.equals("COMMODITY", ignoreCase = true)) {
                    return@forEach
                }

                if (contract.expiryMillis == null || contract.expiryMillis <= 0L) {
                    return@forEach
                }

                if (contract.token.isNullOrBlank()) {
                    return@forEach
                }

                val contractSymbol = contract.tradingSymbol.trim()
                if (contractSymbol.isBlank()) return@forEach

                // Actual Kotak trading symbol is the unique contract identity.
                val mcxContract = contract.copy(
                    symbol = contractSymbol,
                    tradingSymbol = contractSymbol,
                    exchange = "MCX",
                    segment = "COMMODITY",
                    instrumentType = "FUT",
                    isActive = true
                )

                val id = db.insertWithOnConflict(
                    SBDatabaseHelper.TABLE_INSTRUMENTS,
                    null,
                    valuesOf(mcxContract),
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

    fun findBySymbol(
        symbol: String,
        exchange: String? = null
    ): Instrument? {
        val normalized = normalize(symbol)
        if (normalized.isBlank()) return null

        val selection: String
        val args: Array<String>

        if (exchange.isNullOrBlank()) {
            selection = "UPPER(symbol) = ?"
            args = arrayOf(normalized)
        } else {
            val normalizedExchange = normalize(exchange)
            if (normalizedExchange.isBlank()) return null

            selection = "UPPER(symbol) = ? AND UPPER(exchange) = ?"
            args = arrayOf(normalized, normalizedExchange)
        }

        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            projection,
            selection,
            args,
            null,
            null,
            "is_active DESC, CASE WHEN exchange = 'NSE' THEN 0 ELSE 1 END, id ASC",
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                readInstrument(cursor)
            } else {
                null
            }
        }
    }

    fun search(
        query: String,
        exchange: String? = null,
        segment: String? = null,
        limit: Int = 100
    ): List<Instrument> {
        val text = query.trim()
        val safeLimit = limit.coerceIn(1, 500)

        val clauses = ArrayList<String>()
        val args = ArrayList<String>()

        clauses.add("is_active = 1")

        if (text.isNotBlank()) {
            val pattern = "%${escapeLike(text)}%"

            clauses.add(
                "(symbol LIKE ? ESCAPE '\\' " +
                    "OR name LIKE ? ESCAPE '\\' " +
                    "OR trading_symbol LIKE ? ESCAPE '\\')"
            )

            args.add(pattern)
            args.add(pattern)
            args.add(pattern)
        }

        if (!exchange.isNullOrBlank()) {
            clauses.add("UPPER(exchange) = ?")
            args.add(normalize(exchange))
        }

        if (!segment.isNullOrBlank()) {
            clauses.add("UPPER(segment) = ?")
            args.add(normalize(segment))
        }

        val order = if (text.isBlank()) {
            "symbol COLLATE NOCASE"
        } else {
            "CASE WHEN UPPER(symbol) = ? THEN 0 " +
                "WHEN UPPER(symbol) LIKE ? ESCAPE '\\' THEN 1 " +
                "ELSE 2 END, symbol COLLATE NOCASE"
        }

        val queryArgs = ArrayList(args)

        if (text.isNotBlank()) {
            val upper = normalize(text)
            queryArgs.add(upper)
            queryArgs.add("${escapeLike(upper)}%")
        }

        queryArgs.add(safeLimit.toString())

        val sql = "SELECT ${projection.joinToString(", ")} " +
            "FROM ${SBDatabaseHelper.TABLE_INSTRUMENTS} " +
            "WHERE ${clauses.joinToString(" AND ")} " +
            "ORDER BY $order LIMIT ?"

        val result = ArrayList<Instrument>()

        databaseHelper.readableDatabase.rawQuery(
            sql,
            queryArgs.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(readInstrument(cursor))
            }
        }

        return result
    }

    fun getBySegment(
        segment: String,
        exchange: String? = null,
        limit: Int = 500
    ): List<Instrument> {
        val normalizedSegment = normalize(segment)
        if (normalizedSegment.isBlank()) return emptyList()

        val clauses = ArrayList<String>()
        val args = ArrayList<String>()

        clauses.add("is_active = 1")
        clauses.add("UPPER(segment) = ?")
        args.add(normalizedSegment)

        if (!exchange.isNullOrBlank()) {
            clauses.add("UPPER(exchange) = ?")
            args.add(normalize(exchange))
        }

        val result = ArrayList<Instrument>()

        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            projection,
            clauses.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "CASE WHEN expiry_millis IS NULL THEN 1 ELSE 0 END, " +
                "expiry_millis ASC, symbol COLLATE NOCASE",
            limit.coerceIn(1, 2000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(readInstrument(cursor))
            }
        }

        return result
    }

    fun getActive(limit: Int = 5000): List<Instrument> {
        val result = ArrayList<Instrument>()

        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            projection,
            "is_active = 1",
            null,
            null,
            null,
            "CASE WHEN exchange = 'NSE' THEN 0 " +
                "WHEN exchange = 'BSE' THEN 1 ELSE 2 END, " +
                "CASE WHEN expiry_millis IS NULL THEN 1 ELSE 0 END, " +
                "expiry_millis ASC, symbol COLLATE NOCASE",
            limit.coerceIn(1, 10000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(readInstrument(cursor))
            }
        }

        return result
    }

    fun getActiveWithTokens(limit: Int = 3000): List<Instrument> {
        val result = ArrayList<Instrument>()

        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            projection,
            "is_active = 1 AND token IS NOT NULL AND TRIM(token) != ''",
            null,
            null,
            null,
            "CASE WHEN exchange = 'NSE' THEN 0 " +
                "WHEN exchange = 'BSE' THEN 1 ELSE 2 END, " +
                "CASE WHEN expiry_millis IS NULL THEN 1 ELSE 0 END, " +
                "expiry_millis ASC, symbol COLLATE NOCASE",
            limit.coerceIn(1, 5000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(readInstrument(cursor))
            }
        }

        return result
    }

    fun count(): Int {
        databaseHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_INSTRUMENTS}",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun backfillKnownTokens(): Int {
        val db = databaseHelper.writableDatabase
        var updated = 0

        db.beginTransaction()
        try {
            KotakKnownTokenMaster.instruments.forEach { known ->
                val values = ContentValues().apply {
                    put("token", known.token)
                }

                updated += db.update(
                    SBDatabaseHelper.TABLE_INSTRUMENTS,
                    values,
                    """
                        REPLACE(UPPER(symbol), ' ', '') = ?
                        AND UPPER(exchange) = ?
                        AND UPPER(segment) = ?
                        AND (token IS NULL OR TRIM(token) = '')
                    """.trimIndent(),
                    arrayOf(
                        normalizedSymbol(known.symbol),
                        normalize(known.exchange),
                        normalize(known.segment)
                    )
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return updated
    }

    fun applyKotakScripTokens(tokens: List<KotakScripToken>): Int {
        if (tokens.isEmpty()) return 0

        val db = databaseHelper.writableDatabase
        var updated = 0

        db.beginTransaction()
        try {
            tokens.forEach { token ->
                if (token.token.isBlank() || token.segments.isEmpty()) {
                    return@forEach
                }

                val placeholders = token.segments.joinToString(",") {
                    "?"
                }

                val values = ContentValues().apply {
                    put("token", token.token.trim())
                }

                val args = ArrayList<String>()
                args.add(normalizedSymbol(token.symbol))
                args.add(normalize(token.exchange))
                token.segments.forEach { args.add(normalize(it)) }

                updated += db.update(
                    SBDatabaseHelper.TABLE_INSTRUMENTS,
                    values,
                    """
                        REPLACE(UPPER(symbol), ' ', '') = ?
                        AND UPPER(exchange) = ?
                        AND UPPER(segment) IN ($placeholders)
                    """.trimIndent(),
                    args.toTypedArray()
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return updated
    }

    fun deleteAll(): Int {
        return databaseHelper.writableDatabase.delete(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            null,
            null
        )
    }

    fun seedBuiltInMasters(): Int {
        val db = databaseHelper.writableDatabase
        var inserted = 0

        db.beginTransaction()
        try {
            inserted += insertEquities(
                db,
                EquityStockMaster.allEquities,
                "NSE",
                "EQ"
            )

            inserted += insertBseStocks(db)
            inserted += insertIndexSymbols(db)
            inserted += insertEtfs(db)
            inserted += insertCommodities(db)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return inserted
    }

    private fun insertEquities(
        db: SQLiteDatabase,
        items: List<Triple<String, String, String>>,
        exchange: String,
        segment: String
    ): Int {
        var inserted = 0

        items.forEach { item ->
            val instrument = Instrument(
                symbol = item.first,
                name = item.second,
                exchange = exchange,
                segment = segment,
                instrumentType = item.third.ifBlank { "EQ" }
            )

            if (insertIfMissing(db, instrument) != -1L) {
                inserted++
            }
        }

        return inserted
    }

    private fun insertBseStocks(db: SQLiteDatabase): Int {
        var inserted = 0

        BseEquityStockMaster.stocks.forEach { stock ->
            val instrument = Instrument(
                symbol = stock.symbol,
                name = stock.name,
                exchange = "BSE",
                segment = "EQ",
                instrumentType = "EQ"
            )

            if (insertIfMissing(db, instrument) != -1L) {
                inserted++
            }
        }

        return inserted
    }

    private fun insertIndexSymbols(db: SQLiteDatabase): Int {
        val indices = listOf(
            "NIFTY 50" to "Nifty 50",
            "BANK NIFTY" to "Nifty Bank",
            "FINNIFTY" to "Nifty Financial Services",
            "MIDCPNIFTY" to "Nifty Midcap Select",
            "NIFTY 100" to "Nifty 100",
            "NIFTY 200" to "Nifty 200",
            "NIFTY 500" to "Nifty 500",
            "NIFTY NEXT 50" to "Nifty Next 50",
            "NIFTY MIDCAP 50" to "Nifty Midcap 50",
            "NIFTY MIDCAP 100" to "Nifty Midcap 100",
            "NIFTY SMALLCAP 100" to "Nifty Smallcap 100",
            "NIFTY IT" to "Nifty IT",
            "NIFTY AUTO" to "Nifty Auto",
            "NIFTY PHARMA" to "Nifty Pharma",
            "NIFTY FMCG" to "Nifty FMCG",
            "NIFTY METAL" to "Nifty Metal",
            "NIFTY REALTY" to "Nifty Realty",
            "NIFTY ENERGY" to "Nifty Energy"
        )

        var inserted = 0

        indices.forEach { item ->
            val instrument = Instrument(
                symbol = item.first,
                name = item.second,
                exchange = "NSE",
                segment = "INDEX",
                instrumentType = "INDEX"
            )

            if (insertIfMissing(db, instrument) != -1L) {
                inserted++
            }
        }

        return inserted
    }

    private fun insertEtfs(db: SQLiteDatabase): Int {
        var inserted = 0

        NseEtfStockMaster.allEtfs.forEach { etf ->
            val instrument = Instrument(
                symbol = etf.symbol,
                name = etf.name,
                exchange = "NSE",
                segment = "ETF",
                instrumentType = "ETF"
            )

            if (insertIfMissing(db, instrument) != -1L) {
                inserted++
            }
        }

        return inserted
    }

    private fun insertCommodities(db: SQLiteDatabase): Int {
        var inserted = 0

        MCXStockMaster.allCommodities.forEach { commodity ->
            val instrument = Instrument(
                symbol = commodity.symbol,
                name = commodity.name,
                exchange = "MCX",
                segment = "COMMODITY",
                instrumentType = "FUT"
            )

            if (insertIfMissing(db, instrument) != -1L) {
                inserted++
            }
        }

        return inserted
    }

    private fun insertIfMissing(
        db: SQLiteDatabase,
        instrument: Instrument
    ): Long {
        if (!isValid(instrument)) return -1L

        val knownToken = KotakKnownTokenMaster.find(instrument.symbol)

        val matchedToken = knownToken?.takeIf {
            it.exchange.equals(instrument.exchange, ignoreCase = true) &&
                it.segment.equals(instrument.segment, ignoreCase = true)
        }?.token

        val instrumentWithToken = instrument.copy(
            token = matchedToken ?: instrument.token
        )

        val values = valuesOf(instrumentWithToken)

        val insertedId = db.insertWithOnConflict(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )

        if (insertedId == -1L && !matchedToken.isNullOrBlank()) {
            val tokenValues = ContentValues().apply {
                put("token", matchedToken)
            }

            db.update(
                SBDatabaseHelper.TABLE_INSTRUMENTS,
                tokenValues,
                """
                    REPLACE(UPPER(symbol), ' ', '') = ?
                    AND UPPER(exchange) = ?
                    AND UPPER(segment) = ?
                    AND (token IS NULL OR TRIM(token) = '')
                """.trimIndent(),
                arrayOf(
                    normalizedSymbol(instrument.symbol),
                    normalize(instrument.exchange),
                    normalize(instrument.segment)
                )
            )
        }

        return insertedId
    }

    private fun isValid(instrument: Instrument): Boolean {
        return instrument.symbol.isNotBlank() &&
            instrument.name.isNotBlank() &&
            instrument.exchange.isNotBlank() &&
            instrument.segment.isNotBlank() &&
            instrument.instrumentType.isNotBlank() &&
            instrument.lotSize > 0 &&
            instrument.tickSize.isFinite() &&
            instrument.tickSize > 0.0
    }

    private fun valuesOf(instrument: Instrument): ContentValues {
        val symbol = normalize(instrument.symbol)

        return ContentValues().apply {
            put("symbol", symbol)
            put("name", instrument.name.trim())
            put("exchange", normalize(instrument.exchange))
            put("segment", normalize(instrument.segment))
            put("instrument_type", normalize(instrument.instrumentType))
            put(
                "trading_symbol",
                normalize(instrument.tradingSymbol.ifBlank { symbol })
            )

            if (instrument.token.isNullOrBlank()) {
                putNull("token")
            } else {
                put("token", instrument.token.trim())
            }

            put("lot_size", instrument.lotSize)
            put("tick_size", instrument.tickSize)
            put("is_active", if (instrument.isActive) 1 else 0)

            if (instrument.expiryMillis == null) {
                putNull("expiry_millis")
            } else {
                put("expiry_millis", instrument.expiryMillis)
            }
        }
    }

    private fun readInstrument(cursor: Cursor): Instrument {
        return Instrument(
            id = cursor.getLong(0),
            symbol = cursor.getString(1),
            name = cursor.getString(2),
            exchange = cursor.getString(3),
            segment = cursor.getString(4),
            instrumentType = cursor.getString(5),
            tradingSymbol = cursor.getString(6),
            token = if (cursor.isNull(7)) null else cursor.getString(7),
            lotSize = cursor.getInt(8),
            tickSize = cursor.getDouble(9),
            isActive = cursor.getInt(10) == 1,
            expiryMillis = if (cursor.isNull(11)) {
                null
            } else {
                cursor.getLong(11)
            }
        )
    }

    private fun normalize(value: String): String {
        return value.trim().toUpperCase(Locale.ROOT)
    }

    private fun normalizedSymbol(value: String): String {
        return normalize(value.replace(" ", ""))
    }

    private fun escapeLike(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    companion object {
        private val projection = arrayOf(
            "id",
            "symbol",
            "name",
            "exchange",
            "segment",
            "instrument_type",
            "trading_symbol",
            "token",
            "lot_size",
            "tick_size",
            "is_active",
            "expiry_millis"
        )
    }
}

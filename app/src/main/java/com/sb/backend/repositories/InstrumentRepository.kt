
package com.sb.backend.repositories

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.sb.BseEquityStockMaster
import com.sb.EquityStockMaster
import com.sb.MCXStockMaster
import com.sb.NseEtfStockMaster
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.database.DatabaseMaintenanceGate
import com.sb.backend.models.Instrument
import com.sb.backend.services.KotakKnownTokenMaster
import com.sb.backend.services.KotakScripToken
import java.util.Locale

class InstrumentRepository(
    private val databaseHelper: SBDatabaseHelper,
    private val maintenanceGate: DatabaseMaintenanceGate = DatabaseMaintenanceGate.shared
) {

    // ------------------------------------------------------------
    // SAVE
    // ------------------------------------------------------------

    fun save(instrument: Instrument): Long {
        return maintenanceGate.withOperation {
        if (!isValid(instrument)) return -1L

        return upsertPreservingId(
            databaseHelper.writableDatabase,
            instrument
        )
    
        }
    }

    fun saveAll(instruments: List<Instrument>): Int {
        return maintenanceGate.withOperation {
        if (instruments.isEmpty()) return 0

        val db = databaseHelper.writableDatabase
        var saved = 0

        db.beginTransaction()
        try {
            instruments.forEach { instrument ->
                if (!isValid(instrument)) return@forEach

                if (upsertPreservingId(db, instrument) != -1L) {
                    saved++
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return saved
    
        }
    }

    /**
     * Inserts a new row or updates the existing row without
     * deleting/replacing its primary key.
     *
     * Instrument identity is exchange + symbol.
     */
    private fun upsertPreservingId(
        db: SQLiteDatabase,
        instrument: Instrument
    ): Long {
        if (!isValid(instrument)) return -1L

        val normalizedInstrument = instrument.copy(
            symbol = normalize(instrument.symbol),
            exchange = normalize(instrument.exchange),
            segment = normalize(instrument.segment),
            instrumentType = normalize(instrument.instrumentType),
            tradingSymbol = normalize(
                instrument.tradingSymbol.ifBlank { instrument.symbol }
            )
        )

        val values = valuesOf(normalizedInstrument)

        val insertedId = db.insertWithOnConflict(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )

        if (insertedId != -1L) {
            return insertedId
        }

        val selection = "UPPER(exchange) = ? AND UPPER(symbol) = ?"
        val args = arrayOf(
            normalize(normalizedInstrument.exchange),
            normalize(normalizedInstrument.symbol)
        )

        val updated = db.update(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            values,
            selection,
            args
        )

        if (updated <= 0) return -1L

        db.query(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            arrayOf("id"),
            selection,
            args,
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getLong(0)
            } else {
                -1L
            }
        }
    }

    // ------------------------------------------------------------
    // MCX CONTRACT UPSERT
    // ------------------------------------------------------------

    /**
     * Saves expiry-specific MCX futures as separate instrument rows.
     *
     * Actual trading symbol is used as the contract identity.
     * Existing row IDs are preserved during updates.
     */
    fun upsertMcxContracts(contracts: List<Instrument>): Int {
        return maintenanceGate.withOperation {
        if (contracts.isEmpty()) return 0

        val db = databaseHelper.writableDatabase
        var saved = 0

        db.beginTransaction()
        try {
            contracts.forEach { contract ->

                if (!contract.exchange.equals("MCX", ignoreCase = true)) {
                    return@forEach
                }

                if (!contract.segment.equals("COMMODITY", ignoreCase = true)) {
                    return@forEach
                }

                if (contract.expiryMillis == null ||
                    contract.expiryMillis <= 0L
                ) {
                    return@forEach
                }

                if (contract.token.isNullOrBlank()) {
                    return@forEach
                }

                val contractSymbol = contract.tradingSymbol.trim()
                if (contractSymbol.isBlank()) return@forEach

                // Normalize before validation.
                // This allows a valid tradingSymbol even if the
                // incoming base symbol is blank.
                val mcxContract = contract.copy(
                    symbol = contractSymbol,
                    tradingSymbol = contractSymbol,
                    exchange = "MCX",
                    segment = "COMMODITY",
                    instrumentType = "FUT",
                    isActive = true
                )

                if (!isValid(mcxContract)) return@forEach

                if (upsertPreservingId(db, mcxContract) != -1L) {
                    saved++
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return saved
    
        }
    }

    // ------------------------------------------------------------
    // MCX FUTURES QUERIES
    // ------------------------------------------------------------

    fun getActiveMcxFutures(
        now: Long = System.currentTimeMillis()
    ): List<Instrument> {
        return maintenanceGate.withOperation {
        val result = ArrayList<Instrument>()

        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            projection,
            "exchange = 'MCX' AND segment = 'COMMODITY' " +
                "AND instrument_type = 'FUT' AND is_active = 1 " +
                "AND token IS NOT NULL AND TRIM(token) != '' " +
                "AND expiry_millis IS NOT NULL AND expiry_millis > ?",
            arrayOf(now.toString()),
            null,
            null,
            "name COLLATE NOCASE, expiry_millis ASC, symbol COLLATE NOCASE"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(readInstrument(cursor))
            }
        }

        return result
    
        }
    }

    fun countActiveMcxFutures(
        now: Long = System.currentTimeMillis()
    ): Int {
        return maintenanceGate.withOperation {
        databaseHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_INSTRUMENTS} " +
                "WHERE exchange = 'MCX' AND segment = 'COMMODITY' " +
                "AND instrument_type = 'FUT' AND is_active = 1 " +
                "AND token IS NOT NULL AND TRIM(token) != '' " +
                "AND expiry_millis IS NOT NULL AND expiry_millis > ?",
            arrayOf(now.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    
        }
    }

    fun deactivateExpiredMcxContracts(
        now: Long = System.currentTimeMillis()
    ): Int {
        return maintenanceGate.withOperation {
        val values = ContentValues().apply {
            put("is_active", 0)
        }

        return databaseHelper.writableDatabase.update(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            values,
            "exchange = 'MCX' AND segment = 'COMMODITY' " +
                "AND instrument_type = 'FUT' " +
                "AND expiry_millis IS NOT NULL AND expiry_millis <= ?",
            arrayOf(now.toString())
        )
    
        }
    }

    /**
     * Deactivates MCX futures not present in the latest valid master.
     *
     * Empty input intentionally does nothing to prevent accidental
     * deactivation of every MCX contract.
     */
    fun deactivateMcxFuturesNotIn(
        symbols: Collection<String>
    ): Int {
        return maintenanceGate.withOperation {
        val activeSymbols = symbols
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()

        if (activeSymbols.isEmpty()) return 0

        val values = ContentValues().apply {
            put("is_active", 0)
        }

        val placeholders = activeSymbols.joinToString(",") { "?" }

        return databaseHelper.writableDatabase.update(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            values,
            "exchange = 'MCX' AND segment = 'COMMODITY' " +
                "AND instrument_type = 'FUT' " +
                "AND expiry_millis IS NOT NULL " +
                "AND UPPER(symbol) NOT IN ($placeholders)",
            activeSymbols.toTypedArray()
        )
    
        }
    }

    // ------------------------------------------------------------
    // FIND BY SYMBOL
    // ------------------------------------------------------------

    fun findBySymbol(
        symbol: String,
        exchange: String? = null
    ): Instrument? {
        return maintenanceGate.withOperation {
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
    }

    // ------------------------------------------------------------
    // SEARCH
    // ------------------------------------------------------------

    fun search(
        query: String,
        exchange: String? = null,
        segment: String? = null,
        limit: Int = 100
    ): List<Instrument> {
        return maintenanceGate.withOperation {
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
    }

    // ------------------------------------------------------------
    // SEGMENT QUERIES
    // ------------------------------------------------------------

    fun getBySegment(
        segment: String,
        exchange: String? = null,
        limit: Int = 500
    ): List<Instrument> {
        return maintenanceGate.withOperation {
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
    }

    // ------------------------------------------------------------
    // ACTIVE INSTRUMENTS
    // ------------------------------------------------------------

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
        return maintenanceGate.withOperation {
        databaseHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_INSTRUMENTS}",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    
        }
    }

    // ------------------------------------------------------------
    // KNOWN TOKEN BACKFILL
    // ------------------------------------------------------------

    fun backfillKnownTokens(): Int {
        return maintenanceGate.withOperation {
        val db = databaseHelper.writableDatabase
        var updated = 0

        db.beginTransaction()
        try {
            KotakKnownTokenMaster.instruments.forEach { known ->

                if (known.token.isBlank() ||
                    known.symbol.isBlank() ||
                    known.exchange.isBlank() ||
                    known.segment.isBlank()
                ) {
                    return@forEach
                }

                val values = ContentValues().apply {
                    put("token", known.token.trim())
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
    }

    // ------------------------------------------------------------
    // KOTAK SCRIP MASTER TOKEN UPDATE
    // ------------------------------------------------------------

    fun applyKotakScripTokens(
        tokens: List<KotakScripToken>
    ): Int {
        return maintenanceGate.withOperation {
        if (tokens.isEmpty()) return 0

        val db = databaseHelper.writableDatabase
        var updated = 0

        db.beginTransaction()
        try {
            tokens.forEach { token ->

                val normalizedSymbol = normalizedSymbol(token.symbol)
                val normalizedExchange = normalize(token.exchange)
                val normalizedSegments = token.segments
                    .map { normalize(it) }
                    .filter { it.isNotBlank() }
                    .distinct()

                val normalizedToken = token.token.trim()

                if (normalizedSymbol.isBlank() ||
                    normalizedExchange.isBlank() ||
                    normalizedToken.isBlank() ||
                    normalizedSegments.isEmpty()
                ) {
                    return@forEach
                }

                val placeholders = normalizedSegments.joinToString(",") {
                    "?"
                }

                val values = ContentValues().apply {
                    put("token", normalizedToken)
                }

                val args = ArrayList<String>()
                args.add(normalizedSymbol)
                args.add(normalizedExchange)
                normalizedSegments.forEach { args.add(it) }

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
    }

    // ------------------------------------------------------------
    // DELETE
    // ------------------------------------------------------------

    fun deleteAll(): Int {
        return maintenanceGate.withOperation {
        return databaseHelper.writableDatabase.delete(
            SBDatabaseHelper.TABLE_INSTRUMENTS,
            null,
            null
        )
    
        }
    }

    // ------------------------------------------------------------
    // BUILT-IN MASTER SEEDING
    // ------------------------------------------------------------

    fun seedBuiltInMasters(): Int {
        return maintenanceGate.withOperation {
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

    // ------------------------------------------------------------
    // VALIDATION
    // ------------------------------------------------------------

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

    // ------------------------------------------------------------
    // CONTENT VALUES
    // ------------------------------------------------------------

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

    // ------------------------------------------------------------
    // CURSOR MAPPING
    // ------------------------------------------------------------

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

    // ------------------------------------------------------------
    // NORMALIZATION
    // ------------------------------------------------------------

    private fun normalize(value: String): String {
        return value.trim().uppercase(Locale.ROOT)
    }

    private fun normalizedSymbol(value: String): String {
        return normalize(value.replace(" ", ""))
    }

    private fun escapeLike(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    // ------------------------------------------------------------
    // PROJECTION
    // ------------------------------------------------------------

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

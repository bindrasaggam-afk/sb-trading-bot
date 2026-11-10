
package com.sb.backend.repositories

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.database.DatabaseMaintenanceGate
import com.sb.backend.models.SignalStatus
import com.sb.backend.models.SignalType
import com.sb.backend.models.TradingSignal
import com.sb.backend.models.TradingStyle
import java.util.Locale

class SignalRepository(
    private val databaseHelper: SBDatabaseHelper,
    private val maintenanceGate: DatabaseMaintenanceGate = DatabaseMaintenanceGate.shared
) {

    fun save(signal: TradingSignal): Long {
        return maintenanceGate.withOperation {
        validateSignal(signal)

        return databaseHelper.writableDatabase.insertWithOnConflict(
            SBDatabaseHelper.TABLE_SIGNALS,
            null,
            valuesOf(signal),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    
        }
    }

    fun saveAll(signals: List<TradingSignal>): Int {
        return maintenanceGate.withOperation {
        if (signals.isEmpty()) return 0

        // Validate the complete batch before opening the transaction.
        signals.forEach { validateSignal(it) }

        val db = databaseHelper.writableDatabase
        var saved = 0

        db.beginTransaction()
        try {
            signals.forEach { signal ->
                val id = db.insertWithOnConflict(
                    SBDatabaseHelper.TABLE_SIGNALS,
                    null,
                    valuesOf(signal),
                    SQLiteDatabase.CONFLICT_REPLACE
                )

                if (id != -1L) {
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

    fun getActive(limit: Int = 100): List<TradingSignal> {
        val now = System.currentTimeMillis()

        return query(
            "status IN (?, ?) AND (expires_at IS NULL OR expires_at > ?)",
            arrayOf(
                SignalStatus.ACTIVE.name,
                SignalStatus.CONFIRMED.name,
                now.toString()
            ),
            limit
        )
    }

    fun getAll(limit: Int = 200): List<TradingSignal> {
        return query(null, null, limit)
    }

    fun getByStatus(
        status: SignalStatus,
        limit: Int = 100
    ): List<TradingSignal> {
        return maintenanceGate.withOperation {
        return query(
            "status = ?",
            arrayOf(status.name),
            limit
        )
    
        }
    }

    fun getForSymbol(
        symbol: String,
        limit: Int = 20
    ): List<TradingSignal> {
        return maintenanceGate.withOperation {
        val normalized = normalizeRequired(symbol, "Symbol")

        return query(
            "UPPER(symbol) = ?",
            arrayOf(normalized),
            limit
        )
    
        }
    }

    fun latestForSymbol(symbol: String): TradingSignal? {
        return maintenanceGate.withOperation {
        val normalized = normalizeRequired(symbol, "Symbol")

        return query(
            "UPPER(symbol) = ?",
            arrayOf(normalized),
            1
        ).firstOrNull()
    
        }
    }

    fun updateStatus(
        id: Long,
        status: SignalStatus
    ): Boolean {
        return maintenanceGate.withOperation {
        require(id > 0L) {
            "Invalid signal ID."
        }

        val values = ContentValues().apply {
            put("status", status.name)
        }

        return databaseHelper.writableDatabase.update(
            SBDatabaseHelper.TABLE_SIGNALS,
            values,
            "id = ?",
            arrayOf(id.toString())
        ) == 1
    
        }
    }

    fun hasRecent(
        symbol: String,
        timeframe: String,
        signalType: SignalType,
        sinceTimestamp: Long
    ): Boolean {
        return maintenanceGate.withOperation {
        val normalizedSymbol =
            normalizeRequired(symbol, "Symbol")

        val normalizedTimeframe =
            normalizeRequired(timeframe, "Timeframe")

        require(sinceTimestamp > 0L) {
            "Invalid timestamp."
        }

        databaseHelper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_SIGNALS} " +
                "WHERE UPPER(symbol) = ? " +
                "AND UPPER(timeframe) = ? " +
                "AND signal_type = ? " +
                "AND created_at >= ?",
            arrayOf(
                normalizedSymbol,
                normalizedTimeframe,
                signalType.name,
                sinceTimestamp.toString()
            )
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) > 0
        }
    
        }
    }

    fun expireOld(
        nowTimestamp: Long = System.currentTimeMillis()
    ): Int {
        return maintenanceGate.withOperation {
        require(nowTimestamp > 0L) {
            "Invalid expiration timestamp."
        }

        val values = ContentValues().apply {
            put("status", SignalStatus.EXPIRED.name)
        }

        return databaseHelper.writableDatabase.update(
            SBDatabaseHelper.TABLE_SIGNALS,
            values,
            "status IN (?, ?) " +
                "AND expires_at IS NOT NULL " +
                "AND expires_at > 0 " +
                "AND expires_at <= ?",
            arrayOf(
                SignalStatus.ACTIVE.name,
                SignalStatus.CONFIRMED.name,
                nowTimestamp.toString()
            )
        )
    
        }
    }

    fun deleteOlderThan(timestamp: Long): Int {
        return maintenanceGate.withOperation {
        require(timestamp > 0L) {
            "Invalid deletion timestamp."
        }

        return databaseHelper.writableDatabase.delete(
            SBDatabaseHelper.TABLE_SIGNALS,
            "created_at < ?",
            arrayOf(timestamp.toString())
        )
    
        }
    }

    fun clear(): Int {
        return maintenanceGate.withOperation {
        return databaseHelper.writableDatabase.delete(
            SBDatabaseHelper.TABLE_SIGNALS,
            null,
            null
        )
    
        }
    }

    private fun query(
        selection: String?,
        args: Array<String>?,
        limit: Int
    ): List<TradingSignal> {
        if (limit <= 0) return emptyList()

        val result = ArrayList<TradingSignal>()

        databaseHelper.readableDatabase.query(
            SBDatabaseHelper.TABLE_SIGNALS,
            projection,
            selection,
            args,
            null,
            null,
            "created_at DESC",
            limit.coerceAtMost(1000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(readSignal(cursor))
            }
        }

        return result
    }

    private fun validateSignal(signal: TradingSignal) {
        normalizeRequired(signal.symbol, "Symbol")
        normalizeRequired(signal.exchange, "Exchange")
        normalizeRequired(signal.timeframe, "Timeframe")

        require(signal.id >= 0L) {
            "Signal ID cannot be negative."
        }

        require(signal.createdAt > 0L) {
            "Invalid signal creation timestamp."
        }

        require(
            signal.expiresAt == null || signal.expiresAt > 0L
        ) {
            "Invalid signal expiration timestamp."
        }

        require(signal.confidence.isFinite()) {
            "Signal confidence must be a finite number."
        }

        require(signal.confirmationCount >= 0) {
            "Confirmation count cannot be negative."
        }

        require(signal.confirmationRequired >= 0) {
            "Required confirmation count cannot be negative."
        }

        validatePrice(signal.entryPrice, "Entry price")
        validatePrice(signal.stopLoss, "Stop loss")
        validatePrice(signal.target1, "Target 1")
        validatePrice(signal.target2, "Target 2")
        validatePrice(signal.target3, "Target 3")
    }

    private fun validatePrice(
        value: Double?,
        fieldName: String
    ) {
        if (value == null) return

        require(value.isFinite() && value > 0.0) {
            "$fieldName must be a positive finite number."
        }
    }

    private fun normalizeRequired(
        value: String,
        fieldName: String
    ): String {
        val normalized = value.trim().toUpperCase(Locale.ROOT)

        require(normalized.isNotBlank()) {
            "$fieldName cannot be blank."
        }

        return normalized
    }

    private fun valuesOf(
        signal: TradingSignal
    ): ContentValues {
        return ContentValues().apply {
            if (signal.id > 0L) {
                put("id", signal.id)
            }

            put(
                "symbol",
                normalizeRequired(signal.symbol, "Symbol")
            )

            put(
                "exchange",
                normalizeRequired(signal.exchange, "Exchange")
            )

            put("trading_style", signal.tradingStyle.name)
            put("signal_type", signal.signal.name)

            putNullable("entry_price", signal.entryPrice)
            putNullable("stop_loss", signal.stopLoss)
            putNullable("target1", signal.target1)
            putNullable("target2", signal.target2)
            putNullable("target3", signal.target3)

            put(
                "confidence",
                signal.confidence.coerceIn(0.0, 100.0)
            )

            put(
                "timeframe",
                normalizeRequired(signal.timeframe, "Timeframe")
            )

            put("status", signal.status.name)
            put("reason", signal.reason)
            put("created_at", signal.createdAt)
            putNullable("expires_at", signal.expiresAt)

            put("confirmation_count", signal.confirmationCount)
            put("confirmation_required", signal.confirmationRequired)
            put("is_stable", if (signal.isStable) 1 else 0)
        }
    }

    private fun readSignal(
        cursor: Cursor
    ): TradingSignal {
        return TradingSignal(
            id = cursor.getLong(0),
            symbol = cursor.getString(1),
            exchange = cursor.getString(2),
            tradingStyle = parseTradingStyle(cursor.getString(3)),
            signal = parseSignalType(cursor.getString(4)),
            entryPrice = cursor.nullableDouble(5),
            stopLoss = cursor.nullableDouble(6),
            target1 = cursor.nullableDouble(7),
            target2 = cursor.nullableDouble(8),
            target3 = cursor.nullableDouble(9),
            confidence = cursor.getDouble(10),
            timeframe = cursor.getString(11),
            status = parseStatus(cursor.getString(12)),
            reason = cursor.getString(13),
            createdAt = cursor.getLong(14),
            expiresAt = cursor.nullableLong(15),
            confirmationCount = cursor.getInt(16),
            confirmationRequired = cursor.getInt(17),
            isStable = cursor.getInt(18) == 1
        )
    }

    private fun parseSignalType(value: String?): SignalType {
        return try {
            SignalType.valueOf(
                (value ?: "WAIT").trim().toUpperCase(Locale.ROOT)
            )
        } catch (e: IllegalArgumentException) {
            SignalType.WAIT
        }
    }

    private fun parseTradingStyle(value: String?): TradingStyle {
        return try {
            TradingStyle.valueOf(
                (value ?: "INTRADAY").trim().toUpperCase(Locale.ROOT)
            )
        } catch (e: IllegalArgumentException) {
            TradingStyle.INTRADAY
        }
    }

    private fun parseStatus(value: String?): SignalStatus {
        return try {
            SignalStatus.valueOf(
                (value ?: "ACTIVE").trim().toUpperCase(Locale.ROOT)
            )
        } catch (e: IllegalArgumentException) {
            SignalStatus.ACTIVE
        }
    }

    private fun ContentValues.putNullable(
        key: String,
        value: Double?
    ) {
        if (value == null) {
            putNull(key)
        } else {
            put(key, value)
        }
    }

    private fun ContentValues.putNullable(
        key: String,
        value: Long?
    ) {
        if (value == null) {
            putNull(key)
        } else {
            put(key, value)
        }
    }

    private fun Cursor.nullableDouble(index: Int): Double? {
        return if (isNull(index)) null else getDouble(index)
    }

    private fun Cursor.nullableLong(index: Int): Long? {
        return if (isNull(index)) null else getLong(index)
    }

    companion object {
        private val projection = arrayOf(
            "id",
            "symbol",
            "exchange",
            "trading_style",
            "signal_type",
            "entry_price",
            "stop_loss",
            "target1",
            "target2",
            "target3",
            "confidence",
            "timeframe",
            "status",
            "reason",
            "created_at",
            "expires_at",
            "confirmation_count",
            "confirmation_required",
            "is_stable"
        )
    }
}

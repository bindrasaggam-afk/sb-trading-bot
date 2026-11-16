package com.sb.backend.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.sb.backend.core.AppConfig
import java.io.File

/**
 * Removes pre-automation paper records that have no genuine signal provenance.
 *
 * Cleanup is all-or-nothing: a backup is taken first, then all paper ledger rows
 * are removed and the account is reset in one transaction. If any signal-linked
 * data exists, cleanup refuses to touch the ledger.
 */
object PaperLegacyCleanup {

    const val MARKER_KEY = "paper_legacy_cleanup_v1"

    private const val SUPPRESSED_MIGRATION_VALUE = "suppressed_by_cleanup_v1"
    private const val EPSILON = 0.000001

    data class Result(
        val applied: Boolean,
        val skipped: Boolean,
        val backupFile: File?,
        val deletedOrders: Int,
        val deletedPositions: Int,
        val deletedExecutions: Int,
        val deletedEvents: Int,
        val message: String
    )

    fun cleanup(
        context: Context,
        databaseHelper: SBDatabaseHelper,
        now: Long = System.currentTimeMillis()
    ): Result {
        val db = databaseHelper.writableDatabase
        if (readMarker(db) != null) {
            return Result(
                applied = false,
                skipped = true,
                backupFile = null,
                deletedOrders = 0,
                deletedPositions = 0,
                deletedExecutions = 0,
                deletedEvents = 0,
                message = "Paper legacy cleanup already completed."
            )
        }

        val provenance = readProvenance(db)
        if (provenance.signalLinkedRows > 0) {
            return Result(
                applied = false,
                skipped = true,
                backupFile = null,
                deletedOrders = 0,
                deletedPositions = 0,
                deletedExecutions = 0,
                deletedEvents = 0,
                message = "Cleanup skipped: signal-linked paper data exists."
            )
        }

        val existingRows = provenance.totalRows
        val backupFile = if (existingRows > 0) {
            DatabaseBackup(context, databaseHelper)
                .backup("sb_paper_legacy_cleanup")
                ?: return Result(
                    applied = false,
                    skipped = true,
                    backupFile = null,
                    deletedOrders = 0,
                    deletedPositions = 0,
                    deletedExecutions = 0,
                    deletedEvents = 0,
                    message = "Cleanup skipped: database backup failed."
                )
        } else {
            null
        }

        db.beginTransaction()
        try {
            val recheck = readProvenance(db)
            check(recheck.signalLinkedRows == 0) {
                "Signal-linked paper data appeared during cleanup."
            }

            val deletedExecutions = db.delete(
                SBDatabaseHelper.TABLE_PAPER_EXECUTIONS,
                null,
                null
            )
            val deletedEvents = db.delete(
                SBDatabaseHelper.TABLE_PAPER_EVENTS,
                null,
                null
            )
            val deletedPositions = db.delete(
                SBDatabaseHelper.TABLE_POSITIONS,
                null,
                null
            )
            val deletedOrders = db.delete(
                SBDatabaseHelper.TABLE_PAPER_ORDERS,
                null,
                null
            )

            val startingBalance = readStartingBalance(db)
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: AppConfig.get().paperStartingBalance
            val accountValues = ContentValues().apply {
                put("id", 1)
                put("starting_balance", startingBalance)
                put("balance", startingBalance)
                put("reserved_margin", 0.0)
                put("realized_pnl", 0.0)
                put("updated_at", now)
            }
            db.insertWithOnConflict(
                SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
                null,
                accountValues,
                SQLiteDatabase.CONFLICT_REPLACE
            )

            verifyCleanup(db, startingBalance)
            writeMarker(
                db = db,
                value = "v1|backup=${backupFile?.name.orEmpty()}" +
                    "|orders=$deletedOrders" +
                    "|positions=$deletedPositions" +
                    "|executions=$deletedExecutions" +
                    "|events=$deletedEvents" +
                    "|starting=$startingBalance",
                now = now
            )
            suppressLegacyMigration(db, now)

            db.setTransactionSuccessful()
            return Result(
                applied = true,
                skipped = false,
                backupFile = backupFile,
                deletedOrders = deletedOrders,
                deletedPositions = deletedPositions,
                deletedExecutions = deletedExecutions,
                deletedEvents = deletedEvents,
                message = "Paper legacy cleanup completed."
            )
        } finally {
            db.endTransaction()
        }
    }

    fun readMarker(db: SQLiteDatabase): String? {
        if (!tableExists(db, SBDatabaseHelper.TABLE_APP_STATE)) return null
        db.query(
            SBDatabaseHelper.TABLE_APP_STATE,
            arrayOf("value"),
            "key = ?",
            arrayOf(MARKER_KEY),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun readProvenance(db: SQLiteDatabase): Provenance {
        val signalLinked = count(
            db,
            "SELECT " +
                "(SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_PAPER_ORDERS} " +
                "WHERE signal_id IS NOT NULL) + " +
                "(SELECT COUNT(*) FROM " +
                "${SBDatabaseHelper.TABLE_PAPER_EXECUTIONS} " +
                "WHERE signal_id IS NOT NULL) + " +
                "(SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_PAPER_EVENTS} " +
                "WHERE signal_id IS NOT NULL)"
        )
        val total = count(
            db,
            "SELECT (SELECT COUNT(*) FROM " +
                "${SBDatabaseHelper.TABLE_PAPER_ORDERS}) + " +
                "(SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_POSITIONS}) + " +
                "(SELECT COUNT(*) FROM " +
                "${SBDatabaseHelper.TABLE_PAPER_EXECUTIONS}) + " +
                "(SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_PAPER_EVENTS})"
        ) + if (readStartingBalance(db) != null) 1 else 0
        return Provenance(
            signalLinkedRows = signalLinked,
            totalRows = total
        )
    }

    private fun verifyCleanup(db: SQLiteDatabase, startingBalance: Double) {
        val remainingData = count(
            db,
            "SELECT (SELECT COUNT(*) FROM " +
                "${SBDatabaseHelper.TABLE_PAPER_ORDERS}) + " +
                "(SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_POSITIONS}) + " +
                "(SELECT COUNT(*) FROM " +
                "${SBDatabaseHelper.TABLE_PAPER_EXECUTIONS}) + " +
                "(SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_PAPER_EVENTS})"
        )
        check(remainingData == 0) {
            "Paper cleanup verification failed: ledger rows remain."
        }

        db.query(
            SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
            arrayOf(
                "starting_balance",
                "balance",
                "reserved_margin",
                "realized_pnl"
            ),
            "id = ?",
            arrayOf("1"),
            null,
            null,
            null
        ).use { cursor ->
            check(cursor.moveToFirst()) {
                "Paper cleanup verification failed: account is missing."
            }
            check(kotlin.math.abs(cursor.getDouble(0) - startingBalance) <= EPSILON) {
                "Paper cleanup verification failed: starting balance mismatch."
            }
            check(kotlin.math.abs(cursor.getDouble(1) - startingBalance) <= EPSILON) {
                "Paper cleanup verification failed: balance mismatch."
            }
            check(kotlin.math.abs(cursor.getDouble(2)) <= EPSILON) {
                "Paper cleanup verification failed: reserved margin remains."
            }
            check(kotlin.math.abs(cursor.getDouble(3)) <= EPSILON) {
                "Paper cleanup verification failed: realized P&L remains."
            }
        }
    }

    private fun readStartingBalance(db: SQLiteDatabase): Double? {
        if (!tableExists(db, SBDatabaseHelper.TABLE_PAPER_ACCOUNT)) return null
        db.query(
            SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
            arrayOf("starting_balance"),
            "id = ?",
            arrayOf("1"),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getDouble(0) else null
        }
    }

    private fun suppressLegacyMigration(
        db: SQLiteDatabase,
        now: Long
    ) {
        if (LegacyPaperMigration.readMarker(db) != null) return
        val values = ContentValues().apply {
            put("key", LegacyPaperMigration.MARKER_KEY)
            put("value", SUPPRESSED_MIGRATION_VALUE)
            put("updated_at", now)
        }
        db.insertWithOnConflict(
            SBDatabaseHelper.TABLE_APP_STATE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun writeMarker(
        db: SQLiteDatabase,
        value: String,
        now: Long
    ) {
        val values = ContentValues().apply {
            put("key", MARKER_KEY)
            put("value", value)
            put("updated_at", now)
        }
        db.insertWithOnConflict(
            SBDatabaseHelper.TABLE_APP_STATE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun count(db: SQLiteDatabase, sql: String): Int {
        db.rawQuery(sql, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery(
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private data class Provenance(
        val signalLinkedRows: Int,
        val totalRows: Int
    )
}

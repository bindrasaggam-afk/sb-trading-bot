package com.sb.backend.services

import android.content.ContentValues
import android.content.Context
import com.sb.backend.database.DatabaseMaintenanceGate
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.repositories.InstrumentRepository
import java.security.MessageDigest

data class InstrumentImportResult(
    val importedNow: Boolean,
    val rowCount: Int,
    val savedCount: Int,
    val skippedCount: Int,
    val hash: String,
    val categoryCounts: Map<String, Int>,
    val errors: List<String>
)

class InstrumentMasterImporter(
    context: Context,
    private val databaseHelper: SBDatabaseHelper,
    private val instrumentRepository: InstrumentRepository
) {
    private val appContext = context.applicationContext

    fun importIfNeeded(): InstrumentImportResult {
        val hash = try {
            sha256(ASSET_NAME)
        } catch (error: Exception) {
            return failed("Unable to hash $ASSET_NAME: ${error.message}")
        }

        val previousHash = readState(KEY_SHA256)
        if (previousHash == hash) {
            return InstrumentImportResult(
                importedNow = false,
                rowCount = readState(KEY_ROW_COUNT)?.toIntOrNull() ?: 0,
                savedCount = 0,
                skippedCount = 0,
                hash = hash,
                categoryCounts = emptyCategoryCounts(),
                errors = emptyList()
            )
        }

        val parsed = try {
            appContext.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                InstrumentCsvParser.parse(reader)
            }
        } catch (error: Exception) {
            return failed(
                "Unable to read $ASSET_NAME: ${error.message}",
                hash = hash
            )
        }

        if (parsed.rows.isEmpty()) {
            return failed(
                "No usable rows in $ASSET_NAME",
                hash = hash,
                errors = parsed.errors
            )
        }

        val classified = InstrumentClassifier.classify(parsed.rows)
        if (classified.instruments.isEmpty()) {
            return failed(
                "No instruments classified from $ASSET_NAME",
                hash = hash,
                errors = parsed.errors + classified.errors
            )
        }

        var saved = 0
        try {
            classified.instruments.chunked(IMPORT_BATCH_SIZE).forEach { batch ->
                saved += instrumentRepository.upsertMasterBatch(batch)
            }
        } catch (error: Exception) {
            return failed(
                "Instrument import failed after $saved rows: ${error.message}",
                hash = hash,
                errors = parsed.errors + classified.errors
            )
        }

        val importedAt = System.currentTimeMillis()
        writeState(KEY_SHA256, hash, importedAt)
        writeState(KEY_IMPORTED_AT, importedAt.toString(), importedAt)
        writeState(KEY_ROW_COUNT, parsed.rows.size.toString(), importedAt)
        writeState(KEY_SAVED_COUNT, saved.toString(), importedAt)

        return InstrumentImportResult(
            importedNow = true,
            rowCount = parsed.rows.size,
            savedCount = saved,
            skippedCount = parsed.rows.size - classified.instruments.size,
            hash = hash,
            categoryCounts = classified.categoryCounts,
            errors = parsed.errors + classified.errors
        )
    }

    private fun sha256(assetName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        appContext.assets.open(assetName).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    private fun readState(key: String): String? {
        return DatabaseMaintenanceGate.shared.withOperation {
            databaseHelper.readableDatabase.query(
                SBDatabaseHelper.TABLE_APP_STATE,
                arrayOf("value"),
                "key = ?",
                arrayOf(key),
                null,
                null,
                null,
                "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
    }

    private fun writeState(key: String, value: String, updatedAt: Long) {
        DatabaseMaintenanceGate.shared.withOperation {
            val values = ContentValues().apply {
                put("key", key)
                put("value", value)
                put("updated_at", updatedAt)
            }
            databaseHelper.writableDatabase.insertWithOnConflict(
                SBDatabaseHelper.TABLE_APP_STATE,
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    private fun failed(
        message: String,
        hash: String = "",
        errors: List<String> = emptyList()
    ): InstrumentImportResult {
        return InstrumentImportResult(
            importedNow = false,
            rowCount = 0,
            savedCount = 0,
            skippedCount = 0,
            hash = hash,
            categoryCounts = emptyCategoryCounts(),
            errors = listOf(message) + errors
        )
    }

    private fun emptyCategoryCounts(): Map<String, Int> = mapOf(
        "EQUITY" to 0,
        "ETF" to 0,
        "INDEX" to 0,
        "BOND" to 0,
        "MUTUAL_FUND" to 0,
        "OTHER" to 0,
        "COMMODITY" to 0
    )

    private companion object {
        const val ASSET_NAME = "sb.csv"
        const val IMPORT_BATCH_SIZE = 500
        const val KEY_SHA256 = "kotak_sb_csv_sha256"
        const val KEY_IMPORTED_AT = "kotak_sb_csv_imported_at"
        const val KEY_ROW_COUNT = "kotak_sb_csv_row_count"
        const val KEY_SAVED_COUNT = "kotak_sb_csv_saved_count"
    }
}

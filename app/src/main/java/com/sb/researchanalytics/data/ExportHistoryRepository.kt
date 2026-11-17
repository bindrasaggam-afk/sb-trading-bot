package com.sb.researchanalytics.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.sb.researchanalytics.domain.ExportHistoryEntry
import com.sb.signals.data.SignalMigrations
import com.sb.signals.services.SignalsPlatform

class ExportHistoryRepository(
    private val context: Context
) {

    fun record(entry: ExportHistoryEntry): Long {
        val values = ContentValues().apply {
            put("export_type", entry.exportType)
            put("format", entry.format)
            put("file_name", entry.fileName)
            put("filters_json", entry.filtersJson)
            put("row_count", entry.rowCount)
            put("status", entry.status)
            put("message", entry.message)
            put("created_at", entry.createdAt)
        }
        return SignalsPlatform.database(context)
            .writableDatabase
            .insert(
                SignalMigrations.TABLE_EXPORT_HISTORY,
                null,
                values
            )
    }

    fun recent(limit: Int = 20): List<ExportHistoryEntry> {
        val result = ArrayList<ExportHistoryEntry>()
        SignalsPlatform.database(context)
            .readableDatabase
            .query(
                SignalMigrations.TABLE_EXPORT_HISTORY,
                null,
                null,
                null,
                null,
                null,
                "created_at DESC, id DESC",
                limit.coerceIn(1, 200).toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(read(cursor))
                }
            }
        return result
    }

    private fun read(cursor: Cursor): ExportHistoryEntry =
        ExportHistoryEntry(
            id = cursor.getLong(
                cursor.getColumnIndexOrThrow("id")
            ),
            exportType = cursor.getString(
                cursor.getColumnIndexOrThrow("export_type")
            ),
            format = cursor.getString(
                cursor.getColumnIndexOrThrow("format")
            ),
            fileName = cursor.getString(
                cursor.getColumnIndexOrThrow("file_name")
            ),
            filtersJson = cursor.getString(
                cursor.getColumnIndexOrThrow("filters_json")
            ).orEmpty(),
            rowCount = cursor.getInt(
                cursor.getColumnIndexOrThrow("row_count")
            ),
            status = cursor.getString(
                cursor.getColumnIndexOrThrow("status")
            ),
            message = cursor.getString(
                cursor.getColumnIndexOrThrow("message")
            ).orEmpty(),
            createdAt = cursor.getLong(
                cursor.getColumnIndexOrThrow("created_at")
            )
        )
}

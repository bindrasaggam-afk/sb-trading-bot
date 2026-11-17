package com.sb.researchanalytics.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.sb.researchanalytics.data.ExportHistoryRepository
import com.sb.researchanalytics.domain.ExportHistoryEntry
import com.sb.researchanalytics.domain.ResearchAnalyticsReport
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(
    val extension: String,
    val mimeType: String
) {
    XLSX(
        "xlsx",
        "application/vnd.openxmlformats-officedocument." +
            "spreadsheetml.sheet"
    ),
    CSV("csv", "text/csv")
}

data class ExportResult(
    val fileName: String,
    val uri: Uri,
    val rowCount: Int,
    val format: ExportFormat,
    val message: String
)

class ResearchExportManager(
    private val context: Context
) {

    fun export(
        report: ResearchAnalyticsReport,
        format: ExportFormat = ExportFormat.XLSX,
        exportType: String = "MANUAL"
    ): ExportResult {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())
        val baseName = "SB_RESEARCH_ANALYTICS_$timestamp"
        val fileName = "$baseName.${format.extension}"
        val tempFile = File(
            context.cacheDir,
            "research_export_$timestamp.${format.extension}"
        )
        val rowCount = writeToFile(report, format, tempFile)
        val result = try {
            publish(tempFile, fileName, format, rowCount)
        } finally {
            tempFile.delete()
        }
        try {
            ExportHistoryRepository(context).record(
                ExportHistoryEntry(
                    exportType = exportType,
                    format = format.name,
                    fileName = result.fileName,
                    filtersJson = ResearchExportMetadata.filterJson(
                        report
                    ),
                    rowCount = result.rowCount,
                    status = "COMPLETED",
                    message = result.message,
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {
        }
        return result
    }

    private fun writeToFile(
        report: ResearchAnalyticsReport,
        format: ExportFormat,
        file: File
    ): Int {
        return when (format) {
            ExportFormat.XLSX -> {
                FileOutputStream(file).use { output ->
                    XlsxWriter.write(report.sheets, output)
                }
                report.sheets.sumOf { it.rowCount }
            }
            ExportFormat.CSV -> {
                val sheet = report.sheets.firstOrNull {
                    it.name == "Signal Outcomes"
                } ?: report.sheets.first()
                FileOutputStream(file).use { output ->
                    CsvWriter.write(sheet, output)
                }
                sheet.rowCount
            }
        }
    }

    private fun publish(
        file: File,
        fileName: String,
        format: ExportFormat,
        rowCount: Int
    ): ExportResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/SB BOT"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException(
                "Could not create export file"
            )
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(file).use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException(
                    "Could not open export destination"
                )
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
            return ExportResult(
                fileName = fileName,
                uri = uri,
                rowCount = rowCount,
                format = format,
                message = "Saved to Downloads/SB BOT"
            )
        }

        val directory = File(context.cacheDir, "exports").apply {
            mkdirs()
        }
        val destination = File(directory, fileName)
        FileInputStream(file).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destination
        )
        return ExportResult(
            fileName = fileName,
            uri = uri,
            rowCount = rowCount,
            format = format,
            message = "Saved to app exports; use Share to export"
        )
    }

    fun recentExports(limit: Int = 20): List<ExportHistoryEntry> =
        ExportHistoryRepository(context).recent(limit)
}

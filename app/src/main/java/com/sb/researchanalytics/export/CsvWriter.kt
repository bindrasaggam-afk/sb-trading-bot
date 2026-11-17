package com.sb.researchanalytics.export

import com.sb.researchanalytics.domain.ExportSheet
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CsvWriter {

    fun write(
        sheet: ExportSheet,
        output: OutputStream
    ) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        writer.write("\uFEFF")
        writer.write(line(sheet.headers))
        sheet.rows.forEach { row ->
            writer.write(line(row))
        }
        writer.flush()
    }

    fun writeBundle(
        sheets: List<ExportSheet>,
        output: OutputStream
    ) {
        ZipOutputStream(output).use { zip ->
            sheets.forEach { sheet ->
                zip.putNextEntry(
                    ZipEntry("${safeName(sheet.name)}.csv")
                )
                val writer = OutputStreamWriter(zip, Charsets.UTF_8)
                writer.write("\uFEFF")
                writer.write(line(sheet.headers))
                sheet.rows.forEach { row ->
                    writer.write(line(row))
                }
                writer.flush()
                zip.closeEntry()
            }
        }
    }

    private fun line(values: List<Any?>): String =
        values.joinToString(",") { escape(it) } + "\r\n"

    private fun escape(value: Any?): String {
        if (value == null) return ""
        val text = when (value) {
            is Number -> {
                val numeric = value.toDouble()
                if (numeric.isFinite()) value.toString()
                else "NOT_AVAILABLE"
            }
            else -> value.toString()
        }
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${text.replace("\"", "\"\"")}\""
        } else {
            text
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "_")
}

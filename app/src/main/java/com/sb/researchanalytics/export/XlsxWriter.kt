package com.sb.researchanalytics.export

import com.sb.researchanalytics.domain.ExportSheet
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free OOXML workbook writer. It creates real .xlsx
 * archives with inline strings, numeric cells, a header style and frozen
 * header rows.
 */
object XlsxWriter {

    fun write(
        sheets: List<ExportSheet>,
        output: OutputStream
    ) {
        require(sheets.isNotEmpty()) { "Workbook requires at least one sheet" }
        val safeSheets = sanitizeSheets(sheets)
        ZipOutputStream(output).use { zip ->
            writeEntry(zip, "[Content_Types].xml", contentTypes(safeSheets))
            writeEntry(zip, "_rels/.rels", rootRels())
            writeEntry(zip, "xl/workbook.xml", workbook(safeSheets))
            writeEntry(
                zip,
                "xl/_rels/workbook.xml.rels",
                workbookRels(safeSheets)
            )
            writeEntry(zip, "xl/styles.xml", styles())
            safeSheets.forEachIndexed { index, sheet ->
                writeEntry(
                    zip,
                    "xl/worksheets/sheet${index + 1}.xml",
                    worksheet(sheet)
                )
            }
        }
    }

    private fun writeEntry(
        zip: ZipOutputStream,
        name: String,
        content: String
    ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypes(sheets: List<ExportSheet>): String {
        val overrides = sheets.indices.joinToString("") { index ->
            "<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" " +
                "ContentType=\"application/vnd.openxmlformats-" +
                "officedocument.spreadsheetml.worksheet+xml\"/>"
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
$overrides
</Types>"""
    }

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbook(sheets: List<ExportSheet>): String {
        val entries = sheets.mapIndexed { index, sheet ->
            "<sheet name=\"${xml(sheet.name)}\" sheetId=\"${index + 1}\" " +
                "r:id=\"rId${index + 1}\"/>"
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>$entries</sheets>
</workbook>"""
    }

    private fun workbookRels(sheets: List<ExportSheet>): String {
        val entries = sheets.indices.joinToString("") { index ->
            "<Relationship Id=\"rId${index + 1}\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/" +
                "2006/relationships/worksheet\" " +
                "Target=\"worksheets/sheet${index + 1}.xml\"/>"
        }
        val styleId = sheets.size + 1
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$entries
<Relationship Id="rId$styleId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
    }

    private fun styles(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="10"/><name val="Calibri"/></font><font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1F4E79"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>
</styleSheet>"""

    private fun worksheet(sheet: ExportSheet): String {
        val maxColumns = maxOf(
            sheet.headers.size,
            sheet.rows.maxOfOrNull { it.size } ?: 0
        ).coerceAtLeast(1)
        val widths = (0 until maxColumns).joinToString("") { index ->
            "<col min=\"${index + 1}\" max=\"${index + 1}\" " +
                "width=\"${columnWidth(index)}\" customWidth=\"1\"/>"
        }
        val data = StringBuilder()
        data.append(rowXml(1, sheet.headers, header = true))
        sheet.rows.forEachIndexed { index, row ->
            data.append(rowXml(index + 2, row, header = false))
        }
        val lastColumn = columnName(maxColumns)
        val lastRow = sheet.rows.size + 1
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
<cols>$widths</cols>
<sheetData>$data</sheetData>
<autoFilter ref="A1:$lastColumn$lastRow"/>
</worksheet>"""
    }

    private fun rowXml(
        rowNumber: Int,
        values: List<Any?>,
        header: Boolean
    ): String {
        val cells = values.mapIndexed { index, value ->
            val reference = "${columnName(index + 1)}$rowNumber"
            val style = if (header) " s=\"1\"" else ""
            when (value) {
                null -> "<c r=\"$reference\"$style/>"
                is Number -> {
                    val numeric = value.toDouble()
                    if (!numeric.isFinite()) {
                        inlineCell(reference, style, "NOT_AVAILABLE")
                    } else {
                        "<c r=\"$reference\"$style><v>$numeric</v></c>"
                    }
                }
                is Boolean ->
                    "<c r=\"$reference\"$style t=\"b\"><v>${
                        if (value) 1 else 0
                    }</v></c>"
                else -> inlineCell(reference, style, value.toString())
            }
        }.joinToString("")
        return "<row r=\"$rowNumber\">$cells</row>"
    }

    private fun inlineCell(
        reference: String,
        style: String,
        value: String
    ): String =
        "<c r=\"$reference\"$style t=\"inlineStr\"><is><t " +
            "xml:space=\"preserve\">${xml(value)}</t></is></c>"

    private fun columnName(index: Int): String {
        var value = index
        val result = StringBuilder()
        while (value > 0) {
            value--
            result.insert(0, ('A'.code + value % 26).toChar())
            value /= 26
        }
        return result.toString()
    }

    private fun columnWidth(index: Int): Int = when (index) {
        0, 1 -> 18
        2, 3, 4, 5 -> 14
        else -> 16
    }

    private fun sanitizeSheets(
        sheets: List<ExportSheet>
    ): List<ExportSheet> {
        val used = HashSet<String>()
        return sheets.map { sheet ->
            val base = sheet.name
                .replace(Regex("[\\\\/?*\\[\\]:]"), "_")
                .take(31)
                .ifBlank { "Sheet" }
            var name = base
            var suffix = 2
            while (!used.add(name)) {
                val tail = "_$suffix"
                name = base.take(31 - tail.length) + tail
                suffix++
            }
            sheet.copy(name = name)
        }
    }

    private fun xml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}

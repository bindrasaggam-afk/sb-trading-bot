package com.sb.researchanalytics.export

import com.sb.researchanalytics.domain.ExportSheet
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class XlsxWriterTest {

    @Test
    fun writesReadableOoxmlArchiveWithEscapedCells() {
        val sheets = listOf(
            ExportSheet(
                name = "Summary / Overview",
                headers = listOf("Metric", "Value"),
                rows = listOf(
                    listOf("Net P&L", 123.5),
                    listOf("Note", "a, \"quoted\" <value>"),
                    listOf("Missing", null)
                )
            ),
            ExportSheet(
                name = "Signal Outcomes",
                headers = listOf("ID"),
                rows = listOf(listOf(1))
            )
        )
        val output = ByteArrayOutputStream()

        XlsxWriter.write(sheets, output)

        val entries = readZip(output.toByteArray())
        assertTrue("[Content_Types].xml" in entries)
        assertTrue("xl/workbook.xml" in entries)
        assertTrue("xl/worksheets/sheet1.xml" in entries)
        assertTrue("xl/worksheets/sheet2.xml" in entries)
        assertTrue(
            entries.getValue("xl/workbook.xml")
                .contains("Summary _ Overview")
        )
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<v>123.5</v>"))
        assertTrue(
            sheet.contains("a, &quot;quoted&quot; &lt;value&gt;")
        )
        assertTrue(sheet.contains("autoFilter"))
        assertTrue(sheet.contains("state=\"frozen\""))
    }

    private fun readZip(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes()
                    .toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }
}

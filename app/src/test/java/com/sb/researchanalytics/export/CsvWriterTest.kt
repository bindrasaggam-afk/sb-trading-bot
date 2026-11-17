package com.sb.researchanalytics.export

import com.sb.researchanalytics.domain.ExportSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class CsvWriterTest {

    @Test
    fun writesActualValuesAndEscapesCommasAndQuotes() {
        val sheet = ExportSheet(
            name = "Signal Outcomes",
            headers = listOf("Signal", "Return"),
            rows = listOf(
                listOf("A, B", 1.25),
                listOf("C \"quoted\"", null)
            )
        )
        val output = ByteArrayOutputStream()

        CsvWriter.write(sheet, output)

        val text = output.toString("UTF-8")
        assertTrue(text.startsWith("\uFEFFSignal,Return\r\n"))
        assertTrue(text.contains("\"A, B\",1.25\r\n"))
        assertTrue(text.contains("\"C \"\"quoted\"\"\",\r\n"))
        assertEquals(2, text.trim().lines().size - 1)
    }
}

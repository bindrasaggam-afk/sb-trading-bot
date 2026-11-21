package com.sb.ui.chart.persistence

import com.sb.ui.chart.ChartTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChartDrawingCodecTest {

    @Test
    fun roundTripsEscapedTextAndAnchors() {
        val document = ChartDrawingDocument(
            symbol = "NIFTY 50",
            timeframe = "5m",
            exchange = "NSE",
            updatedAt = 42L,
            drawings = listOf(
                ChartDrawingRecord(
                    id = "d1",
                    tool = ChartTool.TEXT,
                    anchors = listOf(ChartAnchor(1_000L, 101.125)),
                    text = "quote \" slash \\ नमस्ते"
                )
            )
        )

        val decoded = ChartDrawingCodec.decode(ChartDrawingCodec.encode(document))

        assertNotNull(decoded)
        assertEquals("quote \" slash \\ नमस्ते", decoded!!.drawings.single().text)
        assertEquals(101.125, decoded.drawings.single().anchors.single().price, 0.0)
    }

    @Test
    fun encodingIsStableAfterNormalization() {
        val anchorA = ChartAnchor(1_000L, 100.0)
        val anchorB = ChartAnchor(2_000L, 110.0)
        val first = ChartDrawingDocument(
            symbol = " NIFTY 50 ",
            timeframe = " 1m ",
            updatedAt = 7L,
            drawings = listOf(
                ChartDrawingRecord("d2", ChartTool.HORIZONTAL, listOf(anchorB)),
                ChartDrawingRecord("d1", ChartTool.TRENDLINE, listOf(anchorB, anchorA))
            )
        )
        val second = first.copy(
            symbol = "NIFTY 50",
            timeframe = "1m",
            drawings = first.drawings.reversed()
        )

        assertEquals(ChartDrawingCodec.encode(first), ChartDrawingCodec.encode(second))
    }

    @Test
    fun rejectsMalformedAndInvalidDocuments() {
        assertNull(ChartDrawingCodec.decode("{"))
        assertNull(ChartDrawingCodec.decode("""{"version":99}"""))
        assertNull(
            ChartDrawingCodec.decode(
                """{"version":1,"symbol":"X","timeframe":"1m","updatedAt":1,"drawings":[{"id":"bad","tool":"BOGUS","anchors":[],"text":""}]}"""
            )
        )
    }
}

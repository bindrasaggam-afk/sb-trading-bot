package com.sb.ui.chart.persistence

import com.sb.ui.chart.ChartCandle
import com.sb.ui.chart.ChartDrawing
import com.sb.ui.chart.ChartPoint
import com.sb.ui.chart.ChartTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartDrawingBridgeTest {

    @Test
    fun exportMapsIndicesToTimestampsAndDropsUnmappableDrawings() {
        val source = candles()
        val drawings = listOf(
            ChartDrawing(
                ChartTool.TRENDLINE,
                mutableListOf(ChartPoint(1, 111.0), ChartPoint(3, 113.0))
            ),
            ChartDrawing(
                ChartTool.HORIZONTAL,
                mutableListOf(ChartPoint(99, 100.0))
            )
        )

        val document = ChartDrawingBridge.export(
            source = source,
            drawings = drawings,
            symbol = "NIFTY 50",
            timeframe = "1m",
            exchange = "NSE",
            updatedAt = 42L
        )

        assertTrue(ChartDrawingPolicy.validate(document).ok)
        assertEquals(1, document.drawings.size)
        assertEquals(source[1].timestamp, document.drawings.single().anchors[0].timestamp)
        assertEquals(source[3].timestamp, document.drawings.single().anchors[1].timestamp)
    }

    @Test
    fun importRestoresIndexBasedDrawings() {
        val source = candles()
        val expected = ChartDrawing(
            ChartTool.TEXT,
            mutableListOf(ChartPoint(2, 112.0)),
            "Hello"
        )
        val document = ChartDrawingBridge.export(
            source = source,
            drawings = listOf(expected),
            symbol = "NIFTY 50",
            timeframe = "1m",
            exchange = "NSE",
            updatedAt = 42L
        )

        val restored = ChartDrawingBridge.import(document, source)

        assertEquals(1, restored.size)
        assertEquals(ChartTool.TEXT, restored.single().tool)
        assertEquals("Hello", restored.single().text)
        assertEquals(2, restored.single().points.single().index)
        assertEquals(112.0, restored.single().points.single().price, 0.0)
    }

    @Test
    fun importIgnoresUnknownTimestampsAndInvalidDocuments() {
        val source = candles()
        val document = ChartDrawingDocument(
            symbol = "NIFTY 50",
            timeframe = "1m",
            exchange = "NSE",
            updatedAt = 42L,
            drawings = listOf(
                ChartDrawingRecord(
                    "d1",
                    ChartTool.HORIZONTAL,
                    listOf(ChartAnchor(999_999L, 100.0))
                )
            )
        )

        assertTrue(ChartDrawingBridge.import(document, source).isEmpty())
        assertTrue(ChartDrawingBridge.import(document.copy(version = 99), source).isEmpty())
    }

    private fun candles(): List<ChartCandle> = (1..5).map { index ->
        ChartCandle(
            timestamp = index * 60_000L,
            open = 100.0 + index,
            high = 110.0 + index,
            low = 95.0 + index,
            close = 105.0 + index,
            volume = 100L
        )
    }
}

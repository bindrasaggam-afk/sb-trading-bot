package com.sb.ui.chart.persistence

import com.sb.ui.chart.ChartTool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartDrawingPolicyTest {

    @Test
    fun validatesPointCountPerTool() {
        val anchors = listOf(
            ChartAnchor(1_000L, 100.0),
            ChartAnchor(2_000L, 110.0)
        )

        assertTrue(document(ChartTool.TRENDLINE, anchors).valid())
        assertFalse(document(ChartTool.TRENDLINE, anchors.take(1)).valid())
        assertFalse(document(ChartTool.HORIZONTAL, anchors).valid())

        assertTrue(document(ChartTool.HORIZONTAL, anchors.take(1)).valid())
        assertTrue(document(ChartTool.TEXT, anchors.take(1), text = "Note").valid())
        assertFalse(document(ChartTool.TEXT, anchors.take(1), text = " ").valid())
    }

    @Test
    fun rejectsInvalidAnchorsAndDuplicateIds() {
        val good = record("d1", ChartTool.TRENDLINE)

        assertFalse(
            document(good.copy(anchors = listOf(ChartAnchor(0L, 100.0), ChartAnchor(2_000L, 110.0)))).valid()
        )
        assertFalse(
            document(good.copy(anchors = listOf(ChartAnchor(1_000L, -1.0), ChartAnchor(2_000L, 110.0)))).valid()
        )
        assertFalse(
            document(good.copy(anchors = listOf(ChartAnchor(1_000L, 100.0), ChartAnchor(1_000L, 110.0)))).valid()
        )
        assertFalse(documents(good, good).valid())
    }

    @Test
    fun normalizeSortsAnchorsAndDrawings() {
        val later = record("d2", ChartTool.HORIZONTAL, listOf(ChartAnchor(2_000L, 110.0)))
        val earlier = record("d1", ChartTool.TRENDLINE).copy(
            anchors = listOf(ChartAnchor(2_000L, 110.0), ChartAnchor(1_000L, 100.0))
        )

        val normalized = ChartDrawingPolicy.normalize(documents(later, earlier))

        assertTrue(normalized.valid())
        assertTrue(normalized.drawings.map { it.id } == listOf("d1", "d2"))
        assertTrue(normalized.drawings.first().anchors.map { it.timestamp } == listOf(1_000L, 2_000L))
    }

    private fun document(record: ChartDrawingRecord): ChartDrawingDocument =
        documents(record)

    private fun document(
        tool: ChartTool,
        anchors: List<ChartAnchor>,
        text: String = ""
    ): ChartDrawingDocument = documents(record("d1", tool, anchors, text))

    private fun documents(vararg records: ChartDrawingRecord): ChartDrawingDocument =
        ChartDrawingDocument(
            symbol = "NIFTY 50",
            timeframe = "1m",
            exchange = "NSE",
            updatedAt = 1L,
            drawings = records.toList()
        )

    private fun record(
        id: String,
        tool: ChartTool,
        anchors: List<ChartAnchor> = listOf(
            ChartAnchor(1_000L, 100.0),
            ChartAnchor(2_000L, 110.0)
        ),
        text: String = ""
    ): ChartDrawingRecord = ChartDrawingRecord(id, tool, anchors, text)

    private fun ChartDrawingDocument.valid(): Boolean = ChartDrawingPolicy.validate(this).ok
}

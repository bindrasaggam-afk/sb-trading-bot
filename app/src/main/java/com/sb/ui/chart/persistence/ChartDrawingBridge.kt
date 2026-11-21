package com.sb.ui.chart.persistence

import com.sb.ui.chart.ChartCandle
import com.sb.ui.chart.ChartDrawing
import com.sb.ui.chart.ChartPoint
import com.sb.ui.chart.ChartTool

object ChartDrawingBridge {

    fun export(
        source: List<ChartCandle>,
        drawings: List<ChartDrawing>,
        symbol: String,
        timeframe: String,
        exchange: String = "",
        updatedAt: Long
    ): ChartDrawingDocument {
        val records = ArrayList<ChartDrawingRecord>()
        drawings.forEach { drawing ->
            val anchors = ArrayList<ChartAnchor>(drawing.points.size)
            var mappable = true
            drawing.points.forEach { point ->
                val candle = source.getOrNull(point.index)
                if (candle == null) {
                    mappable = false
                } else {
                    anchors.add(ChartAnchor(candle.timestamp, point.price))
                }
            }
            if (!mappable || anchors.distinctBy { it.timestamp }.size != anchors.size) {
                return@forEach
            }

            val text = if (drawing.tool == ChartTool.TEXT) {
                drawing.text.ifBlank { "Note" }
            } else {
                drawing.text
            }
            val candidate = ChartDrawingRecord(
                id = ChartDrawingPolicy.drawingId(records.size),
                tool = drawing.tool,
                anchors = anchors,
                text = text
            )
            val probe = ChartDrawingDocument(
                symbol = symbol,
                timeframe = timeframe,
                exchange = exchange,
                updatedAt = updatedAt,
                drawings = listOf(candidate)
            )
            if (ChartDrawingPolicy.validate(ChartDrawingPolicy.normalize(probe)).ok) {
                records.add(candidate)
            }
        }

        return ChartDrawingPolicy.normalize(
            ChartDrawingDocument(
                symbol = symbol,
                timeframe = timeframe,
                exchange = exchange,
                updatedAt = updatedAt,
                drawings = records
            )
        )
    }

    fun import(
        document: ChartDrawingDocument,
        source: List<ChartCandle>
    ): List<ChartDrawing> {
        if (!ChartDrawingPolicy.validate(ChartDrawingPolicy.normalize(document)).ok) {
            return emptyList()
        }
        val indexByTimestamp = HashMap<Long, Int>(source.size)
        source.forEachIndexed { index, candle ->
            indexByTimestamp[candle.timestamp] = index
        }

        return ChartDrawingPolicy.normalize(document).drawings.mapNotNull { record ->
            val points = ArrayList<ChartPoint>(record.anchors.size)
            var mappable = true
            record.anchors.forEach { anchor ->
                val index = indexByTimestamp[anchor.timestamp]
                if (index == null) {
                    mappable = false
                } else {
                    points.add(ChartPoint(index, anchor.price))
                }
            }
            if (mappable) {
                ChartDrawing(record.tool, points, record.text)
            } else {
                null
            }
        }
    }
}

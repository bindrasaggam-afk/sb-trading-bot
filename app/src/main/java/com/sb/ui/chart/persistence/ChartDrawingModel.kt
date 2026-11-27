package com.sb.ui.chart.persistence

import com.sb.ui.chart.ChartTool
import java.util.Locale

data class ChartAnchor(
    val timestamp: Long,
    val price: Double
)

data class ChartDrawingRecord(
    val id: String,
    val tool: ChartTool,
    val anchors: List<ChartAnchor>,
    val text: String = ""
)

data class ChartDrawingDocument(
    val version: Int = ChartDrawingPolicy.FORMAT_VERSION,
    val symbol: String,
    val timeframe: String,
    val exchange: String = "",
    val updatedAt: Long,
    val drawings: List<ChartDrawingRecord> = emptyList()
)

data class ChartDrawingValidation(
    val ok: Boolean,
    val reason: String = ""
)

object ChartDrawingPolicy {

    const val FORMAT_VERSION = 1
    const val MAX_DRAWINGS = 200
    const val MAX_TEXT_LENGTH = 120
    const val MAX_ID_LENGTH = 64
    const val MAX_ABS_PRICE: Double = 1e12

    private val ID_REGEX = Regex("[A-Za-z0-9_.-]{1,$MAX_ID_LENGTH}")

    fun expectedPointCount(tool: ChartTool): Int = when (tool) {
        ChartTool.HORIZONTAL, ChartTool.TEXT -> 1
        ChartTool.TRENDLINE, ChartTool.FIBONACCI, ChartTool.RULER -> 2
        ChartTool.CROSSHAIR -> 0
    }

    fun normalize(document: ChartDrawingDocument): ChartDrawingDocument =
        document.copy(
            symbol = document.symbol.trim(),
            timeframe = document.timeframe.trim(),
            exchange = document.exchange.trim(),
            drawings = document.drawings
                .map { record ->
                    record.copy(
                        id = record.id.trim(),
                        anchors = record.anchors.sortedBy { it.timestamp },
                        text = record.text.trim()
                    )
                }
                .sortedBy { it.id }
        )

    fun validate(document: ChartDrawingDocument): ChartDrawingValidation {
        if (document.version != FORMAT_VERSION) {
            return invalid("unsupported-version")
        }
        if (document.symbol.isBlank()) return invalid("missing-symbol")
        if (document.timeframe.isBlank()) return invalid("missing-timeframe")
        if (document.updatedAt <= 0L) return invalid("invalid-updated-at")
        if (document.drawings.size > MAX_DRAWINGS) return invalid("too-many-drawings")

        val ids = HashSet<String>()
        document.drawings.forEach { record ->
            if (!ID_REGEX.matches(record.id)) return invalid("invalid-drawing-id")
            if (!ids.add(record.id)) return invalid("duplicate-drawing-id")

            val expected = expectedPointCount(record.tool)
            if (expected == 0 || record.anchors.size != expected) {
                return invalid("invalid-point-count")
            }
            if (record.text.length > MAX_TEXT_LENGTH) return invalid("text-too-long")
            if (record.tool == ChartTool.TEXT && record.text.isBlank()) {
                return invalid("missing-text")
            }

            val timestamps = HashSet<Long>()
            record.anchors.forEach { anchor ->
                if (anchor.timestamp <= 0L) return invalid("invalid-timestamp")
                if (!timestamps.add(anchor.timestamp)) return invalid("duplicate-timestamp")
                if (!anchor.price.isFinite() ||
                    anchor.price <= 0.0 ||
                    anchor.price > MAX_ABS_PRICE
                ) {
                    return invalid("invalid-price")
                }
            }
        }
        return ChartDrawingValidation(true)
    }

    fun drawingId(index: Int): String =
        String.format(Locale.ROOT, "d%04d", index + 1)

    private fun invalid(reason: String) = ChartDrawingValidation(false, reason)
}

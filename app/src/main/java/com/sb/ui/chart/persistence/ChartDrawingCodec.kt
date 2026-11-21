package com.sb.ui.chart.persistence

import com.sb.ui.chart.ChartTool
import org.json.JSONObject

object ChartDrawingCodec {

    fun encode(document: ChartDrawingDocument): String {
        val normalized = ChartDrawingPolicy.normalize(document)
        val out = StringBuilder("{")
        appendField(out, "version", ChartDrawingPolicy.FORMAT_VERSION.toString(), true)
        appendField(out, "symbol", JSONObject.quote(normalized.symbol))
        appendField(out, "timeframe", JSONObject.quote(normalized.timeframe))
        appendField(out, "exchange", JSONObject.quote(normalized.exchange))
        appendField(out, "updatedAt", normalized.updatedAt.toString())

        val drawings = StringBuilder("[")
        normalized.drawings.forEachIndexed { index, record ->
            if (index > 0) drawings.append(',')
            drawings.append('{')
            appendField(drawings, "id", JSONObject.quote(record.id), true)
            appendField(drawings, "tool", JSONObject.quote(record.tool.name))
            appendField(drawings, "text", JSONObject.quote(record.text))

            val anchors = StringBuilder("[")
            record.anchors.forEachIndexed { anchorIndex, anchor ->
                if (anchorIndex > 0) anchors.append(',')
                anchors.append("{\"timestamp\":")
                    .append(anchor.timestamp)
                    .append(",\"price\":")
                    .append(anchor.price.toString())
                    .append('}')
            }
            anchors.append(']')
            appendField(drawings, "anchors", anchors.toString())
            drawings.append('}')
        }
        drawings.append(']')
        appendField(out, "drawings", drawings.toString())
        out.append('}')
        return out.toString()
    }

    fun decode(raw: String): ChartDrawingDocument? {
        return try {
            val root = JSONObject(raw)
            if (root.optInt("version", -1) != ChartDrawingPolicy.FORMAT_VERSION) {
                null
            } else {
                val drawingsArray = root.optJSONArray("drawings") ?: return null
                val drawings = ArrayList<ChartDrawingRecord>(drawingsArray.length())
                for (index in 0 until drawingsArray.length()) {
                    val item = drawingsArray.getJSONObject(index)
                    val anchorsArray = item.optJSONArray("anchors") ?: return null
                    val anchors = ArrayList<ChartAnchor>(anchorsArray.length())
                    for (anchorIndex in 0 until anchorsArray.length()) {
                        val anchorItem = anchorsArray.getJSONObject(anchorIndex)
                        anchors.add(
                            ChartAnchor(
                                timestamp = anchorItem.getLong("timestamp"),
                                price = anchorItem.getDouble("price")
                            )
                        )
                    }
                    drawings.add(
                        ChartDrawingRecord(
                            id = item.getString("id"),
                            tool = ChartTool.valueOf(item.getString("tool")),
                            anchors = anchors,
                            text = item.optString("text", "")
                        )
                    )
                }
                val decoded = ChartDrawingDocument(
                    version = ChartDrawingPolicy.FORMAT_VERSION,
                    symbol = root.optString("symbol", ""),
                    timeframe = root.optString("timeframe", ""),
                    exchange = root.optString("exchange", ""),
                    updatedAt = root.optLong("updatedAt", 0L),
                    drawings = drawings
                )
                val normalized = ChartDrawingPolicy.normalize(decoded)
                if (ChartDrawingPolicy.validate(normalized).ok) normalized else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun appendField(
        out: StringBuilder,
        name: String,
        value: String,
        first: Boolean = false
    ) {
        if (!first) out.append(',')
        out.append(JSONObject.quote(name)).append(':').append(value)
    }
}

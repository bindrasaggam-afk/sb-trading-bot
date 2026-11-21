package com.sb.ui.chart.config

import com.sb.ui.chart.ChartIndicator
import org.json.JSONObject

object IndicatorConfigCodec {

    fun encode(config: IndicatorConfig): String {
        val normalized = IndicatorConfigPolicy.normalize(config)
        val out = StringBuilder("{")
        appendField(out, "version", IndicatorConfigPolicy.FORMAT_VERSION.toString(), true)
        appendField(out, "timeframe", JSONObject.quote(normalized.timeframeKey))
        appendField(out, "source", JSONObject.quote(normalized.source.name))

        val enabled = StringBuilder("[")
        normalized.enabled.forEachIndexed { index, indicator ->
            if (index > 0) enabled.append(',')
            enabled.append(JSONObject.quote(indicator.name))
        }
        enabled.append(']')
        appendField(out, "enabled", enabled.toString())

        appendField(out, "emaPeriod", normalized.emaPeriod.toString())
        appendField(out, "smaPeriod", normalized.smaPeriod.toString())
        appendField(out, "rsiPeriod", normalized.rsiPeriod.toString())
        appendField(out, "macdFast", normalized.macdFast.toString())
        appendField(out, "macdSlow", normalized.macdSlow.toString())
        appendField(out, "macdSignal", normalized.macdSignal.toString())
        appendField(out, "bollingerPeriod", normalized.bollingerPeriod.toString())
        appendField(out, "bollingerMultiplier", normalized.bollingerMultiplier.toString())
        appendField(out, "supertrendPeriod", normalized.supertrendPeriod.toString())
        appendField(out, "supertrendMultiplier", normalized.supertrendMultiplier.toString())
        out.append('}')
        return out.toString()
    }

    fun decode(raw: String): IndicatorConfig? {
        return try {
            val root = JSONObject(raw)
            if (root.optInt("version", -1) != IndicatorConfigPolicy.FORMAT_VERSION) {
                null
            } else {
                val enabledArray = root.optJSONArray("enabled") ?: return null
                val enabled = LinkedHashSet<ChartIndicator>()
                for (index in 0 until enabledArray.length()) {
                    enabled.add(ChartIndicator.valueOf(enabledArray.getString(index)))
                }
                val decoded = IndicatorConfig(
                    enabled = enabled,
                    timeframeKey = root.optString("timeframe", ""),
                    source = IndicatorSource.valueOf(root.optString("source", "CLOSE")),
                    emaPeriod = root.optInt("emaPeriod", IndicatorConfigPolicy.DEFAULT.emaPeriod),
                    smaPeriod = root.optInt("smaPeriod", IndicatorConfigPolicy.DEFAULT.smaPeriod),
                    rsiPeriod = root.optInt("rsiPeriod", IndicatorConfigPolicy.DEFAULT.rsiPeriod),
                    macdFast = root.optInt("macdFast", IndicatorConfigPolicy.DEFAULT.macdFast),
                    macdSlow = root.optInt("macdSlow", IndicatorConfigPolicy.DEFAULT.macdSlow),
                    macdSignal = root.optInt("macdSignal", IndicatorConfigPolicy.DEFAULT.macdSignal),
                    bollingerPeriod = root.optInt(
                        "bollingerPeriod",
                        IndicatorConfigPolicy.DEFAULT.bollingerPeriod
                    ),
                    bollingerMultiplier = root.optDouble(
                        "bollingerMultiplier",
                        IndicatorConfigPolicy.DEFAULT.bollingerMultiplier
                    ),
                    supertrendPeriod = root.optInt(
                        "supertrendPeriod",
                        IndicatorConfigPolicy.DEFAULT.supertrendPeriod
                    ),
                    supertrendMultiplier = root.optDouble(
                        "supertrendMultiplier",
                        IndicatorConfigPolicy.DEFAULT.supertrendMultiplier
                    )
                )
                val normalized = IndicatorConfigPolicy.normalize(decoded)
                if (IndicatorConfigPolicy.validate(normalized).ok) normalized else null
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

package com.sb.signals.data

import com.sb.signals.domain.DataQuality
import com.sb.signals.domain.SignalCategory
import com.sb.signals.domain.StrategyConfig
import org.json.JSONArray
import org.json.JSONObject

object SignalJson {

    fun encodeStringList(values: List<String>): String {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array.toString()
    }

    fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeBooleanMap(values: Map<String, Boolean>): String {
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    fun decodeBooleanMap(raw: String?): Map<String, Boolean> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = linkedMapOf<String, Boolean>()
            json.keys().forEach { key ->
                result[key] = json.optBoolean(key, false)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun encodeDoubleMap(values: Map<String, Double?>): String {
        val json = JSONObject()
        values.forEach { (key, value) ->
            if (value == null || !value.isFinite()) {
                json.put(key, JSONObject.NULL)
            } else {
                json.put(key, value)
            }
        }
        return json.toString()
    }

    fun decodeDoubleMap(raw: String?): Map<String, Double?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = linkedMapOf<String, Double?>()
            json.keys().forEach { key ->
                result[key] = if (json.isNull(key)) {
                    null
                } else {
                    val value = json.optDouble(key, Double.NaN)
                    if (value.isFinite()) value else null
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun encodeQuality(quality: DataQuality): String {
        return JSONObject().apply {
            put("score", quality.score)
            put("candleCount", quality.candleCount)
            put("firstTimestamp", quality.firstTimestamp ?: JSONObject.NULL)
            put("lastTimestamp", quality.lastTimestamp ?: JSONObject.NULL)
            put("notes", JSONArray(quality.notes))
        }.toString()
    }

    fun decodeQuality(raw: String?): DataQuality {
        if (raw.isNullOrBlank()) {
            return DataQuality(0.0, 0, null, null, emptyList())
        }
        return try {
            val json = JSONObject(raw)
            val notesArray = json.optJSONArray("notes")
            val notes = ArrayList<String>()
            if (notesArray != null) {
                for (index in 0 until notesArray.length()) {
                    notes.add(notesArray.optString(index))
                }
            }
            DataQuality(
                score = json.optDouble("score", 0.0),
                candleCount = json.optInt("candleCount", 0),
                firstTimestamp = if (json.isNull("firstTimestamp")) {
                    null
                } else {
                    json.optLong("firstTimestamp")
                },
                lastTimestamp = if (json.isNull("lastTimestamp")) {
                    null
                } else {
                    json.optLong("lastTimestamp")
                },
                notes = notes
            )
        } catch (_: Exception) {
            DataQuality(0.0, 0, null, null, emptyList())
        }
    }

    fun encodeConfig(config: StrategyConfig): String {
        return JSONObject().apply {
            put("strategyId", config.strategyId)
            put("version", config.version)
            put("category", config.category.name)
            put("name", config.name)
            put("minScore", config.minScore)
            put("minScoreLead", config.minScoreLead)
            put("minPrimaryCandles", config.minPrimaryCandles)
            put("minConfirmationCandles", config.minConfirmationCandles)
            put("minTrendCandles", config.minTrendCandles)
            put("emaFast", config.emaFast)
            put("emaSlow", config.emaSlow)
            put("emaTrend", config.emaTrend)
            put("rsiPeriod", config.rsiPeriod)
            put("atrPeriod", config.atrPeriod)
            put("adxPeriod", config.adxPeriod)
            put("volumePeriod", config.volumePeriod)
            put("volumeMultiplier", config.volumeMultiplier)
            put("minAdx", config.minAdx)
            put("stopAtrMultiplier", config.stopAtrMultiplier)
            put("entryAtrBuffer", config.entryAtrBuffer)
            put("targetR1", config.targetR1)
            put("targetR2", config.targetR2)
            put("targetR3", config.targetR3)
            put("costBpsPerSide", config.costBpsPerSide)
            put("slippageBpsPerSide", config.slippageBpsPerSide)
            put("maxSignalAgeBars", config.maxSignalAgeBars)
            put("allowShorts", config.allowShorts)
            put("deliveryHoldingPeriod", config.deliveryHoldingPeriod.name)
            put("categoryLabel", config.category.label)
        }.toString()
    }

    fun categoryName(raw: String?): SignalCategory? =
        raw?.let { SignalCategory.fromName(it) }
}

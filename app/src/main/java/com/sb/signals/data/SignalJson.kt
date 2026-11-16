package com.sb.signals.data

import com.sb.signals.domain.DataQuality
import com.sb.signals.domain.HoldingPeriod
import com.sb.signals.domain.SignalCategory
import com.sb.signals.domain.StrategyConfig
import com.sb.signals.domain.StrategyDefaults
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
            put("primaryTimeframe", config.primaryTimeframe)
            put("confirmationTimeframe", config.confirmationTimeframe)
            put("trendTimeframe", config.trendTimeframe)
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
            put("smaPeriod", config.smaPeriod)
            put("supertrendPeriod", config.supertrendPeriod)
            put("supertrendMultiplier", config.supertrendMultiplier)
            put("bollingerPeriod", config.bollingerPeriod)
            put("bollingerMultiplier", config.bollingerMultiplier)
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

    fun decodeConfig(raw: String?): StrategyConfig? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            val category = SignalCategory.fromName(
                json.optString("category")
            ) ?: return null
            val strategyId = json.optString("strategyId").trim()
            val version = json.optString("version").trim()
            if (strategyId.isBlank() || version.isBlank()) return null
            val defaults = StrategyDefaults.forCategory(category)
            StrategyConfig(
                strategyId = strategyId,
                version = version,
                category = category,
                name = json.optString("name").ifBlank { defaults.name },
                primaryTimeframe = json.optString("primaryTimeframe")
                    .ifBlank { defaults.primaryTimeframe },
                confirmationTimeframe = json.optString(
                    "confirmationTimeframe"
                ).ifBlank { defaults.confirmationTimeframe },
                trendTimeframe = json.optString("trendTimeframe")
                    .ifBlank { defaults.trendTimeframe },
                minScore = json.optDouble("minScore", defaults.minScore),
                minScoreLead = json.optDouble(
                    "minScoreLead",
                    defaults.minScoreLead
                ),
                minPrimaryCandles = json.optInt(
                    "minPrimaryCandles",
                    defaults.minPrimaryCandles
                ),
                minConfirmationCandles = json.optInt(
                    "minConfirmationCandles",
                    defaults.minConfirmationCandles
                ),
                minTrendCandles = json.optInt(
                    "minTrendCandles",
                    defaults.minTrendCandles
                ),
                emaFast = json.optInt("emaFast", defaults.emaFast),
                emaSlow = json.optInt("emaSlow", defaults.emaSlow),
                emaTrend = json.optInt("emaTrend", defaults.emaTrend),
                rsiPeriod = json.optInt(
                    "rsiPeriod",
                    defaults.rsiPeriod
                ),
                atrPeriod = json.optInt(
                    "atrPeriod",
                    defaults.atrPeriod
                ),
                adxPeriod = json.optInt(
                    "adxPeriod",
                    defaults.adxPeriod
                ),
                smaPeriod = json.optInt(
                    "smaPeriod",
                    defaults.smaPeriod
                ),
                supertrendPeriod = json.optInt(
                    "supertrendPeriod",
                    defaults.supertrendPeriod
                ),
                supertrendMultiplier = json.optDouble(
                    "supertrendMultiplier",
                    defaults.supertrendMultiplier
                ),
                bollingerPeriod = json.optInt(
                    "bollingerPeriod",
                    defaults.bollingerPeriod
                ),
                bollingerMultiplier = json.optDouble(
                    "bollingerMultiplier",
                    defaults.bollingerMultiplier
                ),
                volumePeriod = json.optInt(
                    "volumePeriod",
                    defaults.volumePeriod
                ),
                volumeMultiplier = json.optDouble(
                    "volumeMultiplier",
                    defaults.volumeMultiplier
                ),
                minAdx = json.optDouble("minAdx", defaults.minAdx),
                stopAtrMultiplier = json.optDouble(
                    "stopAtrMultiplier",
                    defaults.stopAtrMultiplier
                ),
                entryAtrBuffer = json.optDouble(
                    "entryAtrBuffer",
                    defaults.entryAtrBuffer
                ),
                targetR1 = json.optDouble(
                    "targetR1",
                    defaults.targetR1
                ),
                targetR2 = json.optDouble(
                    "targetR2",
                    defaults.targetR2
                ),
                targetR3 = json.optDouble(
                    "targetR3",
                    defaults.targetR3
                ),
                costBpsPerSide = json.optDouble(
                    "costBpsPerSide",
                    defaults.costBpsPerSide
                ),
                slippageBpsPerSide = json.optDouble(
                    "slippageBpsPerSide",
                    defaults.slippageBpsPerSide
                ),
                maxSignalAgeBars = json.optInt(
                    "maxSignalAgeBars",
                    defaults.maxSignalAgeBars
                ),
                allowShorts = json.optBoolean(
                    "allowShorts",
                    defaults.allowShorts
                ),
                deliveryHoldingPeriod = HoldingPeriod.fromName(
                    json.optString("deliveryHoldingPeriod")
                ) ?: defaults.deliveryHoldingPeriod
            )
        } catch (_: Exception) {
            null
        }
    }

    fun categoryName(raw: String?): SignalCategory? =
        raw?.let { SignalCategory.fromName(it) }
}

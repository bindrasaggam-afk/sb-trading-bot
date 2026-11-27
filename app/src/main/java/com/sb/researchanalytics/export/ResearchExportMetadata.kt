package com.sb.researchanalytics.export

import com.sb.researchanalytics.domain.ResearchAnalyticsReport
import org.json.JSONArray
import org.json.JSONObject

object ResearchExportMetadata {

    fun filterJson(report: ResearchAnalyticsReport): String {
        val filter = report.dataset.filter
        return JSONObject().apply {
            put("fromMillis", filter.fromMillis ?: JSONObject.NULL)
            put("toMillis", filter.toMillis ?: JSONObject.NULL)
            put(
                "categories",
                JSONArray(filter.categories.map { it.name })
            )
            put(
                "directions",
                JSONArray(filter.directions.map { it.name })
            )
            put("strategies", JSONArray(filter.strategies.toList()))
            put("symbols", JSONArray(filter.symbols.toList()))
            put(
                "timeframes",
                JSONArray(filter.timeframes.toList())
            )
            put(
                "statuses",
                JSONArray(filter.statuses.map { it.name })
            )
            put(
                "minConfidence",
                filter.minConfidence ?: JSONObject.NULL
            )
            put("paperFilter", filter.paperFilter)
            put("dataThrough", report.dataset.dataThrough)
            put("generatedAt", report.dataset.generatedAt)
        }.toString()
    }
}

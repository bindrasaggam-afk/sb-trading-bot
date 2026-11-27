package com.sb.ui.chart.persistence

import android.content.Context

class SharedPreferencesChartDrawingStorage(
    context: Context,
    scopeKey: String
) : ChartDrawingStorage {

    private val preferences = context.applicationContext.getSharedPreferences(
        "sb_chart_drawings",
        Context.MODE_PRIVATE
    )
    private val key = "document_$scopeKey"

    override fun load(): String? = preferences.getString(key, null)

    override fun save(raw: String) {
        preferences.edit().putString(key, raw).apply()
    }

    override fun clear() {
        preferences.edit().remove(key).apply()
    }
}

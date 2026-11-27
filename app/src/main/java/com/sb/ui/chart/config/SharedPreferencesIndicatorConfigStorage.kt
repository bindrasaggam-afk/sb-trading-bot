package com.sb.ui.chart.config

import android.content.Context

class SharedPreferencesIndicatorConfigStorage(
    context: Context,
    scopeKey: String
) : IndicatorConfigStorage {

    private val preferences = context.applicationContext.getSharedPreferences(
        "sb_chart_indicator_configs",
        Context.MODE_PRIVATE
    )
    private val key = "config_$scopeKey"

    override fun load(): String? = preferences.getString(key, null)

    override fun save(raw: String) {
        preferences.edit().putString(key, raw).apply()
    }

    override fun clear() {
        preferences.edit().remove(key).apply()
    }
}

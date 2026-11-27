package com.sb.ui.chart.config

interface IndicatorConfigStorage {
    fun load(): String?
    fun save(raw: String)
    fun clear()
}

class IndicatorConfigRepository(
    private val storage: IndicatorConfigStorage
) {

    fun load(): IndicatorConfig? =
        storage.load()?.let { IndicatorConfigCodec.decode(it) }

    fun save(config: IndicatorConfig): Boolean {
        val normalized = IndicatorConfigPolicy.normalize(config)
        if (!IndicatorConfigPolicy.validate(normalized).ok) return false
        storage.save(IndicatorConfigCodec.encode(normalized))
        return true
    }

    fun clear() {
        storage.clear()
    }
}

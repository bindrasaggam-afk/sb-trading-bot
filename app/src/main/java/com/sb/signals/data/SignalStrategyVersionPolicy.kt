package com.sb.signals.data

import com.sb.signals.domain.StrategyConfig

object SignalStrategyVersionPolicy {

    fun canSave(
        existingConfigJson: String?,
        incoming: StrategyConfig
    ): Boolean {
        if (existingConfigJson.isNullOrBlank()) return true
        val existing = SignalJson.decodeConfig(existingConfigJson)
            ?: return false
        return existing == incoming
    }
}

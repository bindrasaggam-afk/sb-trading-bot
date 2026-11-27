package com.sb.signals.data

import com.sb.signals.domain.StrategyDefaults
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalStrategyVersionPolicyTest {

    private val config = StrategyDefaults.intraday("5m")

    @Test
    fun identicalConfigCanBeReused() {
        assertTrue(
            SignalStrategyVersionPolicy.canSave(
                SignalJson.encodeConfig(config),
                config
            )
        )
    }

    @Test
    fun changedConfigUnderSameVersionIsRejected() {
        assertFalse(
            SignalStrategyVersionPolicy.canSave(
                SignalJson.encodeConfig(config),
                config.copy(minScore = config.minScore + 1.0)
            )
        )
    }

    @Test
    fun unreadableExistingConfigIsRejected() {
        assertFalse(
            SignalStrategyVersionPolicy.canSave("{not-json", config)
        )
    }
}

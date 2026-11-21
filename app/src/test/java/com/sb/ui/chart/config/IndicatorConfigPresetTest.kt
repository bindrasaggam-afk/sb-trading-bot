package com.sb.ui.chart.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorConfigPresetTest {

    @Test
    fun allPresetsAreValidAndDistinct() {
        val presets = IndicatorConfigPresets.all()

        assertEquals(4, presets.size)
        assertEquals(
            presets.size,
            presets.map { it.first }.toSet().size
        )
        presets.forEach { (_, config) ->
            assertTrue(IndicatorConfigPolicy.validate(config).ok)
        }
        assertEquals(
            presets.size,
            presets.map { it.second.copy(enabled = emptySet(), timeframeKey = "") }
                .toSet()
                .size
        )
    }
}

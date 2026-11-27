package com.sb.ui.chart.config

import com.sb.ui.chart.ChartIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorConfigRepositoryTest {

    @Test
    fun saveLoadAndClear() {
        val storage = MemoryConfigStorage()
        val repository = IndicatorConfigRepository(storage)
        val config = IndicatorConfig(
            enabled = setOf(ChartIndicator.EMA_20),
            timeframeKey = "5m",
            emaPeriod = 9
        )

        assertFalse(repository.save(config.copy(emaPeriod = 1)))
        assertNull(storage.load())
        assertTrue(repository.save(config))

        val loaded = repository.load()
        assertNotNull(loaded)
        assertEquals(9, loaded!!.emaPeriod)
        assertEquals("5m", loaded.timeframeKey)

        repository.clear()
        assertNull(repository.load())
    }

    private class MemoryConfigStorage : IndicatorConfigStorage {
        private var raw: String? = null

        override fun load(): String? = raw

        override fun save(raw: String) {
            this.raw = raw
        }

        override fun clear() {
            raw = null
        }
    }
}

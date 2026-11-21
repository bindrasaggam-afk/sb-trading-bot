package com.sb.ui.chart.persistence

import com.sb.ui.chart.ChartTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartDrawingRepositoryTest {

    @Test
    fun saveLoadAndClear() {
        val storage = MemoryDrawingStorage()
        val repository = ChartDrawingRepository(storage, clock = { 99L })
        val document = ChartDrawingDocument(
            symbol = "NIFTY 50",
            timeframe = "1m",
            exchange = "NSE",
            updatedAt = 0L,
            drawings = listOf(
                ChartDrawingRecord(
                    "d1",
                    ChartTool.HORIZONTAL,
                    listOf(ChartAnchor(1_000L, 100.0))
                )
            )
        )

        assertFalse(storage.load() != null)
        assertFalse(repository.save(document.copy(symbol = "")))
        assertNull(storage.load())
        assertTrue(repository.save(document))

        val loaded = repository.load()
        assertNotNull(loaded)
        assertEquals(99L, loaded!!.updatedAt)
        assertEquals("NIFTY 50", loaded.symbol)
        assertEquals("d1", loaded.drawings.single().id)

        repository.clear()
        assertNull(repository.load())
    }

    @Test
    fun updateReplacesDocumentAndScopeKeyIsStable() {
        val storage = MemoryDrawingStorage()
        val repository = ChartDrawingRepository(storage, clock = { 123L })

        assertTrue(
            repository.update(
                symbol = "NIFTY 50",
                timeframe = "1m",
                exchange = "NSE",
                drawings = emptyList()
            )
        )
        assertTrue(
            repository.update(
                symbol = "NIFTY 50",
                timeframe = "1m",
                exchange = "NSE",
                drawings = listOf(
                    ChartDrawingRecord(
                        "d1",
                        ChartTool.HORIZONTAL,
                        listOf(ChartAnchor(1_000L, 100.0))
                    )
                )
            )
        )

        assertEquals(1, repository.load()!!.drawings.size)
        assertEquals(
            "NSE|NIFTY 50|1m",
            ChartStateScope.key("NIFTY 50", "1m", "NSE")
        )
    }

    private class MemoryDrawingStorage : ChartDrawingStorage {
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

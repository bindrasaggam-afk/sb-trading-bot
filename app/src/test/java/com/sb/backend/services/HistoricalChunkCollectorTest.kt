package com.sb.backend.services

import com.sb.backend.models.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalChunkCollectorTest {

    @Test
    fun acceptsCandlesInNewerChunksAndEmptyOldestChunk() {
        val collector = HistoricalChunkCollector()
        val newer = candle(300L)
        val older = candle(200L)

        collector.addChunk(listOf(newer))
        collector.addChunk(listOf(older))
        collector.addChunk(emptyList())

        assertEquals(listOf(newer, older), collector.candles())
    }

    @Test
    fun rejectsOlderCandlesAfterAnEmptyNewerChunk() {
        val collector = HistoricalChunkCollector()
        val newest = candle(300L)

        collector.addChunk(listOf(newest))
        collector.addChunk(emptyList())

        try {
            collector.addChunk(listOf(candle(100L)))
            throw AssertionError("Expected historical data gap to be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Historical data gap"))
        }

        assertEquals(listOf(newest), collector.candles())
    }

    @Test
    fun acceptsMultipleEmptyChunksAtOldestEdge() {
        val collector = HistoricalChunkCollector()
        val newest = candle(300L)

        collector.addChunk(listOf(newest))
        collector.addChunk(emptyList())
        collector.addChunk(emptyList())

        assertEquals(listOf(newest), collector.candles())
    }

    private fun candle(timestamp: Long) = Candle(
        symbol = "TEST",
        timeframe = "1D",
        timestamp = timestamp,
        open = 10.0,
        high = 12.0,
        low = 9.0,
        close = 11.0,
        volume = 100L
    )
}

package com.sb.backend.services

import com.sb.backend.models.Candle

/**
 * Collects newest-to-oldest historical chunks without accepting a hole
 * followed by older data. Empty chunks are valid only at the oldest edge.
 */
internal class HistoricalChunkCollector {
    private val collected = ArrayList<Candle>()
    private var emptyChunkSeen = false

    fun addChunk(chunkCandles: List<Candle>) {
        if (chunkCandles.isEmpty()) {
            emptyChunkSeen = true
            return
        }

        check(!emptyChunkSeen) {
            "Historical data gap: candles returned in an older chunk after an empty newer chunk"
        }
        collected.addAll(chunkCandles)
    }

    fun candles(): List<Candle> = collected.toList()
}

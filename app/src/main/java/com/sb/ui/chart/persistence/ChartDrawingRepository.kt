package com.sb.ui.chart.persistence

interface ChartDrawingStorage {
    fun load(): String?
    fun save(raw: String)
    fun clear()
}

class ChartDrawingRepository(
    private val storage: ChartDrawingStorage,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    fun load(): ChartDrawingDocument? =
        storage.load()?.let { ChartDrawingCodec.decode(it) }

    fun save(document: ChartDrawingDocument): Boolean {
        val withTime = if (document.updatedAt > 0L) {
            document
        } else {
            document.copy(updatedAt = clock())
        }
        val normalized = ChartDrawingPolicy.normalize(withTime)
        if (!ChartDrawingPolicy.validate(normalized).ok) return false
        storage.save(ChartDrawingCodec.encode(normalized))
        return true
    }

    fun update(
        symbol: String,
        timeframe: String,
        exchange: String,
        drawings: List<ChartDrawingRecord>
    ): Boolean = save(
        ChartDrawingDocument(
            symbol = symbol,
            timeframe = timeframe,
            exchange = exchange,
            updatedAt = clock(),
            drawings = drawings
        )
    )

    fun clear() {
        storage.clear()
    }
}

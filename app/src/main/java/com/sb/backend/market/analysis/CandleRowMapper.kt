package com.sb.backend.market.analysis

import com.sb.backend.models.Candle

interface CandleRow {
    fun string(index: Int): String?
    fun long(index: Int): Long
    fun double(index: Int): Double
}

object CandleRowMapper {
    fun read(row: CandleRow): Candle {
        val status = row.string(9) ?: "COMPLETED"
        return Candle(
            symbol = row.string(0) ?: "",
            exchange = row.string(1) ?: "",
            timeframe = row.string(2) ?: "",
            timestamp = row.long(3),
            open = row.double(4),
            high = row.double(5),
            low = row.double(6),
            close = row.double(7),
            volume = row.long(8),
            isComplete = status != "LIVE"
        )
    }
}

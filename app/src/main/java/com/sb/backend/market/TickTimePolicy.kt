package com.sb.backend.market

import java.util.Locale

object TickTimePolicy {

    enum class Source {
        EXCHANGE_TRADE,
        EXCHANGE_UPDATE,
        DEVICE_RECEIVE,
        NONE
    }

    data class Resolution(
        val eventTimeMillis: Long,
        val source: Source,
        val originalTradeTime: Long,
        val originalUpdateTime: Long,
        val receivedAt: Long,
        val reason: String
    )

    private const val MILLIS_THRESHOLD = 10_000_000_000L
    private const val MAX_FUTURE_SKEW_MILLIS = 30_000L
    private const val MAX_STALE_MILLIS = 15L * 60L * 1_000L
    private const val SESSION_TOLERANCE_MINUTES = 15

    fun normalize(value: Long?): Long? {
        if (value == null || value <= 0L) return null
        return if (value < MILLIS_THRESHOLD) value * 1_000L else value
    }

    fun resolve(
        tradeTime: Long?,
        updateTime: Long?,
        receivedAt: Long,
        exchange: String
    ): Resolution {
        val received = normalize(receivedAt)

        if (received == null) {
            return Resolution(
                eventTimeMillis = 0L,
                source = Source.NONE,
                originalTradeTime = tradeTime ?: 0L,
                originalUpdateTime = updateTime ?: 0L,
                receivedAt = receivedAt,
                reason = "invalid receive time"
            )
        }

        val acceptedTrade = exchangeCandidate(
            raw = tradeTime,
            source = Source.EXCHANGE_TRADE,
            received = received,
            exchange = exchange
        )
        if (acceptedTrade != null) return acceptedTrade

        val acceptedUpdate = exchangeCandidate(
            raw = updateTime,
            source = Source.EXCHANGE_UPDATE,
            received = received,
            exchange = exchange
        )
        if (acceptedUpdate != null) return acceptedUpdate

        if (!ExchangeSessionCalendar.isWithinSessionWindow(
                timestamp = received,
                exchange = exchange,
                toleranceMinutes = SESSION_TOLERANCE_MINUTES
            )
        ) {
            return Resolution(
                eventTimeMillis = 0L,
                source = Source.NONE,
                originalTradeTime = tradeTime ?: 0L,
                originalUpdateTime = updateTime ?: 0L,
                receivedAt = receivedAt,
                reason = "receive time outside exchange session; exchange timestamps unusable"
            )
        }

        return Resolution(
            eventTimeMillis = received,
            source = Source.DEVICE_RECEIVE,
            originalTradeTime = tradeTime ?: 0L,
            originalUpdateTime = updateTime ?: 0L,
            receivedAt = receivedAt,
            reason = "no valid exchange timestamp; device receive time used as fallback"
        )
    }

    private fun exchangeCandidate(
        raw: Long?,
        source: Source,
        received: Long,
        exchange: String
    ): Resolution? {
        val normalized = normalize(raw) ?: return null

        if (normalized > received + MAX_FUTURE_SKEW_MILLIS) return null
        if (normalized < received - MAX_STALE_MILLIS) return null

        if (!ExchangeSessionCalendar.isWithinSessionWindow(
                timestamp = normalized,
                exchange = exchange,
                toleranceMinutes = SESSION_TOLERANCE_MINUTES
            )
        ) {
            return null
        }

        return Resolution(
            eventTimeMillis = normalized,
            source = source,
            originalTradeTime = if (source == Source.EXCHANGE_TRADE) normalized else 0L,
            originalUpdateTime = if (source == Source.EXCHANGE_UPDATE) normalized else 0L,
            receivedAt = received,
            reason = "${source.name.lowercase(Locale.ROOT)} timestamp accepted"
        )
    }
}

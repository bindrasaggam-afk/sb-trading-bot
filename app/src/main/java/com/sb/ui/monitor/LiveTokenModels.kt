package com.sb.ui.monitor

import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.models.Instrument

enum class LiveTokenCategory(val label: String) {
    TOKEN_WITH_TICK("Token with Live Tick"),
    TOKEN_ONLY("Token Only (No Tick)"),
    NO_TOKEN("No Token"),
    NO_LIVE_PRICE("No Live Price")
}

data class LiveTokenCounts(
    val tokenWithTick: Int = 0,
    val token: Int = 0,
    val noToken: Int = 0,
    val noLivePrice: Int = 0,
    val tokenOnly: Int = 0
) {
    val total: Int get() = token + noToken
}

data class LiveTokenEntry(
    val symbol: String,
    val name: String,
    val exchange: String,
    val token: String?,
    val hasLiveTick: Boolean,
    val isAlias: Boolean,
    val detail: String = ""
) {
    val status: String
        get() = when {
            detail.isNotBlank() -> detail
            hasLiveTick -> "live tick available"
            else -> "waiting for live tick"
        }

    val missing: String
        get() = when {
            token.isNullOrBlank() -> "Kotak token missing"
            else -> "nothing missing"
        }

    val displayRow: String
        get() = buildString {
            append(symbol)
            if (name.isNotBlank() && !name.equals(symbol, ignoreCase = true)) {
                append(" (").append(name).append(')')
            }
            append("  •  ").append(exchange)
            if (isAlias) {
                append("  •  alias")
            }
            append("  —  ")
            if (detail.isNotBlank()) {
                if (token.isNullOrBlank()) {
                    append("missing: ").append(detail)
                } else {
                    append(detail)
                }
            } else if (token.isNullOrBlank()) {
                append("missing: ").append(missing)
            } else {
                append(status).append("  •  token ").append(token)
            }
        }
}

object LiveTokenClassifier {

    fun counts(
        instruments: List<Instrument>,
        tickKeys: Set<String>
    ): LiveTokenCounts {
        var tokenWithTick = 0
        var token = 0
        var noToken = 0
        var noLivePrice = 0
        var tokenOnly = 0

        instruments.forEach { instrument ->
            val hasTick = tickKeys.contains(keyOf(instrument))
            if (instrument.token.isNullOrBlank()) {
                noToken++
            } else {
                token++
                if (hasTick) {
                    tokenWithTick++
                } else {
                    tokenOnly++
                }
            }
            if (isMissingLivePrice(instrument, hasTick)) {
                noLivePrice++
            }
        }

        return LiveTokenCounts(
            tokenWithTick = tokenWithTick,
            token = token,
            noToken = noToken,
            noLivePrice = noLivePrice,
            tokenOnly = tokenOnly
        )
    }

    fun entries(
        instruments: List<Instrument>,
        tickKeys: Set<String>,
        category: LiveTokenCategory,
        subscribedKeys: Set<String> = emptySet()
    ): List<LiveTokenEntry> {
        val result = ArrayList<LiveTokenEntry>()

        instruments.forEach { instrument ->
            val hasToken = !instrument.token.isNullOrBlank()
            val hasTick = tickKeys.contains(keyOf(instrument))

            if (category == LiveTokenCategory.NO_LIVE_PRICE) {
                if (!isMissingLivePrice(instrument, hasTick)) {
                    return@forEach
                }
                result.add(
                    LiveTokenEntry(
                        symbol = instrument.symbol,
                        name = instrument.name,
                        exchange = instrument.exchange,
                        token = instrument.token,
                        hasLiveTick = hasTick,
                        isAlias = instrument.isAlias,
                        detail = noLivePriceReason(instrument, subscribedKeys)
                    )
                )
                return@forEach
            }

            val included = when (category) {
                LiveTokenCategory.TOKEN_WITH_TICK -> hasToken && hasTick
                LiveTokenCategory.TOKEN_ONLY -> hasToken && !hasTick
                LiveTokenCategory.NO_TOKEN -> !hasToken
                LiveTokenCategory.NO_LIVE_PRICE -> false
            }
            if (!included) {
                return@forEach
            }

            result.add(
                LiveTokenEntry(
                    symbol = instrument.symbol,
                    name = instrument.name,
                    exchange = instrument.exchange,
                    token = instrument.token,
                    hasLiveTick = hasTick,
                    isAlias = instrument.isAlias
                )
            )
        }

        return result
    }

    private fun isMissingLivePrice(
        instrument: Instrument,
        hasTick: Boolean
    ): Boolean {
        if (hasTick) {
            return false
        }
        return !(
            instrument.segment.equals("COMMODITY", true) &&
                instrument.expiryMillis == null
            )
    }

    private fun noLivePriceReason(
        instrument: Instrument,
        subscribedKeys: Set<String>
    ): String = when {
        instrument.token.isNullOrBlank() -> "no Kotak token"
        instrument.segment.equals("INDEX", true) -> "index feed waiting for tick"
        subscribedKeys.contains(keyOf(instrument)) -> "subscribed, waiting for tick"
        else -> "no live tick received"
    }

    private fun keyOf(instrument: Instrument): String =
        InstrumentKey(
            exchange = instrument.exchange,
            symbol = instrument.symbol
        ).key()
}

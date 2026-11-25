
package com.sb.backend.services

import com.sb.backend.models.Quote
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object LiveQuoteCache {

    private val cache = ConcurrentHashMap<String, Quote>()
    private val listeners = CopyOnWriteArrayList<(Quote) -> Unit>()

    private fun key(symbol: String, exchange: String): String {
        return "${exchange.trim().toUpperCase()}|${symbol.trim().toUpperCase()}"
    }

    /** Uses exchange time when available, otherwise the quote's local timestamp. */
    private fun orderingTimestamp(quote: Quote): Long =
        quote.exchangeTimestamp?.takeIf { it > 0L } ?: quote.timestamp

    /** Publishes only accepted quotes that are not older than the cached quote. */
    fun updateIfAccepted(quote: Quote, accepted: Boolean): Boolean {
        if (!accepted) return false
        return update(quote)
    }

    @Synchronized
    fun update(quote: Quote): Boolean {
        val k = key(quote.symbol, quote.exchange)
        val previous = cache[k]
        if (previous != null && orderingTimestamp(quote) < orderingTimestamp(previous)) {
            return false
        }
        cache[k] = quote
        listeners.forEach { listener ->
            try {
                listener(quote)
            } catch (_: Exception) {
            }
        }
        return true
    }

    fun get(symbol: String, exchange: String): Quote? {
        return cache[key(symbol, exchange)]
    }

    fun getAll(): Map<String, Quote> {
        return HashMap(cache)
    }

    fun addListener(listener: (Quote) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Quote) -> Unit) {
        listeners.remove(listener)
    }

    fun clear() {
        cache.clear()
    }
}

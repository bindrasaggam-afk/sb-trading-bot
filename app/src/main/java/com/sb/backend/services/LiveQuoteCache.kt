
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

    /** Publishes a quote only after the authoritative market pipeline accepts its tick. */
    fun updateIfAccepted(quote: Quote, accepted: Boolean): Boolean {
        if (!accepted) return false
        update(quote)
        return true
    }

    @Synchronized
    fun update(quote: Quote) {
        val k = key(quote.symbol, quote.exchange)
        cache[k] = quote
        listeners.forEach { listener ->
            try {
                listener(quote)
            } catch (_: Exception) {
            }
        }
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

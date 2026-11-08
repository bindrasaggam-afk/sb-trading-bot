
package com.sb.backend.services

import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.model.DepthLevel
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketTick
import com.sb.backend.models.Instrument
import com.sb.backend.models.Quote
import com.sb.backend.repositories.MarketRepository
import com.sb.backend.security.KotakNeoSecureStore
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class KotakNeoLiveFeedService(
    private val secureStore: KotakNeoSecureStore,
    private val marketRepository: MarketRepository,
    private val instruments: List<Instrument>,
    private val onStatus: (String) -> Unit
) {

    private val parser = KotakNeoBinaryParser()
    private val tickCount = AtomicLong(0L)

    private val pendingQuotes = ConcurrentHashMap<String, Quote>()
    private val quoteFlushScheduled = AtomicBoolean(false)
    private val retryWorkerStarted = AtomicBoolean(false)
    private val quoteExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor()
    }

    @Volatile
    private var client: KotakNeoWebSocketClient? = null

    private val indexTokens = listOf(
        "nse_cm|Nifty 50",
        "nse_cm|Nifty Bank",
        "nse_cm|Nifty Fin Service",
        "bse_cm|SENSEX"
    )

    /**
     * Composite mapping:
     * Exchange Segment + Token
     *
     * Example:
     * nse_cm|2885
     * mcx_fo|483079
     */
    private data class FeedKey(
        val feedSegment: String,
        val token: Long
    )

    private fun instrumentKey(instrument: Instrument): FeedKey? {
        if (!instrument.isActive) return null

        val token = instrument.token
            ?.trim()
            ?.toLongOrNull()
            ?: return null

        if (token <= 0L) return null

        return FeedKey(
            feedSegment = feedSegment(instrument),
            token = token
        )
    }

    private val instrumentMap: Map<FeedKey, Instrument> =
        instruments.mapNotNull { instrument ->
            val key = try {
                instrumentKey(instrument)?.let { it to instrument }
            } catch (_: IllegalArgumentException) {
                null
            }
            key
        }.toMap()

    /**
     * Instruments subscribed after the initial 3000-row snapshot.
     * Needed so ticks for on-demand chart subscriptions are not dropped.
     */
    private val requestedInstruments =
        java.util.concurrent.ConcurrentHashMap<FeedKey, Instrument>()

    @Synchronized
    fun connect() {
        if (client?.isAuthenticated() == true) {
            onStatus("Kotak Live Feed already authenticated")
            return
        }

        val ucc = secureStore.getUcc()
        val sid = secureStore.getTradeSid()
        val feedUrl = secureStore.getTradeFeedUrl()

        require(ucc.isNotBlank()) {
            "ERROR | Kotak UCC missing"
        }

        require(sid.isNotBlank()) {
            "ERROR | Kotak SID missing. Login again."
        }

        require(feedUrl.isNotBlank()) {
            "ERROR | Kotak Feed URL missing. Authenticate again."
        }

        val ws = KotakNeoWebSocketClient(
            ucc = ucc,
            sid = sid,
            feedUrl = feedUrl,
            onStatus = onStatus,
            onBinaryFrame = { frame ->
                processFrame(frame)
            },
            onAuthenticated = {
                subscribeAll()
                startQuoteRetryWorker()
            }
        )

        client?.shutdown()
        client = ws

        onStatus(
            "Kotak WebSocket connecting. " +
                "Valid active instruments: ${instrumentMap.size}"
        )

        ws.connect()
    }

    /**
     * Subscribe only active instruments having valid tokens.
     * Duplicate exchange-segment/token combinations are removed.
     */
    @Synchronized
    fun subscribeAll() {
        val allKeys = (instrumentMap.keys + requestedInstruments.keys).distinct()

        val tokens = allKeys.map { key ->
            "${key.feedSegment}|${key.token}"
        }.distinct()

        if (tokens.isEmpty()) {
            onStatus("No active instruments with valid Kotak tokens")
            return
        }

        val ws = client

        if (ws == null || !ws.isAuthenticated()) {
            onStatus("Subscription skipped: WebSocket not authenticated")
            return
        }

        ws.subscribe(tokens)
        ws.subscribeIndices(indexTokens)

        onStatus(
            "Live feed subscription requested: " +
                "${tokens.size} instruments, ${indexTokens.size} indices"
        )
    }

    @Synchronized
    fun unsubscribeIndices() {
        val ws = client

        if (ws == null || !ws.isConnected()) {
            onStatus("Index unsubscribe skipped: WebSocket disconnected")
            return
        }

        ws.unsubscribeIndices(indexTokens)

        onStatus(
            "Index unsubscribe requested: ${indexTokens.size} indices"
        )
    }

    @Synchronized
    fun subscribeInstrument(instrument: Instrument) {
        val ws = client

        require(ws != null) {
            "Kotak WebSocket is not initialized"
        }

        if (instrument.segment.trim().equals("INDEX", ignoreCase = true)) {
            val segment = feedSegment(instrument)
            val feedName = indexFeedName(instrument.symbol)

            require(feedName.isNotBlank()) {
                "Index feed name missing: ${instrument.symbol}"
            }

            ws.subscribeIndices(listOf("$segment|$feedName"))
            onStatus("Index subscription requested: ${instrument.symbol}")
            return
        }

        val key = instrumentKey(instrument)

        require(key != null) {
            "Instrument inactive or token missing/invalid: ${instrument.symbol}"
        }

        requestedInstruments[key] = instrument
        ws.subscribe(
            listOf("${key.feedSegment}|${key.token}")
        )

        onStatus("Subscription requested: ${instrument.symbol}")
    }

    @Synchronized
    fun unsubscribeInstrument(instrument: Instrument) {
        val ws = client

        if (ws == null) {
            onStatus("Unsubscribe skipped: WebSocket disconnected")
            return
        }

        if (instrument.segment.trim().equals("INDEX", ignoreCase = true)) {
            val segment = feedSegment(instrument)
            val feedName = indexFeedName(instrument.symbol)

            if (feedName.isNotBlank()) {
                ws.unsubscribeIndices(listOf("$segment|$feedName"))
            }

            onStatus("Index unsubscribe requested: ${instrument.symbol}")
            return
        }

        val key = instrumentKey(instrument)

        if (key == null) {
            onStatus("Unsubscribe skipped: invalid instrument ${instrument.symbol}")
            return
        }

        requestedInstruments.remove(key)

        // शुरुआती instrumentMap में मौजूद इंस्ट्रूमेंट को
        // ऑन-डिमांड unsubscribe से हटाना नहीं है।
        if (key !in instrumentMap) {
            ws.unsubscribe(
                listOf("${key.feedSegment}|${key.token}")
            )
        }

        onStatus("Unsubscribe requested: ${instrument.symbol}")
    }

    /**
     * Resolve Kotak feed segment from exchange + instrument metadata.
     */
    private fun feedSegment(instrument: Instrument): String {
        val exchange = instrument.exchange
            .trim()
            .toUpperCase(Locale.ROOT)

        val segment = instrument.segment
            .trim()
            .toUpperCase(Locale.ROOT)

        val type = instrument.instrumentType
            .trim()
            .toUpperCase(Locale.ROOT)

        val derivative = listOf(segment, type).any { value: String ->
            value.contains("FO") ||
                value.contains("FUT") ||
                value.contains("OPT") ||
                value.contains("DERIV")
        }

        return when (exchange) {
            "NSE" -> if (derivative) "nse_fo" else "nse_cm"
            "BSE" -> if (derivative) "bse_fo" else "bse_cm"
            "MCX" -> "mcx_fo"

            else -> throw IllegalArgumentException(
                "Unsupported exchange: ${instrument.exchange}"
            )
        }
    }

    private fun safeRecordTickAudit(
        exchange: String,
        symbol: String,
        received: Int = 0,
        parsed: Int = 0,
        mapped: Int = 0,
        rejected: Int = 0,
        rawSaved: Int = 0,
        quoteSaved: Int = 0,
        saveFailed: Int = 0
    ) {
        try {
            marketRepository.recordTickAudit(
                exchange = exchange,
                symbol = symbol,
                received = received,
                parsed = parsed,
                mapped = mapped,
                rejected = rejected,
                rawSaved = rawSaved,
                quoteSaved = quoteSaved,
                saveFailed = saveFailed
            )
        } catch (e: Exception) {
            onStatus(
                "Tick audit persistence error $symbol: ${e.message}"
            )
        }
    }

    private fun processFrame(frame: ByteArray) {
        val ticks = try {
            parser.parseFrame(frame)
        } catch (e: Exception) {
            onStatus("Kotak binary parse error: ${e.message}")
            return
        }

        ticks.forEach { tick ->

            if (tick.messageCode == 105) {
                onStatus(
                    "Market status ${tick.exchange}: ${tick.status}"
                )
                return@forEach
            }

            val symbol: String
            val exchange: String

            if (tick.isIndex) {
                symbol = indexSymbol(tick.symbol)
                exchange = exchangeName(tick.exchange)

                if (symbol.isBlank() || exchange.isBlank()) {
                    return@forEach
                }
            } else {
                val key = FeedKey(
                    feedSegment = tick.exchange
                        .trim()
                        .toLowerCase(Locale.ROOT),
                    token = tick.token
                )

                val instrument = instrumentMap[key]
                    ?: requestedInstruments[key]
                    ?: run {
                        safeRecordTickAudit(
                            exchange = exchangeName(tick.exchange)
                                .ifBlank { tick.exchange },
                            symbol = tick.symbol.ifBlank {
                                tick.token.toString()
                            },
                            rejected = 1
                        )
                        return@forEach
                    }

                symbol = instrument.symbol
                exchange = instrument.exchange
            }

            if (tick.ltp <= 0.0 || !tick.ltp.isFinite()) {
                safeRecordTickAudit(
                    exchange = exchange,
                    symbol = symbol,
                    received = 1,
                    parsed = 1,
                    rejected = 1
                )
                return@forEach
            }

            val received = System.currentTimeMillis()

            val quote = Quote(
                symbol = symbol,
                exchange = exchange,
                ltp = tick.ltp,
                open = tick.open.takeIf {
                    it > 0.0 && it.isFinite()
                },
                high = tick.high.takeIf {
                    it > 0.0 && it.isFinite()
                },
                low = tick.low.takeIf {
                    it > 0.0 && it.isFinite()
                },
                previousClose = tick.close.takeIf {
                    it > 0.0 && it.isFinite()
                },
                volume = tick.volume.coerceAtLeast(0L),
                change = tick.change,
                changePercent = tick.changePercent,
                timestamp = received,
                isLive = true,
                exchangeTimestamp =
                    MarketTick.normalizeMillis(tick.lastUpdateTime),
                receivedAt = received
            )

            try {
                LiveQuoteCache.update(quote)

                val mKey = InstrumentKey(exchange = exchange, symbol = symbol)
                val bids = tick.buyDepth.take(5).map { d ->
                    DepthLevel(price = d.price, quantity = d.quantity, orders = d.orders)
                }
                val asks = tick.sellDepth.take(5).map { d ->
                    DepthLevel(price = d.price, quantity = d.quantity, orders = d.orders)
                }
                val bestBid = bids.firstOrNull()
                val bestAsk = asks.firstOrNull()

                val mTick = MarketTick(
                    key = mKey,
                    ltp = tick.ltp,
                    open = tick.open.takeIf { it > 0.0 && it.isFinite() },
                    high = tick.high.takeIf { it > 0.0 && it.isFinite() },
                    low = tick.low.takeIf { it > 0.0 && it.isFinite() },
                    close = tick.close.takeIf { it > 0.0 && it.isFinite() },
                    previousClose = tick.close.takeIf { it > 0.0 && it.isFinite() },
                    volume = tick.volume.coerceAtLeast(0L),
                    change = tick.change,
                    changePercent = tick.changePercent,
                    bidPrice = bestBid?.price,
                    bidQty = bestBid?.quantity,
                    askPrice = bestAsk?.price,
                    askQty = bestAsk?.quantity,
                    bids = bids,
                    asks = asks,
                    totalBuyQty = tick.totalBuyQuantity,
                    totalSellQty = tick.totalSellQuantity,
                    oi = tick.openInterest,
                    lastTradeQty = tick.lastTradeQuantity,
                    lastTradeTime = MarketTick.normalizeMillis(tick.lastTradeTime),
                    exchangeTime = MarketTick.normalizeMillis(tick.lastUpdateTime),
                    receivedAt = received,
                    isIndex = tick.isIndex
                )
                // Persist every mapped market tick before quote batching.
                // Raw ticks must not be lost when pendingQuotes overwrites older quotes.
                val rawSaved = try {
                    marketRepository.saveRawTick(mTick)
                } catch (e: Exception) {
                    onStatus("Raw tick save error $symbol: ${e.message}")
                    false
                }

                // Record per-instrument feed and raw persistence counters.
                safeRecordTickAudit(
                    exchange = exchange,
                    symbol = symbol,
                    received = 1,
                    parsed = 1,
                    mapped = 1,
                    rejected = 0,
                    rawSaved = if (rawSaved) 1 else 0,
                    quoteSaved = 0,
                    saveFailed = if (rawSaved) 0 else 1
                )

                MarketDataManager.onNewTick(mTick)

                if (MarketDataManager.currentConnection() !=
                    MarketDataManager.ConnectionState.AUTHENTICATED
                ) {
                    MarketDataManager.updateConnection(
                        MarketDataManager.ConnectionState.AUTHENTICATED
                    )
                }

                enqueueQuotePersistence(quote)

                val count = tickCount.incrementAndGet()

                if (count == 1L || count % 250L == 0L) {
                    val price = String.format(
                        Locale.US,
                        "%.2f",
                        tick.ltp
                    )

                    onStatus(
                        "Live ticks: $count | " +
                            "$symbol ₹$price"
                    )
                }
            } catch (e: Exception) {
                onStatus(
                    "Live quote error $symbol: ${e.message}"
                )
            }
        }
    }

    private fun enqueueQuotePersistence(quote: Quote) {
        val key = "${quote.exchange.trim().uppercase(Locale.ROOT)}|" +
            quote.symbol.trim().uppercase(Locale.ROOT)

        pendingQuotes[key] = quote

        if (!quoteFlushScheduled.compareAndSet(false, true)) return

        try {
            quoteExecutor.schedule(
                { flushPendingQuotes() },
                500L,
                TimeUnit.MILLISECONDS
            )
        } catch (_: Exception) {
            quoteFlushScheduled.set(false)
        }
    }

    private fun flushPendingQuotes() {
        quoteFlushScheduled.set(false)

        if (pendingQuotes.isEmpty()) return

        val batch = pendingQuotes.entries.map { entry ->
            entry.key to entry.value
        }

        if (batch.isEmpty()) return

        batch.forEach { (key, quote) ->
            try {
                val saved = marketRepository.saveQuote(quote)

                if (saved) {
                    // केवल वही quote हटेगी जो अभी save की गई है।
                    // इस बीच नई quote आई हो तो वह सुरक्षित रहेगी।
                    pendingQuotes.remove(key, quote)

                    safeRecordTickAudit(
                        exchange = quote.exchange,
                        symbol = quote.symbol,
                        quoteSaved = 1
                    )
                } else {
                    val queued = marketRepository.enqueueQuoteRetry(
                        quote,
                        "Initial quote persistence returned false"
                    )

                    safeRecordTickAudit(
                        exchange = quote.exchange,
                        symbol = quote.symbol,
                        saveFailed = 1
                    )

                    if (queued) {
                        pendingQuotes.remove(key, quote)
                    } else {
                        onStatus(
                            "Quote save and retry enqueue failed: ${quote.symbol}"
                        )
                    }
                }
            } catch (e: Exception) {
                val queued = marketRepository.enqueueQuoteRetry(
                    quote,
                    e.message ?: "Initial quote persistence exception"
                )

                safeRecordTickAudit(
                    exchange = quote.exchange,
                    symbol = quote.symbol,
                    saveFailed = 1
                )

                if (queued) {
                    pendingQuotes.remove(key, quote)
                } else {
                    onStatus(
                        "Quote persistence recovery failed: ${quote.symbol}"
                    )
                }
            }
        }

        // अगर flush के दौरान नई quotes आई हैं तो अगला flush schedule करें।
        if (pendingQuotes.isNotEmpty() &&
            quoteFlushScheduled.compareAndSet(false, true)
        ) {
            try {
                quoteExecutor.schedule(
                    { flushPendingQuotes() },
                    500L,
                    TimeUnit.MILLISECONDS
                )
            } catch (e: Exception) {
                quoteFlushScheduled.set(false)
                onStatus("Quote flush scheduling failed: ${e.message}")
            }
        }
    }

    private fun startQuoteRetryWorker() {
        if (!retryWorkerStarted.compareAndSet(false, true)) return

        try {
            quoteExecutor.scheduleWithFixedDelay(
                {
                    try {
                        val recovered = marketRepository.retryPendingQuotes(100)

                        if (recovered > 0) {
                            onStatus("Quote retry recovered: $recovered")
                        }
                    } catch (e: Exception) {
                        onStatus(
                            "Quote retry worker error: ${e.message}"
                        )
                    }
                },
                5L,
                5L,
                TimeUnit.SECONDS
            )
        } catch (e: Exception) {
            retryWorkerStarted.set(false)
            onStatus("Quote retry scheduler failed: ${e.message}")
        }
    }

    private fun exchangeName(feedSegment: String): String {
        return when (feedSegment.trim().toLowerCase(Locale.ROOT)) {
            "nse_cm", "nse_fo", "nse_com" -> "NSE"
            "bse_cm", "bse_fo", "bse_cd", "bse_co" -> "BSE"
            "mcx_fo" -> "MCX"
            else -> ""
        }
    }

    private fun indexSymbol(name: String): String {
        val normalized = name
            .trim()
            .toUpperCase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

        return when (normalized) {
            "NIFTY 50", "NIFTY50" -> "NIFTY 50"
            "NIFTY BANK", "BANK NIFTY", "BANKNIFTY" -> "BANK NIFTY"
            "NIFTY FIN SERVICE",
            "NIFTY FINANCIAL SERVICES",
            "FINNIFTY" -> "FINNIFTY"
            "SENSEX" -> "SENSEX"
            else -> normalized
        }
    }

    private fun indexFeedName(symbol: String): String {
        val normalized = symbol
            .trim()
            .toUpperCase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

        return when (normalized) {
            "NIFTY 50", "NIFTY50" -> "Nifty 50"
            "NIFTY BANK", "BANK NIFTY", "BANKNIFTY" -> "Nifty Bank"
            "FINNIFTY",
            "NIFTY FIN SERVICE",
            "NIFTY FINANCIAL SERVICES" -> "Nifty Fin Service"
            "SENSEX" -> "SENSEX"
            "NIFTY MIDCAP SELECT", "MIDCPNIFTY" -> "Nifty Midcap Select"
            "NIFTY NEXT 50" -> "Nifty Next 50"
            "NIFTY 100" -> "Nifty 100"
            "NIFTY 200" -> "Nifty 200"
            "NIFTY 500" -> "Nifty 500"
            "NIFTY MIDCAP 50" -> "Nifty Midcap 50"
            "NIFTY MIDCAP 100" -> "Nifty Midcap 100"
            "NIFTY SMALLCAP 100" -> "Nifty Smallcap 100"
            "NIFTY IT" -> "Nifty IT"
            "NIFTY AUTO" -> "Nifty Auto"
            "NIFTY PHARMA" -> "Nifty Pharma"
            "NIFTY FMCG" -> "Nifty FMCG"
            "NIFTY METAL" -> "Nifty Metal"
            "NIFTY REALTY" -> "Nifty Realty"
            "NIFTY ENERGY" -> "Nifty Energy"
            "NIFTY PSU BANK" -> "Nifty PSU Bank"
            "NIFTY PRIVATE BANK" -> "Nifty Private Bank"
            "NIFTY MEDIA" -> "Nifty Media"
            "INDIA VIX" -> "India VIX"
            else -> symbol.trim()
        }
    }

    fun isConnected(): Boolean =
        client?.isConnected() == true

    fun isAuthenticated(): Boolean =
        client?.isAuthenticated() == true

    fun webSocketClient(): KotakNeoWebSocketClient? = client

    @Synchronized
    fun disconnect() {
        client?.unsubscribeIndices(indexTokens)
        client?.disconnect()
        client = null
        onStatus("Kotak Live Feed disconnected")
    }

    @Synchronized
    fun close() {
        client?.unsubscribeIndices(indexTokens)
        client?.shutdown()
        client = null

        try {
            quoteExecutor.execute { flushPendingQuotes() }
        } catch (_: Exception) {
        }

        quoteExecutor.shutdown()
        onStatus("Kotak Live Feed closed")
    }
}

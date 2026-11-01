
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
import java.util.concurrent.atomic.AtomicLong

class KotakNeoLiveFeedService(
    private val secureStore: KotakNeoSecureStore,
    private val marketRepository: MarketRepository,
    private val instruments: List<Instrument>,
    private val onStatus: (String) -> Unit
) {

    private val parser = KotakNeoBinaryParser()
    private val tickCount = AtomicLong(0L)

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
    private data class InstrumentKey(
        val feedSegment: String,
        val token: Long
    )

    private fun instrumentKey(instrument: Instrument): InstrumentKey? {
        if (!instrument.isActive) return null

        val token = instrument.token
            ?.trim()
            ?.toLongOrNull()
            ?: return null

        if (token <= 0L) return null

        return InstrumentKey(
            feedSegment = feedSegment(instrument),
            token = token
        )
    }

    private val instrumentMap: Map<InstrumentKey, Instrument> =
        instruments.mapNotNull { instrument ->
            val key = try {
                instrumentKey(instrument)?.let { it to instrument }
            } catch (_: IllegalArgumentException) {
                null
            }
            key
        }.toMap()

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
        val tokens = instrumentMap.keys
            .map { key ->
                "${key.feedSegment}|${key.token}"
            }
            .distinct()

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
        val key = instrumentKey(instrument)

        require(key != null) {
            "Instrument inactive or token missing/invalid: ${instrument.symbol}"
        }

        val ws = client

        require(ws != null && ws.isAuthenticated()) {
            "Kotak WebSocket is not authenticated"
        }

        ws.subscribe(
            listOf("${key.feedSegment}|${key.token}")
        )

        onStatus("Subscription requested: ${instrument.symbol}")
    }

    @Synchronized
    fun unsubscribeInstrument(instrument: Instrument) {
        val key = instrumentKey(instrument)

        if (key == null) {
            onStatus("Unsubscribe skipped: invalid instrument ${instrument.symbol}")
            return
        }

        val ws = client

        if (ws == null || !ws.isConnected()) {
            onStatus("Unsubscribe skipped: WebSocket disconnected")
            return
        }

        ws.unsubscribe(
            listOf("${key.feedSegment}|${key.token}")
        )

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

            if (tick.ltp <= 0.0 || !tick.ltp.isFinite()) {
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
                val key = InstrumentKey(
                    feedSegment = tick.exchange
                        .trim()
                        .toLowerCase(Locale.ROOT),
                    token = tick.token
                )

                val instrument = instrumentMap[key]
                    ?: return@forEach

                symbol = instrument.symbol
                exchange = instrument.exchange
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
                exchangeTimestamp = null,
                receivedAt = received
            )

            try {
                LiveQuoteCache.update(quote)

                val mKey = InstrumentKey(exchange = exchange, symbol = symbol)
                val bids = tick.depth.take(5).map { d ->
                    DepthLevel(price = d.price, quantity = d.quantity, orders = d.orders)
                }
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
                    bidPrice = bids.firstOrNull()?.price,
                    bidQty = bids.firstOrNull()?.quantity,
                    askPrice = null,
                    askQty = null,
                    bids = bids,
                    asks = emptyList(),
                    totalBuyQty = tick.totalBuyQuantity,
                    totalSellQty = tick.totalSellQuantity,
                    oi = tick.openInterest,
                    lastTradeQty = tick.lastTradeQuantity,
                    receivedAt = received,
                    isIndex = tick.isIndex
                )
                MarketDataManager.onNewTick(mTick)

                if (!marketRepository.saveQuote(quote)) {
                    onStatus(
                        "Live quote database save failed: $symbol"
                    )
                } else {
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
                }
            } catch (e: Exception) {
                onStatus(
                    "Live quote error $symbol: ${e.message}"
                )
            }
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

    fun isConnected(): Boolean =
        client?.isConnected() == true

    fun isAuthenticated(): Boolean =
        client?.isAuthenticated() == true

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
        onStatus("Kotak Live Feed closed")
    }
}

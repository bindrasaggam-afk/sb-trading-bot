
package com.sb.backend.services

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class KotakNeoWebSocketClient(
    private val ucc: String,
    private val sid: String,
    private val feedUrl: String,
    private val onStatus: (String) -> Unit,
    private val onBinaryFrame: (ByteArray) -> Unit,
    private val onAuthenticated: () -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connected = AtomicBoolean(false)
    private val authenticated = AtomicBoolean(false)
    private val manuallyClosed = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var reconnectAttempts = 0

    // Equity, F&O and MCX subscriptions
    private val subscriptions =
        Collections.synchronizedSet(mutableSetOf<String>())

    // Index subscriptions are maintained separately
    private val indexSubscriptions =
        Collections.synchronizedSet(mutableSetOf<String>())

    private fun status(message: String) {
        mainHandler.post { onStatus(message) }
    }

    private fun websocketUrl(): String {
        val value = feedUrl.trim()

        require(value.isNotBlank()) {
            "Kotak feed URL missing"
        }

        val wsUrl = when {
            value.startsWith("https://", ignoreCase = true) ->
                "wss://" + value.substring(8)

            value.startsWith("wss://", ignoreCase = true) ->
                value

            else -> throw IllegalArgumentException(
                "Invalid Kotak feed URL scheme: HTTPS/WSS required"
            )
        }

        val uri = java.net.URI(wsUrl)

        require(uri.host != null && uri.path.isNotBlank()) {
            "Invalid Kotak WebSocket endpoint"
        }

        return wsUrl
    }

    fun connect() {
        require(ucc.isNotBlank()) { "Kotak UCC missing" }
        require(sid.isNotBlank()) { "Kotak SID missing" }
        require(feedUrl.isNotBlank()) {
            "Kotak feed URL missing"
        }

        manuallyClosed.set(false)
        openSocket()
    }

    @Synchronized
    private fun openSocket() {
        if (manuallyClosed.get()) return

        status("CONNECTING | Connecting to Kotak WebSocket...")

        val wsUrl = websocketUrl()
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {
                    connected.set(true)
                    authenticated.set(false)

                    val auth = JSONObject().apply {
                        put("user", ucc)
                        put("auth", sid)
                        put("format", "native_batch")
                        put("source", "NEOTRADEAPI")
                        put("sdk_version", 1.1)
                        put("sdk_date", "2026-10-30")
                    }

                    webSocket.send(auth.toString())

                    status(
                        "CONNECTED | WebSocket connected. Authenticating..."
                    )
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {
                    handleTextMessage(webSocket, text)
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString
                ) {
                    onBinaryFrame(bytes.toByteArray())
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    connected.set(false)
                    authenticated.set(false)

                    status(
                        "ERROR | WebSocket closed: $code $reason"
                    )

                    scheduleReconnect()
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    connected.set(false)
                    authenticated.set(false)

                    status(
                        "ERROR | WebSocket error: " +
                            (t.message ?: "Unknown error")
                    )

                    scheduleReconnect()
                }
            }
        )
    }

    private fun handleTextMessage(
        webSocket: WebSocket,
        text: String
    ) {
        try {
            val json = JSONObject(text)
            val code = json.optInt("message_code", -1)

            when (code) {

                1117 -> {
                    authenticated.set(true)
                    reconnectAttempts = 0

                    status(
                        "AUTHENTICATED | Kotak WebSocket authenticated"
                    )

                    // Restore saved subscriptions first.
                    resubscribe(webSocket)

                    // Notify LiveFeedService.
                    mainHandler.post {
                        onAuthenticated()
                    }
                }

                1120 -> {
                    authenticated.set(false)

                    status(
                        "ERROR | Kotak WebSocket authentication failed: " +
                            json.optString("message", text)
                    )
                }

                1119 -> {
                    status(
                        "Kotak WebSocket connection response received"
                    )
                }

                1109 -> {
                    status(
                        "Kotak subscription acknowledgement received"
                    )
                }

                else -> {
                    status(
                        json.optString(
                            "message",
                            "WebSocket message received: $code"
                        )
                    )
                }
            }

        } catch (_: Exception) {
            status("WebSocket text message received")
        }
    }

    // -------------------------------------------------
    // EQUITY / F&O / MCX SUBSCRIPTION
    // Format: nse_cm|2885
    // -------------------------------------------------

    fun subscribe(tokens: List<String>) {

        val newTokens = mutableListOf<String>()

        tokens.filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .forEach { token ->

                if (subscriptions.add(token)) {
                    newTokens.add(token)
                }
            }

        if (authenticated.get() && newTokens.isNotEmpty()) {
            sendScripSubscriptions(newTokens)
        }
    }

    fun unsubscribe(tokens: List<String>) {

        val removedTokens = mutableListOf<String>()

        tokens.filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .forEach { token ->

                if (subscriptions.remove(token)) {
                    removedTokens.add(token)
                }
            }

        if (authenticated.get() && removedTokens.isNotEmpty()) {
            sendUnsubscribeScrips(removedTokens)
        }
    }

    private fun sendScripSubscriptions(
        tokens: List<String>
    ) {
        val activeSocket = socket ?: return

        if (!authenticated.get()) return

        tokens.distinct()
            .chunked(3000)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "subscribeScrips")
                    put("inputtoken", batch.joinToString(","))
                    put("ack_symbol", true)
                }

                activeSocket.send(payload.toString())
            }
    }

    private fun sendUnsubscribeScrips(
        tokens: List<String>
    ) {
        val activeSocket = socket ?: return

        if (!authenticated.get()) return

        tokens.distinct()
            .chunked(3000)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "unsubscribeScrips")
                    put("inputtoken", batch.joinToString(","))
                    put("ack_symbol", true)
                }

                activeSocket.send(payload.toString())
            }
    }

    // -------------------------------------------------
    // INDEX SUBSCRIPTION
    // Format: nse_cm|Nifty 50
    // -------------------------------------------------

    fun subscribeIndices(indices: List<String>) {

        val newIndices = mutableListOf<String>()

        indices.filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .forEach { index ->

                if (indexSubscriptions.add(index)) {
                    newIndices.add(index)
                }
            }

        if (authenticated.get() && newIndices.isNotEmpty()) {
            sendIndexSubscriptions(newIndices)
        }
    }

    fun unsubscribeIndices(indices: List<String>) {

        val removedIndices = mutableListOf<String>()

        indices.filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .forEach { index ->

                if (indexSubscriptions.remove(index)) {
                    removedIndices.add(index)
                }
            }

        if (authenticated.get() && removedIndices.isNotEmpty()) {
            sendUnsubscribeIndices(removedIndices)
        }
    }

    private fun sendIndexSubscriptions(
        indices: List<String>
    ) {
        val activeSocket = socket ?: return

        if (!authenticated.get()) return

        indices.distinct()
            .chunked(3000)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "subscribeIndices")
                    put("inputtoken", batch.joinToString(","))
                }

                activeSocket.send(payload.toString())
            }
    }

    private fun sendUnsubscribeIndices(
        indices: List<String>
    ) {
        val activeSocket = socket ?: return

        if (!authenticated.get()) return

        indices.distinct()
            .chunked(3000)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "unsubscribeIndices")
                    put("inputtoken", batch.joinToString(","))
                }

                activeSocket.send(payload.toString())
            }
    }

    // -------------------------------------------------
    // RECONNECT AND RESUBSCRIBE
    // -------------------------------------------------

    private fun resubscribe(webSocket: WebSocket) {

        val scripSnapshot = synchronized(subscriptions) {
            subscriptions.toList()
        }

        val indexSnapshot = synchronized(indexSubscriptions) {
            indexSubscriptions.toList()
        }

        scripSnapshot.chunked(3000)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "subscribeScrips")
                    put("inputtoken", batch.joinToString(","))
                    put("ack_symbol", true)
                }

                webSocket.send(payload.toString())
            }

        indexSnapshot.chunked(3000)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "subscribeIndices")
                    put("inputtoken", batch.joinToString(","))
                }

                webSocket.send(payload.toString())
            }

        status(
            "Restored ${scripSnapshot.size} scrips and " +
                "${indexSnapshot.size} indices"
        )
    }

    private fun scheduleReconnect() {

        if (manuallyClosed.get()) return

        val delay = minOf(
            30000L,
            1000L * (1L shl reconnectAttempts.coerceAtMost(5))
        )

        reconnectAttempts++

        status(
            "RECONNECTING in ${delay / 1000} seconds..."
        )

        mainHandler.postDelayed({

            if (!manuallyClosed.get()) {
                openSocket()
            }

        }, delay)
    }

    // -------------------------------------------------
    // CONNECTION STATUS
    // -------------------------------------------------

    fun isConnected(): Boolean {
        return connected.get()
    }

    fun isAuthenticated(): Boolean {
        return authenticated.get()
    }

    fun disconnect() {

        manuallyClosed.set(true)
        connected.set(false)
        authenticated.set(false)

        socket?.close(1000, "User disconnected")
        socket = null

        status("Kotak WebSocket disconnected")
    }

    fun shutdown() {

        disconnect()

        client.dispatcher()
            .executorService()
            .shutdown()

        client.connectionPool().evictAll()
    }
}

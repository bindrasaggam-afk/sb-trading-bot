
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
import java.net.URI
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

    companion object {
        private const val MAX_RECONNECT_DELAY = 30_000L
        private const val MAX_SUBSCRIPTION_BATCH = 3000
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val connected = AtomicBoolean(false)
    private val authenticated = AtomicBoolean(false)
    private val manuallyClosed = AtomicBoolean(false)
    private val shutdown = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var reconnectAttempts = 0

    private val reconnectRunnable = Runnable {
        if (
            !manuallyClosed.get() &&
            !shutdown.get()
        ) {
            openSocket()
        }
    }

    private val subscriptions =
        Collections.synchronizedSet(mutableSetOf<String>())

    private val indexSubscriptions =
        Collections.synchronizedSet(mutableSetOf<String>())

    private fun status(message: String) {
        mainHandler.post {
            onStatus(message)
        }
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

        val uri = URI(wsUrl)

        require(
            uri.host != null &&
                uri.path.isNotBlank()
        ) {
            "Invalid Kotak WebSocket endpoint"
        }

        return wsUrl
    }

    @Synchronized
    fun connect() {
        check(!shutdown.get()) {
            "Kotak WebSocket client has been shut down"
        }

        require(ucc.isNotBlank()) {
            "Kotak UCC missing"
        }

        require(sid.isNotBlank()) {
            "Kotak SID missing"
        }

        require(feedUrl.isNotBlank()) {
            "Kotak feed URL missing"
        }

        manuallyClosed.set(false)

        mainHandler.removeCallbacks(reconnectRunnable)

        if (
            connected.get() ||
            authenticated.get()
        ) {
            return
        }

        openSocket()
    }

    @Synchronized
    private fun openSocket() {
        if (
            manuallyClosed.get() ||
            shutdown.get()
        ) {
            return
        }

        if (
            connected.get() ||
            authenticated.get()
        ) {
            return
        }

        mainHandler.removeCallbacks(reconnectRunnable)

        val wsUrl = try {
            websocketUrl()
        } catch (e: Exception) {
            status(
                "ERROR | Invalid WebSocket URL: " +
                    (e.message ?: "Unknown error")
            )
            return
        }

        status("CONNECTING | Connecting to Kotak WebSocket...")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        try {
            val newSocket = client.newWebSocket(
                request,
                createListener()
            )

            socket = newSocket

        } catch (e: Exception) {
            connected.set(false)
            authenticated.set(false)

            status(
                "ERROR | WebSocket creation failed: " +
                    (e.message ?: "Unknown error")
            )

            scheduleReconnect()
        }
    }

    private fun createListener(): WebSocketListener {

        return object : WebSocketListener() {

            private fun isCurrent(
                webSocket: WebSocket
            ): Boolean {
                return socket === webSocket &&
                    !manuallyClosed.get() &&
                    !shutdown.get()
            }

            override fun onOpen(
                webSocket: WebSocket,
                response: Response
            ) {
                if (!isCurrent(webSocket)) {
                    webSocket.close(1000, "Stale connection")
                    return
                }

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

                val sent = webSocket.send(auth.toString())

                if (!sent) {
                    status("ERROR | Authentication request send failed")
                    webSocket.close(1011, "Authentication send failed")
                    return
                }

                status(
                    "CONNECTED | WebSocket connected. Authenticating..."
                )
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String
            ) {
                if (!isCurrent(webSocket)) return

                handleTextMessage(webSocket, text)
            }

            override fun onMessage(
                webSocket: WebSocket,
                bytes: ByteString
            ) {
                if (!isCurrent(webSocket)) return

                try {
                    onBinaryFrame(bytes.toByteArray())
                } catch (e: Exception) {
                    status(
                        "ERROR | Binary frame processing failed: " +
                            (e.message ?: "Unknown error")
                    )
                }
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
                if (socket !== webSocket) return

                connected.set(false)
                authenticated.set(false)
                socket = null

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
                if (socket !== webSocket) return

                connected.set(false)
                authenticated.set(false)
                socket = null

                status(
                    "ERROR | WebSocket error: " +
                        (t.message ?: "Unknown error")
                )

                scheduleReconnect()
            }
        }
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
                    if (socket !== webSocket) return

                    authenticated.set(true)
                    reconnectAttempts = 0

                    status(
                        "AUTHENTICATED | Kotak WebSocket authenticated"
                    )

                    resubscribe(webSocket)

                    mainHandler.post {
                        if (
                            socket === webSocket &&
                            authenticated.get()
                        ) {
                            onAuthenticated()
                        }
                    }
                }

                1120 -> {
                    if (socket !== webSocket) return

                    authenticated.set(false)

                    status(
                        "ERROR | Kotak WebSocket authentication failed: " +
                            json.optString("message", text)
                    )

                    // Authentication failure should not cause
                    // an unlimited reconnect loop.
                    manuallyClosed.set(true)

                    mainHandler.removeCallbacks(reconnectRunnable)

                    webSocket.close(
                        1008,
                        "Authentication failed"
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

        } catch (e: Exception) {
            status(
                "WebSocket text message received"
            )
        }
    }

    // -------------------------------------------------
    // EQUITY / F&O / MCX SUBSCRIPTION
    // Format: nse_cm|2885
    // -------------------------------------------------

    @Synchronized
    fun subscribe(tokens: List<String>) {

        if (shutdown.get()) return

        val newTokens = mutableListOf<String>()

        tokens.asSequence()
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .forEach { token ->
                if (subscriptions.add(token)) {
                    newTokens.add(token)
                }
            }

        if (
            authenticated.get() &&
            newTokens.isNotEmpty()
        ) {
            sendScripSubscriptions(newTokens)
        }
    }

    @Synchronized
    fun unsubscribe(tokens: List<String>) {

        val removedTokens = mutableListOf<String>()

        tokens.asSequence()
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .forEach { token ->
                if (subscriptions.remove(token)) {
                    removedTokens.add(token)
                }
            }

        if (
            authenticated.get() &&
            removedTokens.isNotEmpty()
        ) {
            sendUnsubscribeScrips(removedTokens)
        }
    }

    private fun sendScripSubscriptions(
        tokens: List<String>
    ) {
        val activeSocket = socket ?: return

        if (!authenticated.get()) return

        tokens.distinct()
            .chunked(MAX_SUBSCRIPTION_BATCH)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "subscribeDepth")
                    put("inputtoken", batch.joinToString(","))
                    put("ack_symbol", true)
                }

                if (!activeSocket.send(payload.toString())) {
                    status(
                        "ERROR | Scrip subscription send failed"
                    )
                }
            }
    }

    private fun sendUnsubscribeScrips(
        tokens: List<String>
    ) {
        val activeSocket = socket ?: return

        if (!authenticated.get()) return

        tokens.distinct()
            .chunked(MAX_SUBSCRIPTION_BATCH)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "unsubscribeDepth")
                    put("inputtoken", batch.joinToString(","))
                    put("ack_symbol", true)
                }

                if (!activeSocket.send(payload.toString())) {
                    status(
                        "ERROR | Scrip unsubscribe send failed"
                    )
                }
            }
    }

    // -------------------------------------------------
    // INDEX SUBSCRIPTION
    // Format: nse_cm|Nifty 50
    // -------------------------------------------------

    @Synchronized
    fun subscribeIndices(indices: List<String>) {

        if (shutdown.get()) return

        val newIndices = mutableListOf<String>()

        indices.asSequence()
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .forEach { index ->
                if (indexSubscriptions.add(index)) {
                    newIndices.add(index)
                }
            }

        if (
            authenticated.get() &&
            newIndices.isNotEmpty()
        ) {
            sendIndexSubscriptions(newIndices)
        }
    }

    @Synchronized
    fun unsubscribeIndices(indices: List<String>) {

        val removedIndices = mutableListOf<String>()

        indices.asSequence()
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .forEach { index ->
                if (indexSubscriptions.remove(index)) {
                    removedIndices.add(index)
                }
            }

        if (
            authenticated.get() &&
            removedIndices.isNotEmpty()
        ) {
            sendUnsubscribeIndices(removedIndices)
        }
    }

    private fun sendIndexSubscriptions(
        indices: List<String>
    ) {
        val activeSocket = socket ?: return

        if (!authenticated.get()) return

        indices.distinct()
            .chunked(MAX_SUBSCRIPTION_BATCH)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "subscribeIndices")
                    put("inputtoken", batch.joinToString(","))
                }

                if (!activeSocket.send(payload.toString())) {
                    status(
                        "ERROR | Index subscription send failed"
                    )
                }
            }
    }

    private fun sendUnsubscribeIndices(
        indices: List<String>
    ) {
        val activeSocket = socket ?: return

        if (!authenticated.get()) return

        indices.distinct()
            .chunked(MAX_SUBSCRIPTION_BATCH)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "unsubscribeIndices")
                    put("inputtoken", batch.joinToString(","))
                }

                if (!activeSocket.send(payload.toString())) {
                    status(
                        "ERROR | Index unsubscribe send failed"
                    )
                }
            }
    }

    // -------------------------------------------------
    // RECONNECT AND RESUBSCRIBE
    // -------------------------------------------------

    private fun resubscribe(
        webSocket: WebSocket
    ) {

        if (
            socket !== webSocket ||
            !authenticated.get()
        ) {
            return
        }

        val scripSnapshot = synchronized(subscriptions) {
            subscriptions.toList()
        }

        val indexSnapshot = synchronized(indexSubscriptions) {
            indexSubscriptions.toList()
        }

        scripSnapshot.chunked(MAX_SUBSCRIPTION_BATCH)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "subscribeDepth")
                    put("inputtoken", batch.joinToString(","))
                    put("ack_symbol", true)
                }

                if (!webSocket.send(payload.toString())) {
                    status(
                        "ERROR | Restoring scrip subscriptions failed"
                    )
                }
            }

        indexSnapshot.chunked(MAX_SUBSCRIPTION_BATCH)
            .forEach { batch ->

                val payload = JSONObject().apply {
                    put("event", "subscribeIndices")
                    put("inputtoken", batch.joinToString(","))
                }

                if (!webSocket.send(payload.toString())) {
                    status(
                        "ERROR | Restoring index subscriptions failed"
                    )
                }
            }

        status(
            "Restored ${scripSnapshot.size} scrips and " +
                "${indexSnapshot.size} indices"
        )
    }

    @Synchronized
    private fun scheduleReconnect() {

        if (
            manuallyClosed.get() ||
            shutdown.get()
        ) {
            return
        }

        mainHandler.removeCallbacks(reconnectRunnable)

        val exponent = reconnectAttempts.coerceAtMost(5)

        val delay = minOf(
            MAX_RECONNECT_DELAY,
            1000L * (1L shl exponent)
        )

        reconnectAttempts++

        status(
            "RECONNECTING in ${delay / 1000} seconds..."
        )

        mainHandler.postDelayed(
            reconnectRunnable,
            delay
        )
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

    @Synchronized
    fun disconnect() {

        manuallyClosed.set(true)

        mainHandler.removeCallbacks(reconnectRunnable)

        connected.set(false)
        authenticated.set(false)

        val activeSocket = socket
        socket = null

        activeSocket?.close(
            1000,
            "User disconnected"
        )

        status("Kotak WebSocket disconnected")
    }

    @Synchronized
    fun shutdown() {

        if (!shutdown.compareAndSet(false, true)) {
            return
        }

        disconnect()

        client.dispatcher()
            .cancelAll()

        client.dispatcher()
            .executorService()
            .shutdown()

        client.connectionPool()
            .evictAll()
    }
}

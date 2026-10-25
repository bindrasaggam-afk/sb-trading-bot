package com.sb

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SB BOTE Paper Trading API client.
 *
 * Uses the existing FastAPI backend.
 * No mock balances or mock orders are generated.
 */
object PaperTradingApi {

    // Replace with the reachable address of your EXISTING backend.
    // Android emulator + backend on development PC:
    // http://10.0.2.2:8000
    //
    // Physical Android phone + backend on PC:
    // http://YOUR_PC_LAN_IP:8000
    //
    // Cloud deployment:
    // Use your existing HTTPS backend URL.
    @Volatile
    var baseUrl: String = "http://10.0.2.2:8000"

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 10000

    private val executor: ExecutorService =
        Executors.newFixedThreadPool(2)

    data class ApiResult(
        val success: Boolean,
        val httpCode: Int? = null,
        val data: JSONObject? = null,
        val error: String? = null
    )

    /**
     * Execute a GET request away from the Android main thread.
     * Callback is delivered on the worker thread.
     */
    fun get(
        path: String,
        callback: (ApiResult) -> Unit
    ) {
        executor.execute {
            callback(request(path))
        }
    }

    private fun request(path: String): ApiResult {
        var connection: HttpURLConnection? = null

        return try {
            val root = baseUrl.trim().trimEnd('/')
            require(root.startsWith("http://") ||
                    root.startsWith("https://")) {
                "Backend URL must start with http:// or https://"
            }

            require(path.startsWith("/") && !path.startsWith("//")) {
                "Invalid API path"
            }

            connection = (URL(root + path)
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (code !in 200..299) {
                ApiResult(
                    success = false,
                    httpCode = code,
                    error = "API returned HTTP $code" +
                        if (body.isNotBlank()) ": $body" else ""
                )
            } else {
                val json = JSONObject(body)
                ApiResult(
                    success = true,
                    httpCode = code,
                    data = json
                )
            }
        } catch (e: Exception) {
            ApiResult(
                success = false,
                error = when (e) {
                    is IOException ->
                        "Backend connection failed: ${e.message}"
                    else -> e.message ?: "Unknown API error"
                }
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Health check: this endpoint was previously used by SB BOTE.
     */
    fun checkHealth(callback: (ApiResult) -> Unit) {
        get("/api/health", callback)
    }

    /**
     * These Paper Trading routes must be checked against
     * the current FastAPI implementation before using them.
     */
    fun getPaperStatus(callback: (ApiResult) -> Unit) {
        get("/api/paper/status", callback)
    }

    fun getPaperPositions(callback: (ApiResult) -> Unit) {
        get("/api/paper/positions", callback)
    }

    fun getPaperPnl(callback: (ApiResult) -> Unit) {
        get("/api/paper/pnl", callback)
    }
}

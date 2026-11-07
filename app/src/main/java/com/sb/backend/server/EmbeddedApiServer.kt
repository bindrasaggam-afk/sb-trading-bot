package com.sb.backend.server

import com.sb.backend.core.BackendManager
import com.sb.backend.services.KotakSessionCoordinator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class EmbeddedApiServer(
    private val port: Int = 10000
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    fun start(): Boolean {
        if (running.get()) return true

        return try {
            val socket = ServerSocket(
                port,
                50,
                InetAddress.getByName("127.0.0.1")
            )

            serverSocket = socket
            running.set(true)

            executor.execute {
                while (running.get()) {
                    try {
                        val client = socket.accept()
                        executor.execute {
                            handleClient(client)
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            true
        } catch (e: Exception) {
            running.set(false)
            e.printStackTrace()
            false
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { connection ->
                val input = BufferedInputStream(connection.getInputStream())
                val output = BufferedOutputStream(connection.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")

                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1)?.substringBefore("?") ?: "/"

                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                }

                val authenticated =
                    KotakSessionCoordinator.isFeedAuthenticated()

                val connected =
                    KotakSessionCoordinator.isFeedConnected()

                val brokerStatus = when {
                    authenticated -> "authenticated"
                    connected -> "connected"
                    else -> "disconnected"
                }

                val brokerMessage = jsonEscape(
                    KotakSessionCoordinator.currentStatus()
                )

                val response = when {
                    method == "GET" && path == "/api/health" ->
                        """{"status":"ok","service":"SB Bot Android Backend","server":"running"}"""

                    method == "GET" && path == "/api/broker/status" ->
                        """
                        {
                            "status": "$brokerStatus",
                            "broker": "Kotak Neo",
                            "connected": $connected,
                            "authenticated": $authenticated,
                            "message": "$brokerMessage"
                        }
                        """.trimIndent()

                    method == "GET" && path == "/api/system/status" ->
                        """{"app":"SB Bot","backend":"running","mode":"LOCAL_ANDROID"}"""

                    method == "GET" && path == "/api/database/diagnostics" ->
                        databaseDiagnostics()

                    method == "OPTIONS" ->
                        """{"status":"ok"}"""

                    else ->
                        """{"status":"error","message":"Endpoint not found"}"""
                }

                val status = if (
                    path == "/api/health" ||
                    path == "/api/broker/status" ||
                    path == "/api/system/status" ||
                    path == "/api/database/diagnostics" ||
                    method == "OPTIONS"
                ) "200 OK" else "404 Not Found"

                val body = response.toByteArray(Charsets.UTF_8)

                val headers = (
                    "HTTP/1.1 $status\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
                    "\r\n"
                )

                output.write(headers.toByteArray(Charsets.UTF_8))
                output.write(body)
                output.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun databaseDiagnostics(): String {
        if (!BackendManager.isInitialized()) {
            return JSONObject()
                .put("status", "not_ready")
                .put("message", "Backend database is not initialized yet")
                .toString()
        }

        return try {
            val db = BackendManager.database().readableDatabase

            val tableNames = listOf(
                "instruments",
                "quotes",
                "candles",
                "signals",
                "paper_account",
                "paper_orders",
                "positions",
                "device_identity",
                "app_state"
            )

            // Actual row count of every main backend table.
            val tables = JSONArray()

            tableNames.forEach { table ->
                val count = db.rawQuery(
                    "SELECT COUNT(*) FROM `$table`",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                }

                tables.put(
                    JSONObject()
                        .put("table", table)
                        .put("rows", count)
                )
            }

            // Total quote rows.
            val quoteCount = db.rawQuery(
                "SELECT COUNT(*) FROM quotes",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }

            // Total candle rows.
            val candleCount = db.rawQuery(
                "SELECT COUNT(*) FROM candles",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }

            // Candle counts grouped by timeframe.
            val timeframeCounts = JSONArray()

            db.rawQuery(
                """
                SELECT timeframe, COUNT(*)
                FROM candles
                GROUP BY timeframe
                ORDER BY timeframe
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    timeframeCounts.put(
                        JSONObject()
                            .put("timeframe", cursor.getString(0))
                            .put("rows", cursor.getLong(1))
                    )
                }
            }

            // Candle counts grouped by exchange, symbol and timeframe.
            val instrumentCounts = JSONArray()

            db.rawQuery(
                """
                SELECT exchange, symbol, timeframe, COUNT(*)
                FROM candles
                GROUP BY exchange, symbol, timeframe
                ORDER BY COUNT(*) DESC
                LIMIT 100
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    instrumentCounts.put(
                        JSONObject()
                            .put("exchange", cursor.getString(0))
                            .put("symbol", cursor.getString(1))
                            .put("timeframe", cursor.getString(2))
                            .put("rows", cursor.getLong(3))
                    )
                }
            }

            // Invalid OHLC rows.
            val invalidOhlcCount = db.rawQuery(
                """
                SELECT COUNT(*)
                FROM candles
                WHERE high < low
                   OR high < open
                   OR high < close
                   OR low > open
                   OR low > close
                """.trimIndent(),
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }

            // Latest quote.
            val latestQuote = JSONObject()

            db.rawQuery(
                """
                SELECT symbol, exchange, ltp, volume, timestamp,
                       is_live, exchange_timestamp, received_at
                FROM quotes
                ORDER BY timestamp DESC
                LIMIT 1
                """.trimIndent(),
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    latestQuote
                        .put("symbol", cursor.getString(0))
                        .put("exchange", cursor.getString(1))
                        .put("ltp", cursor.getDouble(2))
                        .put("volume", cursor.getLong(3))
                        .put("timestamp", cursor.getLong(4))
                        .put("is_live", cursor.getInt(5) == 1)
                        .put(
                            "exchange_timestamp",
                            if (cursor.isNull(6)) JSONObject.NULL
                            else cursor.getLong(6)
                        )
                        .put("received_at", cursor.getLong(7))
                }
            }

            // Latest stored candle.
            val latestCandle = JSONObject()

            db.rawQuery(
                """
                SELECT symbol, exchange, timeframe, timestamp,
                       open, high, low, close, volume
                FROM candles
                ORDER BY timestamp DESC
                LIMIT 1
                """.trimIndent(),
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    latestCandle
                        .put("symbol", cursor.getString(0))
                        .put("exchange", cursor.getString(1))
                        .put("timeframe", cursor.getString(2))
                        .put("timestamp", cursor.getLong(3))
                        .put("open", cursor.getDouble(4))
                        .put("high", cursor.getDouble(5))
                        .put("low", cursor.getDouble(6))
                        .put("close", cursor.getDouble(7))
                        .put("volume", cursor.getLong(8))
                }
            }

            JSONObject()
                .put("status", "ok")
                .put("database", "sb_backend.db")
                .put("database_version", 4)
                .put("read_only", true)
                .put("table_count", tableNames.size)
                .put("tables", tables)
                .put(
                    "summary",
                    JSONObject()
                        .put("quote_rows", quoteCount)
                        .put("candle_rows", candleCount)
                        .put("invalid_ohlc_rows", invalidOhlcCount)
                )
                .put("candle_timeframes", timeframeCounts)
                .put("candle_instruments", instrumentCounts)
                .put("latest_quote", latestQuote)
                .put("latest_candle", latestCandle)
                .toString()
        } catch (error: Exception) {
            JSONObject()
                .put("status", "error")
                .put("message", error.message ?: "Database diagnostics failed")
                .toString()
        }
    }

    private fun jsonEscape(value: String): String {
        val builder = StringBuilder(value.length + 16)

        value.forEach { character ->
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (character < ' ') {
                        builder.append("\\u")
                        builder.append(
                            character.code
                                .toString(16)
                                .padStart(4, '0')
                        )
                    } else {
                        builder.append(character)
                    }
                }
            }
        }

        return builder.toString()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val result = StringBuilder()

        while (true) {
            val value = input.read()
            if (value == -1) {
                return if (result.isEmpty()) null else result.toString()
            }
            if (value == 10) break
            if (value != 13) result.append(value.toChar())
        }

        return result.toString()
    }

    fun isRunning(): Boolean {
        return running.get() && serverSocket?.isBound == true
    }

    fun stop() {
        running.set(false)

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null
    }
}

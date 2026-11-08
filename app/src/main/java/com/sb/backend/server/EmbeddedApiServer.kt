
package com.sb.backend.server

import com.sb.backend.core.BackendManager
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.services.KotakSessionCoordinator
import android.database.sqlite.SQLiteDatabase
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

    /**
     * Returns actual user tables from the currently opened database.
     * SQLite internal tables are excluded.
     */
    private fun getDatabaseTableNames(
        db: SQLiteDatabase
    ): List<String> {
        val names = mutableListOf<String>()

        db.rawQuery(
            """
            SELECT name
            FROM sqlite_master
            WHERE type = 'table'
              AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                names.add(cursor.getString(0))
            }
        }

        return names
    }

    private fun hasTable(
        db: SQLiteDatabase,
        table: String
    ): Boolean {
        return db.rawQuery(
            """
            SELECT 1
            FROM sqlite_master
            WHERE type = 'table' AND name = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(table)
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    private fun getColumnNames(
        db: SQLiteDatabase,
        table: String
    ): Set<String> {
        if (!hasTable(db, table)) return emptySet()

        val columns = mutableSetOf<String>()

        db.rawQuery(
            "PRAGMA table_info(${quoteIdentifier(table)})",
            null
        ).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")

            if (nameIndex >= 0) {
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIndex))
                }
            }
        }

        return columns
    }

    private fun quoteIdentifier(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_]+"))) {
            "Invalid SQLite identifier"
        }

        return "`$value`"
    }

    private fun countRows(
        db: SQLiteDatabase,
        table: String
    ): Long {
        if (!hasTable(db, table)) return 0L

        return db.rawQuery(
            "SELECT COUNT(*) FROM ${quoteIdentifier(table)}",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun columnExpression(
        columns: Set<String>,
        column: String,
        fallback: String = "NULL"
    ): String {
        return if (column in columns) {
            quoteIdentifier(column)
        } else {
            fallback
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

            val tableNames = getDatabaseTableNames(db)
            val tables = JSONArray()

            tableNames.forEach { table ->
                val columns = getColumnNames(db, table)
                val columnArray = JSONArray()

                columns.sorted().forEach { column ->
                    columnArray.put(column)
                }

                tables.put(
                    JSONObject()
                        .put("table", table)
                        .put("rows", countRows(db, table))
                        .put("columns", columnArray)
                )
            }

            val quoteColumns = getColumnNames(db, "quotes")
            val candleColumns = getColumnNames(db, "candles")

            val quoteCount = countRows(db, "quotes")
            val candleCount = countRows(db, "candles")

            // Candle counts grouped by timeframe.
            val timeframeCounts = JSONArray()

            if (
                "timeframe" in candleColumns &&
                hasTable(db, "candles")
            ) {
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
            }

            // Candle counts grouped by exchange, symbol and timeframe.
            val instrumentCounts = JSONArray()

            if (
                hasTable(db, "candles") &&
                setOf("exchange", "symbol", "timeframe")
                    .all { it in candleColumns }
            ) {
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
            }

            // Invalid OHLC rows.
            val invalidOhlcCount = if (
                hasTable(db, "candles") &&
                setOf("open", "high", "low", "close")
                    .all { it in candleColumns }
            ) {
                db.rawQuery(
                    """
                    SELECT COUNT(*)
                    FROM candles
                    WHERE high <= 0
                       OR low <= 0
                       OR open <= 0
                       OR close <= 0
                       OR high < low
                       OR high < open
                       OR high < close
                       OR low > open
                       OR low > close
                    """.trimIndent(),
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                }
            } else {
                0L
            }

            // Latest quote. Optional columns are schema-aware.
            val latestQuote = JSONObject()

            if (
                hasTable(db, "quotes") &&
                setOf("symbol", "exchange", "ltp", "timestamp")
                    .all { it in quoteColumns }
            ) {
                val query = """
                    SELECT
                        ${columnExpression(quoteColumns, "symbol")},
                        ${columnExpression(quoteColumns, "exchange")},
                        ${columnExpression(quoteColumns, "ltp")},
                        ${columnExpression(quoteColumns, "volume", "0")},
                        ${columnExpression(quoteColumns, "timestamp")},
                        ${columnExpression(quoteColumns, "is_live", "0")},
                        ${columnExpression(quoteColumns, "exchange_timestamp")},
                        ${columnExpression(quoteColumns, "received_at", "0")}
                    FROM quotes
                    ORDER BY timestamp DESC
                    LIMIT 1
                """.trimIndent()

                db.rawQuery(query, null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        latestQuote
                            .put(
                                "symbol",
                                if (cursor.isNull(0)) JSONObject.NULL
                                else cursor.getString(0)
                            )
                            .put(
                                "exchange",
                                if (cursor.isNull(1)) JSONObject.NULL
                                else cursor.getString(1)
                            )
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
            }

            // Latest stored candle.
            val latestCandle = JSONObject()

            if (
                hasTable(db, "candles") &&
                setOf(
                    "symbol",
                    "exchange",
                    "timeframe",
                    "timestamp",
                    "open",
                    "high",
                    "low",
                    "close",
                    "volume"
                ).all { it in candleColumns }
            ) {
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
            }

            // Raw tick and retry queue summaries.
            val rawTickCount = countRows(db, SBDatabaseHelper.TABLE_RAW_TICKS)
            val tickAuditCount = countRows(db, SBDatabaseHelper.TABLE_TICK_AUDIT)
            val quoteRetryCount =
                countRows(db, SBDatabaseHelper.TABLE_QUOTE_PERSISTENCE_RETRY)

            val summary = JSONObject()
                .put("quote_rows", quoteCount)
                .put("candle_rows", candleCount)
                .put("raw_tick_rows", rawTickCount)
                .put("tick_audit_rows", tickAuditCount)
                .put("quote_retry_rows", quoteRetryCount)
                .put("invalid_ohlc_rows", invalidOhlcCount)

            JSONObject()
                .put("status", "ok")
                .put("database", SBDatabaseHelper.DATABASE_NAME)
                .put("database_version", db.version)
                .put("read_only", true)
                .put("table_count", tableNames.size)
                .put("tables", tables)
                .put("summary", summary)
                .put("candle_timeframes", timeframeCounts)
                .put("candle_instruments", instrumentCounts)
                .put("latest_quote", latestQuote)
                .put("latest_candle", latestCandle)
                .toString()
        } catch (error: Exception) {
            JSONObject()
                .put("status", "error")
                .put(
                    "message",
                    error.message ?: "Database diagnostics failed"
                )
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

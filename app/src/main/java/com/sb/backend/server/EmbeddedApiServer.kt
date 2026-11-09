
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
import java.net.URLDecoder
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
                val requestTarget = parts.getOrNull(1) ?: "/"
                val path = requestTarget.substringBefore("?")
                val query = requestTarget.substringAfter("?", "")
                val queryParams = query.split("&").filter { it.isNotBlank() }.associate {
                    val pair = it.split("=", limit = 2)
                    URLDecoder.decode(pair[0], "UTF-8") to
                        URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                }

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

                    method == "GET" && path == "/api/database/candle-audit" ->
                        databaseCandleAudit(
                            symbol = queryParams["symbol"] ?: "",
                            exchange = queryParams["exchange"] ?: "MCX",
                            timeframe = queryParams["timeframe"] ?: "1m",
                            limit = queryParams["limit"]?.toIntOrNull() ?: 500
                        )

                    method == "GET" && path == "/api/database/capital-month-audit" ->
                        databaseCapitalMonthAudit()

                    method == "POST" && path == "/api/database/capital-month-backup" ->
                        databaseCapitalMonthBackup()

                    method == "POST" && path == "/api/database/capital-month-migrate" ->
                        databaseCapitalMonthMigrate()

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
                    path == "/api/database/candle-audit" ||
                    path == "/api/database/capital-month-audit" ||
                    path == "/api/database/capital-month-backup" ||
                    path == "/api/database/capital-month-migrate" ||
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

    /** Read-only candle integrity audit for one instrument and timeframe. */
    private fun databaseCandleAudit(symbol: String, exchange: String, timeframe: String, limit: Int): String {
        if (!BackendManager.isInitialized()) return JSONObject().put("status", "not_ready").toString()
        if (symbol.isBlank() || symbol.length > 128 || exchange.isBlank() || exchange.length > 32 || timeframe.isBlank() || timeframe.length > 16) return JSONObject().put("status", "error").put("message", "Provide symbol, exchange and timeframe").toString()
        return try {
            val db = BackendManager.database().readableDatabase
            val columns = getColumnNames(db, SBDatabaseHelper.TABLE_CANDLES)
            val required = setOf("symbol", "exchange", "timeframe", "timestamp", "open", "high", "low", "close", "volume")
            if (!required.all { it in columns }) return JSONObject().put("status", "error").put("message", "Candles table schema is incomplete").toString()
            val safeLimit = limit.coerceIn(1, 2000)
            // Keep timeframe case-sensitive: "1m" is one minute; "1M" is one month.
            val interval = when (timeframe.trim()) {
                "1m" -> 60_000L
                "2m" -> 120_000L
                "3m" -> 180_000L
                "5m" -> 300_000L
                "10m" -> 600_000L
                "15m" -> 900_000L
                "30m" -> 1_800_000L
                "1h" -> 3_600_000L
                "2h" -> 7_200_000L
                "4h" -> 14_400_000L
                "1D" -> 86_400_000L
                else -> 0L // Calendar periods are not fixed-duration intervals.
            }
            val rows = JSONArray()
            val stamps = ArrayList<Long>()
            var invalidOhlc = 0
            var invalidTimestamp = 0
            db.rawQuery("SELECT timestamp, open, high, low, close, volume FROM `candles` WHERE symbol = ? AND exchange = ? AND timeframe = ? ORDER BY timestamp DESC LIMIT ?", arrayOf(symbol, exchange, timeframe, safeLimit.toString())).use { c ->
                val batch = ArrayList<JSONObject>()
                while (c.moveToNext()) {
                    val ts = c.getLong(0); val o = c.getDouble(1); val h = c.getDouble(2); val l = c.getDouble(3); val cl = c.getDouble(4); val v = c.getLong(5)
                    val valid = ts > 0L && o.isFinite() && h.isFinite() && l.isFinite() && cl.isFinite() && o > 0 && h >= maxOf(o, cl, l) && l <= minOf(o, cl, h) && v >= 0
                    if (!valid) invalidOhlc++
                    if (ts <= 0L || ts < 1_000_000_000_000L) invalidTimestamp++
                    batch.add(JSONObject().put("timestamp", ts).put("open", o).put("high", h).put("low", l).put("close", cl).put("volume", v).put("ohlc_valid", valid))
                    stamps.add(ts)
                }
                batch.reverse(); stamps.reverse(); batch.forEach { rows.put(it) }
            }
            var gaps = 0L; var largestGap = 0L; var nonIncreasing = 0
            for (i in 1 until stamps.size) {
                val delta = stamps[i] - stamps[i - 1]
                if (delta <= 0L) nonIncreasing++
                if (delta > largestGap) largestGap = delta
                if (interval > 0L && delta > interval * 3 / 2) gaps += ((delta / interval) - 1L).coerceAtLeast(1L)
            }
            val total = db.rawQuery("SELECT COUNT(*) FROM `candles` WHERE symbol = ? AND exchange = ? AND timeframe = ?", arrayOf(symbol, exchange, timeframe)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            val duplicateGroups = db.rawQuery("SELECT COUNT(*) FROM (SELECT timestamp FROM `candles` WHERE symbol = ? AND exchange = ? AND timeframe = ? GROUP BY timestamp HAVING COUNT(*) > 1)", arrayOf(symbol, exchange, timeframe)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            JSONObject().put("status", "ok").put("read_only", true).put("symbol", symbol).put("exchange", exchange).put("timeframe", timeframe).put("returned_rows", rows.length()).put("total_rows", total).put("checks", JSONObject().put("invalid_ohlc_rows_in_sample", invalidOhlc).put("invalid_timestamp_rows_in_sample", invalidTimestamp).put("duplicate_timestamp_groups", duplicateGroups).put("non_increasing_timestamp_steps_in_sample", nonIncreasing).put("estimated_missing_intervals_in_sample", gaps).put("largest_timestamp_gap_ms", largestGap).put("expected_interval_ms", interval)).put("candles", rows).toString()
        } catch (error: Exception) { JSONObject().put("status", "error").put("message", error.message ?: "Candle audit failed").toString() }
    }

    /**
     * Read-only classification of uppercase 1M rows before any backup or move.
     * A group is a minute-data candidate only when it has at least 3 rows and
     * at least 70% of adjacent intervals are <= 10 minutes.
     */
    private fun databaseCapitalMonthAudit(): String {
        if (!BackendManager.isInitialized()) {
            return JSONObject().put("status", "not_ready").toString()
        }
        return try {
            val db = BackendManager.database().readableDatabase
            val groups = JSONArray()
            var totalRows = 0L
            var candidateRows = 0L
            var trueMonthRows = 0L
            db.rawQuery(
                """
                SELECT exchange, symbol, timestamp, open, high, low, close, volume
                FROM candles
                WHERE timeframe = '1M'
                ORDER BY exchange, symbol, timestamp
                """.trimIndent(),
                null
            ).use { cursor ->
                var key = ""
                var exchange = ""
                var symbol = ""
                var rows = 0L
                var previous = 0L
                var shortSteps = 0
                var steps = 0
                var first = 0L
                var last = 0L
                var invalid = 0
                fun flush() {
                    if (rows == 0L) return
                    val ratio = if (steps == 0) 0.0 else shortSteps.toDouble() / steps
                    val minuteCandidate = rows >= 3 && ratio >= 0.70
                    if (minuteCandidate) candidateRows += rows else trueMonthRows += rows
                    groups.put(
                        JSONObject()
                            .put("exchange", exchange)
                            .put("symbol", symbol)
                            .put("rows", rows)
                            .put("first_timestamp", first)
                            .put("last_timestamp", last)
                            .put("short_interval_steps", shortSteps)
                            .put("interval_steps", steps)
                            .put("short_interval_ratio", ratio)
                            .put("invalid_ohlc_rows", invalid)
                            .put("classification", if (minuteCandidate) "minute_candidate" else "needs_manual_review_or_monthly")
                    )
                }
                while (cursor.moveToNext()) {
                    val nextExchange = cursor.getString(0) ?: ""
                    val nextSymbol = cursor.getString(1) ?: ""
                    val nextKey = "$nextExchange|$nextSymbol"
                    if (key.isNotEmpty() && key != nextKey) {
                        flush()
                        rows = 0L; previous = 0L; shortSteps = 0; steps = 0; invalid = 0
                    }
                    if (key != nextKey) {
                        key = nextKey
                        exchange = nextExchange
                        symbol = nextSymbol
                    }
                    val ts = cursor.getLong(2)
                    val o = cursor.getDouble(3)
                    val h = cursor.getDouble(4)
                    val l = cursor.getDouble(5)
                    val cl = cursor.getDouble(6)
                    val volume = cursor.getLong(7)
                    if (ts <= 0L || o <= 0.0 || h < maxOf(o, cl, l) ||
                        l > minOf(o, cl, h) || cl <= 0.0 || volume < 0L
                    ) invalid++
                    if (rows == 0L) first = ts
                    if (previous > 0L && ts > previous) {
                        steps++
                        if (ts - previous <= 600_000L) shortSteps++
                    }
                    previous = ts
                    last = ts
                    rows++
                    totalRows++
                }
                flush()
            }
            JSONObject()
                .put("status", "ok")
                .put("read_only", true)
                .put("timeframe", "1M")
                .put("total_uppercase_1M_rows", totalRows)
                .put("minute_candidate_rows", candidateRows)
                .put("monthly_or_review_rows", trueMonthRows)
                .put("groups", groups)
                .put("note", "No records were changed or deleted. Candidate groups require backup and a separate migration step.")
                .toString()
        } catch (error: Exception) {
            JSONObject().put("status", "error")
                .put("message", error.message ?: "Uppercase 1M audit failed")
                .toString()
        }
    }


    /**
     * Creates a durable, idempotent backup of all uppercase 1M candle rows.
     * The source table is never modified by this operation.
     */
    private fun databaseCapitalMonthBackup(): String {
        if (!BackendManager.isInitialized()) {
            return JSONObject().put("status", "not_ready").toString()
        }
        return try {
            val db = BackendManager.database().writableDatabase
            val required = setOf(
                "symbol", "exchange", "timeframe", "timestamp",
                "open", "high", "low", "close", "volume"
            )
            val columns = getColumnNames(db, SBDatabaseHelper.TABLE_CANDLES)
            if (!required.all { it in columns }) {
                return JSONObject().put("status", "error")
                    .put("message", "Candles table schema is incomplete")
                    .toString()
            }
            db.beginTransaction()
            try {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS candles_1m_recovery_backup
                    AS SELECT * FROM candles WHERE 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                        idx_candles_1m_recovery_backup_key
                    ON candles_1m_recovery_backup(
                        symbol, exchange, timeframe, timestamp
                    )
                    """.trimIndent()
                )
                val before = db.rawQuery(
                    "SELECT COUNT(*) FROM candles_1m_recovery_backup",
                    null
                ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO candles_1m_recovery_backup
                    SELECT * FROM candles WHERE timeframe = '1M'
                    """.trimIndent()
                )
                val after = db.rawQuery(
                    "SELECT COUNT(*) FROM candles_1m_recovery_backup",
                    null
                ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                val source = db.rawQuery(
                    "SELECT COUNT(*) FROM candles WHERE timeframe = '1M'",
                    null
                ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                db.setTransactionSuccessful()
                JSONObject()
                    .put("status", "ok")
                    .put("backup_table", "candles_1m_recovery_backup")
                    .put("source_uppercase_1M_rows", source)
                    .put("backup_rows", after)
                    .put("newly_backed_up_rows", after - before)
                    .put("source_rows_preserved", true)
                    .put("source_deleted", false)
                    .toString()
            } finally {
                db.endTransaction()
            }
        } catch (error: Exception) {
            JSONObject().put("status", "error")
                .put("message", error.message ?: "Uppercase 1M backup failed")
                .toString()
        }
    }


    /**
     * Recover minute-like records from uppercase 1M after a complete backup.
     * Existing 1m rows win on timestamp conflicts. All remaining uppercase
     * 1M rows are removed only after every candidate timestamp is present in
     * 1m and the complete source set is confirmed in the backup table.
     */
    private fun databaseCapitalMonthMigrate(): String {
        if (!BackendManager.isInitialized()) {
            return JSONObject().put("status", "not_ready").toString()
        }
        return try {
            val db = BackendManager.database().writableDatabase
            val sourceCount = db.rawQuery(
                "SELECT COUNT(*) FROM candles WHERE timeframe = '1M'",
                null
            ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (sourceCount == 0L) {
                return JSONObject().put("status", "ok")
                    .put("source_uppercase_1M_rows", 0)
                    .put("recovered_into_1m_rows", 0)
                    .put("deleted_remaining_1M_rows", 0)
                    .toString()
            }
            if (!hasTable(db, "candles_1m_recovery_backup")) {
                return JSONObject().put("status", "blocked")
                    .put("message", "Run the uppercase 1M backup endpoint first")
                    .toString()
            }
            val backupCount = db.rawQuery(
                "SELECT COUNT(*) FROM candles_1m_recovery_backup WHERE timeframe = '1M'",
                null
            ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (backupCount < sourceCount) {
                return JSONObject().put("status", "blocked")
                    .put("message", "Backup is incomplete; no source rows were changed")
                    .put("source_rows", sourceCount)
                    .put("backup_rows", backupCount)
                    .toString()
            }

            data class Candidate(val exchange: String, val symbol: String, val rows: Long)
            val candidates = ArrayList<Candidate>()
            db.rawQuery(
                """
                SELECT exchange, symbol, SUM(is_valid) AS rows,
                       SUM(CASE WHEN delta_ms <= 600000 THEN 1 ELSE 0 END) AS short_steps,
                       COUNT(delta_ms) AS steps
                FROM (
                    SELECT exchange, symbol, timestamp,
                           CASE WHEN timestamp > 0
                                  AND open > 0 AND high >= open
                                  AND high >= close AND high >= low
                                  AND low <= open AND low <= close
                                  AND close > 0 AND volume >= 0
                                THEN 1 ELSE 0 END AS is_valid,
                           timestamp - LAG(timestamp) OVER (
                               PARTITION BY exchange, symbol ORDER BY timestamp
                           ) AS delta_ms
                    FROM candles
                    WHERE timeframe = '1M'
                )
                GROUP BY exchange, symbol
                HAVING COUNT(*) >= 3
                   AND SUM(is_valid) > 0
                   AND CAST(SUM(CASE WHEN delta_ms <= 600000 THEN 1 ELSE 0 END) AS REAL)
                       / NULLIF(COUNT(delta_ms), 0) >= 0.70
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    candidates.add(
                        Candidate(
                            exchange = cursor.getString(0),
                            symbol = cursor.getString(1),
                            rows = cursor.getLong(2)
                        )
                    )
                }
            }

            var recovered = 0L
            var verified = 0L
            var deleted = 0
            db.beginTransaction()
            try {
                candidates.forEach { candidate ->
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO candles(
                            symbol, exchange, timeframe, timestamp,
                            open, high, low, close, volume
                        )
                        SELECT symbol, exchange, '1m', timestamp,
                               open, high, low, close, volume
                        FROM candles
                        WHERE timeframe = '1M'
                          AND exchange = ? AND symbol = ?
                          AND timestamp > 0
                          AND open > 0 AND high >= open
                          AND high >= close AND high >= low
                          AND low <= open AND low <= close
                          AND close > 0 AND volume >= 0
                        """.trimIndent(),
                        arrayOf(candidate.exchange, candidate.symbol)
                    )
                    recovered += db.rawQuery(
                        """
                        SELECT COUNT(*)
                        FROM candles source
                        WHERE source.timeframe = '1M'
                          AND source.exchange = ? AND source.symbol = ?
                          AND source.timestamp > 0
                          AND source.open > 0 AND source.high >= source.open
                          AND source.high >= source.close AND source.high >= source.low
                          AND source.low <= source.open AND source.low <= source.close
                          AND source.close > 0 AND source.volume >= 0
                          AND EXISTS (
                              SELECT 1 FROM candles target
                              WHERE target.timeframe = '1m'
                                AND target.exchange = source.exchange
                                AND target.symbol = source.symbol
                                AND target.timestamp = source.timestamp
                          )
                        """.trimIndent(),
                        arrayOf(candidate.exchange, candidate.symbol)
                    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                }
                val expected = candidates.sumOf { it.rows }
                check(recovered == expected) {
                    "Recovery verification failed: verified=$recovered expected=$expected"
                }
                verified = recovered

                // User-requested cleanup: backup is complete and all minute
                // candidates are verified in 1m before remaining 1M is cleared.
                deleted = db.delete("candles", "timeframe = ?", arrayOf("1M"))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            JSONObject()
                .put("status", "ok")
                .put("source_uppercase_1M_rows", sourceCount)
                .put("backup_rows", backupCount)
                .put("minute_candidate_groups", candidates.size)
                .put("recovered_into_1m_rows", recovered)
                .put("verified_candidate_rows", verified)
                .put("deleted_remaining_1M_rows", deleted)
                .put("backup_preserved", true)
                .toString()
        } catch (error: Exception) {
            JSONObject().put("status", "error")
                .put("message", error.message ?: "Uppercase 1M recovery failed")
                .put("source_preserved_on_failure", true)
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

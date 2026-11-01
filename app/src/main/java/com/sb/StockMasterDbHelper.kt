package com.sb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class StockMasterDbHelper(context: Context) :
    SQLiteOpenHelper(context, "sb_stock_master.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stock_master (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                name TEXT NOT NULL,
                isin TEXT,
                instrument_type TEXT NOT NULL DEFAULT 'EQUITY',
                UNIQUE(exchange, symbol)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_stock_symbol ON stock_master(symbol)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_stock_name ON stock_master(name)"
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
    }

    /**
     * CSV header must contain:
     * exchange,symbol,name,isin,instrument_type
     *
     * Returns number of rows imported or updated.
     */
    fun importCsv(context: Context, assetName: String = "stock_master.csv"): Int {
        val db = writableDatabase
        var imported = 0

        context.assets.open(assetName).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                val headerLine = reader.readLine()
                    ?: throw IllegalArgumentException("Stock CSV is empty.")

                val headers = parseCsvLine(headerLine)
                    .map { it.trim().removePrefix("\uFEFF").toLowerCase(Locale.ROOT) }

                val exchangeIndex = headers.indexOf("exchange")
                val symbolIndex = headers.indexOf("symbol")
                val nameIndex = headers.indexOf("name")
                val isinIndex = headers.indexOf("isin")
                val typeIndex = headers.indexOf("instrument_type")

                require(exchangeIndex >= 0 && symbolIndex >= 0 && nameIndex >= 0) {
                    "CSV must include exchange, symbol and name columns."
                }

                db.beginTransaction()
                try {
                    var line: String?
                    while (true) {
                        line = reader.readLine() ?: break
                        val raw = line
                        if (raw.isBlank()) continue

                        val fields = parseCsvLine(raw)
                        fun field(index: Int): String =
                            if (index >= 0 && index < fields.size) {
                                fields[index].trim()
                            } else ""

                        val exchange = field(exchangeIndex)
                            .uppercaseCompat()
                        val symbol = field(symbolIndex)
                            .uppercaseCompat()
                        val name = field(nameIndex)
                        val isin = field(isinIndex).ifBlank { null }
                        val type = field(typeIndex)
                            .ifBlank { "EQUITY" }
                            .uppercaseCompat()

                        if (exchange !in setOf("NSE", "BSE") ||
                            symbol.isBlank() || name.isBlank()
                        ) {
                            continue
                        }

                        val values = ContentValues().apply {
                            put("exchange", exchange)
                            put("symbol", symbol)
                            put("name", name)
                            if (isin == null) putNull("isin") else put("isin", isin)
                            put("instrument_type", type)
                        }

                        db.insertWithOnConflict(
                            "stock_master",
                            null,
                            values,
                            SQLiteDatabase.CONFLICT_REPLACE
                        )
                        imported++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
        return imported
    }

    fun search(query: String, limit: Int = 100): List<Stock> {
        val text = query.trim()
        val result = mutableListOf<Stock>()
        if (text.isBlank()) return result

        val safeLimit = limit.coerceIn(1, 500)
        val pattern = "%${text.replace("%", "\\%").replace("_", "\\_")}%"

        readableDatabase.rawQuery(
            """
            SELECT id, exchange, symbol, name, isin, instrument_type
            FROM stock_master
            WHERE symbol LIKE ? ESCAPE '\'
               OR name LIKE ? ESCAPE '\'
               OR isin LIKE ? ESCAPE '\'
            ORDER BY
                CASE WHEN symbol = ? THEN 0
                     WHEN symbol LIKE ? ESCAPE '\' THEN 1
                     ELSE 2 END,
                name COLLATE NOCASE
            LIMIT $safeLimit
            """.trimIndent(),
            arrayOf(
                pattern, pattern, pattern,
                text.uppercaseCompat(), "${text.uppercaseCompat()}%"
            )
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    Stock(
                        id = cursor.getLong(0),
                        exchange = cursor.getString(1),
                        symbol = cursor.getString(2),
                        name = cursor.getString(3),
                        isin = if (cursor.isNull(4)) null else cursor.getString(4),
                        instrumentType = cursor.getString(5)
                    )
                )
            }
        }
        return result
    }

    fun getStockCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM stock_master", null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun getStocksByExchange(
        exchange: String,
        limit: Int = 500
    ): List<Stock> {
        val result = mutableListOf<Stock>()
        val safeExchange = exchange.trim().uppercaseCompat()

        if (safeExchange !in setOf("NSE", "BSE")) return result

        val safeLimit = limit.coerceIn(1, 2000)

        readableDatabase.rawQuery(
            """
            SELECT id, exchange, symbol, name, isin, instrument_type
            FROM stock_master
            WHERE exchange = ?
            ORDER BY symbol COLLATE NOCASE
            LIMIT $safeLimit
            """.trimIndent(),
            arrayOf(safeExchange)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    Stock(
                        id = cursor.getLong(0),
                        exchange = cursor.getString(1),
                        symbol = cursor.getString(2),
                        name = cursor.getString(3),
                        isin = if (cursor.isNull(4)) null else cursor.getString(4),
                        instrumentType = cursor.getString(5)
                    )
                )
            }
        }

        return result
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length &&
                    line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    values.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        values.add(current.toString())
        return values
    }

    private fun String.uppercaseCompat(): String =
        toUpperCase(Locale.ROOT)
}
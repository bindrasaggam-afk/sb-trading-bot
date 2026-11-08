
package com.sb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class StockMasterDbHelper(context: Context) :
    SQLiteOpenHelper(context, "sb_stock_master.db", null, 2) {

    companion object {
        private const val DATABASE_VERSION = 2
        private const val TABLE_STOCK_MASTER = "stock_master"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            createSchema(db)
            ensureColumn(db, "isin", "TEXT")
            ensureColumn(
                db,
                "instrument_type",
                "TEXT NOT NULL DEFAULT 'EQUITY'"
            )
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
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

        ensureColumn(db, "isin", "TEXT")
        ensureColumn(
            db,
            "instrument_type",
            "TEXT NOT NULL DEFAULT 'EQUITY'"
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_stock_symbol ON stock_master(symbol)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_stock_name ON stock_master(name)"
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_stock_exchange_symbol
            ON stock_master(exchange, symbol)
            """.trimIndent()
        )
    }

    private fun ensureColumn(
        db: SQLiteDatabase,
        columnName: String,
        columnDefinition: String
    ) {
        var exists = false

        db.rawQuery(
            "PRAGMA table_info(stock_master)",
            null
        ).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")

            while (cursor.moveToNext()) {
                if (nameIndex >= 0 &&
                    cursor.getString(nameIndex).equals(
                        columnName,
                        ignoreCase = true
                    )
                ) {
                    exists = true
                    break
                }
            }
        }

        if (!exists) {
            db.execSQL(
                "ALTER TABLE stock_master ADD COLUMN $columnName $columnDefinition"
            )
        }
    }

    /**
     * CSV required headers:
     * exchange,symbol,name
     *
     * Optional headers:
     * isin,instrument_type
     *
     * Returns successfully inserted or updated rows.
     * Invalid non-empty rows cause the entire import to roll back.
     */
    fun importCsv(
        context: Context,
        assetName: String = "stock_master.csv"
    ): Int {
        val db = writableDatabase
        var imported = 0
        var lineNumber = 1

        context.assets.open(assetName).use { input ->
            BufferedReader(
                InputStreamReader(input, Charsets.UTF_8)
            ).use { reader ->

                val headerLine = reader.readLine()
                    ?: throw IllegalArgumentException(
                        "Stock CSV is empty."
                    )

                val headers = parseCsvLine(headerLine)
                    .map {
                        it.trim()
                            .removePrefix("\uFEFF")
                            .toLowerCase(Locale.ROOT)
                    }

                require(headers.distinct().size == headers.size) {
                    "CSV contains duplicate column headers."
                }

                val exchangeIndex = headers.indexOf("exchange")
                val symbolIndex = headers.indexOf("symbol")
                val nameIndex = headers.indexOf("name")
                val isinIndex = headers.indexOf("isin")
                val typeIndex = headers.indexOf("instrument_type")

                require(
                    exchangeIndex >= 0 &&
                    symbolIndex >= 0 &&
                    nameIndex >= 0
                ) {
                    "CSV must include exchange, symbol and name columns."
                }

                db.beginTransaction()

                try {
                    while (true) {
                        val raw = reader.readLine() ?: break
                        lineNumber++

                        if (raw.isBlank()) continue

                        val fields = try {
                            parseCsvLine(raw)
                        } catch (e: Exception) {
                            throw IllegalArgumentException(
                                "Invalid CSV at line $lineNumber: ${e.message}",
                                e
                            )
                        }

                        fun field(index: Int): String {
                            return if (
                                index >= 0 && index < fields.size
                            ) {
                                fields[index].trim()
                            } else {
                                ""
                            }
                        }

                        val exchange = field(exchangeIndex)
                            .uppercaseCompat()

                        val symbol = field(symbolIndex)
                            .uppercaseCompat()

                        val name = field(nameIndex)

                        val isin = field(isinIndex)
                            .ifBlank { null }

                        val type = field(typeIndex)
                            .ifBlank { "EQUITY" }
                            .uppercaseCompat()

                        require(exchange == "NSE" || exchange == "BSE") {
                            "Unsupported exchange '$exchange' at CSV line $lineNumber."
                        }

                        require(symbol.isNotBlank()) {
                            "Empty symbol at CSV line $lineNumber."
                        }

                        require(name.isNotBlank()) {
                            "Empty name at CSV line $lineNumber."
                        }

                        require(fields.size <= headers.size) {
                            "Too many CSV columns at line $lineNumber."
                        }

                        val values = ContentValues().apply {
                            put("exchange", exchange)
                            put("symbol", symbol)
                            put("name", name)

                            if (isin == null) {
                                putNull("isin")
                            } else {
                                put("isin", isin)
                            }

                            put("instrument_type", type)
                        }

                        // Update existing row first.
                        // This preserves the existing primary key ID.
                        val updated = db.update(
                            TABLE_STOCK_MASTER,
                            values,
                            "exchange = ? AND symbol = ?",
                            arrayOf(exchange, symbol)
                        )

                        if (updated > 0) {
                            imported += updated
                        } else {
                            // Insert only when the stock does not exist.
                            db.insertOrThrow(
                                TABLE_STOCK_MASTER,
                                null,
                                values
                            )
                            imported++
                        }
                    }

                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        return imported
    }

    fun search(
        query: String,
        limit: Int = 100
    ): List<Stock> {
        val text = query.trim()
        val result = mutableListOf<Stock>()

        if (text.isBlank()) return result

        val safeLimit = limit.coerceIn(1, 500)
        val pattern = "%${
            text.replace("%", "\\%")
                .replace("_", "\\_")
        }%"

        readableDatabase.rawQuery(
            """
            SELECT id, exchange, symbol, name, isin, instrument_type
            FROM stock_master
            WHERE symbol LIKE ? ESCAPE '\'
               OR name LIKE ? ESCAPE '\'
               OR isin LIKE ? ESCAPE '\'
            ORDER BY
                CASE
                    WHEN symbol = ? THEN 0
                    WHEN symbol LIKE ? ESCAPE '\' THEN 1
                    ELSE 2
                END,
                name COLLATE NOCASE
            LIMIT $safeLimit
            """.trimIndent(),
            arrayOf(
                pattern,
                pattern,
                pattern,
                text.uppercaseCompat(),
                "${text.uppercaseCompat()}%"
            )
        ).use { cursor ->

            while (cursor.moveToNext()) {
                result.add(
                    Stock(
                        id = cursor.getLong(0),
                        exchange = cursor.getString(1),
                        symbol = cursor.getString(2),
                        name = cursor.getString(3),
                        isin = if (cursor.isNull(4)) {
                            null
                        } else {
                            cursor.getString(4)
                        },
                        instrumentType = cursor.getString(5)
                    )
                )
            }
        }

        return result
    }

    fun getStockCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM stock_master",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getInt(0)
            } else {
                0
            }
        }
    }

    fun getStocksByExchange(
        exchange: String,
        limit: Int = 500
    ): List<Stock> {
        val result = mutableListOf<Stock>()
        val safeExchange = exchange.trim().uppercaseCompat()

        if (safeExchange !in setOf("NSE", "BSE")) {
            return result
        }

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
                        isin = if (cursor.isNull(4)) {
                            null
                        } else {
                            cursor.getString(4)
                        },
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
                char == '"' &&
                    inQuotes &&
                    index + 1 < line.length &&
                    line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }

                char == '"' -> {
                    inQuotes = !inQuotes
                }

                char == ',' && !inQuotes -> {
                    values.add(current.toString())
                    current.setLength(0)
                }

                else -> {
                    current.append(char)
                }
            }

            index++
        }

        require(!inQuotes) {
            "Unclosed quotation mark."
        }

        values.add(current.toString())
        return values
    }

    private fun String.uppercaseCompat(): String {
        return toUpperCase(Locale.ROOT)
    }
}

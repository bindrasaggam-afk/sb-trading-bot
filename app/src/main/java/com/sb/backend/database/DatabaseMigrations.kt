
package com.sb.backend.database

import android.database.sqlite.SQLiteDatabase

object DatabaseMigrations {

    private const val TABLE_INSTRUMENTS =
        SBDatabaseHelper.TABLE_INSTRUMENTS
    private const val TABLE_QUOTES =
        SBDatabaseHelper.TABLE_QUOTES
    private const val TABLE_CANDLES =
        SBDatabaseHelper.TABLE_CANDLES
    private const val TABLE_RAW_TICKS =
        SBDatabaseHelper.TABLE_RAW_TICKS
    private const val TABLE_TICK_AUDIT =
        SBDatabaseHelper.TABLE_TICK_AUDIT
    private const val TABLE_QUOTE_RETRY =
        SBDatabaseHelper.TABLE_QUOTE_PERSISTENCE_RETRY

    fun createAll(db: SQLiteDatabase) {

        // ---------------------------------------------------------
        // 1. INSTRUMENTS
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$TABLE_INSTRUMENTS` (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                segment TEXT NOT NULL,
                instrument_type TEXT NOT NULL DEFAULT 'EQ',
                trading_symbol TEXT NOT NULL,
                token TEXT,
                lot_size INTEGER NOT NULL DEFAULT 1,
                tick_size REAL NOT NULL DEFAULT 0.05,
                is_active INTEGER NOT NULL DEFAULT 1,
                expiry_millis INTEGER,
                UNIQUE(exchange, symbol)
            )
            """.trimIndent()
        )

        ensureColumn(
            db, TABLE_INSTRUMENTS,
            "expiry_millis", "INTEGER"
        )

        // ---------------------------------------------------------
        // 2. QUOTES
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$TABLE_QUOTES` (
                symbol TEXT NOT NULL,
                exchange TEXT NOT NULL,
                ltp REAL NOT NULL,
                open REAL,
                high REAL,
                low REAL,
                previous_close REAL,
                volume INTEGER NOT NULL DEFAULT 0,
                change REAL,
                change_percent REAL,
                timestamp INTEGER NOT NULL,
                is_live INTEGER NOT NULL DEFAULT 0,
                exchange_timestamp INTEGER,
                received_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(symbol, exchange)
            )
            """.trimIndent()
        )

        // पुराने Quotes database को भी compatible रखें।
        ensureColumn(db, TABLE_QUOTES, "exchange_timestamp", "INTEGER")
        ensureColumn(
            db, TABLE_QUOTES,
            "received_at", "INTEGER NOT NULL DEFAULT 0"
        )

        db.execSQL(
            """
            UPDATE `$TABLE_QUOTES`
            SET received_at = timestamp
            WHERE received_at = 0 AND timestamp > 0
            """.trimIndent()
        )

        // ---------------------------------------------------------
        // 3. CANDLES
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$TABLE_CANDLES` (
                symbol TEXT NOT NULL,
                exchange TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                open REAL NOT NULL,
                high REAL NOT NULL,
                low REAL NOT NULL,
                close REAL NOT NULL,
                volume INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(
                    symbol,
                    exchange,
                    timeframe,
                    timestamp
                )
            )
            """.trimIndent()
        )

        // Candle completeness metadata.
        ensureColumn(
            db, TABLE_CANDLES,
            "status", "TEXT NOT NULL DEFAULT 'FINAL'"
        )
        ensureColumn(
            db, TABLE_CANDLES,
            "source_tick_count", "INTEGER NOT NULL DEFAULT 0"
        )
        ensureColumn(
            db, TABLE_CANDLES,
            "first_tick_timestamp", "INTEGER"
        )
        ensureColumn(
            db, TABLE_CANDLES,
            "last_tick_timestamp", "INTEGER"
        )
        ensureColumn(
            db, TABLE_CANDLES,
            "finalized_at", "INTEGER"
        )

        // ---------------------------------------------------------
        // 4. RAW TICKS
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$TABLE_RAW_TICKS` (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                ltp REAL NOT NULL CHECK (ltp > 0),
                volume INTEGER NOT NULL DEFAULT 0
                    CHECK (volume >= 0),
                last_trade_qty INTEGER NOT NULL DEFAULT 0
                    CHECK (last_trade_qty >= 0),
                last_trade_time INTEGER,
                exchange_time INTEGER,
                received_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // ---------------------------------------------------------
        // 5. TICK AUDIT
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$TABLE_TICK_AUDIT` (
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                received INTEGER NOT NULL DEFAULT 0,
                parsed INTEGER NOT NULL DEFAULT 0,
                mapped INTEGER NOT NULL DEFAULT 0,
                rejected INTEGER NOT NULL DEFAULT 0,
                raw_saved INTEGER NOT NULL DEFAULT 0,
                quote_saved INTEGER NOT NULL DEFAULT 0,
                save_failed INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(exchange, symbol)
            )
            """.trimIndent()
        )

        // ---------------------------------------------------------
        // 6. QUOTE PERSISTENCE RETRY
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$TABLE_QUOTE_RETRY` (
                symbol TEXT NOT NULL,
                exchange TEXT NOT NULL,
                ltp REAL NOT NULL,
                open REAL,
                high REAL,
                low REAL,
                previous_close REAL,
                volume INTEGER NOT NULL DEFAULT 0,
                change REAL,
                change_percent REAL,
                timestamp INTEGER NOT NULL,
                is_live INTEGER NOT NULL DEFAULT 0,
                exchange_timestamp INTEGER,
                received_at INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                queued_at INTEGER NOT NULL,
                PRIMARY KEY(symbol, exchange)
            )
            """.trimIndent()
        )

        // ---------------------------------------------------------
        // 7. SIGNALS
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS signals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                exchange TEXT NOT NULL,
                trading_style TEXT NOT NULL,
                signal_type TEXT NOT NULL,
                entry_price REAL,
                stop_loss REAL,
                target1 REAL,
                target2 REAL,
                target3 REAL,
                confidence REAL NOT NULL DEFAULT 0,
                timeframe TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                reason TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                expires_at INTEGER,
                confirmation_count INTEGER NOT NULL DEFAULT 0,
                confirmation_required INTEGER NOT NULL DEFAULT 2,
                is_stable INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // ---------------------------------------------------------
        // 8. PAPER ACCOUNT
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS paper_account (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                starting_balance REAL NOT NULL,
                balance REAL NOT NULL,
                reserved_margin REAL NOT NULL DEFAULT 0,
                realized_pnl REAL NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // ---------------------------------------------------------
        // 9. PAPER ORDERS
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS paper_orders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                exchange TEXT NOT NULL,
                side TEXT NOT NULL,
                quantity INTEGER NOT NULL CHECK (quantity > 0),
                entry_price REAL NOT NULL CHECK (entry_price > 0),
                exit_price REAL,
                realized_pnl REAL,
                reserved_margin REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'OPEN',
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // ---------------------------------------------------------
        // 10. POSITIONS
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS positions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                order_id INTEGER NOT NULL,
                symbol TEXT NOT NULL,
                exchange TEXT NOT NULL,
                side TEXT NOT NULL,
                quantity INTEGER NOT NULL CHECK (quantity > 0),
                entry_price REAL NOT NULL CHECK (entry_price > 0),
                last_price REAL NOT NULL,
                reserved_margin REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'OPEN',
                opened_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(order_id)
            )
            """.trimIndent()
        )

        // ---------------------------------------------------------
        // 11. DEVICE IDENTITY
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS device_identity (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                device_id TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // ---------------------------------------------------------
        // 12. APP STATE
        // ---------------------------------------------------------

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_state (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // ---------------------------------------------------------
        // 13. INDEXES
        // ---------------------------------------------------------

        createIndex(
            db,
            "idx_instruments_symbol",
            TABLE_INSTRUMENTS,
            "symbol"
        )

        createIndex(
            db,
            "idx_instruments_name",
            TABLE_INSTRUMENTS,
            "name"
        )

        createIndex(
            db,
            "idx_instruments_segment",
            TABLE_INSTRUMENTS,
            "segment"
        )

        createIndex(
            db,
            "idx_instruments_expiry",
            TABLE_INSTRUMENTS,
            "exchange, segment, expiry_millis"
        )

        createIndex(
            db,
            "idx_instruments_exchange_token",
            TABLE_INSTRUMENTS,
            "exchange, token"
        )

        createIndex(
            db,
            "idx_instruments_exchange_segment_symbol",
            TABLE_INSTRUMENTS,
            "exchange, segment, symbol"
        )

        createIndex(
            db,
            "idx_candles_lookup",
            TABLE_CANDLES,
            "symbol, timeframe, timestamp"
        )

        createIndex(
            db,
            "idx_raw_ticks_instrument_time",
            TABLE_RAW_TICKS,
            "exchange, symbol, received_at"
        )

        createIndex(
            db,
            "idx_raw_ticks_received",
            TABLE_RAW_TICKS,
            "received_at"
        )

        createIndex(
            db,
            "idx_quote_retry_queued",
            TABLE_QUOTE_RETRY,
            "queued_at"
        )

        createIndex(
            db,
            "idx_signals_status",
            "signals",
            "status, created_at"
        )

        createIndex(
            db,
            "idx_signals_symbol",
            "signals",
            "symbol, created_at"
        )

        createIndex(
            db,
            "idx_orders_status",
            "paper_orders",
            "status, created_at"
        )

        createIndex(
            db,
            "idx_positions_status",
            "positions",
            "status"
        )

        // ---------------------------------------------------------
        // 14. CANDLE VALIDATION TRIGGERS
        // ---------------------------------------------------------

        createCandleValidationTriggers(db)
    }

    // -------------------------------------------------------------
    // DATABASE UPGRADE
    // -------------------------------------------------------------

    fun upgrade(db: SQLiteDatabase, oldVersion: Int) {

        // Idempotent schema creation and missing-column repair.
        // Existing rows are preserved.
        createAll(db)

        if (oldVersion < 3) {
            db.execSQL(
                """
                UPDATE `$TABLE_QUOTES`
                SET received_at = timestamp
                WHERE received_at = 0 AND timestamp > 0
                """.trimIndent()
            )
        }
    }

    // -------------------------------------------------------------
    // SAFE COLUMN MIGRATION
    // -------------------------------------------------------------

    private fun ensureColumn(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String
    ) {
        requireValidIdentifier(table)
        requireValidIdentifier(column)

        if (columnExists(db, table, column)) return

        db.execSQL(
            "ALTER TABLE `$table` ADD COLUMN `$column` $definition"
        )
    }

    private fun columnExists(
        db: SQLiteDatabase,
        table: String,
        column: String
    ): Boolean {
        requireValidIdentifier(table)

        db.rawQuery(
            "PRAGMA table_info(`$table`)",
            null
        ).use { cursor ->

            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return false

            while (cursor.moveToNext()) {
                if (
                    cursor.getString(nameIndex)
                        .equals(column, ignoreCase = true)
                ) {
                    return true
                }
            }
        }

        return false
    }

    private fun requireValidIdentifier(value: String) {
        require(
            value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
        ) {
            "Invalid SQLite identifier: $value"
        }
    }

    // -------------------------------------------------------------
    // SAFE INDEX CREATION
    // -------------------------------------------------------------

    private fun createIndex(
        db: SQLiteDatabase,
        indexName: String,
        table: String,
        columns: String
    ) {
        requireValidIdentifier(indexName)
        requireValidIdentifier(table)

        val safeColumns = columns.split(",").map { raw ->
            val column = raw.trim()
            requireValidIdentifier(column)
            "`$column`"
        }.joinToString(", ")

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `$indexName`
            ON `$table` ($safeColumns)
            """.trimIndent()
        )
    }

    // -------------------------------------------------------------
    // CANDLE VALIDATION
    // -------------------------------------------------------------

    private fun createCandleValidationTriggers(
        db: SQLiteDatabase
    ) {
        val validation = """
            SELECT CASE
                WHEN NEW.timestamp IS NULL
                  OR NEW.timestamp <= 0

                  OR NEW.open IS NULL
                  OR NEW.high IS NULL
                  OR NEW.low IS NULL
                  OR NEW.close IS NULL

                  OR NEW.open <= 0
                  OR NEW.high <= 0
                  OR NEW.low <= 0
                  OR NEW.close <= 0

                  OR NEW.high < NEW.open
                  OR NEW.high < NEW.close
                  OR NEW.high < NEW.low

                  OR NEW.low > NEW.open
                  OR NEW.low > NEW.close

                  OR NEW.volume IS NULL
                  OR NEW.volume < 0

                THEN RAISE(ABORT, 'Invalid OHLC candle')
            END;
        """.trimIndent()

        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS
                trg_candles_validate_insert
            BEFORE INSERT ON `$TABLE_CANDLES`
            BEGIN
                $validation
            END
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS
                trg_candles_validate_update
            BEFORE UPDATE ON `$TABLE_CANDLES`
            BEGIN
                $validation
            END
            """.trimIndent()
        )
    }
}


package com.sb.backend.database

import android.database.sqlite.SQLiteDatabase

object DatabaseMigrations {

    fun createAll(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS instruments (
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

        // Existing database में भी expiry column सुनिश्चित करें।
        addColumnIfMissing(
            db,
            "instruments",
            "expiry_millis",
            "INTEGER"
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS quotes (
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

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS candles (
                symbol TEXT NOT NULL,
                exchange TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                open REAL NOT NULL,
                high REAL NOT NULL,
                low REAL NOT NULL,
                close REAL NOT NULL,
                volume INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(symbol, exchange, timeframe, timestamp)
            )
            """.trimIndent()
        )

        // Durable raw market tick history.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS raw_ticks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                ltp REAL NOT NULL CHECK (ltp > 0),
                volume INTEGER NOT NULL DEFAULT 0 CHECK (volume >= 0),
                last_trade_qty INTEGER NOT NULL DEFAULT 0
                    CHECK (last_trade_qty >= 0),
                last_trade_time INTEGER,
                exchange_time INTEGER,
                received_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Durable per-instrument tick processing counters.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tick_audit (
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                received INTEGER NOT NULL DEFAULT 0,
                parsed INTEGER NOT NULL DEFAULT 0,
                mapped INTEGER NOT NULL DEFAULT 0,
                rejected INTEGER NOT NULL DEFAULT 0,
                raw_saved INTEGER NOT NULL DEFAULT 0,
                quote_saved INTEGER NOT NULL DEFAULT 0,
                save_failed INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(exchange, symbol)
            )
            """.trimIndent()
        )

        // Durable retry queue: one latest pending quote per instrument.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS quote_persistence_retry (
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

        // Candle completeness metadata.
        addColumnIfMissing(db, "candles", "status",
            "TEXT NOT NULL DEFAULT 'FINAL'")
        addColumnIfMissing(db, "candles", "source_tick_count",
            "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(db, "candles", "first_tick_timestamp",
            "INTEGER")
        addColumnIfMissing(db, "candles", "last_tick_timestamp",
            "INTEGER")
        addColumnIfMissing(db, "candles", "finalized_at",
            "INTEGER")

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_raw_ticks_instrument_time " +
                "ON raw_ticks(exchange, symbol, received_at)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_quote_retry_queued " +
                "ON quote_persistence_retry(queued_at)"
        )

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

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS device_identity (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                device_id TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_state (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_instruments_symbol " +
                "ON instruments(symbol)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_instruments_name " +
                "ON instruments(name)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_instruments_segment " +
                "ON instruments(segment)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_instruments_expiry " +
                "ON instruments(exchange, segment, expiry_millis)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_candles_lookup " +
                "ON candles(symbol, timeframe, timestamp)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_signals_status " +
                "ON signals(status, created_at)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_signals_symbol " +
                "ON signals(symbol, created_at)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_orders_status " +
                "ON paper_orders(status, created_at)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_positions_status " +
                "ON positions(status)"
        )

        createV5Schema(db)
    }

    fun upgrade(db: SQLiteDatabase, oldVersion: Int) {
        // Preserve existing schema and records.
        createAll(db)

        if (oldVersion < 3) {
            addColumnIfMissing(db, "quotes", "exchange_timestamp", "INTEGER")
            addColumnIfMissing(
                db, "quotes", "received_at", "INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                """
                UPDATE quotes
                SET received_at = timestamp
                WHERE received_at = 0
                """.trimIndent()
            )
        }

        if (oldVersion < 4) {
            addColumnIfMissing(db, "instruments", "expiry_millis", "INTEGER")
        }

        if (oldVersion < 5) {
            createV5Schema(db)
        }
    }

    private fun createV5Schema(db: SQLiteDatabase) {

        // Instrument identity lookup. Non-unique to avoid breaking
        // existing databases that may contain duplicate tokens.
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_instruments_exchange_token
            ON instruments(exchange, token)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_instruments_exchange_segment_symbol
            ON instruments(exchange, segment, symbol)
            """.trimIndent()
        )

        // Database-level OHLC validation.
        // SQLite stores non-finite REAL values as NULL; NOT NULL and
        // these checks prevent invalid persisted candles.
        db.execSQL("DROP TRIGGER IF EXISTS trg_candles_validate_insert")
        db.execSQL("DROP TRIGGER IF EXISTS trg_candles_validate_update")

        val validation = """
            SELECT CASE
                WHEN NEW.timestamp <= 0
                  OR NEW.open IS NULL OR NEW.high IS NULL
                  OR NEW.low IS NULL OR NEW.close IS NULL
                  OR NEW.open <= 0 OR NEW.high <= 0
                  OR NEW.low <= 0 OR NEW.close <= 0
                  OR NEW.high < NEW.open
                  OR NEW.high < NEW.close
                  OR NEW.high < NEW.low
                  OR NEW.low > NEW.open
                  OR NEW.low > NEW.close
                  OR NEW.volume < 0
                THEN RAISE(ABORT, 'Invalid OHLC candle')
            END;
        """.trimIndent()

        db.execSQL(
            """
            CREATE TRIGGER trg_candles_validate_insert
            BEFORE INSERT ON candles
            BEGIN
                $validation
            END
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TRIGGER trg_candles_validate_update
            BEFORE UPDATE ON candles
            BEGIN
                $validation
            END
            """.trimIndent()
        )

        // Retention support: 7 days by default at the caller level.
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_raw_ticks_received
            ON raw_ticks(received_at)
            """.trimIndent()
        )
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String
    ) {
        var exists = false

        db.rawQuery(
            "PRAGMA table_info($table)",
            null
        ).use { cursor ->

            val nameIndex = cursor.getColumnIndex("name")

            while (cursor.moveToNext()) {
                if (
                    cursor.getString(nameIndex)
                        .equals(column, true)
                ) {
                    exists = true
                    break
                }
            }
        }

        if (!exists) {
            db.execSQL(
                "ALTER TABLE $table ADD COLUMN $column $definition"
            )
        }
    }
}

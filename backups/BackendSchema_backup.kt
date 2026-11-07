package com.sb.backend.database

import android.database.sqlite.SQLiteDatabase

/**
 * SB BOT - Backend Database Schema
 *
 * यह class केवल backend की नई tables और indexes बनाती है।
 * Existing DashboardDbHelper की tables को modify या delete नहीं करती।
 */
object BackendSchema {

    const val VERSION = 1

    const val TABLE_INSTRUMENTS = "backend_instruments"
    const val TABLE_QUOTES = "backend_quotes"
    const val TABLE_CANDLES = "backend_candles"
    const val TABLE_SIGNALS = "backend_signals"
    const val TABLE_METADATA = "backend_metadata"

    /**
     * सभी backend tables और indexes बनाता है।
     *
     * CREATE TABLE IF NOT EXISTS के कारण
     * existing data overwrite नहीं होगा।
     */
    fun create(db: SQLiteDatabase) {

        createMetadataTable(db)

        createInstrumentsTable(db)

        createQuotesTable(db)

        createCandlesTable(db)

        createSignalsTable(db)

        createIndexes(db)

        saveSchemaVersion(db)
    }

    // --------------------------------------------------
    // METADATA
    // --------------------------------------------------

    private fun createMetadataTable(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_METADATA (
                meta_key TEXT PRIMARY KEY,
                meta_value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun saveSchemaVersion(db: SQLiteDatabase) {

        val now = System.currentTimeMillis()

        db.execSQL(
            """
            INSERT OR REPLACE INTO $TABLE_METADATA
            (
                meta_key,
                meta_value,
                updated_at
            )
            VALUES
            (
                'backend_schema_version',
                '$VERSION',
                $now
            )
            """.trimIndent()
        )
    }

    // --------------------------------------------------
    // INSTRUMENTS
    // --------------------------------------------------

    private fun createInstrumentsTable(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_INSTRUMENTS (

                id INTEGER PRIMARY KEY AUTOINCREMENT,

                symbol TEXT NOT NULL,

                name TEXT NOT NULL,

                exchange TEXT NOT NULL,

                segment TEXT NOT NULL,

                instrument_type TEXT NOT NULL,

                trading_symbol TEXT NOT NULL,

                token TEXT,

                lot_size INTEGER NOT NULL DEFAULT 1,

                tick_size REAL NOT NULL DEFAULT 0.05,

                is_active INTEGER NOT NULL DEFAULT 1,

                created_at INTEGER NOT NULL,

                updated_at INTEGER NOT NULL,

                CHECK (lot_size > 0),

                CHECK (tick_size > 0),

                UNIQUE (
                    exchange,
                    trading_symbol
                )
            )
            """.trimIndent()
        )
    }

    // --------------------------------------------------
    // LIVE QUOTES
    // --------------------------------------------------

    private fun createQuotesTable(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_QUOTES (

                id INTEGER PRIMARY KEY AUTOINCREMENT,

                symbol TEXT NOT NULL,

                exchange TEXT NOT NULL,

                ltp REAL NOT NULL,

                open_price REAL,

                high_price REAL,

                low_price REAL,

                previous_close REAL,

                volume INTEGER NOT NULL DEFAULT 0,

                change_value REAL,

                change_percent REAL,

                timestamp INTEGER NOT NULL,

                is_live INTEGER NOT NULL DEFAULT 0,

                CHECK (ltp >= 0),

                CHECK (volume >= 0),

                UNIQUE (
                    symbol,
                    exchange
                )
            )
            """.trimIndent()
        )
    }

    // --------------------------------------------------
    // HISTORICAL CANDLES
    // --------------------------------------------------

    private fun createCandlesTable(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CANDLES (

                id INTEGER PRIMARY KEY AUTOINCREMENT,

                symbol TEXT NOT NULL,

                exchange TEXT NOT NULL,

                timeframe TEXT NOT NULL,

                candle_timestamp INTEGER NOT NULL,

                open_price REAL NOT NULL,

                high_price REAL NOT NULL,

                low_price REAL NOT NULL,

                close_price REAL NOT NULL,

                volume INTEGER NOT NULL DEFAULT 0,

                is_complete INTEGER NOT NULL DEFAULT 1,

                CHECK (open_price >= 0),

                CHECK (high_price >= 0),

                CHECK (low_price >= 0),

                CHECK (close_price >= 0),

                CHECK (high_price >= low_price),

                CHECK (volume >= 0),

                UNIQUE (
                    symbol,
                    exchange,
                    timeframe,
                    candle_timestamp
                )
            )
            """.trimIndent()
        )
    }

    // --------------------------------------------------
    // TRADING SIGNALS
    // --------------------------------------------------

    private fun createSignalsTable(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SIGNALS (

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

                timeframe TEXT NOT NULL DEFAULT '5m',

                status TEXT NOT NULL DEFAULT 'ACTIVE',

                reason TEXT NOT NULL DEFAULT '',

                created_at INTEGER NOT NULL,

                expires_at INTEGER,

                confirmation_count INTEGER NOT NULL DEFAULT 0,

                confirmation_required INTEGER NOT NULL DEFAULT 2,

                is_stable INTEGER NOT NULL DEFAULT 0,

                CHECK (
                    confidence >= 0
                    AND confidence <= 100
                ),

                CHECK (confirmation_count >= 0),

                CHECK (confirmation_required > 0)
            )
            """.trimIndent()
        )
    }

    // --------------------------------------------------
    // INDEXES
    // --------------------------------------------------

    private fun createIndexes(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            idx_backend_instruments_symbol
            ON $TABLE_INSTRUMENTS(symbol)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            idx_backend_instruments_exchange
            ON $TABLE_INSTRUMENTS(exchange)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            idx_backend_quotes_timestamp
            ON $TABLE_QUOTES(timestamp)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            idx_backend_candles_lookup
            ON $TABLE_CANDLES(
                symbol,
                exchange,
                timeframe,
                candle_timestamp
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            idx_backend_signals_symbol
            ON $TABLE_SIGNALS(symbol)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            idx_backend_signals_status
            ON $TABLE_SIGNALS(status)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            idx_backend_signals_created
            ON $TABLE_SIGNALS(created_at)
            """.trimIndent()
        )
    }
}

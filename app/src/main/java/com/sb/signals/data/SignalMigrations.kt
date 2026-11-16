package com.sb.signals.data

import android.database.sqlite.SQLiteDatabase

object SignalMigrations {

    const val VERSION = 3

    const val TABLE_SIGNALS = "research_signals"
    const val TABLE_EVENTS = "signal_events"
    const val TABLE_VERSIONS = "strategy_versions"
    const val TABLE_RUNS = "analysis_runs"

    fun createAll(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$TABLE_SIGNALS` (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                exchange TEXT NOT NULL,
                category TEXT NOT NULL,
                direction TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                strategy_id TEXT NOT NULL,
                strategy_version TEXT NOT NULL,
                strategy_config_json TEXT NOT NULL DEFAULT '',
                candle_timestamp INTEGER NOT NULL,
                generated_at INTEGER NOT NULL,
                reference_price REAL NOT NULL,
                entry_low REAL NOT NULL,
                entry_high REAL NOT NULL,
                entry_fill_price REAL,
                stop_loss REAL NOT NULL,
                target1 REAL NOT NULL,
                target2 REAL NOT NULL,
                target3 REAL NOT NULL,
                risk_per_unit REAL NOT NULL,
                reward_risk1 REAL NOT NULL,
                reward_risk2 REAL NOT NULL,
                reward_risk3 REAL NOT NULL,
                confidence REAL NOT NULL,
                holding_period TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                reasons_json TEXT NOT NULL,
                indicator_json TEXT NOT NULL,
                confirmations_json TEXT NOT NULL,
                indicator_params_json TEXT NOT NULL DEFAULT '{}',
                data_quality_json TEXT NOT NULL,
                triggered_at INTEGER,
                target1_at INTEGER,
                target2_at INTEGER,
                target3_at INTEGER,
                stopped_at INTEGER,
                closed_at INTEGER,
                exit_price REAL,
                outcome_r REAL,
                outcome_pnl_percent REAL,
                outcome_net_pnl_percent REAL,
                outcome_cost_percent REAL,
                outcome_reason TEXT,
                ambiguous_bar INTEGER NOT NULL DEFAULT 0,
                mfe_percent REAL,
                mae_percent REAL,
                last_evaluated_at INTEGER,
                evaluation_version TEXT NOT NULL,
                dedupe_key TEXT NOT NULL UNIQUE,
                CHECK (direction IN ('BUY', 'SELL')),
                CHECK (category IN ('INTRADAY', 'SWING', 'DELIVERY'))
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$TABLE_EVENTS` (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                signal_id INTEGER NOT NULL,
                from_status TEXT,
                to_status TEXT NOT NULL,
                event_time INTEGER NOT NULL,
                price REAL,
                message TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(signal_id) REFERENCES `$TABLE_SIGNALS`(id)
                    ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$TABLE_VERSIONS` (
                strategy_id TEXT NOT NULL,
                version TEXT NOT NULL,
                category TEXT NOT NULL,
                name TEXT NOT NULL,
                config_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                active INTEGER NOT NULL DEFAULT 1,
                notes TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(strategy_id, version)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$TABLE_RUNS` (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                finished_at INTEGER NOT NULL,
                categories TEXT NOT NULL,
                symbols_evaluated INTEGER NOT NULL,
                signals_generated INTEGER NOT NULL,
                signals_evaluated INTEGER NOT NULL,
                errors INTEGER NOT NULL,
                message TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_research_signals_status " +
                "ON `$TABLE_SIGNALS`(status, generated_at DESC)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_research_signals_category " +
                "ON `$TABLE_SIGNALS`(category, generated_at DESC)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_research_signals_symbol " +
                "ON `$TABLE_SIGNALS`(exchange, symbol, generated_at DESC)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_signal_events_signal " +
                "ON `$TABLE_EVENTS`(signal_id, event_time)"
        )
    }

    fun upgrade(
        db: SQLiteDatabase,
        oldVersion: Int
    ) {
        if (oldVersion < 1) {
            createAll(db)
        }
        if (oldVersion < 2 &&
            !hasColumn(db, TABLE_SIGNALS, "entry_fill_price")
        ) {
            db.execSQL(
                "ALTER TABLE `$TABLE_SIGNALS` " +
                    "ADD COLUMN entry_fill_price REAL"
            )
        }
        if (oldVersion < 3 &&
            !hasColumn(db, TABLE_SIGNALS, "strategy_config_json")
        ) {
            db.execSQL(
                "ALTER TABLE `$TABLE_SIGNALS` " +
                    "ADD COLUMN strategy_config_json TEXT NOT NULL DEFAULT ''"
            )
        }
        if (oldVersion < 3 &&
            !hasColumn(db, TABLE_SIGNALS, "indicator_params_json")
        ) {
            db.execSQL(
                "ALTER TABLE `$TABLE_SIGNALS` " +
                    "ADD COLUMN indicator_params_json TEXT NOT NULL DEFAULT '{}'"
            )
        }
    }

    private fun hasColumn(
        db: SQLiteDatabase,
        table: String,
        column: String
    ): Boolean {
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }
}

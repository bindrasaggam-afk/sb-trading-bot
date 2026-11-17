package com.sb.researchanalytics.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.sb.researchanalytics.domain.IndicatorSnapshotRecord
import com.sb.researchanalytics.domain.MarketSnapshotRecord
import com.sb.researchanalytics.domain.ResearchSnapshotFactory
import com.sb.signals.data.SignalJson
import com.sb.signals.data.SignalMigrations
import com.sb.signals.domain.ResearchSignal
import org.json.JSONArray

/**
 * Insert-only projection of an already-generated research signal.
 * The signal row is the source of truth; this writer never recalculates.
 */
object ResearchSnapshotWriter {

    fun capture(
        db: SQLiteDatabase,
        signalId: Long,
        signal: ResearchSignal
    ) {
        if (signalId <= 0L) return
        val pair = ResearchSnapshotFactory.fromSignal(signal)
        insertIndicator(
            db,
            pair.indicator.copy(
                signalId = signalId,
                source = IndicatorSnapshotRecord.SOURCE_CAPTURED
            )
        )
        insertMarket(db, pair.market.copy(signalId = signalId))
    }

    private fun insertIndicator(
        db: SQLiteDatabase,
        record: IndicatorSnapshotRecord
    ) {
        val values = ContentValues().apply {
            put("signal_id", record.signalId)
            put("snapshot_version", record.snapshotVersion)
            put("source", record.source)
            put("captured_at", record.capturedAt)
            put("candle_timestamp", record.candleTimestamp)
            put("primary_timeframe", record.primaryTimeframe)
            put(
                "confirmation_timeframe",
                record.confirmationTimeframe
            )
            put("trend_timeframe", record.trendTimeframe)
            put("strategy_config_json", record.strategyConfigJson)
            put(
                "indicator_values_json",
                SignalJson.encodeDoubleMap(record.values)
            )
            put(
                "indicator_scores_json",
                SignalJson.encodeDoubleMap(record.scores)
            )
            put(
                "confirmations_json",
                SignalJson.encodeBooleanMap(record.confirmations)
            )
            put(
                "parameters_json",
                SignalJson.encodeDoubleMap(record.parameters)
            )
            putDouble("ema_fast", record.emaFast)
            putDouble("ema_slow", record.emaSlow)
            putDouble("ema_trend", record.emaTrend)
            putDouble("rsi", record.rsi)
            putDouble("macd", record.macd)
            putDouble("macd_signal", record.macdSignal)
            putDouble("macd_histogram", record.macdHistogram)
            putDouble("supertrend", record.supertrend)
            putDouble(
                "supertrend_direction",
                record.supertrendDirection
            )
            putDouble("adx", record.adx)
            putDouble("plus_di", record.plusDi)
            putDouble("minus_di", record.minusDi)
            putDouble("vwap", record.vwap)
            putDouble("bb_upper", record.bbUpper)
            putDouble("bb_middle", record.bbMiddle)
            putDouble("bb_lower", record.bbLower)
            putDouble("atr", record.atr)
            putDouble("volume", record.volume)
            putDouble("volume_ma", record.volumeMa)
            putDouble("volume_ratio", record.volumeRatio)
            putBoolean2("breakout_confirmed", record.breakoutConfirmed)
            putBoolean2(
                "higher_tf_confirmed",
                record.higherTimeframeConfirmed
            )
            putBoolean2(
                "trend_tf_confirmed",
                record.trendTimeframeConfirmed
            )
            putDouble("total_score", record.totalScore)
            putDouble("bull_score", record.bullScore)
            putDouble("bear_score", record.bearScore)
            put("created_at", record.createdAt)
        }
        db.insertWithOnConflict(
            SignalMigrations.TABLE_INDICATOR_SNAPSHOTS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun insertMarket(
        db: SQLiteDatabase,
        record: MarketSnapshotRecord
    ) {
        val conditions = JSONArray()
        record.conditions.forEach { conditions.put(it) }
        val values = ContentValues().apply {
            put("signal_id", record.signalId)
            put("captured_at", record.capturedAt)
            put("candle_timestamp", record.candleTimestamp)
            putDouble("volatility_pct", record.volatilityPct)
            put("volatility_bucket", record.volatilityBucket)
            putDouble("adx", record.adx)
            put("adx_bucket", record.adxBucket)
            putDouble("volume_ratio", record.volumeRatio)
            put("volume_bucket", record.volumeBucket)
            putBoolean2(
                "higher_tf_aligned",
                record.higherTimeframeAligned
            )
            putBoolean2(
                "trend_tf_aligned",
                record.trendTimeframeAligned
            )
            put("session_label", record.sessionLabel)
            put("market_condition_json", conditions.toString())
            put("created_at", record.createdAt)
        }
        db.insertWithOnConflict(
            SignalMigrations.TABLE_MARKET_SNAPSHOTS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun ContentValues.putDouble(
        key: String,
        value: Double?
    ) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putBoolean2(
        key: String,
        value: Boolean?
    ) {
        when (value) {
            null -> putNull(key)
            true -> put(key, 1)
            false -> put(key, 0)
        }
    }
}

package com.sb.backend.repositories

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.models.Candle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarketRepositoryAtomicSaveTest {

    @Test
    fun invalidCandleRollsBackEntireBatch() {
        // Use the instrumentation APK's private database, never the user's app database.
        val context = InstrumentationRegistry.getInstrumentation().context
        context.deleteDatabase(SBDatabaseHelper.DATABASE_NAME)
        val helper = SBDatabaseHelper(context)
        try {
            val repository = MarketRepository(helper)
            val valid = candle(timestamp = 1_800_000_000_000L)
            val invalid = candle(
                timestamp = 1_800_000_060_000L,
                high = 8.0
            )

            val saved = repository.saveCandles(
                symbol = "ATOMIC_TEST",
                exchange = "NSE",
                timeframe = "1D",
                candles = listOf(valid, invalid)
            )

            assertEquals(0, saved)
            assertEquals(
                0,
                repository.countCandles("ATOMIC_TEST", "1D", "NSE")
            )
        } finally {
            helper.close()
            context.deleteDatabase(SBDatabaseHelper.DATABASE_NAME)
        }
    }

    private fun candle(
        timestamp: Long,
        high: Double = 12.0
    ) = Candle(
        symbol = "ATOMIC_TEST",
        timeframe = "1D",
        timestamp = timestamp,
        open = 10.0,
        high = high,
        low = 9.0,
        close = 11.0,
        volume = 100L
    )
}

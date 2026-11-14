package com.sb.backend.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.backend.models.Candle
import com.sb.backend.repositories.MarketRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarketRepositoryCandlePersistenceTest {

    @Test
    fun saveReloadPreservesCandlesInAscendingTimestampOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SBDatabaseHelper.DATABASE_NAME)

        val expected = listOf(
            Candle("SB_PERSIST_TEST", "1m", 1_700_000_060_000L, 101.0, 103.0, 100.0, 102.0, 150L),
            Candle("SB_PERSIST_TEST", "1m", 1_700_000_000_000L, 100.0, 102.0, 99.0, 101.0, 120L)
        )

        try {
            val firstHelper = SBDatabaseHelper(context)
            val firstRepository = MarketRepository(firstHelper)
            assertEquals(
                2,
                firstRepository.saveCandles(
                    symbol = "SB_PERSIST_TEST",
                    exchange = "NSE",
                    timeframe = "1m",
                    candles = expected
                )
            )
            firstHelper.close()

            val reopenedHelper = SBDatabaseHelper(context)
            try {
                val reloaded = MarketRepository(reopenedHelper).getCandles(
                    symbol = "SB_PERSIST_TEST",
                    exchange = "1m",
                    timeframe = "NSE",
                    limit = 10
                )

                assertEquals(2, reloaded.size)
                assertEquals(
                    listOf(1_700_000_000_000L, 1_700_000_060_000L),
                    reloaded.map { it.timestamp }
                )
                assertEquals(101.0, reloaded[0].close, 0.0001)
                assertEquals(102.0, reloaded[1].close, 0.0001)
                assertEquals(listOf(120L, 150L), reloaded.map { it.volume })
                assertTrue(reloaded.all { it.symbol == "SB_PERSIST_TEST" })
            } finally {
                reopenedHelper.close()
            }
        } finally {
            context.deleteDatabase(SBDatabaseHelper.DATABASE_NAME)
        }
    }
}

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

    private companion object {
        const val TEST_DATABASE_NAME = "sb_backend_candle_test.db"
    }

    @Test
    fun saveReloadPreservesCandlesInAscendingTimestampOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)

        val expected = listOf(
            Candle("SB_PERSIST_TEST", "1m", 1_700_000_060_000L, 101.0, 103.0, 100.0, 102.0, 150L),
            Candle("SB_PERSIST_TEST", "1m", 1_700_000_000_000L, 100.0, 102.0, 99.0, 101.0, 120L)
        )

        try {
            val firstHelper = SBDatabaseHelper(context, TEST_DATABASE_NAME)
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

            val reopenedHelper = SBDatabaseHelper(context, TEST_DATABASE_NAME)
            try {
                val reloaded = MarketRepository(reopenedHelper).getCandles(
                    symbol = "SB_PERSIST_TEST",
                    timeframe = "1m",
                    exchange = "NSE",
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
            context.deleteDatabase(TEST_DATABASE_NAME)
        }
    }
    @Test
    fun durablePendingCandleQueueSurvivesHelperReopenAndCanBeRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
        val candle = Candle(
            "SB_QUEUE_TEST", "5m", 1_700_000_300_000L,
            200.0, 205.0, 198.0, 204.0, 777L, false, "NSE"
        )
        try {
            val helper = SBDatabaseHelper(context, TEST_DATABASE_NAME)
            val repository = MarketRepository(helper)
            assertTrue(repository.enqueueCandlePersistence(
                candle.symbol, candle.exchange, candle.timeframe, candle
            ))
            helper.close()

            val reopened = SBDatabaseHelper(context, TEST_DATABASE_NAME)
            try {
                val recoveredRepository = MarketRepository(reopened)
                val pending = recoveredRepository.loadPendingCandlePersistence(10)
                assertEquals(1, pending.size)
                assertEquals(candle.timestamp, pending[0].candle.timestamp)
                assertEquals(candle.close, pending[0].candle.close, 0.0001)
                assertEquals(false, pending[0].candle.isComplete)

                assertTrue(recoveredRepository.markPendingCandlePersistenceFailure(
                    pending[0], "test retry"
                ))
                val updated = recoveredRepository.loadPendingCandlePersistence(10)
                assertEquals(1, updated[0].attempts)

                assertTrue(recoveredRepository.removePendingCandlePersistence(updated[0]))
                assertEquals(0, recoveredRepository.loadPendingCandlePersistence(10).size)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(TEST_DATABASE_NAME)
        }
    }

}

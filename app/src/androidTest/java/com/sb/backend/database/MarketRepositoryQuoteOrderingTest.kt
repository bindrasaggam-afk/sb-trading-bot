package com.sb.backend.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sb.backend.models.Quote
import com.sb.backend.repositories.MarketRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarketRepositoryQuoteOrderingTest {

    private companion object {
        const val TEST_DATABASE_NAME = "sb_backend_quote_order_test.db"
        const val EXCHANGE = "NSE"
        const val SYMBOL = "SB_ORDER_TEST"
    }

    private lateinit var helper: SBDatabaseHelper
    private lateinit var repository: MarketRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
        helper = SBDatabaseHelper(context, TEST_DATABASE_NAME)
        repository = MarketRepository(helper)
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        helper.close()
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test
    fun newerExchangeTimestampUpdatesStoredQuote() {
        assertTrue(repository.saveQuote(quote(ltp = 100.0, exchangeTime = 1_000L)))
        assertTrue(repository.saveQuote(quote(ltp = 105.0, exchangeTime = 2_000L)))

        val stored = repository.getQuote(SYMBOL, EXCHANGE)!!
        assertEquals(105.0, stored.ltp, 0.0001)
        assertEquals(2_000L, stored.exchangeTimestamp)
    }

    @Test
    fun olderExchangeTimestampDoesNotOverwriteStoredQuote() {
        assertTrue(repository.saveQuote(quote(ltp = 105.0, exchangeTime = 2_000L)))
        assertFalse(repository.saveQuote(quote(ltp = 100.0, exchangeTime = 1_000L)))

        val stored = repository.getQuote(SYMBOL, EXCHANGE)!!
        assertEquals(105.0, stored.ltp, 0.0001)
        assertEquals(2_000L, stored.exchangeTimestamp)
    }

    @Test
    fun sameExchangeTimestampWithOlderReceivedAtIsRejected() {
        assertTrue(
            repository.saveQuote(
                quote(ltp = 105.0, exchangeTime = 2_000L, receivedAt = 2_500L)
            )
        )
        assertFalse(
            repository.saveQuote(
                quote(ltp = 100.0, exchangeTime = 2_000L, receivedAt = 2_400L)
            )
        )

        val stored = repository.getQuote(SYMBOL, EXCHANGE)!!
        assertEquals(105.0, stored.ltp, 0.0001)
        assertEquals(2_500L, stored.receivedAt)
    }

    @Test
    fun batchSaveDoesNotOverwriteNewerQuoteWithOlderQuote() {
        val saved = repository.saveQuotes(
            listOf(
                quote(ltp = 110.0, exchangeTime = 3_000L),
                quote(ltp = 100.0, exchangeTime = 1_000L)
            )
        )

        assertEquals(1, saved)

        val stored = repository.getQuote(SYMBOL, EXCHANGE)!!
        assertEquals(110.0, stored.ltp, 0.0001)
        assertEquals(3_000L, stored.exchangeTimestamp)
    }

    @Test
    fun detailedBatchCountsStaleQuoteAsRejected() {
        assertTrue(repository.saveQuote(quote(ltp = 110.0, exchangeTime = 3_000L)))

        val result = repository.saveQuotesDetailed(
            listOf(
                quote(ltp = 120.0, exchangeTime = 4_000L),
                quote(ltp = 100.0, exchangeTime = 1_000L)
            )
        )

        assertEquals(2, result.attempted)
        assertEquals(1, result.saved)
        assertEquals(1, result.rejected)
        assertEquals(0, result.failed)

        val stored = repository.getQuote(SYMBOL, EXCHANGE)!!
        assertEquals(120.0, stored.ltp, 0.0001)
    }

    @Test
    fun incomingExchangeTimestampIsAcceptedOverExistingWithoutOne() {
        val existingWithoutExchangeTime = Quote(
            symbol = SYMBOL,
            exchange = EXCHANGE,
            ltp = 100.0,
            timestamp = 10_000L,
            exchangeTimestamp = null,
            receivedAt = 10_000L
        )
        val incomingWithExchangeTime = Quote(
            symbol = SYMBOL,
            exchange = EXCHANGE,
            ltp = 105.0,
            timestamp = 5_000L,
            exchangeTimestamp = 5_000L,
            receivedAt = 5_000L
        )

        assertTrue(repository.saveQuote(existingWithoutExchangeTime))
        assertTrue(repository.saveQuote(incomingWithExchangeTime))

        val stored = repository.getQuote(SYMBOL, EXCHANGE)!!
        assertEquals(105.0, stored.ltp, 0.0001)
        assertEquals(5_000L, stored.exchangeTimestamp)
    }

    @Test
    fun incomingWithoutExchangeTimestampIsRejectedWhenExistingHasOne() {
        val existingWithExchangeTime = Quote(
            symbol = SYMBOL,
            exchange = EXCHANGE,
            ltp = 105.0,
            timestamp = 5_000L,
            exchangeTimestamp = 5_000L,
            receivedAt = 5_000L
        )
        val incomingWithoutExchangeTime = Quote(
            symbol = SYMBOL,
            exchange = EXCHANGE,
            ltp = 100.0,
            timestamp = 10_000L,
            exchangeTimestamp = null,
            receivedAt = 10_000L
        )

        assertTrue(repository.saveQuote(existingWithExchangeTime))
        assertFalse(repository.saveQuote(incomingWithoutExchangeTime))

        val stored = repository.getQuote(SYMBOL, EXCHANGE)!!
        assertEquals(105.0, stored.ltp, 0.0001)
        assertEquals(5_000L, stored.exchangeTimestamp)
    }

    @Test
    fun quotesWithoutExchangeTimestampsCompareByReceivedAt() {
        val older = Quote(
            symbol = SYMBOL,
            exchange = EXCHANGE,
            ltp = 100.0,
            timestamp = 1_000L,
            exchangeTimestamp = null,
            receivedAt = 1_000L
        )
        val newer = Quote(
            symbol = SYMBOL,
            exchange = EXCHANGE,
            ltp = 105.0,
            timestamp = 2_000L,
            exchangeTimestamp = null,
            receivedAt = 2_000L
        )

        assertTrue(repository.saveQuote(newer))
        assertFalse(repository.saveQuote(older))

        val stored = repository.getQuote(SYMBOL, EXCHANGE)!!
        assertEquals(105.0, stored.ltp, 0.0001)
        assertTrue(stored.exchangeTimestamp == null)
    }

    @Test
    fun retryQueueReadsTimestampAndAppliesItToMainQuote() {
        insertRetryRow(
            quote = quote(ltp = 130.0, exchangeTime = 5_000L),
            attempts = 3
        )

        assertTrue(repository.saveQuote(quote(ltp = 100.0, exchangeTime = 1_000L)))

        assertEquals(1, repository.retryPendingQuotes())

        val stored = repository.getQuote(SYMBOL, EXCHANGE)!!
        assertEquals(130.0, stored.ltp, 0.0001)
        assertEquals(5_000L, stored.exchangeTimestamp)
        assertEquals(0, retryRowCount())
    }

    @Test
    fun retryQueueReadsAttemptCountFromCorrectColumn() {
        insertRetryRow(
            quote = quote(ltp = 150.0, exchangeTime = 7_000L),
            attempts = 3
        )

        // Force the quote insert to fail so retry failure accounting runs and
        // the previously-read attempt count is incremented from 3 to 4.
        helper.writableDatabase.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS fail_quote_insert
            BEFORE INSERT ON `${SBDatabaseHelper.TABLE_QUOTES}`
            WHEN NEW.symbol = '$SYMBOL'
            BEGIN
                SELECT RAISE(ABORT, 'forced failure');
            END
            """.trimIndent()
        )

        try {
            assertEquals(0, repository.retryPendingQuotes())
        } finally {
            helper.writableDatabase.execSQL(
                "DROP TRIGGER IF EXISTS fail_quote_insert"
            )
        }

        assertEquals(1, retryRowCount())
        assertEquals(4, retryAttempts())
    }

    @Test
    fun staleRetryQuoteDoesNotOverwriteNewerMainQuote() {
        insertRetryRow(
            quote = quote(ltp = 100.0, exchangeTime = 1_000L),
            attempts = 0
        )

        assertTrue(repository.saveQuote(quote(ltp = 140.0, exchangeTime = 6_000L)))

        assertEquals(0, repository.retryPendingQuotes())

        val stored = repository.getQuote(SYMBOL, EXCHANGE)!!
        assertEquals(140.0, stored.ltp, 0.0001)
        assertEquals(6_000L, stored.exchangeTimestamp)

        // The stale retry row is dropped so it is not retried forever.
        assertEquals(0, retryRowCount())
    }

    private fun quote(
        ltp: Double,
        exchangeTime: Long,
        receivedAt: Long = exchangeTime
    ): Quote = Quote(
        symbol = SYMBOL,
        exchange = EXCHANGE,
        ltp = ltp,
        timestamp = exchangeTime,
        exchangeTimestamp = exchangeTime,
        receivedAt = receivedAt
    )

    private fun insertRetryRow(quote: Quote, attempts: Int) {
        val values = ContentValues().apply {
            put("symbol", SYMBOL)
            put("exchange", EXCHANGE)
            put("ltp", quote.ltp)
            put("timestamp", quote.timestamp)
            put("is_live", if (quote.isLive) 1 else 0)
            put("exchange_timestamp", quote.exchangeTimestamp)
            put("received_at", quote.receivedAt)
            put("attempts", attempts)
            put("queued_at", System.currentTimeMillis())
        }

        val inserted = helper.writableDatabase.insertWithOnConflict(
            SBDatabaseHelper.TABLE_QUOTE_PERSISTENCE_RETRY,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )

        assertTrue(inserted != -1L)
    }

    private fun retryRowCount(): Int {
        return helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM `${SBDatabaseHelper.TABLE_QUOTE_PERSISTENCE_RETRY}`",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun retryAttempts(): Int {
        return helper.readableDatabase.rawQuery(
            """
            SELECT attempts
            FROM `${SBDatabaseHelper.TABLE_QUOTE_PERSISTENCE_RETRY}`
            WHERE symbol = ? AND exchange = ?
            """.trimIndent(),
            arrayOf(SYMBOL, EXCHANGE)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else -1
        }
    }
}

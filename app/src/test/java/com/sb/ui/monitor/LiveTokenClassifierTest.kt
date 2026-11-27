package com.sb.ui.monitor

import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.models.Instrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTokenClassifierTest {

    @Test
    fun countsSeparateTokenAvailabilityAndLiveTicks() {
        val instruments = listOf(
            instrument("RELIANCE", "2885"),
            instrument("INFY", "11536"),
            instrument("TCS", null),
            instrument("HDFC", null),
            instrument("ICICI", "4242")
        )
        val tickKeys = setOf(
            key("NSE", "RELIANCE"),
            key("NSE", "ICICI"),
            key("NSE", "TCS")
        )

        val counts = LiveTokenClassifier.counts(instruments, tickKeys)

        assertEquals(2, counts.tokenWithTick)
        assertEquals(3, counts.token)
        assertEquals(1, counts.tokenOnly)
        assertEquals(2, counts.noToken)
        assertEquals(2, counts.noLivePrice)
        assertEquals(5, counts.total)
    }

    @Test
    fun tokenWithLiveTickEntriesIncludeOnlyTokenInstrumentsWithTick() {
        val instruments = listOf(
            instrument("RELIANCE", "2885"),
            instrument("INFY", "11536"),
            instrument("TCS", null)
        )
        val tickKeys = setOf(key("NSE", "RELIANCE"), key("NSE", "TCS"))

        val entries = LiveTokenClassifier.entries(
            instruments,
            tickKeys,
            LiveTokenCategory.TOKEN_WITH_TICK
        )

        assertEquals(1, entries.size)
        assertEquals("RELIANCE", entries.first().symbol)
        assertTrue(entries.first().hasLiveTick)
        assertTrue(entries.first().displayRow.contains("2885"))
    }

    @Test
    fun tokenOnlyEntriesExcludeSymbolsThatAlreadyHaveLiveTicks() {
        val instruments = listOf(
            instrument("RELIANCE", "2885"),
            instrument("INFY", "11536"),
            instrument("TCS", null)
        )
        val tickKeys = setOf(key("NSE", "RELIANCE"))

        val entries = LiveTokenClassifier.entries(
            instruments,
            tickKeys,
            LiveTokenCategory.TOKEN_ONLY
        )

        assertEquals(1, entries.size)
        assertTrue(entries.any { it.symbol == "INFY" && it.status == "waiting for live tick" })
        assertTrue(entries.none { it.symbol == "RELIANCE" })
    }

    @Test
    fun noTokenEntriesReportMissingToken() {
        val instruments = listOf(
            instrument("TCS", null),
            instrument("HDFC", null),
            instrument("RELIANCE", "2885")
        )

        val entries = LiveTokenClassifier.entries(
            instruments,
            emptySet(),
            LiveTokenCategory.NO_TOKEN
        )

        assertEquals(2, entries.size)
        assertTrue(entries.all { it.missing == "Kotak token missing" })
        assertTrue(entries.all { it.displayRow.contains("missing: Kotak token missing") })
    }

    @Test
    fun aliasesAreIncludedAndMarked() {
        val alias = instrument("RELIANCE-A", "2885").copy(isAlias = true)
        val entries = LiveTokenClassifier.entries(
            listOf(alias),
            setOf(key("NSE", "RELIANCE-A")),
            LiveTokenCategory.TOKEN_WITH_TICK
        )

        assertEquals(1, entries.size)
        assertTrue(entries.first().isAlias)
        assertTrue(entries.first().displayRow.contains("alias"))
    }

    @Test
    fun noLivePriceEntriesReportReasonsAndSkipCommodityTemplates() {
        val instruments = listOf(
            instrument("TCS", null),
            instrument("NIFTY", "123", segment = "INDEX"),
            instrument("INFY", "11536"),
            instrument("RELIANCE", "2885"),
            instrument("OPTION-TEMPLATE", "999", segment = "COMMODITY"),
            instrument(
                "CRUDE-SEP",
                "777",
                segment = "COMMODITY",
                expiryMillis = 1L
            )
        )
        val tickKeys = setOf(key("NSE", "RELIANCE"))
        val subscribedKeys = setOf(key("NSE", "INFY"))

        val counts = LiveTokenClassifier.counts(instruments, tickKeys)
        val entries = LiveTokenClassifier.entries(
            instruments,
            tickKeys,
            LiveTokenCategory.NO_LIVE_PRICE,
            subscribedKeys
        )

        assertEquals(4, counts.noLivePrice)
        assertEquals(4, entries.size)
        assertTrue(entries.any {
            it.symbol == "TCS" &&
                it.detail == "no Kotak token" &&
                it.displayRow.contains("missing: no Kotak token")
        })
        assertTrue(entries.any {
            it.symbol == "NIFTY" && it.detail == "index feed waiting for tick"
        })
        assertTrue(entries.any {
            it.symbol == "INFY" && it.detail == "subscribed, waiting for tick"
        })
        assertTrue(entries.any {
            it.symbol == "CRUDE-SEP" && it.detail == "no live tick received"
        })
        assertTrue(entries.none { it.symbol == "OPTION-TEMPLATE" })
        assertTrue(entries.none { it.symbol == "RELIANCE" })
    }

    private fun instrument(
        symbol: String,
        token: String?,
        exchange: String = "NSE",
        segment: String = "EQUITY",
        expiryMillis: Long? = null
    ): Instrument = Instrument(
        symbol = symbol,
        name = "$symbol Ltd",
        exchange = exchange,
        segment = segment,
        instrumentType = "EQ",
        token = token,
        expiryMillis = expiryMillis
    )

    private fun key(exchange: String, symbol: String): String =
        InstrumentKey(exchange, symbol).key()
}

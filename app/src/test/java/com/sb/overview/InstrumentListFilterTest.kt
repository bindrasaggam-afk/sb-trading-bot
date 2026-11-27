package com.sb.overview

import com.sb.backend.models.Instrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentListFilterTest {

    private fun instrument(
        symbol: String,
        exchange: String,
        segment: String = "EQ",
        instrumentType: String = "EQ",
        category: String = "EQUITY",
        name: String = "$symbol $exchange",
        isin: String? = null,
        token: String? = null
    ): Instrument {
        return Instrument(
            symbol = symbol,
            name = name,
            exchange = exchange,
            segment = segment,
            instrumentType = instrumentType,
            tradingSymbol = symbol,
            token = token,
            category = category,
            isin = isin
        )
    }

    @Test
    fun sameSymbolOnTwoExchangesIsKeptAsTwoEntries() {
        val nse = instrument(
            symbol = "TCS",
            exchange = "NSE",
            isin = "INE467B01029",
            token = "11536"
        )
        val bse = instrument(
            symbol = "TCS",
            exchange = "BSE",
            isin = "INE467B01029",
            token = "532540"
        )

        val all = InstrumentListFilter.filter(listOf(nse, bse))
        assertEquals(2, all.size)

        val nseOnly = InstrumentListFilter.filter(
            instruments = listOf(nse, bse),
            exchange = "NSE"
        )
        assertEquals(1, nseOnly.size)
        assertEquals("NSE", nseOnly.first().exchange)

        val bseOnly = InstrumentListFilter.filter(
            instruments = listOf(nse, bse),
            exchange = "BSE"
        )
        assertEquals(1, bseOnly.size)
        assertEquals("BSE", bseOnly.first().exchange)
    }

    @Test
    fun categoryBucketsCoverAllInstrumentTypes() {
        val samples = listOf(
            instrument(
                "NIFTY 50", "NSE", "INDEX", "INDEX", "INDEX"
            ),
            instrument("GOLD", "MCX", "COMMODITY", "FUT", "COMMODITY"),
            instrument("NIFTYFUT", "NSE", "FO", "FUT", "F&O"),
            instrument("NIFTYBEES", "NSE", "ETF", "ETF", "ETF"),
            instrument("GOI2030", "NSE", "BOND", "BOND", "BOND"),
            instrument("HDFCMF", "NSE", "MF", "MF", "MUTUAL_FUND"),
            instrument("RELIANCE", "NSE", "EQ", "EQ", "EQUITY"),
            instrument("SOMETHING", "NSE", "OTHER", "OTHER", "OTHER")
        )

        val buckets = samples.associate {
            it.symbol to InstrumentListFilter.categoryBucket(it)
        }

        assertEquals("Index", buckets["NIFTY 50"])
        assertEquals("Commodity", buckets["GOLD"])
        assertEquals("F&O", buckets["NIFTYFUT"])
        assertEquals("ETF", buckets["NIFTYBEES"])
        assertEquals("Bond", buckets["GOI2030"])
        assertEquals("MF", buckets["HDFCMF"])
        assertEquals("Equity", buckets["RELIANCE"])
        assertEquals("Other", buckets["SOMETHING"])
    }

    @Test
    fun searchMatchesSymbolNameIsinAndToken() {
        val samples = listOf(
            instrument(
                "TCS", "NSE",
                name = "Tata Consultancy Services",
                isin = "INE467B01029",
                token = "11536"
            ),
            instrument(
                "INFY", "NSE",
                name = "Infosys Limited",
                isin = "INE009A01021",
                token = "1594"
            )
        )

        assertEquals(
            1,
            InstrumentListFilter.filter(samples, query = "tata").size
        )
        assertEquals(
            1,
            InstrumentListFilter.filter(samples, query = "INE009A01021").size
        )
        assertEquals(
            1,
            InstrumentListFilter.filter(samples, query = "11536").size
        )
        assertEquals(
            1,
            InstrumentListFilter.filter(samples, query = "consultancy").size
        )
    }

    @Test
    fun categoryFilterKeepsEveryMatchingInstrument() {
        val samples = listOf(
            instrument("A", "NSE", "EQ", "EQ", "EQUITY"),
            instrument("B", "BSE", "EQ", "EQ", "EQUITY"),
            instrument("C", "NSE", "FO", "FUT", "F&O"),
            instrument("D", "MCX", "COMMODITY", "FUT", "COMMODITY")
        )

        val equity = InstrumentListFilter.filter(
            samples,
            category = "Equity"
        )
        assertEquals(2, equity.size)
        assertTrue(equity.all {
            InstrumentListFilter.categoryBucket(it) == "Equity"
        })
    }
}

package com.sb.backend.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

class InstrumentMasterImportTest {

    private val header = "pSymbol,pSymbolName,pTrdSymbol,pExchange," +
        "pExchSeg,pGroup,pDesc,pISIN,pAssetCode,pAMCCode,pSegment," +
        "dTickSize ,lLotSize ,lExpiryDate ,pIssueDate,pMaturityDate," +
        "CASEligible"

    private fun line(
        token: String,
        raw: String,
        trading: String,
        exchange: String = "NSE",
        segment: String = "nse_cm",
        group: String = "",
        description: String = raw,
        isin: String = "",
        instrumentSegment: String = "",
        tickSize: String = "1",
        lotSize: String = "1",
        issue: String = "504489600",
        caseEligible: String = "false"
    ): String {
        return listOf(
            token,
            raw,
            trading,
            exchange,
            segment,
            group,
            description,
            isin,
            "",
            "",
            instrumentSegment,
            tickSize,
            lotSize,
            "-1",
            issue,
            "0",
            caseEligible
        ).joinToString(",")
    }

    @Test
    fun parserHandlesQuotedFieldsAndRequiredColumns() {
        val csv = header + "\n" + line(
            token = "1",
            raw = "INFY",
            trading = "INFY-EQ",
            group = "EQ",
            description = "\"Infosys, Ltd\"",
            isin = "INE009A01021",
            instrumentSegment = "EQ",
            tickSize = "5"
        ) + "\n"

        val parsed = InstrumentCsvParser.parse(StringReader(csv))

        assertEquals(0, parsed.errors.size)
        assertEquals(1, parsed.rows.size)

        val row = parsed.rows.single()
        assertEquals("1", row.token)
        assertEquals("INFY", row.rawSymbol)
        assertEquals("Infosys, Ltd", row.description)
        assertEquals("INE009A01021", row.isin)
        assertEquals("5", row.tickSizeRaw)
        assertEquals("504489600", row.issueRaw)
    }

    @Test
    fun classifierCoversIndexEquityEtfBondMfAndOther() {
        val csv = header + "\n" +
            line("0", "NIFTY", "NIFTY", group = "", description = "Nifty 50", isin = "", instrumentSegment = "") + "\n" +
            line("1", "INFY", "INFY-EQ", group = "EQ", description = "Infosys", isin = "INE009A01021", instrumentSegment = "EQ", tickSize = "5") + "\n" +
            line("2", "NIFTYBEES", "NIFTYBEES-EQ", group = "EQ", description = "Nifty BeES", isin = "INF204KB14I2", instrumentSegment = "EQ", caseEligible = "true") + "\n" +
            line("3", "IMC1", "IMC1-N1", group = "N1", description = "NCD", isin = "INE009A01021", instrumentSegment = "N1", lotSize = "100") + "\n" +
            line("4", "ABC", "ABC-SG", group = "SG", description = "SDL", isin = "IN0020000001", instrumentSegment = "SG", lotSize = "100") + "\n" +
            line("5", "MF1", "MF1-MF", group = "MF", description = "MF", isin = "INF200K01010", instrumentSegment = "MF") + "\n" +
            line("6", "IV1", "IV1-IV", group = "IV", description = "InvIT", isin = "INE000000001", instrumentSegment = "IV") + "\n"

        val parsed = InstrumentCsvParser.parse(StringReader(csv))
        val classified = InstrumentClassifier.classify(parsed.rows)

        assertEquals(0, classified.errors.size)
        assertEquals(7, classified.instruments.size)

        val byRaw = classified.instruments.associateBy { it.rawSymbol }
        assertEquals("NIFTY 50", byRaw.getValue("NIFTY").symbol)
        assertEquals("INDEX", byRaw.getValue("NIFTY").category)
        assertEquals("EQUITY", byRaw.getValue("INFY").category)
        assertEquals("NSE_CASH", byRaw.getValue("INFY").subcategory)
        assertEquals("ETF", byRaw.getValue("NIFTYBEES").category)
        assertEquals("NCD", byRaw.getValue("IMC1").subcategory)
        assertEquals("SDL", byRaw.getValue("ABC").subcategory)
        assertEquals("MUTUAL_FUND", byRaw.getValue("MF1").category)
        assertEquals("OTHER", byRaw.getValue("IV1").category)
    }

    @Test
    fun duplicateRawSymbolUsesTradingSymbol() {
        val csv = header + "\n" +
            line("1", "IMC1", "IMC1-N1", group = "N1", description = "NCD 1", instrumentSegment = "N1", lotSize = "100") + "\n" +
            line("2", "IMC1", "IMC1-N2", group = "N2", description = "NCD 2", instrumentSegment = "N2", lotSize = "100") + "\n"

        val parsed = InstrumentCsvParser.parse(StringReader(csv))
        val classified = InstrumentClassifier.classify(parsed.rows)
        val symbols = classified.instruments.map { it.symbol }.toSet()

        assertEquals(setOf("IMC1-N1", "IMC1-N2"), symbols)
    }

    @Test
    fun classifiesRepositoryCsvWhenAvailable() {
        val source = sequenceOf(
            File("../sb.csv"),
            File("sb.csv")
        ).firstOrNull { it.isFile }
        assumeTrue("Repository sb.csv is not available", source != null)

        val parsed = source!!.bufferedReader().use {
            InstrumentCsvParser.parse(it)
        }
        val classified = InstrumentClassifier.classify(parsed.rows)

        assertEquals(10_064, parsed.rows.size)
        assertEquals(0, parsed.errors.size)
        assertEquals(0, classified.errors.size)
        assertEquals(10_064, classified.instruments.size)
        assertEquals(3_218, classified.categoryCounts["EQUITY"])
        assertEquals(360, classified.categoryCounts["ETF"])
        assertEquals(4, classified.categoryCounts["INDEX"])
        assertEquals(6_276, classified.categoryCounts["BOND"])
        assertEquals(172, classified.categoryCounts["MUTUAL_FUND"])
        assertEquals(34, classified.categoryCounts["OTHER"])

        val indexSymbols = classified.instruments
            .filter { it.category == "INDEX" }
            .map { it.symbol }
            .toSet()
        assertEquals(
            setOf("NIFTY 50", "BANK NIFTY", "FINNIFTY", "MIDCPNIFTY"),
            indexSymbols
        )

        val allSymbols = classified.instruments.map { it.symbol }
        assertEquals(allSymbols.size, allSymbols.distinct().size)
        assertTrue(allSymbols.contains("IMC1-N1"))
        assertTrue(allSymbols.contains("IMC1-N2"))
        assertTrue(allSymbols.contains("IMC1-N3"))
        assertTrue(allSymbols.contains("ELECTCAST"))
        assertTrue(allSymbols.contains("ELECTCAST-W1"))
        assertTrue(allSymbols.contains("MOTHERSON"))
        assertTrue(allSymbols.contains("MOTHERSON-D1"))

        val subcategories = classified.instruments
            .groupingBy { it.subcategory }
            .eachCount()
        assertEquals(2_397, subcategories["NSE_CASH"])
        assertEquals(484, subcategories["NSE_SME"])
        assertEquals(204, subcategories["NSE_T2T"])
        assertEquals(99, subcategories["NSE_SME_T2T"])
        assertEquals(34, subcategories["NSE_T2T_SURVEILLANCE"])
        assertEquals(4_359, subcategories["SDL"])
        assertEquals(1_661, subcategories["NCD"])
        assertEquals(133, subcategories["GSEC"])
        assertEquals(79, subcategories["TBILL"])
        assertEquals(43, subcategories["SGB"])
        assertEquals(1, subcategories["FCD"])
    }

    @Test
    fun epochParserHandlesSecondsAndBlankValues() {
        assertEquals(504489600000L, InstrumentClassifier.parseEpochMillis("504489600"))
        assertNull(InstrumentClassifier.parseEpochMillis(""))
        assertNull(InstrumentClassifier.parseEpochMillis("-1"))
        assertTrue(
            InstrumentClassifier.parseEpochMillis("1985-12-31")!! > 0L
        )
    }
}

package com.sb.researchanalytics.domain

import com.sb.backend.models.OrderStatus
import com.sb.backend.models.PaperExecution
import com.sb.backend.models.PaperExecutionKind
import com.sb.backend.models.PaperOrder
import com.sb.signals.TestSignals
import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.SignalIndicatorSnapshot
import com.sb.signals.domain.SignalLifecycleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchAnalyticsEngineTest {

    @Test
    fun separatesPendingPaperFromCompletedResearch() {
        val win = signal(
            id = 1L,
            status = SignalLifecycleStatus.TARGET_3_HIT,
            outcomeNet = 2.0,
            outcomeR = 2.0,
            confirm = true
        )
        val loss = signal(
            id = 2L,
            status = SignalLifecycleStatus.STOPLOSS_HIT,
            outcomeNet = -1.0,
            outcomeR = -1.0,
            confirm = false
        )
        val pending = signal(
            id = 3L,
            status = SignalLifecycleStatus.NEW
        )
        val closedOrder = PaperOrder(
            id = 100L,
            symbol = "TEST",
            side = "BUY",
            quantity = 10,
            entryPrice = 100.0,
            exitPrice = 110.0,
            realizedPnl = 100.0,
            status = OrderStatus.CLOSED.name,
            signalId = 1L
        )
        val pendingOrder = PaperOrder(
            id = 101L,
            symbol = "TEST",
            side = "BUY",
            quantity = 10,
            entryPrice = 100.0,
            status = OrderStatus.PENDING.name,
            signalId = 3L
        )
        val orphanOrder = PaperOrder(
            id = 102L,
            symbol = "ORPHAN",
            side = "BUY",
            quantity = 1,
            entryPrice = 10.0,
            status = OrderStatus.OPEN.name,
            signalId = 999L
        )
        val execution = PaperExecution(
            id = 10L,
            orderId = 100L,
            signalId = 1L,
            kind = PaperExecutionKind.TARGET_1,
            quantity = 10,
            price = 110.0,
            grossPnl = 100.0,
            netPnl = 100.0,
            executedAt = TestSignals.testNow,
            eventKey = "test"
        )
        val rows = listOf(win, loss, pending).map { signal ->
            val orders = when (signal.id) {
                1L -> listOf(closedOrder)
                3L -> listOf(pendingOrder)
                else -> emptyList()
            }
            val executions = if (signal.id == 1L) {
                listOf(execution)
            } else {
                emptyList()
            }
            SignalAnalysisRow(
                signal = signal,
                snapshot = ResearchSnapshotFactory.fromSignal(signal),
                paperOrders = orders,
                events = emptyList(),
                paperExecutions = executions
            )
        }
        val dataset = AnalyticsDataset(
            generatedAt = TestSignals.testNow,
            dataThrough = TestSignals.testNow,
            filter = AnalyticsFilter(),
            signals = rows.map { it.signal },
            rows = rows,
            allSignalIds = rows.map { it.signal.id }.toSet(),
            allPaperOrders = listOf(closedOrder, pendingOrder, orphanOrder),
            allPaperExecutions = listOf(execution),
            paperAccount = null,
            snapshotTableAvailable = true
        )

        val report = ResearchAnalyticsEngine.analyze(dataset)

        assertEquals(3, report.research.total)
        assertEquals(2, report.research.completed)
        assertEquals(1, report.research.wins)
        assertEquals(1, report.research.losses)
        assertEquals(50.0, report.research.winRate ?: 0.0, 0.0)
        assertEquals(1, report.research.pending)
        assertEquals(1, report.paper.completed)
        assertEquals(1, report.paper.wins)
        assertEquals(2, report.paper.pending)
        assertEquals(2, report.reconciliation.linkedSignals)
        assertEquals(1, report.reconciliation.orphanPaperOrders)
        assertEquals(1, report.reconciliation.paperPendingWithoutExecution)
        assertTrue(
            report.dataQuality.reconstructedSnapshots >= 1
        )
        assertEquals(
            1,
            report.indicators.count {
                it.indicator == "EMA trend" && it.confirmed
            }
        )
        assertEquals(3, report.combinations.size)
    }

    @Test
    fun reportIncludesAllRequiredWorkbookSheets() {
        val signal = signal(
            id = 1L,
            status = SignalLifecycleStatus.NEW
        )
        val row = SignalAnalysisRow(
            signal = signal,
            snapshot = ResearchSnapshotFactory.fromSignal(signal),
            paperOrders = emptyList(),
            events = emptyList()
        )
        val report = ResearchAnalyticsEngine.analyze(
            AnalyticsDataset(
                generatedAt = TestSignals.testNow,
                dataThrough = TestSignals.testNow,
                filter = AnalyticsFilter(),
                signals = listOf(signal),
                rows = listOf(row),
                allSignalIds = setOf(signal.id),
                allPaperOrders = emptyList(),
                allPaperExecutions = emptyList(),
                paperAccount = null,
                snapshotTableAvailable = true
            )
        )
        val names = report.sheets.map { it.name }.toSet()
        listOf(
            "Summary",
            "Signal Outcomes",
            "Indicator Snapshots",
            "Indicator Analysis",
            "Indicator Scores",
            "Indicator Combinations",
            "Strategy Performance",
            "Entry Timing",
            "Exit Analysis",
            "Market Conditions",
            "Data Quality",
            "Reconciliation",
            "Paper Orders",
            "Paper Executions",
            "Pending Signals",
            "Closed Signals",
            "Metadata"
        ).forEach { expected ->
            assertTrue("Missing sheet $expected", expected in names)
        }
        assertTrue(
            report.sheets.all { sheet ->
                sheet.headers.isNotEmpty()
            }
        )
    }

    private fun signal(
        id: Long,
        status: SignalLifecycleStatus,
        outcomeNet: Double? = null,
        outcomeR: Double? = null,
        confirm: Boolean = true
    ): ResearchSignal = TestSignals.signal(
        id = id,
        status = status,
        outcomeNet = outcomeNet,
        outcomeR = outcomeR
    ).copy(
        indicatorSnapshot = SignalIndicatorSnapshot(
            values = mapOf(
                "Close" to 100.0,
                "EMA20" to 101.0,
                "EMA50" to 99.0,
                "EMA200" to 95.0,
                "RSI14" to 60.0,
                "MACD" to 1.0,
                "MACDSignal" to 0.5,
                "MACDHistogram" to 0.5,
                "SupertrendDirection" to 1.0,
                "ADX14" to 31.0,
                "PlusDI" to 25.0,
                "MinusDI" to 12.0,
                "VWAP" to 99.0,
                "ATR14" to 1.0,
                "Volume" to 2_000.0,
                "VolumeMA20" to 1_000.0
            ),
            confirmations = mapOf(
                "EMA trend" to confirm,
                "RSI" to confirm,
                "Higher timeframe" to true
            ),
            scores = mapOf(
                "EMA trend" to if (confirm) 18.0 else 0.0,
                "RSI" to if (confirm) 10.0 else 0.0
            )
        )
    )
}

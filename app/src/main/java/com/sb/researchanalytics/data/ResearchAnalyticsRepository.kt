package com.sb.researchanalytics.data

import android.content.Context
import android.database.Cursor
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.models.PaperExecution
import com.sb.backend.models.PaperOrder
import com.sb.backend.repositories.PaperAccount
import com.sb.backend.repositories.PaperRepository
import com.sb.researchanalytics.domain.AnalyticsDataset
import com.sb.researchanalytics.domain.AnalyticsFilter
import com.sb.researchanalytics.domain.AnalyticsTime
import com.sb.researchanalytics.domain.ResearchSnapshotFactory
import com.sb.researchanalytics.domain.SignalAnalysisRow
import com.sb.researchanalytics.domain.SnapshotPair
import com.sb.signals.data.SignalJson
import com.sb.signals.data.SignalMigrations
import com.sb.signals.domain.ResearchSignal
import com.sb.signals.domain.SignalEvent
import com.sb.signals.services.SignalsPlatform
import kotlin.math.max

/**
 * Read-only access to research and paper data. This repository never inserts,
 * updates or deletes signal, paper, order or trade rows.
 */
class ResearchAnalyticsRepository(
    private val context: Context
) {

    fun load(
        filter: AnalyticsFilter = AnalyticsFilter(),
        signalLimit: Int = 50000
    ): AnalyticsDataset {
        val signalRepository = SignalsPlatform.repository(context)
        val allSignals = signalRepository.getAllSignals(signalLimit)
        val events = signalRepository.getAllEvents(signalLimit * 2)
        val paperRepository = PaperRepository(
            SBDatabaseHelper(context.applicationContext)
        )
        val paperOrders = paperRepository.getOrders()
        val paperExecutions = paperRepository.getExecutions(
            limit = 100000
        )
        val account = paperRepository.getAccount()

        val snapshotResult = loadStoredSnapshots(
            allSignals.associateBy { it.id }
        )
        val snapshots = snapshotResult.first
        val snapshotError = snapshotResult.second

        val filteredSignals = allSignals.filter(filter::matches)
        val eventsBySignal = events.groupBy(SignalEvent::signalId)
        val ordersBySignal = paperOrders
            .mapNotNull { order ->
                order.signalId?.let { it to order }
            }
            .groupBy({ it.first }, { it.second })
        val executionsByOrder = paperExecutions.groupBy {
            it.orderId
        }
        val executionsBySignal = paperExecutions
            .mapNotNull { execution ->
                execution.signalId?.let { it to execution }
            }
            .groupBy({ it.first }, { it.second })

        var rows = filteredSignals.map { signal ->
            val snapshot = snapshots[signal.id]
                ?: ResearchSnapshotFactory.fromSignal(
                    signal,
                    capturedAt = signal.generatedAt
                )
            val orders = ordersBySignal[signal.id].orEmpty()
            val orderExecutions = orders.flatMap { order ->
                executionsByOrder[order.id].orEmpty()
            }.distinctBy { it.id }
            val legacyExecutions = executionsBySignal[
                signal.id
            ].orEmpty()
            val linkedExecutions = (orderExecutions + legacyExecutions)
                .distinctBy { it.id }
            SignalAnalysisRow(
                signal = signal,
                snapshot = snapshot,
                paperOrders = orders,
                events = eventsBySignal[signal.id].orEmpty(),
                paperExecutions = linkedExecutions
            )
        }
        if (filter.paperFilter != AnalyticsFilter.ALL) {
            rows = rows.filter { row ->
                when (filter.paperFilter) {
                    AnalyticsFilter.PAPER_LINKED ->
                        row.paperOrders.isNotEmpty()
                    AnalyticsFilter.PAPER_PENDING ->
                        !row.paperClosed
                    AnalyticsFilter.PAPER_CLOSED ->
                        row.paperClosed
                    AnalyticsFilter.RESEARCH_ONLY ->
                        row.paperOrders.isEmpty()
                    else -> true
                }
            }
        }

        val dataThrough = max(
            rows.maxOfOrNull { it.signal.generatedAt } ?: 0L,
            max(
                paperOrders.maxOfOrNull { it.updatedAt } ?: 0L,
                paperExecutions.maxOfOrNull { it.executedAt } ?: 0L
            )
        ).takeIf { it > 0L } ?: System.currentTimeMillis()

        return AnalyticsDataset(
            generatedAt = System.currentTimeMillis(),
            dataThrough = dataThrough,
            filter = filter,
            signals = rows.map { it.signal },
            rows = rows,
            allSignalIds = allSignals.mapTo(HashSet()) { it.id },
            allPaperOrders = paperOrders,
            allPaperExecutions = paperExecutions,
            paperAccount = account,
            snapshotTableAvailable = snapshotError == null,
            snapshotTableError = snapshotError
        )
    }

    private fun loadStoredSnapshots(
        signals: Map<Long, ResearchSignal>
    ): Pair<
        Map<Long, SnapshotPair>,
        String?
        > {
        val result = HashMap<Long, SnapshotPair>()
        val payloads = HashMap<Long, StoredSnapshot>()
        return try {
            val database = SignalsPlatform.database(context)
                .readableDatabase
            database.query(
                SignalMigrations.TABLE_INDICATOR_SNAPSHOTS,
                arrayOf(
                    "signal_id",
                    "source",
                    "captured_at",
                    "indicator_values_json",
                    "indicator_scores_json",
                    "confirmations_json",
                    "parameters_json"
                ),
                null,
                null,
                null,
                null,
                "signal_id ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val signalId = cursor.getLong(0)
                    val source = cursor.getStringOrNull(1)
                        ?: "CAPTURED"
                    val capturedAt = cursor.getLong(2)
                    val values = SignalJson.decodeDoubleMap(
                        cursor.getStringOrNull(3)
                    )
                    val scores = SignalJson.decodeDoubleMap(
                        cursor.getStringOrNull(4)
                    )
                    val confirmations = SignalJson.decodeBooleanMap(
                        cursor.getStringOrNull(5)
                    )
                    val parameters = SignalJson.decodeDoubleMap(
                        cursor.getStringOrNull(6)
                    )
                    payloads[signalId] = StoredSnapshot(
                        values = values,
                        scores = scores,
                        confirmations = confirmations,
                        parameters = parameters,
                        source = source,
                        capturedAt = capturedAt
                    )
                }
            }
            payloads.forEach { (signalId, stored) ->
                val signal = signals[signalId] ?: return@forEach
                result[signalId] = ResearchSnapshotFactory.fromStored(
                    signal = signal,
                    values = stored.values,
                    confirmations = stored.confirmations,
                    scores = stored.scores,
                    parameters = stored.parameters,
                    source = stored.source,
                    capturedAt = stored.capturedAt
                )
            }
            payloads.clear()
            result to null
        } catch (error: Exception) {
            payloads.clear()
            result to (error.message
                ?: "Snapshot table unavailable")
        }
    }

    private data class StoredSnapshot(
        val values: Map<String, Double?>,
        val scores: Map<String, Double?>,
        val confirmations: Map<String, Boolean>,
        val parameters: Map<String, Double?>,
        val source: String,
        val capturedAt: Long
    )

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

}

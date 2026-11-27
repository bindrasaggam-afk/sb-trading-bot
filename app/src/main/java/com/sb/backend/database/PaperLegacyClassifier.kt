package com.sb.backend.database

import java.util.Locale

enum class PaperRecordClass {
    GENUINE_SIGNAL,
    TEST_GENERATED,
    UNCERTAIN
}

object PaperLegacyClassifier {

    private val testGeneratedSources = setOf(
        "MARKET",
        "MANUAL",
        "LEGACY",
        ""
    )

    fun classifyOrder(
        signalId: Long?,
        entrySource: String?
    ): PaperRecordClass {
        if (signalId != null && signalId > 0L) {
            return PaperRecordClass.GENUINE_SIGNAL
        }
        val source = entrySource?.trim()?.uppercase(Locale.ROOT) ?: ""
        return if (source in testGeneratedSources) {
            PaperRecordClass.TEST_GENERATED
        } else {
            PaperRecordClass.UNCERTAIN
        }
    }

    fun classifyChild(
        parentClass: PaperRecordClass?,
        childSignalId: Long?
    ): PaperRecordClass {
        if (parentClass == PaperRecordClass.TEST_GENERATED) {
            return PaperRecordClass.TEST_GENERATED
        }
        if (childSignalId != null && childSignalId > 0L) {
            return PaperRecordClass.GENUINE_SIGNAL
        }
        return when (parentClass) {
            PaperRecordClass.GENUINE_SIGNAL ->
                PaperRecordClass.GENUINE_SIGNAL
            PaperRecordClass.TEST_GENERATED ->
                PaperRecordClass.TEST_GENERATED
            PaperRecordClass.UNCERTAIN, null ->
                PaperRecordClass.UNCERTAIN
        }
    }
}

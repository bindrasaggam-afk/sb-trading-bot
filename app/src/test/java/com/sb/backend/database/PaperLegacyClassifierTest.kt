package com.sb.backend.database

import org.junit.Assert.assertEquals
import org.junit.Test

class PaperLegacyClassifierTest {

    @Test
    fun `signal linked orders are genuine`() {
        assertEquals(
            PaperRecordClass.GENUINE_SIGNAL,
            PaperLegacyClassifier.classifyOrder(42L, "SIGNAL")
        )
        assertEquals(
            PaperRecordClass.GENUINE_SIGNAL,
            PaperLegacyClassifier.classifyOrder(42L, null)
        )
    }

    @Test
    fun `known fixture sources without signals are test generated`() {
        listOf("MARKET", "MANUAL", "LEGACY", "", null).forEach { source ->
            assertEquals(
                PaperRecordClass.TEST_GENERATED,
                PaperLegacyClassifier.classifyOrder(null, source)
            )
        }
    }

    @Test
    fun `unknown provenance is preserved as uncertain`() {
        assertEquals(
            PaperRecordClass.UNCERTAIN,
            PaperLegacyClassifier.classifyOrder(null, "SIGNAL")
        )
        assertEquals(
            PaperRecordClass.UNCERTAIN,
            PaperLegacyClassifier.classifyOrder(null, "IMPORTED")
        )
    }

    @Test
    fun `children follow parent provenance without creating orphans`() {
        assertEquals(
            PaperRecordClass.TEST_GENERATED,
            PaperLegacyClassifier.classifyChild(
                PaperRecordClass.TEST_GENERATED,
                99L
            )
        )
        assertEquals(
            PaperRecordClass.GENUINE_SIGNAL,
            PaperLegacyClassifier.classifyChild(
                PaperRecordClass.GENUINE_SIGNAL,
                null
            )
        )
        assertEquals(
            PaperRecordClass.GENUINE_SIGNAL,
            PaperLegacyClassifier.classifyChild(null, 7L)
        )
        assertEquals(
            PaperRecordClass.UNCERTAIN,
            PaperLegacyClassifier.classifyChild(null, null)
        )
    }
}

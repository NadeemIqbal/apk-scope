package com.nadeem.apkscope.domain.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Milestone 9 (Pixel 8 acceptance, fifth pass, items 2/3): real, on-device coverage for
 * [UrlEvidenceImportStatusStore]'s two fixes this pass —
 * (a) the store now derives its path from `context.applicationContext.filesDir` instead of a
 *     hardcoded `/data/data/com.nadeem.apkscope/...` path (the same fragility class MS9-PER02 already
 *     fixed for `StaticAnalysisResultStore`, found while tracing a genuinely completed
 *     physical-device session's missing import status);
 * (b) [UrlEvidenceImportStatusStore.markPending] gives a real, durable "started but not concluded"
 *     signal, distinguishable from both "never attempted" (no record at all) and any concluded
 *     outcome — real device storage, not a JVM temp directory.
 */
@RunWith(AndroidJUnit4::class)
class UrlEvidenceImportStatusStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun neverRecorded_returnsNull_distinctFromAnyConcludedOrPendingOutcome() {
        val analysisId = "status-store-never-${UUID.randomUUID()}"
        assertNull("an analysis this store has never seen must read back as null, not a fabricated outcome", UrlEvidenceImportStatusStore.get(context, analysisId))
    }

    @Test
    fun markPending_isDurableAndReadsBackAsPendingWithZeroEntries_onRealDeviceStorage() {
        val analysisId = "status-store-pending-${UUID.randomUUID()}"
        UrlEvidenceImportStatusStore.markPending(context, analysisId)

        val read = UrlEvidenceImportStatusStore.get(context, analysisId)
        assertEquals(UrlEvidenceImportStatusStore.Status.PENDING, read?.status)
        assertEquals(0, read?.entryCount)
        assertNull("a pending marker must never carry a fabricated error", read?.error)
    }

    @Test
    fun aConcludedOutcome_overwritesAnEarlierPendingMarker_onRealDeviceStorage() {
        val analysisId = "status-store-overwrite-${UUID.randomUUID()}"
        UrlEvidenceImportStatusStore.markPending(context, analysisId)
        assertEquals(UrlEvidenceImportStatusStore.Status.PENDING, UrlEvidenceImportStatusStore.get(context, analysisId)?.status)

        UrlEvidenceImportStatusStore.record(
            context, analysisId,
            UrlEvidenceImportStatusStore.Outcome(System.currentTimeMillis(), entryCount = 1, truncated = false, error = null),
        )
        val read = UrlEvidenceImportStatusStore.get(context, analysisId)
        assertEquals("a real conclusion must replace the earlier pending marker, not coexist with it", UrlEvidenceImportStatusStore.Status.IMPORTED, read?.status)
        assertEquals(1, read?.entryCount)
    }

    @Test
    fun emptySuccessfulOutcome_isDistinctFromFailedAndFromPending() {
        val analysisId = "status-store-empty-${UUID.randomUUID()}"
        UrlEvidenceImportStatusStore.record(
            context, analysisId,
            UrlEvidenceImportStatusStore.Outcome(System.currentTimeMillis(), entryCount = 0, truncated = false, error = null),
        )
        val read = UrlEvidenceImportStatusStore.get(context, analysisId)
        assertEquals("a genuine zero-entry success must read as EMPTY, not PENDING or FAILED", UrlEvidenceImportStatusStore.Status.EMPTY, read?.status)
    }

    @Test
    fun failedOutcome_isReportedAsFailedWithItsRealError() {
        val analysisId = "status-store-failed-${UUID.randomUUID()}"
        UrlEvidenceImportStatusStore.record(
            context, analysisId,
            UrlEvidenceImportStatusStore.Outcome(System.currentTimeMillis(), entryCount = 0, truncated = false, error = "IOException: simulated"),
        )
        val read = UrlEvidenceImportStatusStore.get(context, analysisId)
        assertEquals(UrlEvidenceImportStatusStore.Status.FAILED, read?.status)
        assertEquals("IOException: simulated", read?.error)
    }
}

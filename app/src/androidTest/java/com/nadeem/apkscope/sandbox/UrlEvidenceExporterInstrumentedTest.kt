package com.nadeem.apkscope.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nadeem.apkscope.core.network.traffic.OwnershipVerificationStatus
import com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore
import com.nadeem.apkscope.core.network.traffic.TrafficProtocol
import com.nadeem.apkscope.core.network.traffic.TrafficRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Milestone 9 (fourth acceptance rigor pass, item 1): the genuinely **on-device, instrumented**
 * counterpart to `UrlEvidenceExporterTest` (JVM-level) — this requirement's own text asks for an
 * on-device automated assertion specifically, not merely a fast JVM one, so both are kept rather than
 * treating the JVM test as sufficient on its own. Same real production
 * [UrlEvidenceExporter.export] function, same clearly-labeled synthetic Play-Store-interference-shaped
 * input, run via `AndroidJUnit4` on `emulator-5554`.
 */
@RunWith(AndroidJUnit4::class)
class UrlEvidenceExporterInstrumentedTest {
    @Before
    fun setup() { TrafficInspectionStore.clear() }

    @After
    fun tearDown() { TrafficInspectionStore.clear() }

    @Test
    fun onDevice_export_includesTargetTransactionByIdentity_andExcludesTheUnrelatedSecondApplicationTransaction() {
        val sessionId = "on-device-export-boundary-session"
        val targetPackage = "com.apksandbox.fixture"

        TrafficInspectionStore.record(
            TrafficRecord(
                id = "on-device-matched-jsonplaceholder", sessionId = sessionId, targetPackage = targetPackage,
                observedOwnerUid = 1110292, ownershipStatus = OwnershipVerificationStatus.MATCHED,
                protocol = TrafficProtocol.HTTPS, host = "jsonplaceholder.typicode.com",
                url = "https://jsonplaceholder.typicode.com/posts/1", method = "GET", statusCode = 200,
            )
        )
        TrafficInspectionStore.record(
            TrafficRecord(
                id = "on-device-mismatched-play-store", sessionId = sessionId, targetPackage = targetPackage,
                observedOwnerUid = 1110153, ownershipStatus = OwnershipVerificationStatus.MISMATCHED,
                protocol = TrafficProtocol.HTTPS, host = "play.googleapis.com",
                url = "https://play.googleapis.com/some/path", method = "GET", statusCode = 200,
            )
        )

        val artifact = UrlEvidenceExporter.export(sessionId, targetPackage)

        assertEquals(1, artifact.entries.size)
        val exported = artifact.entries.single()
        assertEquals("on-device-matched-jsonplaceholder", exported.trafficTransactionId)
        assertEquals("https://jsonplaceholder.typicode.com/posts/1", exported.requestUrl)
        assertEquals(sessionId, exported.analysisSessionId)
        assertEquals(targetPackage, exported.analysisTargetPackage)

        assertFalse(
            "the second application's transaction id must not appear anywhere in the exported entries, verified on-device",
            artifact.entries.any { it.trafficTransactionId == "on-device-mismatched-play-store" },
        )
        assertEquals("both records genuinely exist in the raw, unfiltered store", 2, TrafficInspectionStore.all().size)
    }
}

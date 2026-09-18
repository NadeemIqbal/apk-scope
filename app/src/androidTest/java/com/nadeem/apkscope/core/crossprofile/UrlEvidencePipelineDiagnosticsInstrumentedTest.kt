package com.nadeem.apkscope.core.crossprofile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Milestone 9 (Pixel 8 acceptance, fifth pass, item 2): real, on-device coverage for
 * [UrlEvidencePipelineDiagnostics] itself — this infrastructure was added and relied on throughout
 * this pass (`SandboxSessionCoordinator`/`SandboxCleanupViewModel`/`SandboxWorkQueryActivity` all
 * call it) but had no direct test of its own record/read round trip or its bounded-retention
 * guarantee. Writes go through a real background writer thread onto real device storage
 * (`context.filesDir`), not a fake/in-memory stand-in — the same discipline every other durable
 * store in this codebase is held to.
 */
@RunWith(AndroidJUnit4::class)
class UrlEvidencePipelineDiagnosticsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Polls for up to 5s — [UrlEvidencePipelineDiagnostics.record] hands off to an async writer thread; a bare, unretried read right after `record` would be racy, not a real assertion. */
    private fun readEventually(operationId: String, expectedCount: Int): List<String> {
        val deadline = System.currentTimeMillis() + 5_000
        var last = emptyList<String>()
        while (System.currentTimeMillis() < deadline) {
            last = UrlEvidencePipelineDiagnostics.read(context, operationId)
            if (last.size >= expectedCount) return last
            Thread.sleep(50)
        }
        return last
    }

    @Test
    fun recordAndRead_roundTripsRealFieldsAndFiltersByOperationId_onRealDeviceStorage() {
        val opA = "diag-test-a-${UUID.randomUUID()}"
        val opB = "diag-test-b-${UUID.randomUUID()}"
        val sessionId = "diag-test-session-${UUID.randomUUID()}"

        UrlEvidencePipelineDiagnostics.record(context, opA, "personal", sessionId, "import_scheduled", "detail-a")
        UrlEvidencePipelineDiagnostics.record(context, opA, "work", sessionId, "work_query_received", "detail-b")
        UrlEvidencePipelineDiagnostics.record(context, opB, "personal", sessionId, "import_scheduled", "unrelated-operation")

        val forA = readEventually(opA, 2)
        assertTrue("expected both opA lines to be durably recorded and readable, got: $forA", forA.size == 2)
        assertFalse(
            "read(..., opA) must never leak a different operation's line",
            forA.any { it.contains(opB) },
        )

        val first = JSONObject(forA[0])
        assertTrue("recorded lines must be readable oldest-first", first.getString("stage") == "import_scheduled")
        assertTrue("op id must round-trip exactly", first.getString("op") == opA)
        assertTrue("profile must round-trip exactly", first.getString("profile") == "personal")
        assertTrue("sessionId must round-trip exactly", first.getString("sessionId") == sessionId)
        assertTrue("detail must round-trip exactly, never dropped", first.getString("detail") == "detail-a")

        val second = JSONObject(forA[1])
        assertTrue(second.getString("profile") == "work")
        assertTrue(second.getString("stage") == "work_query_received")
    }

    @Test
    fun neverRecorded_readsAsEmpty_distinctFromAnyRealOperation() {
        val neverUsedOperationId = "diag-test-never-${UUID.randomUUID()}"
        val result = UrlEvidencePipelineDiagnostics.read(context, neverUsedOperationId)
        assertTrue("an operationId this store has never seen must read back empty, not fabricated rows", result.isEmpty())
    }

    @Test
    fun retentionIsBoundedAndKeepsOnlyTheMostRecentEntries_onRealDeviceStorage() {
        // A marker unique to this test run/process, so this assertion is not affected by whatever
        // this device's file already accumulated from other tests/app runs sharing the same file.
        val marker = "diag-bound-${UUID.randomUUID()}"
        val sessionId = "diag-bound-session"
        val totalWritten = 550 // > the store's own 500-line cap, deliberately, to exercise truncation.
        for (i in 0 until totalWritten) {
            UrlEvidencePipelineDiagnostics.record(context, "op-irrelevant", "personal", sessionId, "$marker-$i")
        }

        // Wait for the async writer to drain the whole queue, then read the *unfiltered* file (the
        // cap applies to the file as a whole, not per-operationId) and look only at our own marker's
        // entries within it.
        val deadline = System.currentTimeMillis() + 15_000
        var allLines = emptyList<String>()
        while (System.currentTimeMillis() < deadline) {
            allLines = UrlEvidencePipelineDiagnostics.read(context)
            if (allLines.any { it.contains("$marker-${totalWritten - 1}") }) break
            Thread.sleep(50)
        }

        assertTrue("the file itself must never exceed the store's own bounded cap", allLines.size <= 500)
        assertTrue(
            "the most recently written entry must survive truncation",
            allLines.any { it.contains("$marker-${totalWritten - 1}") },
        )
        assertFalse(
            "the oldest entries from this same burst must be evicted once the cap is exceeded — an unbounded file would still contain this",
            allLines.any { it.contains("$marker-0\"") },
        )
    }
}

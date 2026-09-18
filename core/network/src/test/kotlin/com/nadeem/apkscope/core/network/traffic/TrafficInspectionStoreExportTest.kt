package com.nadeem.apkscope.core.network.traffic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Milestone 9 (userspace traffic ownership verification) — focused regression coverage for
 * [TrafficInspectionStore.forSession], the sole production read path behind
 * `SandboxWorkQueryActivity.exportUrlEvidence()` (the URL-evidence export DEX04 correlates
 * against). Distinct from [com.nadeem.apkscope.core.network.https.HttpsInspectionStoreTest]'s
 * mirror-overwrite regressions: this file asserts the *export* guarantee directly, against a
 * realistic mixed set of records (matched target traffic, a second real application's traffic in
 * the same Work Profile, and an install-race unknown), not just a single record's lifecycle.
 */
class TrafficInspectionStoreExportTest {

    @Before
    fun setup() {
        TrafficInspectionStore.clear()
    }

    @After
    fun tearDown() {
        TrafficInspectionStore.clear()
    }

    private fun record(
        id: String,
        sessionId: String?,
        targetPackage: String?,
        observedOwnerUid: Int?,
        status: OwnershipVerificationStatus,
        host: String = "httpbin.org",
    ) = TrafficRecord(
        id = id,
        sessionId = sessionId,
        targetPackage = targetPackage,
        observedOwnerUid = observedOwnerUid,
        ownershipStatus = status,
        protocol = TrafficProtocol.HTTPS,
        host = host,
        method = "GET",
        statusCode = 200,
    )

    /**
     * The core export guarantee: given a realistic mixed batch (the target's own genuinely-owned
     * traffic, a *second, real* application's traffic in the same Work Profile — simulating the
     * "exercise a second controlled application" scenario at the data level — and an install-race
     * UNKNOWN), only the MATCHED record reaches the export path. MISMATCHED and UNKNOWN must never
     * be silently promoted into target URL evidence merely by sharing sessionId/targetPackage.
     */
    @Test
    fun testForSessionExportOnlyIncludesMatchedOwnership() {
        val sessionId = "session-export-test"
        val targetPackage = "com.apksandbox.fixture"
        val targetUid = 1310303

        // The target's own genuine request — the only one that should ever export.
        TrafficInspectionStore.record(
            record("matched-id", sessionId, targetPackage, targetUid, OwnershipVerificationStatus.MATCHED)
        )
        // A second, real application's traffic captured in the same unscoped-VPN Work Profile
        // (Phase 9.1's platform-limitation finding) — same sessionId/targetPackage stamped by the
        // active session, but the *observed* owner is a different, real UID. Must never export.
        TrafficInspectionStore.record(
            record("mismatched-id", sessionId, targetPackage, 1310167, OwnershipVerificationStatus.MISMATCHED)
        )
        // An install-race window: ownership could not be verified at all. Must never export as if
        // it were confirmed target evidence.
        TrafficInspectionStore.record(
            record("unknown-id", sessionId, targetPackage, null, OwnershipVerificationStatus.UNKNOWN)
        )

        val exported = TrafficInspectionStore.forSession(sessionId, targetPackage)
        assertEquals("Only the MATCHED record may enter the export path", 1, exported.size)
        assertEquals("matched-id", exported[0].id)

        // All three must still be visible via the unfiltered general read path (Live Monitor) —
        // "unrelated or unknown traffic" stays distinguishable, not hidden from the app entirely.
        assertEquals(3, TrafficInspectionStore.all().size)
        val byId = TrafficInspectionStore.all().associateBy { it.id }
        assertEquals(OwnershipVerificationStatus.MISMATCHED, byId["mismatched-id"]?.ownershipStatus)
        assertEquals(OwnershipVerificationStatus.UNKNOWN, byId["unknown-id"]?.ownershipStatus)
    }

    /** A record from a genuinely unrelated session/target must never leak into this session's export, regardless of its own ownership status. */
    @Test
    fun testForSessionExcludesUnrelatedSessionEvenWhenMatched() {
        TrafficInspectionStore.record(
            record("other-session-id", "session-other", "com.apksandbox.fixture", 1310303, OwnershipVerificationStatus.MATCHED)
        )
        assertTrue(TrafficInspectionStore.forSession("session-export-test", "com.apksandbox.fixture").isEmpty())
    }

    /** A record whose sessionId matches but whose targetPackage differs (a stale/rotated session reusing an id) must not export either. */
    @Test
    fun testForSessionExcludesMatchingSessionButDifferentTargetPackage() {
        val sessionId = "session-export-test-2"
        TrafficInspectionStore.record(
            record("wrong-target-id", sessionId, "com.apksandbox.riskfixture", 1310280, OwnershipVerificationStatus.MATCHED)
        )
        assertTrue(TrafficInspectionStore.forSession(sessionId, "com.apksandbox.fixture").isEmpty())
    }

    @Test
    fun testForSessionReturnsEmptyForNullSessionId() {
        assertTrue(TrafficInspectionStore.forSession(null, "com.apksandbox.fixture").isEmpty())
    }
}

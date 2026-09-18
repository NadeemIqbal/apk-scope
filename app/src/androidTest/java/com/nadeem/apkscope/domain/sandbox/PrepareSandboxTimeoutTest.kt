package com.nadeem.apkscope.domain.sandbox

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.MainActivity
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxOperationResult
import com.nadeem.apkscope.core.model.SandboxPolicy
import com.nadeem.apkscope.core.model.SandboxSessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Milestone 9 (fourth acceptance rigor pass, item 2): a focused, real on-device test of
 * [SandboxSessionCoordinator.prepare]'s active-session-query timeout guard — the fix for the
 * previously-observed indefinite "Preparing Sandbox" hang. Uses
 * [DefaultSandboxSessionCoordinator]'s `activeSessionQueryOverride` test seam (production code path
 * otherwise completely unchanged — every real caller leaves it null) to deterministically simulate a
 * cross-profile active-session query that never returns, without needing to actually reproduce the
 * background-activity-launch drop on a real device.
 *
 * Real [Context]/Room via [InstrumentationRegistry], run on `emulator-5554`. A real (but otherwise
 * idle) [MainActivity] instance is used only to satisfy `prepare(activity: Activity, ...)`'s
 * signature — with the override installed, `queryWorkActiveSession` never actually dereferences it in
 * the timeout/late-callback tests below (it returns before ever reaching `Handoff.send`, the only
 * place `prepare()` uses the activity directly).
 */
@RunWith(AndroidJUnit4::class)
class PrepareSandboxTimeoutTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = SandboxSessionRepository(context)
    private val createdSessionIds = mutableListOf<String>()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: Activity

    private fun launchActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After
    fun cleanup() {
        runBlocking { createdSessionIds.forEach { repository.delete(it) } }
        if (::scenario.isInitialized) scenario.close()
    }

    private suspend fun createSession(coordinator: DefaultSandboxSessionCoordinator, label: String): com.nadeem.apkscope.core.model.SandboxSession {
        val analysisId = "prepare-timeout-test-$label-${UUID.randomUUID()}"
        val result = coordinator.create(analysisId, "com.example.timeouttarget.$label", "/nonexistent/$label.apk", SandboxPolicy())
        val session = (result as SandboxOperationResult.Success).session
        createdSessionIds += session.id
        return session
    }

    @Test
    fun activeSessionQueryNeverReturning_prepareFailsClosedWithinTimeout_neverProceedsPastTheGuard() = runBlocking {
        launchActivity()
        val neverCompletes = CompletableDeferred<WorkActiveSessionSnapshot>()
        val coordinator = DefaultSandboxSessionCoordinator(context, activeSessionQueryOverride = { neverCompletes.await() })
        val session = createSession(coordinator, "hang")

        val start = System.currentTimeMillis()
        // Outer bound generous but finite: proves this is a real timeout, not an actual hang the test
        // itself would otherwise wait on forever.
        val result = withTimeout(20_000) { coordinator.prepare(activity, session.id) }
        val elapsedMs = System.currentTimeMillis() - start

        assertTrue(
            "prepare() must return within its own configured 10s bound (plus scheduling headroom), took ${elapsedMs}ms",
            elapsedMs in 9_000..18_000,
        )
        assertTrue("expected a Failure result for a query that never answers, got $result", result is SandboxOperationResult.Failure)
        val failure = result as SandboxOperationResult.Failure
        assertEquals(SandboxErrorCode.WORK_SESSION_STATE_UNKNOWN, failure.error.code)
        assertEquals(SandboxSessionState.FAILED, failure.session.state)

        // Never proceeded past the guard: the persisted row must still be FAILED — never PREPARING,
        // which is the state that would mean the real Work-side install handoff had been dispatched.
        // No competing session was established either: this is the only row this test created.
        val persisted = repository.get(session.id)
        assertEquals(SandboxSessionState.FAILED, persisted?.state)
        assertEquals(1, repository.observeForAnalysis(session.analysisId).first().size)
    }

    @Test
    fun lateCallbackAfterTimeout_doesNotResumeTheFailedOperationOrCorruptALaterPreparation() = runBlocking {
        launchActivity()
        val neverCompletesInTime = CompletableDeferred<WorkActiveSessionSnapshot>()
        val hangingCoordinator = DefaultSandboxSessionCoordinator(context, activeSessionQueryOverride = { neverCompletesInTime.await() })
        val hungSession = createSession(hangingCoordinator, "late")

        val firstResult = withTimeout(20_000) { hangingCoordinator.prepare(activity, hungSession.id) }
        assertTrue(firstResult is SandboxOperationResult.Failure)
        assertEquals(SandboxErrorCode.WORK_SESSION_STATE_UNKNOWN, (firstResult as SandboxOperationResult.Failure).error.code)

        // The "late callback": complete the deferred *after* prepare() already returned via timeout.
        // withTimeoutOrNull has already cancelled the coroutine that was awaiting it; a resume on an
        // already-cancelled CompletableDeferred must be a safe no-op, never a crash and never a
        // resurrection of the already-failed prepare() call.
        var lateCompleteThrew = false
        try {
            neverCompletesInTime.complete(WorkActiveSessionSnapshot(active = true, sessionId = "late-arriving-session", packageName = "com.example.late", startedAtEpochMs = System.currentTimeMillis()))
        } catch (_: Throwable) {
            lateCompleteThrew = true
        }
        assertTrue("completing the deferred late must not itself throw", !lateCompleteThrew)

        // The original session's persisted state must be unaffected by the late-arriving snapshot —
        // still FAILED/WORK_SESSION_STATE_UNKNOWN, not silently overwritten to reflect the late data.
        val persistedAfterLateCallback = repository.get(hungSession.id)
        assertEquals(SandboxSessionState.FAILED, persistedAfterLateCallback?.state)
        assertEquals(SandboxErrorCode.WORK_SESSION_STATE_UNKNOWN, persistedAfterLateCallback?.error?.code)

        // A later, independent preparation attempt (a fresh coordinator instance and a fresh session,
        // with a query that resolves immediately and reports no conflict) must proceed normally —
        // proving the earlier hang/late-resume left no corrupted shared state behind.
        val healthyCoordinator = DefaultSandboxSessionCoordinator(
            context,
            activeSessionQueryOverride = { WorkActiveSessionSnapshot(active = false, sessionId = null, packageName = null, startedAtEpochMs = null) },
        )
        val freshSession = createSession(healthyCoordinator, "after-late")
        val secondResult = withTimeout(5_000) { healthyCoordinator.prepare(activity, freshSession.id) }
        // Must not repeat WORK_SESSION_STATE_UNKNOWN or ANOTHER_SESSION_ACTIVE — whatever it does next
        // (likely failing later at environment preflight in this test harness, e.g. no real staged
        // APK/work profile fixture here) must be a *different*, later-stage outcome, proving this
        // fresh attempt was never blocked by the earlier hung/late-resumed one.
        val secondCode = (secondResult as? SandboxOperationResult.Failure)?.error?.code
        assertTrue(
            "a later independent prepare() must not be corrupted by the earlier hang/late callback (got $secondCode)",
            secondCode != SandboxErrorCode.WORK_SESSION_STATE_UNKNOWN && secondCode != SandboxErrorCode.ANOTHER_SESSION_ACTIVE,
        )
    }

    @Test
    fun activeSessionQueryRespondingImmediatelyWithNoConflict_prepareProceedsPastTheGuardWithoutDelay() = runBlocking {
        launchActivity()
        val coordinator = DefaultSandboxSessionCoordinator(
            context,
            activeSessionQueryOverride = { WorkActiveSessionSnapshot(active = false, sessionId = null, packageName = null, startedAtEpochMs = null) },
        )
        val session = createSession(coordinator, "normal")

        val start = System.currentTimeMillis()
        val result = withTimeout(5_000) { coordinator.prepare(activity, session.id) }
        val elapsedMs = System.currentTimeMillis() - start

        assertTrue("the no-conflict path must complete quickly, not wait anywhere near the 10s timeout bound (took ${elapsedMs}ms)", elapsedMs < 3_000)
        val code = (result as? SandboxOperationResult.Failure)?.error?.code
        assertTrue(
            "the guard itself must never fire on a prompt, no-conflict answer (got $code)",
            code != SandboxErrorCode.WORK_SESSION_STATE_UNKNOWN && code != SandboxErrorCode.ANOTHER_SESSION_ACTIVE,
        )
    }
}

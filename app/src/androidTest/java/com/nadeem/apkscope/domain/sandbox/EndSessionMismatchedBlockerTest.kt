package com.nadeem.apkscope.domain.sandbox

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.MainActivity
import com.nadeem.apkscope.core.model.Recoverability
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxOperationResult
import com.nadeem.apkscope.core.model.SandboxPolicy
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxSessionState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduces, at the coordinator level, the on-device bug confirmed manually on `emulator-5554`:
 * a `FAILED` session whose own error is `ANOTHER_SESSION_ACTIVE` is blocked by a *different*
 * session's live Work-side VPN — not by its own id. Before this fix, [DefaultSandboxSessionCoordinator.end]'s
 * terminal-state branch only ever tore down a same-id match and silently reported [SandboxOperationResult.Success]
 * for every other case, which meant the Preparing screen's own "End Session" button could never
 * actually clear the conflict it exists to resolve.
 *
 * Uses the same `activeSessionQueryOverride` test seam as `PrepareSandboxTimeoutTest` to deterministically
 * simulate Work's answer, without depending on a real cross-profile round trip's timing. `Handoff.send`
 * itself is *not* mocked — it fires a real cross-profile push to the Work profile for a synthetic,
 * harmless session/package id, exactly like `PrepareSandboxTimeoutTest`'s own real `prepare()` calls
 * against nonexistent packages. Whether `endOrphanWithoutPersonalRow` actually ran is verified by
 * asserting on the control file it writes to disk before dispatching that push — direct evidence of
 * *which* session id the teardown was attempted for.
 */
@RunWith(AndroidJUnit4::class)
class EndSessionMismatchedBlockerTest {
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

    private suspend fun saveFailedSession(label: String, errorCode: SandboxErrorCode): SandboxSession {
        val id = UUID.randomUUID().toString()
        val session = SandboxSession(
            id = id,
            analysisId = "end-mismatch-test-$label",
            packageName = "com.example.endmismatch.$label",
            state = SandboxSessionState.CREATED,
            requestedPolicy = SandboxPolicy(),
            createdAt = Instant.now(),
        ).transitionTo(SandboxSessionState.FAILED).copy(
            error = SandboxError(errorCode, "test", "test", Recoverability.REQUIRES_USER_ACTION),
        )
        repository.save(session)
        createdSessionIds += id
        return session
    }

    @Test
    fun endOnFailedSession_blockedByDifferentOrphanSession_tearsDownTheRealBlockerNotItself() = runBlocking {
        launchActivity()
        val failedSession = saveFailedSession("orphan-case", SandboxErrorCode.ANOTHER_SESSION_ACTIVE)
        val blockerId = "blocker-${UUID.randomUUID()}"
        val callCount = AtomicInteger(0)
        val coordinator = DefaultSandboxSessionCoordinator(
            context,
            activeSessionQueryOverride = {
                if (callCount.getAndIncrement() == 0) {
                    WorkActiveSessionSnapshot(active = true, sessionId = blockerId, packageName = "com.example.blocker", startedAtEpochMs = System.currentTimeMillis())
                } else {
                    WorkActiveSessionSnapshot(active = false, sessionId = null, packageName = null, startedAtEpochMs = null)
                }
            },
        )

        val controlFile = File(context.filesDir, "sandbox/control/$blockerId-end.json")
        controlFile.delete()

        val result = withTimeout(15_000) { coordinator.end(activity, failedSession.id) }

        assertTrue("expected Success once the mismatched blocker was resolved, got $result", result is SandboxOperationResult.Success)
        assertEquals(failedSession.id, (result as SandboxOperationResult.Success).session.id)
        assertTrue(
            "end() must have dispatched a real END_SESSION control file for the blocker's own id ($blockerId), not the terminal row's id",
            controlFile.exists(),
        )

        // The original terminal row itself must be untouched -- still FAILED, never repurposed.
        val persisted = repository.get(failedSession.id)
        assertEquals(SandboxSessionState.FAILED, persisted?.state)
    }

    @Test
    fun endOnFailedSession_blockedByADifferentSessionPersonalKnowsIsRunning_refusesAndNeverTearsItDown() = runBlocking {
        launchActivity()
        val failedSession = saveFailedSession("running-case", SandboxErrorCode.ANOTHER_SESSION_ACTIVE)

        // A second, real Personal row genuinely RUNNING -- the exact case end() must never touch.
        val runningId = UUID.randomUUID().toString()
        val runningSession = SandboxSession(
            id = runningId,
            analysisId = "end-mismatch-test-running-blocker",
            packageName = "com.example.runningblocker",
            state = SandboxSessionState.CREATED,
            requestedPolicy = SandboxPolicy(),
            createdAt = Instant.now(),
        ).transitionTo(SandboxSessionState.PREPARING)
            .transitionTo(SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION)
            .transitionTo(SandboxSessionState.INSTALLING)
            .transitionTo(SandboxSessionState.INSTALLED)
            .transitionTo(SandboxSessionState.READY)
            .transitionTo(SandboxSessionState.LAUNCHING)
            .transitionTo(SandboxSessionState.RUNNING)
        repository.save(runningSession)
        createdSessionIds += runningId

        val coordinator = DefaultSandboxSessionCoordinator(
            context,
            activeSessionQueryOverride = {
                WorkActiveSessionSnapshot(active = true, sessionId = runningId, packageName = "com.example.runningblocker", startedAtEpochMs = System.currentTimeMillis())
            },
        )

        val controlFile = File(context.filesDir, "sandbox/control/$runningId-end.json")
        controlFile.delete()

        val result = withTimeout(15_000) { coordinator.end(activity, failedSession.id) }

        assertTrue("must refuse to end a session that's genuinely RUNNING elsewhere, got $result", result is SandboxOperationResult.Failure)
        assertEquals(SandboxErrorCode.ANOTHER_SESSION_ACTIVE, (result as SandboxOperationResult.Failure).error.code)
        assertFalse("must never dispatch END_SESSION for a legitimately running, unrelated session", controlFile.exists())

        // The genuinely-running row must remain exactly RUNNING -- untouched.
        val persistedRunning = repository.get(runningId)
        assertEquals(SandboxSessionState.RUNNING, persistedRunning?.state)
    }
}

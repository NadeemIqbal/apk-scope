package com.nadeem.apkscope.domain.sandbox

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.MainActivity
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxOperationResult
import com.nadeem.apkscope.core.model.SandboxPolicy
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxSessionState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * Reproduces, at the coordinator level, the second bug confirmed manually on `emulator-5554` while
 * verifying the `ANOTHER_SESSION_ACTIVE`/End Session fix: a session whose own `prepare()` coroutine
 * died before ever reaching `WAITING_FOR_INSTALL_CONFIRMATION` (its driving coroutine belonged to a
 * *previous* screen instance whose scope was cancelled when the user navigated away) is left at
 * `PREPARING` forever. `prepare()` itself no-ops for anything already past `CREATED`, and `reconcile()`
 * has no recovery branch for `PREPARING` (it has no `Activity` to pull Work's live evidence with) --
 * so nothing was ever going to re-drive it, and the Preparing screen span on "Sandbox environment
 * checked" indefinitely with no error, discoverable only if the user happened to guess to tap
 * "Check installation status" themselves.
 *
 * [DefaultSandboxSessionCoordinator.failAbandonedPreparing] is the fix's second half (the first half,
 * calling a real [DefaultSandboxSessionCoordinator.importEvidence]+[DefaultSandboxSessionCoordinator.reconcile]
 * pull first, lives in `SandboxPreparingViewModel.recoverAbandonedPreparing` and is exercised here by
 * calling the same two methods directly before asserting the fallback).
 */
@RunWith(AndroidJUnit4::class)
class AbandonedPreparingRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = SandboxSessionRepository(context)
    private val coordinator = DefaultSandboxSessionCoordinator(context)
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

    private suspend fun savePreparingSession(label: String): SandboxSession {
        val id = UUID.randomUUID().toString()
        val session = SandboxSession(
            id = id,
            analysisId = "abandoned-preparing-test-$label",
            packageName = "com.example.abandonedpreparing.$label",
            state = SandboxSessionState.CREATED,
            requestedPolicy = SandboxPolicy(),
            createdAt = Instant.now(),
            personalApkPath = "/nonexistent/$label.apk",
        ).transitionTo(SandboxSessionState.PREPARING)
        repository.save(session)
        createdSessionIds += id
        return session
    }

    @Test
    fun sessionStuckAtPreparing_pullFindsNothing_isFailedWithARetryableError() = runBlocking {
        launchActivity()
        val session = savePreparingSession("stuck")

        // Mirrors SandboxPreparingViewModel.recoverAbandonedPreparing's first step: a real pull.
        // Work genuinely has nothing for this synthetic session, so this must be a no-op.
        withTimeout(10_000) {
            coordinator.importEvidence(activity, session.id)
            coordinator.reconcile(session.id)
        }
        assertEquals(SandboxSessionState.PREPARING, repository.get(session.id)?.state)

        val result = withTimeout(5_000) { coordinator.failAbandonedPreparing(session.id) }

        assertTrue("expected Failure once a stuck PREPARING session is given up on, got $result", result is SandboxOperationResult.Failure)
        val failure = result as SandboxOperationResult.Failure
        assertEquals(SandboxSessionState.FAILED, failure.session.state)
        assertEquals(SandboxErrorCode.HANDOFF_FAILED, failure.error.code)
        assertEquals(com.nadeem.apkscope.core.model.Recoverability.RETRYABLE, failure.error.recoverability)

        val persisted = repository.get(session.id)
        assertEquals(SandboxSessionState.FAILED, persisted?.state)
        assertEquals(SandboxErrorCode.HANDOFF_FAILED, persisted?.error?.code)
    }

    @Test
    fun failAbandonedPreparing_isANoOpForAnySessionNotActuallyStuckAtPreparing() = runBlocking {
        launchActivity()
        val readySession = SandboxSession(
            id = UUID.randomUUID().toString(),
            analysisId = "abandoned-preparing-test-not-preparing",
            packageName = "com.example.abandonedpreparing.notpreparing",
            state = SandboxSessionState.CREATED,
            requestedPolicy = SandboxPolicy(),
            createdAt = Instant.now(),
        ).transitionTo(SandboxSessionState.PREPARING)
            .transitionTo(SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION)
        repository.save(readySession)
        createdSessionIds += readySession.id

        val result = withTimeout(5_000) { coordinator.failAbandonedPreparing(readySession.id) }

        assertTrue("must never fail a session that isn't actually stuck at PREPARING, got $result", result is SandboxOperationResult.Success)
        assertEquals(SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION, (result as SandboxOperationResult.Success).session.state)
        assertEquals(SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION, repository.get(readySession.id)?.state)
    }
}

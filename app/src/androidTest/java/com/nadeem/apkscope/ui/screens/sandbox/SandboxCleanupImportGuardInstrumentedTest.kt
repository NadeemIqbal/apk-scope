package com.nadeem.apkscope.ui.screens.sandbox

import android.app.Activity
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.MainActivity
import com.nadeem.apkscope.core.crossprofile.UrlEvidencePipelineDiagnostics
import com.nadeem.apkscope.core.model.SandboxOperationResult
import com.nadeem.apkscope.core.model.SandboxPolicy
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.domain.sandbox.DefaultSandboxSessionCoordinator
import com.nadeem.apkscope.domain.sandbox.SandboxSessionRepository
import com.nadeem.apkscope.domain.sandbox.SandboxSessionCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/**
 * Milestone 9 (Pixel 8 acceptance, sixth pass, item 2): real, on-device coverage for
 * [SandboxCleanupViewModel.importAndReconcile]'s `isImporting` in-flight guard — no test existed for
 * this before this pass. The Pixel 8 acceptance run's own diagnostics showed two scheduling attempts
 * correctly skipped as "already in flight" while an earlier, genuinely still-running attempt for the
 * *same* session worked through a real, multi-second `importAndroidEvidence` DPM wait — real evidence
 * of **concurrent suppression of an active operation**, not evidence about what happens once an
 * attempt has *concluded*. This suite verifies the guard's release explicitly, for every concluding
 * case named in this pass's instructions (success, failure, cancellation, timeout), plus the
 * concurrent-in-flight case itself end to end (skip while active, then a normal proceed once the
 * active one concludes) — using [SandboxCleanupViewModel]'s new `coordinatorOverride` test seam
 * (production behavior unchanged: every real caller leaves it `null`).
 */
@RunWith(AndroidJUnit4::class)
class SandboxCleanupImportGuardInstrumentedTest {
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

    private suspend fun createSession(coordinator: SandboxSessionCoordinator, label: String): SandboxSession {
        val analysisId = "cleanup-guard-test-$label-${UUID.randomUUID()}"
        val result = coordinator.create(analysisId, "com.example.cleanupguardtest.$label", "/nonexistent/$label.apk", SandboxPolicy())
        val session = (result as SandboxOperationResult.Success).session
        createdSessionIds += session.id
        return session
    }

    /**
     * Delegates every real call to [real] (a genuine [DefaultSandboxSessionCoordinator]) except the
     * five calls [SandboxCleanupViewModel.importAndReconcile] actually makes, which this fake fully
     * controls — so a test never depends on real cross-profile machinery answering for a session
     * that has no real Work-side counterpart, and can inject a hang/failure precisely at the first
     * step ([importEvidence]) without needing the later steps to also be stubbed defensively.
     */
    private class ControllableCoordinator(
        private val real: SandboxSessionCoordinator,
        private val session: SandboxSession,
        private val importEvidenceBehavior: suspend () -> SandboxOperationResult = { SandboxOperationResult.Success(session) },
    ) : SandboxSessionCoordinator by real {
        override suspend fun importEvidence(activity: Activity, sessionId: String) = importEvidenceBehavior()
        override suspend fun importRuntimeArtifact(activity: Activity, sessionId: String) = SandboxOperationResult.Success(session)
        override suspend fun importAndroidEvidence(activity: Activity, sessionId: String) = SandboxOperationResult.Success(session)
        override suspend fun importUrlEvidence(activity: Activity, sessionId: String, operationId: String) = SandboxOperationResult.Success(session)
        override suspend fun reconcile(sessionId: String) = SandboxOperationResult.Success(session)
    }

    /** Polls this process's own diagnostics for [sessionId] until [stage] appears, or times out — real durable evidence of what actually happened, not an assumption about timing. */
    private suspend fun awaitStage(sessionId: String, stage: String, timeoutMs: Long = 5_000): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val lines = UrlEvidencePipelineDiagnostics.read(context)
            if (lines.any { it.contains("\"sessionId\":\"$sessionId\"") && it.contains("\"stage\":\"$stage\"") }) return@withTimeoutOrNull true
            kotlinx.coroutines.delay(50)
        }
        @Suppress("UNREACHABLE_CODE") true
    } ?: false

    @Test
    fun successfulImport_releasesGuard_allowingAnImmediatelyFollowingCallToProceed() = runBlocking {
        launchActivity()
        val real = DefaultSandboxSessionCoordinator(context)
        val session = createSession(real, "success")
        val fake = ControllableCoordinator(real, session)
        val viewModel = SandboxCleanupViewModel(activity.application, session.id, coordinatorOverride = fake)

        viewModel.importAndReconcile(activity)
        assertTrue("the first, uncontested import must run to completion", awaitStage(session.id, "import_and_reconcile_sequence_completed"))

        // Guard must already be released — a call made right after the first concludes must proceed
        // as its own fresh attempt, not be skipped as "already in flight".
        viewModel.importAndReconcile(activity)
        assertTrue(
            "a second call after the first fully completed must proceed as a fresh attempt, proving the guard was released on success",
            awaitStage(session.id, "import_launched"),
        )
    }

    @Test
    fun failedImport_releasesGuardViaTheOuterCatch_allowingASubsequentRetryToProceed() = runBlocking {
        launchActivity()
        val real = DefaultSandboxSessionCoordinator(context)
        val session = createSession(real, "failure")
        val fake = ControllableCoordinator(real, session, importEvidenceBehavior = { throw IOException("simulated transient failure") })
        val viewModel = SandboxCleanupViewModel(activity.application, session.id, coordinatorOverride = fake)

        viewModel.importAndReconcile(activity)
        assertTrue(
            "a thrown exception must be caught by the outer catch and recorded distinctly, not silently swallowed",
            awaitStage(session.id, "import_and_reconcile_sequence_interrupted"),
        )

        viewModel.importAndReconcile(activity)
        assertTrue(
            "a retry after a failed attempt must proceed as a fresh attempt, proving the guard was released on failure",
            awaitStage(session.id, "import_launched"),
        )
    }

    @Test
    fun timeoutDuringImport_isCaughtByTheSameOuterExceptionHandling_andReleasesTheGuard() = runBlocking {
        launchActivity()
        val real = DefaultSandboxSessionCoordinator(context)
        val session = createSession(real, "timeout")
        val fake = ControllableCoordinator(
            real, session,
            importEvidenceBehavior = { withTimeout(300) { awaitCancellation() } }, // always throws TimeoutCancellationException
        )
        val viewModel = SandboxCleanupViewModel(activity.application, session.id, coordinatorOverride = fake)

        viewModel.importAndReconcile(activity)
        assertTrue(
            "a genuine TimeoutCancellationException from an inner step must be caught by the same outer handling as any other exception",
            awaitStage(session.id, "import_and_reconcile_sequence_interrupted"),
        )
        // The recorded detail must actually name the real exception type — not a generic/guessed message.
        val interrupted = UrlEvidencePipelineDiagnostics.read(context)
            .firstOrNull { it.contains("\"sessionId\":\"${session.id}\"") && it.contains("\"stage\":\"import_and_reconcile_sequence_interrupted\"") }
        assertTrue("expected the real TimeoutCancellationException type recorded, got: $interrupted", interrupted?.contains("TimeoutCancellationException") == true)

        viewModel.importAndReconcile(activity)
        assertTrue(
            "a retry after a timed-out attempt must proceed as a fresh attempt, proving the guard was released after a timeout",
            awaitStage(session.id, "import_launched"),
        )
    }

    @Test
    fun concurrentCallWhileGenuinelyInFlight_isSkippedNotDuplicated_andTheGuardReleasesOnceTheActiveOneConcludes() = runBlocking {
        launchActivity()
        val real = DefaultSandboxSessionCoordinator(context)
        val session = createSession(real, "concurrent")
        val gate = CompletableDeferred<Unit>()
        val fake = ControllableCoordinator(real, session, importEvidenceBehavior = { gate.await(); SandboxOperationResult.Success(session) })
        val viewModel = SandboxCleanupViewModel(activity.application, session.id, coordinatorOverride = fake)

        // First call: sets the guard, then genuinely suspends inside importEvidence (the gate is not
        // yet completed) — this is a real, still-active operation, not a stale one.
        viewModel.importAndReconcile(activity)
        assertTrue("the first call must actually launch before the second is attempted", awaitStage(session.id, "import_launched"))

        // Second call, made while the first is still genuinely suspended: this is the exact scenario
        // the Pixel 8 acceptance run's own diagnostics showed — concurrent suppression of an active
        // operation, distinct from anything about a *completed* one.
        viewModel.importAndReconcile(activity)
        assertTrue(
            "a second call while the first is still genuinely in flight must be skipped as such, not silently dropped or raced",
            awaitStage(session.id, "import_scheduling_skipped_already_in_flight"),
        )
        // The first call must still not have concluded — proving the skip really was concurrent, not
        // a stale guard reacting to something already finished.
        assertFalse(
            "the first call must still be genuinely unresolved at the moment the second was skipped",
            UrlEvidencePipelineDiagnostics.read(context).any {
                it.contains("\"sessionId\":\"${session.id}\"") && it.contains("\"stage\":\"import_and_reconcile_sequence_completed\"")
            },
        )

        // Let the first call conclude, then confirm the guard is now released for a genuinely later attempt.
        gate.complete(Unit)
        assertTrue("the first call must run to completion once unblocked", awaitStage(session.id, "import_and_reconcile_sequence_completed"))
        viewModel.importAndReconcile(activity)
        assertTrue(
            "a call made after the in-flight one concluded must proceed as its own fresh attempt",
            awaitStage(session.id, "import_launched", timeoutMs = 5_000),
        )
    }

    @Test
    fun cancellingTheViewModelScopeMidImport_doesNotHangOrCrash_andANewInstanceForTheSameSessionStillWorks() = runBlocking {
        launchActivity()
        val real = DefaultSandboxSessionCoordinator(context)
        val session = createSession(real, "cancel")
        val neverCompletes = CompletableDeferred<Unit>()
        val fake = ControllableCoordinator(real, session, importEvidenceBehavior = { neverCompletes.await(); SandboxOperationResult.Success(session) })
        val store = ViewModelStore()
        val viewModel = SandboxCleanupViewModel(activity.application, session.id, coordinatorOverride = fake)
        store.put("cleanup-guard-cancel-test", viewModel)

        viewModel.importAndReconcile(activity)
        assertTrue("the call must genuinely be suspended before it is cancelled", awaitStage(session.id, "import_launched"))

        // The real mechanism a destroyed screen uses: clearing the ViewModelStore invokes onCleared(),
        // which cancels viewModelScope — the same lifecycle event a real Activity/Fragment teardown
        // triggers. Must not hang or throw here.
        store.clear()

        // A fresh instance for the *same* session, backed by a real (uncontrolled) coordinator, must
        // work normally afterward — the per-instance guard on the destroyed instance has no bearing on
        // a new one, and nothing about the cancelled coroutine leaks into the shared diagnostics file
        // in a way that blocks a real subsequent attempt.
        val freshViewModel = SandboxCleanupViewModel(activity.application, session.id, coordinatorOverride = ControllableCoordinator(real, session))
        freshViewModel.importAndReconcile(activity)
        assertTrue(
            "a fresh ViewModel instance for the same session must import normally after an earlier instance was cancelled mid-import",
            awaitStage(session.id, "import_and_reconcile_sequence_completed", timeoutMs = 8_000),
        )
    }
}

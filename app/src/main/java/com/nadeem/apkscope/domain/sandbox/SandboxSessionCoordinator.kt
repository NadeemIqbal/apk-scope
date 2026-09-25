package com.nadeem.apkscope.domain.sandbox

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.core.model.Recoverability
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxOperationResult
import com.nadeem.apkscope.core.model.SandboxPolicy
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.sandbox.SANDBOX_FILE_PROVIDER_AUTHORITY
import com.nadeem.apkscope.sandbox.CrossProfileQueryBridge
import com.nadeem.apkscope.sandbox.RuntimeObservationArtifact
import com.nadeem.apkscope.sandbox.UrlEvidenceArtifact
import com.nadeem.apkscope.sandbox.SandboxStatusReport
import com.nadeem.apkscope.sandbox.SandboxWorkQueryActivity
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Checkpoint 4, item 3: the production orchestration boundary — Compose talks to a ViewModel,
 * the ViewModel talks to this, and this is the *only* thing that ever touches DPM/cross-profile
 * intents/`PackageInstaller`/`LauncherApps`/VPN/cleanup (indirectly — most of those, being
 * profile-owner-scoped, actually run in `SandboxWorkerService`/`SandboxInstallResultReceiver` on
 * the work-profile side; this coordinator's own job is the personal-side half of each step plus
 * every fact it can determine or verify without leaving this profile).
 *
 * Some methods take an [Activity] because [Handoff.send] — the one already-validated cross-profile
 * transport this checkpoint reuses rather than rewriting — needs one for `startActivity`; the
 * calling ViewModel obtains it from the Composable's `LocalContext.current` for exactly this one
 * call, the same narrow pattern this codebase's screens already use for `startActivity`-requiring
 * flows (e.g. the installation-confirmation intent).
 */
interface SandboxSessionCoordinator {
    fun observe(sessionId: String): Flow<SandboxSession?>
    fun observeForAnalysis(analysisId: String): Flow<List<SandboxSession>>
    suspend fun create(
        analysisId: String,
        packageName: String,
        personalApkPath: String,
        policy: SandboxPolicy
    ): SandboxOperationResult

    suspend fun prepare(activity: Activity, sessionId: String): SandboxOperationResult
    suspend fun continueInstallation(activity: Activity, sessionId: String): SandboxOperationResult
    suspend fun reinstall(activity: Activity, sessionId: String): SandboxOperationResult

    /** Checkpoint 4.1 §9: now takes an [Activity] — launching requires a *fresh* cross-profile tunnel verification (see [NetworkIsolationVerifier]), not merely reading [SandboxSession.enforcementResults]. */
    suspend fun launch(activity: Activity, sessionId: String): SandboxOperationResult
    suspend fun end(activity: Activity, sessionId: String): SandboxOperationResult
    suspend fun cancel(sessionId: String): SandboxOperationResult
    suspend fun reconcile(sessionId: String): SandboxOperationResult

    /**
     * Root-cause fix, confirmed on-device: [prepare] no-ops for any session already past `CREATED`,
     * and [reconcile] has no recovery branch for `PREPARING` (it has no `Activity` to pull Work's
     * live evidence with) — so a session still showing `PREPARING` when its screen is freshly
     * (re)opened can only mean the coroutine that actually drove it (a *previous* screen instance's
     * now-cancelled scope) died before Work ever reported `WAITING_FOR_INSTALL_CONFIRMATION`, and
     * nothing else was ever going to re-drive it. Left unhandled, this is an eternal, error-free
     * spinner recoverable only if the user happened to guess to tap "Check installation status"
     * themselves. Callers first attempt a real [importEvidence]+[reconcile] pull — Work may have
     * genuinely progressed and the report simply never arrived; only if the session is *still*
     * `PREPARING` afterward does this transition it to `FAILED` with a clear, retryable error, so
     * "Retry" (which creates a fresh session) becomes reachable instead of a permanent dead end. A
     * no-op (returns the session unchanged as [SandboxOperationResult.Success]) for any other state.
     */
    suspend fun failAbandonedPreparing(sessionId: String): SandboxOperationResult

    /** Checkpoint 4.1 §6/7/8: the pull/reconciliation half of the transport decision — a foreground-initiated round trip to `SandboxWorkQueryActivity` that imports whatever the Work-local evidence store durably knows for [sessionId], regardless of whether any earlier push for those same facts ever arrived. Safe/idempotent to call repeatedly (re-importing the same facts is a no-op merge). */
    suspend fun importEvidence(activity: Activity, sessionId: String): SandboxOperationResult

    /**
     * Checkpoint 5, item 19/20/21/22/23/24: pulls, validates, and durably persists the
     * runtime-observation artifact for [sessionId] — never as `DeclaredCapability`/`AndroidEvidence`,
     * only as [com.nadeem.apkscope.core.database.NetworkObservationEntity]/
     * [com.nadeem.apkscope.core.database.RuntimeObservationSummaryEntity] runtime observations (item 22).
     * Safe to call repeatedly: an invalid/rejected/unreachable response leaves the session's runtime
     * data exactly as it was, never a failure result — matching [importEvidence]'s own tolerance.
     * Sends the Work-side acknowledgement (item 24) only after this artifact's rows are durably
     * committed locally, never before.
     */
    suspend fun importRuntimeArtifact(activity: Activity, sessionId: String): SandboxOperationResult

    /**
     * Checkpoint 6: pull/validate/persist/ack sequence for the dedicated Android-evidence artifact
     * containing DPM-captured DnsEvent and ConnectEvent logs.
     */
    suspend fun importAndroidEvidence(activity: Activity, sessionId: String): SandboxOperationResult

    /**
     * Checkpoint 8.9: pull/validate/persist sequence for bounded URL evidence artifact containing
     * exact-URL correlation data from VPN-intercepted traffic. No ack required (non-critical overlay
     * on static analysis).
     *
     * [operationId] (Milestone 9, Pixel 8 acceptance, fifth pass, item 2): an opaque id minted once
     * per real import attempt by the caller, threaded through every
     * [com.nadeem.apkscope.core.crossprofile.UrlEvidencePipelineDiagnostics] stage this call and its
     * cross-profile counterpart record — a fresh one is generated if a caller does not supply one, so
     * every existing call site remains valid unchanged.
     */
    suspend fun importUrlEvidence(
        activity: Activity,
        sessionId: String,
        operationId: String = java.util.UUID.randomUUID().toString()
    ): SandboxOperationResult

    suspend fun requestUninstallConfirmation(
        activity: Activity,
        sessionId: String
    ): SandboxOperationResult

    /**
     * Milestone 9 (Pixel 8 acceptance, fifth pass): a fresh, uncached read of the **Work-profile
     * instance's own** `PackageManager.canRequestPackageInstalls()` — the exact fact
     * `SandboxWorkerService`'s install-failure check depends on, and the exact fact the Personal
     * profile's own `PackageManager` cannot answer (it is a genuinely different package instance, not
     * merely a different value of the same one). Every call goes through the real cross-profile query
     * round trip (`SandboxWorkQueryActivity`, the same mechanism every other Work-side fact in this
     * coordinator already uses) — never a cached/remembered value from an earlier check, which is
     * exactly what made the previous remediation flow's "Retry" silently re-check a stale answer.
     * Returns `null` only when the cross-profile query itself could not be answered (dropped/timed
     * out) — never fabricated as granted or denied.
     */
    suspend fun checkWorkInstallPermission(activity: Activity, sessionId: String): Boolean?

    /**
     * Milestone 9 (Pixel 8 acceptance, fifth pass): the actual fix for the confirmed defect where the
     * previous "Open App Settings" remediation opened the **Personal**-profile App Info page while the
     * failing permission belongs to the **Work**-profile instance. Dispatches
     * `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for this app's own package **from within the Work
     * profile itself** (via the same cross-profile query mechanism as [requestUninstallConfirmation] —
     * a real, already-proven `startActivityForResult` launched from `SandboxWorkQueryActivity`, which
     * runs as the Work-profile process), so a supported Android Settings flow opens in the correct
     * profile with no hardcoded user id and no additional privileged permission. Returns `true` only if
     * Android actually resolved and launched that settings screen (the call suspends until the user
     * has returned from it, so a caller can safely re-check the permission immediately after); `false`
     * if it could not be resolved or launched at all — callers must show accurate manual guidance in
     * that case, never silently fall back to opening Personal's own settings.
     */
    suspend fun requestOpenWorkInstallSettings(activity: Activity, sessionId: String): Boolean

    /**
     * Checkpoint 5.3, item 1/2/3: Work's own authoritative "is a sandbox session active, and which
     * one" fact, reconciled against whatever (if anything) Personal's own Room row for that exact
     * sessionId says — see [com.nadeem.apkscope.core.model.SessionReconciliation] for the decision matrix.
     * Returns non-null only for the two outcomes item 3's fail-safe UI state exists for
     * (`ORPHAN_DETECTED`/`STALE_WORK_SESSION`); every other outcome (a real match, or nothing live on
     * Work at all) returns null — there is nothing for the UI to act on.
     */
    suspend fun checkForOrphanWorkSession(activity: Activity): OrphanSessionInfo?

    /**
     * Item 3/5's safe recovery action: adopts [info] into Personal's own Room (only if no row for
     * that exact sessionId already exists — never overwriting a real one), preserves its runtime
     * observations first if any exist, then drives it through the exact same production
     * [end] sequence any normal session uses — never a bespoke "just delete it" shortcut.
     */
    suspend fun resolveOrphanWorkSession(
        activity: Activity,
        info: OrphanSessionInfo
    ): SandboxOperationResult

    /**
     * Checkpoint 5.5, item 5: the RUNNING-session reconciliation [reconcile] itself could never do
     * (no [Activity] available from a plain ViewModel `init` for the live cross-profile query this
     * needs) — a full [com.nadeem.apkscope.core.model.SessionReconciliation] decision using Work's actual
     * live session state, for the one screen that already has [Activity] access while representing a
     * RUNNING session ([com.nadeem.apkscope.ui.screens.monitor.LiveMonitorScreen]). Never special-cases
     * RUNNING before consulting the reconciliation model (item 5's "do not special-case RUNNING").
     * `RECOVER_RUNNING`/`NO_ACTION` leave the session untouched; `INTERRUPTED`/`CONFLICT` transition it
     * to [SandboxSessionState.FAILED] with [SandboxErrorCode.WORK_SESSION_INTERRUPTED] /
     * [SandboxErrorCode.ANOTHER_SESSION_ACTIVE] respectively — a real, factual state, never a
     * fabricated COMPLETED.
     */
    suspend fun reconcileRunningSession(
        activity: Activity,
        sessionId: String
    ): com.nadeem.apkscope.core.model.SessionReconciliation.Outcome
}

/**
 * The raw answer to [SandboxWorkQueryActivity.QUERY_TYPE_ACTIVE_SESSION], before any reconciliation
 * decision is made about it. Plain (module-)public — not `private` — specifically so
 * [DefaultSandboxSessionCoordinator]'s `activeSessionQueryOverride` test seam can be constructed and
 * asserted on from this module's own test source sets without reflection — see that constructor
 * parameter's doc. Carries nothing sensitive (a session id/package name/timestamp already visible
 * elsewhere), so full visibility here has no encapsulation cost.
 */
data class WorkActiveSessionSnapshot(
    val active: Boolean,
    val sessionId: String?,
    val packageName: String?,
    val startedAtEpochMs: Long?
)

/** Item 1/3: the plain facts Work reported about its own live session — never more than what [SandboxWorkQueryActivity.QUERY_TYPE_ACTIVE_SESSION] actually answered. */
data class OrphanSessionInfo(
    val sessionId: String,
    val packageName: String?,
    val startedAtEpochMs: Long?,
    val networkIsolationActive: Boolean,
)

class DefaultSandboxSessionCoordinator(
    private val context: Context,
    private val networkIsolationVerifier: NetworkIsolationVerifier = CrossProfileNetworkIsolationVerifier(
        context
    ),
    /**
     * Milestone 9 (fourth acceptance rigor pass) test seam: when non-null, replaces
     * [queryWorkActiveSession]'s real cross-profile round trip entirely. Every production caller
     * leaves this `null` (the default), so real behavior is completely unchanged — `queryWorkActiveSession`
     * falls through to its existing real implementation below. Exists specifically so
     * `prepare()`'s timeout-guarded active-session check (see [WORK_SESSION_QUERY_TIMEOUT_MS]) can be
     * exercised against a deliberately never-completing query in a real coroutine test, without
     * needing a real Activity/cross-profile round trip to simulate a dropped result — see
     * `PrepareSandboxTimeoutTest`.
     */
    val activeSessionQueryOverride: (suspend (Activity) -> WorkActiveSessionSnapshot)? = null,
) : SandboxSessionCoordinator {
    private val repository = SandboxSessionRepository(context)

    companion object {
        /** See [prepare]'s active-session-query guard: a real cross-profile round trip normally completes
         * in well under a second; 10s is generous headroom for a genuinely slow-but-alive device while
         * still turning a truly dropped query into a bounded, observable failure instead of an indefinite
         * hang. */
        private const val WORK_SESSION_QUERY_TIMEOUT_MS = 10_000L
        private const val WORK_SESSION_CLOSE_TIMEOUT_MS = 10_000L
    }

    override fun observe(sessionId: String) = repository.observe(sessionId)
    override fun observeForAnalysis(analysisId: String) = repository.observeForAnalysis(analysisId)

    override suspend fun create(
        analysisId: String,
        packageName: String,
        personalApkPath: String,
        policy: SandboxPolicy
    ): SandboxOperationResult {
        val session = SandboxSession(
            id = UUID.randomUUID().toString(),
            analysisId = analysisId,
            packageName = packageName,
            state = SandboxSessionState.CREATED,
            requestedPolicy = policy,
            createdAt = Instant.now(),
            personalApkPath = personalApkPath,
        )
        repository.save(session)
        return SandboxOperationResult.Success(session)
    }

    override suspend fun prepare(activity: Activity, sessionId: String): SandboxOperationResult {
        com.nadeem.apkscope.core.crossprofile.HandoffDiagnostics.log("prepare_entered session=$sessionId")
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        if (session.state != SandboxSessionState.CREATED) return SandboxOperationResult.Success(
            session
        )

        // Checkpoint 5.3, item 4: at most one active sandbox runtime session per Work Profile. Query
        // Work's own authoritative state before ever establishing network isolation for a new one —
        // never inferred from Personal's own Room, which is exactly what let the Checkpoint 5.2 orphan
        // accumulate silently.
        //
        // Milestone 9 (second acceptance rigor pass, round 3): bounded with a real timeout —
        // `queryWorkActiveSession` awaits `CrossProfileQueryBridge.launchForResult`, which has no timeout
        // of its own (a plain `suspendCancellableCoroutine` that only resumes when the cross-profile
        // Activity result actually arrives). Reproduced on-device: attempting to prepare a second session
        // while a different target's session already holds the Work Profile's VPN left this exact call
        // suspended forever — no exception, no state transition, "Preparing Sandbox" spinning
        // indefinitely — because the query intent was silently dropped (the same background-activity-
        // launch platform behavior already documented elsewhere in this codebase for other cross-profile
        // pushes). `WORK_SESSION_QUERY_TIMEOUT_MS` below turns that indefinite hang into a bounded,
        // observable, retryable failure. An inconclusive answer is deliberately treated the same as a
        // confirmed conflict (fail closed) rather than as "no session active" — proceeding on an unknown
        // answer could let two targets end up concurrently monitored, which is explicitly not something
        // this fix introduces support for.
        val snapshot = try {
            kotlinx.coroutines.withTimeoutOrNull(WORK_SESSION_QUERY_TIMEOUT_MS) {
                queryWorkActiveSession(
                    activity
                )
            }
        } catch (_: Exception) {
            null
        }
        if (snapshot == null) {
            val error = SandboxError(
                SandboxErrorCode.WORK_SESSION_STATE_UNKNOWN,
                "Could not confirm whether another sandbox session is active. Please try again.",
                "queryWorkActiveSession did not respond within ${WORK_SESSION_QUERY_TIMEOUT_MS}ms for session=$sessionId",
                Recoverability.RETRYABLE
            )
            val failed = session.transitionTo(SandboxSessionState.FAILED).copy(error = error)
            repository.save(failed)
            return SandboxOperationResult.Failure(failed, error)
        }
        val activeElsewhere =
            snapshot.active && snapshot.sessionId != null && snapshot.sessionId != sessionId
        if (activeElsewhere) {
            val error = SandboxError(
                SandboxErrorCode.ANOTHER_SESSION_ACTIVE,
                "Another sandbox session is already active. Resolve it before starting a new one.",
                "work-active-session blocks prepare for session=$sessionId blockedBy=${snapshot.sessionId}",
                Recoverability.REQUIRES_USER_ACTION
            )
            val failed = session.transitionTo(SandboxSessionState.FAILED).copy(error = error)
            repository.save(failed)
            return SandboxOperationResult.Failure(failed, error)
        }

        val preflightError = SandboxEnvironmentPreflight.checkEnvironment(context, session)
        com.nadeem.apkscope.core.crossprofile.HandoffDiagnostics.log("prepare_preflight_result session=$sessionId error=${preflightError?.code}")
        preflightError?.let { error ->
            val failed = session.transitionTo(SandboxSessionState.FAILED).copy(error = error)
            repository.save(failed)
            return SandboxOperationResult.Failure(failed, error)
        }

        var next = session.transitionTo(SandboxSessionState.PREPARING)
        repository.save(next)

        val apkFile = File(requireNotNull(session.personalApkPath))
        com.nadeem.apkscope.core.crossprofile.HandoffDiagnostics.log("prepare_apk_temp_file session=$sessionId path=${apkFile.absolutePath} exists=${apkFile.exists()} bytes=${apkFile.length()}")
        logCrossProfileState(sessionId)

        return try {
            Handoff.send(
                activity,
                apkFile,
                CrossProfileContract.ACTION_SANDBOX_IMPORT_APK,
                sessionId,
                SANDBOX_FILE_PROVIDER_AUTHORITY
            )
            SandboxOperationResult.Success(next)
        } catch (e: Exception) {
            val error = SandboxError(
                SandboxErrorCode.HANDOFF_FAILED,
                "Could not start sandbox preparation.",
                e.toString(),
                Recoverability.RETRYABLE
            )
            next = next.transitionTo(SandboxSessionState.FAILED).copy(error = error)
            repository.save(next)
            SandboxOperationResult.Failure(next, error)
        }
    }

    /**
     * Checkpoint 5.1, item 3: authoritative cross-profile state, logged **immediately before**
     * dispatching the Prepare handoff — the exact set of facts the Checkpoint 5 stall investigation
     * had to reconstruct after the fact from two empty Room tables. `Handoff.isConfigured()` is the
     * one check that actually matters here: it is what distinguishes "the generic system forwarder
     * exists" (always true) from "this specific action+direction was ever registered via
     * `addCrossProfileIntentFilter`" (the thing that was actually missing — see
     * `SandboxAdminReceiver.onProfileProvisioningComplete`'s fix).
     */
    private fun logCrossProfileState(sessionId: String) {
        val workHandle = SandboxEnvironmentPreflight.workProfileHandle(context)
        val configured =
            Handoff.isConfigured(context, CrossProfileContract.ACTION_SANDBOX_IMPORT_APK)
        com.nadeem.apkscope.core.crossprofile.HandoffDiagnostics.log(
            "prepare_cross_profile_state session=$sessionId workProfileHandlePresent=${workHandle != null} " +
                    "actionSandboxImportApkConfigured=$configured",
        )
    }

    override suspend fun continueInstallation(
        activity: Activity,
        sessionId: String
    ): SandboxOperationResult {
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        if (session.state != SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION) return SandboxOperationResult.Success(
            session
        )
        val installSessionId = session.installSessionId
            ?: return failClosed(
                session,
                SandboxErrorCode.INSTALL_FAILED,
                "Installation could not be resumed.",
                "missing installSessionId",
                Recoverability.TERMINAL
            )

        return try {
            val controlFile =
                File(context.filesDir, "sandbox/control/$sessionId-continue.json").apply {
                    parentFile?.mkdirs()
                    writeText(
                        JSONObject().put("sessionId", sessionId)
                            .put("installSessionId", installSessionId).toString()
                    )
                }
            Handoff.send(
                activity,
                controlFile,
                CrossProfileContract.ACTION_CONTINUE_INSTALL,
                sessionId,
                SANDBOX_FILE_PROVIDER_AUTHORITY
            )
            // Work verifies notification availability before reporting INSTALLING.
            SandboxOperationResult.Success(session)
        } catch (e: Exception) {
            failClosed(
                session,
                SandboxErrorCode.HANDOFF_FAILED,
                "Could not continue installation.",
                e.toString(),
                Recoverability.RETRYABLE
            )
        }
    }

    override suspend fun reinstall(
        activity: Activity,
        sessionId: String
    ): SandboxOperationResult {
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        if (session.state == SandboxSessionState.INSTALLED || session.state == SandboxSessionState.READY) {
            return SandboxOperationResult.Success(session)
        }
        val retryableInstallStates = setOf(
            SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION,
            SandboxSessionState.INSTALLING,
        )
        val retryableErrorCodes = setOf(
            SandboxErrorCode.INSTALL_FAILED,
            SandboxErrorCode.INSTALL_USER_CANCELLED,
            SandboxErrorCode.PACKAGE_MISMATCH,
        )
        if (session.state !in retryableInstallStates &&
            !(session.state == SandboxSessionState.FAILED && session.error?.code in retryableErrorCodes)) {
            return SandboxOperationResult.Success(session)
        }

        var retryStarted = false
        val retrySession = repository.update(sessionId) { latest ->
            if (latest != session) return@update latest
            retryStarted = true
            com.nadeem.apkscope.sandbox.InstallRetryMarker.mark(context, sessionId, latest.installSessionId)
            latest.retryInstallation()
        } ?: return notFound(sessionId)
        if (!retryStarted) {
            return SandboxOperationResult.Success(retrySession)
        }

        return try {
            val controlFile =
                File(context.filesDir, "sandbox/control/$sessionId-reinstall.json").apply {
                    parentFile?.mkdirs()
                    writeText(
                        JSONObject().put("sessionId", sessionId)
                            .put("installSessionId", session.installSessionId ?: -1).toString()
                    )
                }
            Handoff.send(
                activity,
                controlFile,
                CrossProfileContract.ACTION_REINSTALL,
                sessionId,
                SANDBOX_FILE_PROVIDER_AUTHORITY,
            )
            SandboxOperationResult.Success(retrySession)
        } catch (e: Exception) {
            failClosed(
                retrySession,
                SandboxErrorCode.HANDOFF_FAILED,
                "Could not restart installation.",
                e.toString(),
                Recoverability.RETRYABLE,
            )
        }
    }

    override suspend fun launch(activity: Activity, sessionId: String): SandboxOperationResult {
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        if (session.state != SandboxSessionState.READY && session.state != SandboxSessionState.RUNNING) {
            return SandboxOperationResult.Failure(
                session,
                SandboxError(
                    SandboxErrorCode.INVALID_STATE_TRANSITION,
                    "Session cannot be launched from current state (${session.state}).",
                    null,
                    Recoverability.TERMINAL,
                ),
            )
        }

        val targetPackage = session.packageName
        val workHandle = SandboxEnvironmentPreflight.workProfileHandle(context)
            ?: return failClosed(
                session,
                SandboxErrorCode.WORK_PROFILE_MISSING,
                "The secure sandbox profile is no longer available.",
                "no work profile handle",
                Recoverability.REQUIRES_USER_ACTION,
            )

        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val isInstalled = try {
            launcherApps?.isPackageEnabled(targetPackage, workHandle) == true
        } catch (_: Exception) {
            false
        }
        if (!isInstalled) {
            return SandboxOperationResult.Failure(
                session,
                SandboxError(
                    SandboxErrorCode.INSTALL_FAILED,
                    "The target app is not installed in the Sandbox Work Profile.",
                    "Package $targetPackage is not enabled or installed in user $workHandle",
                    Recoverability.REQUIRES_USER_ACTION,
                ),
            )
        }

        val readiness = SandboxEnvironmentPreflight.computeLaunchReadiness(context, session)
        if (!readiness.launchAllowed) {
            // Item 6's fail-closed invariant — never transition to LAUNCHING when this is false.
            val code =
                if (!readiness.networkIsolationActive) SandboxErrorCode.NETWORK_ISOLATION_UNAVAILABLE else SandboxErrorCode.ENVIRONMENT_NOT_READY
            return SandboxOperationResult.Failure(
                session,
                SandboxError(
                    code,
                    "This app cannot be launched safely right now.",
                    readiness.toString(),
                    Recoverability.RETRYABLE
                )
            )
        }

        // Checkpoint 4.1 §9/10/11 & Checkpoint 7: verify VPN isolation and ensure target package is unsuspended
        val isolation = networkIsolationVerifier.verify(activity, sessionId, targetPackage)
        if (!isolation.tunnelActive) {
            val detail =
                "freshVerification=$isolation (recoveryAttempted=${isolation.recoveryAttempted})"
            return SandboxOperationResult.Failure(
                session, SandboxError(
                    SandboxErrorCode.NETWORK_ISOLATION_UNAVAILABLE,
                    "Sandbox network isolation is unavailable. The app was not launched.",
                    detail,
                    Recoverability.RETRYABLE,
                )
            )
        }

        if (!isolation.packageUnsuspended) {
            return SandboxOperationResult.Failure(
                session,
                SandboxError(
                    SandboxErrorCode.LAUNCH_FAILED,
                    "Android is still blocking this app through the Sandbox policy.",
                    "Target package remained suspended by DPC policy",
                    Recoverability.RETRYABLE,
                ),
            )
        }

        // Phase 9.1: a tunnel being active is not the same as it being *scoped* to this target — an
        // unscoped tunnel still forwards (and this session would still attribute) every Work Profile
        // app's traffic, not just this one's. A fail-closed gate on `isolation.scoped` lived here
        // (matching the `tunnelActive`/`packageUnsuspended` pattern above) but is NOT enabled: the
        // scoping mechanism itself is currently disabled in `SandboxVpnService` (on-device verification
        // found `addAllowedApplication` breaks DNS resolution for the very app it allows, combined with
        // this service's existing always-on VPN lockdown policy — see that file's note and this date's
        // STATE.md). Gating on `isolation.scoped` while the mechanism that sets it true is permanently
        // disabled would fail-close every single launch, not just unscoped ones — that is a worse outcome
        // than the known, pre-existing whole-Work-Profile-capture gap this was meant to close. Re-enable
        // this gate only once scoping itself is fixed and re-verified; `isolation.scoped` is still
        // computed and available on `NetworkIsolationState` for that point, not removed.

        val activities = launcherApps.getActivityList(targetPackage, workHandle)
        val target = activities?.firstOrNull()
        if (target == null) {
            // User requirement: "No launchable activity: This APK does not provide an activity that can be opened directly. This is not automatically a sandbox failure."
            return SandboxOperationResult.Failure(
                session,
                SandboxError(
                    SandboxErrorCode.NO_LAUNCHABLE_ACTIVITY,
                    "This APK does not provide an activity that can be opened directly.",
                    "No launcher activity found for $targetPackage in work profile",
                    Recoverability.TERMINAL,
                ),
            )
        }

        return if (session.state == SandboxSessionState.READY) {
            var next = session.transitionTo(SandboxSessionState.LAUNCHING)
            repository.save(next)
            try {
                launcherApps.startMainActivity(target.componentName, workHandle, null, null)
                next =
                    next.transitionTo(SandboxSessionState.RUNNING).copy(startedAt = Instant.now())
                repository.save(next)
                SandboxOperationResult.Success(next)
            } catch (e: Exception) {
                val error = SandboxError(
                    SandboxErrorCode.LAUNCH_FAILED,
                    "The app could not be launched.",
                    e.toString(),
                    Recoverability.RETRYABLE
                )
                next = next.transitionTo(SandboxSessionState.FAILED).copy(error = error)
                repository.save(next)
                SandboxOperationResult.Failure(next, error)
            }
        } else {
            // Already in RUNNING state — reopen the target app
            try {
                launcherApps.startMainActivity(target.componentName, workHandle, null, null)
                SandboxOperationResult.Success(session)
            } catch (e: Exception) {
                val error = SandboxError(
                    SandboxErrorCode.LAUNCH_FAILED,
                    "The app could not be launched.",
                    e.toString(),
                    Recoverability.RETRYABLE
                )
                SandboxOperationResult.Failure(session, error)
            }
        }
    }

    override suspend fun end(activity: Activity, sessionId: String): SandboxOperationResult {
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        val terminalStates = setOf(
            SandboxSessionState.COMPLETED,
            SandboxSessionState.FAILED,
            SandboxSessionState.CANCELLED,
        )

        // A failed prepare can still have a live Work-side VPN: the failure may have been
        // reported after Work established isolation, and older Work-profile APKs did not yet
        // run the early-failure teardown hook.  The Preparing screen's End Session action must
        // therefore remain a real cleanup action for terminal Personal rows.  Query Work first
        // and only send the close request when the live session is safe to tear down; never a
        // different, legitimately RUNNING session the user has not asked to end.
        if (session.state in terminalStates) {
            val snapshot = try {
                kotlinx.coroutines.withTimeoutOrNull(WORK_SESSION_QUERY_TIMEOUT_MS) {
                    queryWorkActiveSession(activity)
                }
            } catch (_: Exception) {
                null
            }
            if (snapshot == null) {
                return SandboxOperationResult.Failure(
                    session,
                    SandboxError(
                        SandboxErrorCode.WORK_SESSION_STATE_UNKNOWN,
                        "Could not confirm whether this sandbox session is closed. Please try ending it again.",
                        "end: terminal session cleanup query did not respond for session=$sessionId",
                        Recoverability.RETRYABLE,
                    ),
                )
            }
            val blockingSessionId = snapshot.sessionId
            if (!snapshot.active || blockingSessionId == null) {
                return SandboxOperationResult.Success(session)
            }
            // Root-cause fix, confirmed on-device: the live Work session is not always attributed
            // to this exact terminal row — a `prepare()` that failed with ANOTHER_SESSION_ACTIVE
            // leaves an *older*, different session holding Work's VPN. The old code only ever
            // cleaned up a same-id match and silently reported success for every other case,
            // which meant this exact screen's own "End Session" button could never actually clear
            // the conflict it exists to resolve — every subsequent attempt hit the identical wall.
            // This is an explicit, user-confirmed action (gated by the End Session confirmation
            // dialog), so it is safe to resolve any blocker except one Personal can positively
            // confirm is a *different*, still-legitimately-running session — that one is never
            // torn down as a side effect of ending this unrelated row.
            if (blockingSessionId != sessionId) {
                val blockingPersonal = repository.get(blockingSessionId)
                val outcome = com.nadeem.apkscope.core.model.SessionReconciliation.reconcile(
                    personalSessionId = blockingPersonal?.id,
                    personalState = blockingPersonal?.state,
                    workActive = true,
                    workActiveSessionId = blockingSessionId,
                )
                if (outcome == com.nadeem.apkscope.core.model.SessionReconciliation.Outcome.RECOVER_RUNNING) {
                    return SandboxOperationResult.Failure(
                        session,
                        SandboxError(
                            SandboxErrorCode.ANOTHER_SESSION_ACTIVE,
                            "A different sandbox session is still running. End that session first, then retry.",
                            "end: session=$sessionId cannot clear unrelated live session=$blockingSessionId (outcome=$outcome)",
                            Recoverability.REQUIRES_USER_ACTION,
                        ),
                    )
                }
            }
            val fallbackPackageName = if (blockingSessionId == sessionId) session.packageName else null
            return when (
                val teardown = endOrphanWithoutPersonalRow(
                    activity,
                    OrphanSessionInfo(
                        sessionId = blockingSessionId,
                        packageName = snapshot.packageName ?: fallbackPackageName,
                        startedAtEpochMs = snapshot.startedAtEpochMs,
                        networkIsolationActive = true,
                    ),
                )
            ) {
                is SandboxOperationResult.Success -> SandboxOperationResult.Success(session)
                is SandboxOperationResult.Failure -> SandboxOperationResult.Failure(session, teardown.error)
            }
        }

        if (session.state !in setOf(
                SandboxSessionState.CREATED,
                SandboxSessionState.PREPARING,
                SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION,
                SandboxSessionState.INSTALLING,
                SandboxSessionState.INSTALLED,
                SandboxSessionState.READY,
                SandboxSessionState.RUNNING,
                SandboxSessionState.ENDING,
                SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION,
            )
        ) return SandboxOperationResult.Success(
            session
        )

        var next = if (session.state != SandboxSessionState.ENDING && session.state != SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION) session.transitionTo(
            SandboxSessionState.ENDING
        ) else session
        repository.save(next)

        return try {
            val controlFile = File(context.filesDir, "sandbox/control/$sessionId-end.json").apply {
                parentFile?.mkdirs()
                writeText(
                    JSONObject().put("sessionId", sessionId).put("packageName", session.packageName)
                        .toString()
                )
            }
            Handoff.send(
                activity,
                controlFile,
                CrossProfileContract.ACTION_END_SESSION,
                sessionId,
                SANDBOX_FILE_PROVIDER_AUTHORITY
            )
            if (!awaitWorkSessionClosed(activity, sessionId)) {
                val error = SandboxError(
                    SandboxErrorCode.WORK_SESSION_STATE_UNKNOWN,
                    "Could not confirm that the sandbox session closed. Please retry ending it.",
                    "end: Work active-session query did not confirm inactive for session=$sessionId",
                    Recoverability.RETRYABLE,
                )
                return SandboxOperationResult.Failure(next, error)
            }
            SandboxOperationResult.Success(next)
        } catch (e: Exception) {
            val error = SandboxError(
                SandboxErrorCode.HANDOFF_FAILED,
                "Could not start ending this session.",
                e.toString(),
                Recoverability.RETRYABLE
            )
            // ENDING is the authoritative state after the user has requested teardown. A failed
            // handoff must remain on a legal lifecycle edge; CLEANUP_REQUIRED is only reachable
            // after the actual cleanup sequence has progressed through CLEARING_DATA.
            next = next.transitionTo(SandboxSessionState.FAILED).copy(error = error)
            repository.save(next)
            SandboxOperationResult.Failure(next, error)
        }
    }

    override suspend fun requestUninstallConfirmation(
        activity: Activity,
        sessionId: String
    ): SandboxOperationResult {
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        val pkg = session.packageName
        return try {
            val intent = Handoff.buildQueryIntent(
                activity,
                CrossProfileContract.QUERY_TYPE_REQUEST_UNINSTALL,
                sessionId,
                pkg
            )
            CrossProfileQueryBridge.launchForResult(intent)
            reconcile(sessionId)
            val updated = repository.get(sessionId) ?: session
            SandboxOperationResult.Success(updated)
        } catch (e: Exception) {
            SandboxOperationResult.Failure(
                session,
                SandboxError(
                    SandboxErrorCode.UNKNOWN,
                    "Failed to launch uninstall confirmation",
                    e.toString(),
                    Recoverability.RETRYABLE
                )
            )
        }
    }

    override suspend fun checkWorkInstallPermission(
        activity: Activity,
        sessionId: String
    ): Boolean? {
        return try {
            val intent = Handoff.buildQueryIntent(
                activity,
                SandboxWorkQueryActivity.QUERY_TYPE_CHECK_INSTALL_PERMISSION,
                sessionId
            )
            val result = CrossProfileQueryBridge.launchForResult(intent)
            val json = readBoundedResultJson(context, result) ?: return null
            if (!json.has("canRequestPackageInstalls")) return null
            json.getBoolean("canRequestPackageInstalls")
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun requestOpenWorkInstallSettings(
        activity: Activity,
        sessionId: String
    ): Boolean {
        return try {
            val intent = Handoff.buildQueryIntent(
                activity,
                SandboxWorkQueryActivity.QUERY_TYPE_OPEN_INSTALL_SETTINGS,
                sessionId
            )
            // Suspends until the user has actually returned from the Settings screen (or the attempt to
            // open it definitively failed) — see SandboxWorkQueryActivity's handling of this query type,
            // which mirrors requestUninstallConfirmation's own startActivityForResult-then-respond shape.
            val result = CrossProfileQueryBridge.launchForResult(intent)
            val json = readBoundedResultJson(context, result) ?: return false
            json.optBoolean("opened", false)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun cancel(sessionId: String): SandboxOperationResult {
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        return try {
            val next = session.transitionTo(SandboxSessionState.CANCELLED)
            repository.save(next)
            SandboxOperationResult.Success(next)
        } catch (e: com.nadeem.apkscope.core.model.IllegalSandboxTransitionException) {
            SandboxOperationResult.Failure(
                session,
                SandboxError(
                    SandboxErrorCode.INVALID_STATE_TRANSITION,
                    "This session can no longer be cancelled.",
                    e.toString(),
                    Recoverability.TERMINAL
                )
            )
        }
    }

// /**
//  * Item 9/19: recovers the correct lifecycle state after activity recreation/navigation away/
//  * process death, using real authoritative Android state — [LauncherApps] here, never a Room row
//  * trusted blindly — rather than only an `Activity` callback or cross-profile report that could
//  * have been lost while this process was dead.
//  */
// override suspend fun reconcile(sessionId: String): SandboxOperationResult {
//  val session = repository.get(sessionId) ?: return notFound(sessionId)
//  val workHandle = SandboxEnvironmentPreflight.workProfileHandle(context)
//  val launcherApps = context.getSystemService(LauncherApps::class.java)
//  val installedInWork = workHandle != null && launcherApps != null && try {
//   launcherApps.getApplicationInfo(session.packageName, 0, workHandle) != null
//  } catch (e: Exception) {
//   // TEMP DIAGNOSTIC (Checkpoint 8.9 acceptance scenario — not for commit)
//   android.util.Log.e("ReconcileDiag", "getApplicationInfo(pkg=${session.packageName}, workHandle=$workHandle) threw: ${e.javaClass.name}: ${e.message}", e)
//   false
//  }
//  // TEMP DIAGNOSTIC (Checkpoint 8.9 acceptance scenario — not for commit)
//  android.util.Log.i("ReconcileDiag", "reconcile session=${sessionId} state=${session.state} workHandle=$workHandle installedInWork=$installedInWork")
//
//  var next = session
//  when (session.state) {
//   SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION, SandboxSessionState.INSTALLING -> {
//    // Process-death scenario A: the install confirmation may have completed while we were dead.
//    if (installedInWork) {
//     next = safeTransition(session, SandboxSessionState.INSTALLED) ?: session
//     val readiness = SandboxEnvironmentPreflight.computeLaunchReadiness(context, next)
//     // TEMP DIAGNOSTIC (Checkpoint 8.9 acceptance scenario — not for commit)
//     android.util.Log.i("ReconcileDiag", "readiness after INSTALLED transition: installationConfirmed=${readiness.installationConfirmed} environmentValid=${readiness.environmentValid} requiredPoliciesSatisfied=${readiness.requiredPoliciesSatisfied} networkIsolationActive=${readiness.networkIsolationActive} launchAllowed=${readiness.launchAllowed} transitionSucceeded=${next.state}")
//     if (readiness.launchAllowed) next = safeTransition(next, SandboxSessionState.READY) ?: next
//    }
//   }
//   SandboxSessionState.INSTALLED -> {
//    // Same recovery as scenario A, for a session that already reached INSTALLED but never advanced
//    // to READY — e.g. its install-result report never arrived (a lost cross-profile callback).
//    val readiness = SandboxEnvironmentPreflight.computeLaunchReadiness(context, session)
//    if (readiness.launchAllowed) next = safeTransition(session, SandboxSessionState.READY) ?: session
//   }
//   SandboxSessionState.RUNNING -> {
//    // Process-death scenario B: nothing to correct — the sandboxed app keeps running in the work
//    // profile independently of this process; the persisted RUNNING row is already the right state.
//   }
//   SandboxSessionState.ENDING, SandboxSessionState.CLEARING_DATA, SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION -> {
//    // Process-death scenario C, generalized: the end-session sequence's own status reports are
//    // just as vulnerable to a lost cross-profile callback as the install-confirmation ones (item
//    // 9/19) — real absence from the Work Profile, verified here independently via `LauncherApps`,
//    // is authoritative regardless of which report went missing. ENDING/CLEARING_DATA first need to
//    // walk forward to CLEARING_DATA (the only legal predecessor of CLEANUP_REQUIRED) before the
//    // same recovery scenario C already used for WAITING_FOR_UNINSTALL_CONFIRMATION applies.
//    if (!installedInWork) {
//     // Checkpoint 4.1 §8/15: this uses whatever `dataClearResult` is *already* in Room — which may
//     // be null/never-arrived if no report and no `importEvidence` pull ever completed for this
//     // session. `reconcile` has no `Activity` to perform a fresh pull with (it runs from a plain
//     // ViewModel `init`); `importEvidence` (called separately, with a real Activity, by the
//     // Cleanup screen) is what actually recovers a lost data-clear fact — see item 15's "never
//     // infer success merely because uninstall later succeeded": this deliberately does NOT
//     // fabricate `appDataCleared = true` from absence-of-report; it only ever reflects a real,
//     // previously-merged fact.
//     val personalCleanupDone = performPersonalCleanup(session)
//     val summary = com.nadeem.apkscope.core.model.CleanupSummary(
//      appDataCleared = session.dataClearResult ?: false, apkRemoved = true, workTempApkDeleted = true,
//      personalTempApkDeleted = personalCleanupDone, uriGrantReleased = personalCleanupDone, networkSessionClosed = true,
//     )
//     next = applyCleanupOutcome(session.copy(cleanupSummary = summary))
//    }
//   }
//   else -> { /* every other state is already exactly what Room says; nothing to reconcile */ }
//  }
//  if (next != session) repository.save(next)
//  return SandboxOperationResult.Success(next)
// }
    /**
     * Item 9/19: recovers the correct lifecycle state after activity recreation/navigation away/
     * process death, using real authoritative Android state — [LauncherApps] here, never a Room row
     * trusted blindly — rather than only an `Activity` callback or cross-profile report that could
     * have been lost while this process was dead.
     */
    override suspend fun reconcile(sessionId: String): SandboxOperationResult {
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        val workHandle = SandboxEnvironmentPreflight.workProfileHandle(context)
        val launcherApps = context.getSystemService(LauncherApps::class.java)

        val installedInWork = workHandle != null && launcherApps != null && try {
            launcherApps.getApplicationInfo(session.packageName, 0, workHandle) != null
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            android.util.Log.i(
                "ReconcileDiag",
                "Package ${session.packageName} is not installed in work profile yet; state=${session.state} workHandle=$workHandle"
            )
            false
        } catch (e: Exception) {
            // Safe fallback catch-all for any other unanticipated security/inter-process communication errors.
            android.util.Log.e(
                "ReconcileDiag",
                "Unexpected query error for ${session.packageName}: ${e.message}"
            )
            false
        }

        android.util.Log.i(
            "ReconcileDiag",
            "reconcile session=${sessionId} state=${session.state} workHandle=$workHandle installedInWork=$installedInWork"
        )

        var next = session
        when (session.state) {
            SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION, SandboxSessionState.INSTALLING -> {
                // Package presence is not proof that *this* install completed. A previous APK
                // with the same package can already be installed while the current
                // PackageInstaller session is still waiting for Android's confirmation UI. The
                // old fallback promoted that stale APK to READY, launched it, and caused its old
                // Frida token to be rejected by the current session's monitor. Only the
                // authenticated install-result report may establish INSTALLED; if that report is
                // lost, the user must retry/reinstall rather than silently running an old APK.
                android.util.Log.i(
                    "ReconcileDiag",
                    "not inferring install completion from package presence: state=${session.state} installedInWork=$installedInWork installSessionId=${session.installSessionId}"
                )
            }

            SandboxSessionState.INSTALLED -> {
                // Same recovery as scenario A, for a session that already reached INSTALLED but never advanced
                // to READY — e.g. its install-result report never arrived (a lost cross-profile callback).
                val readiness = SandboxEnvironmentPreflight.computeLaunchReadiness(context, session)
                if (readiness.launchAllowed) next =
                    safeTransition(session, SandboxSessionState.READY) ?: session
            }

            SandboxSessionState.RUNNING -> {
                // Process-death scenario B: nothing to correct — the sandboxed app keeps running in the work
                // profile independently of this process; the persisted RUNNING row is already the right state.
            }

            SandboxSessionState.ENDING, SandboxSessionState.CLEARING_DATA, SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION -> {
                // Process-death scenario C, generalized: the end-session sequence's own status reports are
                // just as vulnerable to a lost cross-profile callback as the install-confirmation ones (item
                // 9/19) — real absence from the Work Profile, verified here independently via `LauncherApps`,
                // is authoritative regardless of which report went missing. ENDING/CLEARING_DATA first need to
                // walk forward to CLEARING_DATA (the only legal predecessor of CLEANUP_REQUIRED) before the
                // same recovery scenario C already used for WAITING_FOR_UNINSTALL_CONFIRMATION applies.
                if (!installedInWork) {
                    // Checkpoint 4.1 §8/15: this uses whatever `dataClearResult` is *already* in Room — which may
                    // be null/never-arrived if no report and no `importEvidence` pull ever completed for this
                    // session. `reconcile` has no `Activity` to perform a fresh pull with (it runs from a plain
                    // ViewModel `init`); `importEvidence` (called separately, with a real Activity, by the
                    // Cleanup screen) is what actually recovers a lost data-clear fact — see item 15's "never
                    // infer success merely because uninstall later succeeded": this deliberately does NOT
                    // fabricate `appDataCleared = true` from absence-of-report; it only ever reflects a real,
                    // previously-merged fact.
                    val personalCleanupDone = performPersonalCleanup(session)
                    val summary = com.nadeem.apkscope.core.model.CleanupSummary(
                        appDataCleared = session.dataClearResult ?: false,
                        apkRemoved = true,
                        workTempApkDeleted = true,
                        personalTempApkDeleted = personalCleanupDone,
                        uriGrantReleased = personalCleanupDone,
                        networkSessionClosed = true,
                    )
                    next = applyCleanupOutcome(session.copy(cleanupSummary = summary))
                }
            }

            else -> { /* every other state is already exactly what Room says; nothing to reconcile */
            }
        }
        if (next != session) repository.save(next)
        return SandboxOperationResult.Success(next)
    }

    override suspend fun failAbandonedPreparing(sessionId: String): SandboxOperationResult {
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        if (session.state != SandboxSessionState.PREPARING) return SandboxOperationResult.Success(session)
        val error = SandboxError(
            SandboxErrorCode.HANDOFF_FAILED,
            "Preparing this sandbox did not finish. Please retry.",
            "abandoned-preparing: session=$sessionId still PREPARING after an explicit reconciliation pull found no further Work evidence",
            Recoverability.RETRYABLE,
        )
        val failed = session.transitionTo(SandboxSessionState.FAILED).copy(error = error)
        repository.save(failed)
        return SandboxOperationResult.Failure(failed, error)
    }

    /**
     * Checkpoint 4.1 §6/7/8: the pull half of the transport decision. Always launched from a
     * foreground Activity (never from a background context — the whole point), and always safe to
     * call speculatively: on any failure (no evidence yet, a rejected/invalid response, an unreachable
     * Work profile) this returns the session **unchanged**, never a failure result — a failed pull
     * must never itself fail or regress a session.
     */
    override suspend fun importEvidence(
        activity: Activity,
        sessionId: String
    ): SandboxOperationResult {
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        val report = try { pullEvidenceFacts(activity, session) } catch (_: Exception) { null }
        val next = repository.update(sessionId) { latest -> applyImportedReport(latest, report) }
            ?: return notFound(sessionId)
        return SandboxOperationResult.Success(next)
    }

    /** Item 1/2/4: the one place this whole coordinator ever asks Work "is a session active, and which one" — every other reconciliation/invariant check goes through this, never a separate ad-hoc query. */
    private suspend fun queryWorkActiveSession(activity: Activity): WorkActiveSessionSnapshot {
        activeSessionQueryOverride?.let { return it(activity) }
        val intent = Handoff.buildQueryIntent(
            activity,
            SandboxWorkQueryActivity.QUERY_TYPE_ACTIVE_SESSION,
            "n/a"
        )
        val result = CrossProfileQueryBridge.launchForResult(intent)
        val json = readBoundedResultJson(context, result) ?: return WorkActiveSessionSnapshot(
            active = false,
            sessionId = null,
            packageName = null,
            startedAtEpochMs = null
        )
        return WorkActiveSessionSnapshot(
            active = json.optBoolean("active", false),
            sessionId = json.optString("sessionId").takeIf { it.isNotEmpty() },
            packageName = json.optString("packageName").takeIf { it.isNotEmpty() },
            startedAtEpochMs = if (json.has("startedAtEpochMs")) json.optLong("startedAtEpochMs") else null,
        )
    }

    override suspend fun checkForOrphanWorkSession(activity: Activity): OrphanSessionInfo? {
        val snapshot = try {
            queryWorkActiveSession(activity)
        } catch (_: Exception) {
            return null
        }
        if (!snapshot.active || snapshot.sessionId == null) return null
        val personal = repository.get(snapshot.sessionId)
        val outcome = com.nadeem.apkscope.core.model.SessionReconciliation.reconcile(
            personalSessionId = personal?.id, personalState = personal?.state,
            workActive = true, workActiveSessionId = snapshot.sessionId,
        )
        if (outcome != com.nadeem.apkscope.core.model.SessionReconciliation.Outcome.ORPHAN_DETECTED &&
            outcome != com.nadeem.apkscope.core.model.SessionReconciliation.Outcome.STALE_WORK_SESSION
        ) return null
        return OrphanSessionInfo(
            snapshot.sessionId,
            snapshot.packageName,
            snapshot.startedAtEpochMs,
            networkIsolationActive = true
        )
    }

    override suspend fun resolveOrphanWorkSession(
        activity: Activity,
        info: OrphanSessionInfo
    ): SandboxOperationResult {
        val existing = repository.get(info.sessionId)
        val session = existing ?: SandboxSession(
            id = info.sessionId,
            // Item 5's "do not fabricate a Personal analysis association if none exists" — this is not a
            // real analysisId, and is never treated as one (no analysis lookup ever keys off it); it exists
            // only so the row satisfies SandboxSession's non-null field, and is labeled unambiguously.
            analysisId = "orphan-recovery",
            packageName = info.packageName.orEmpty(),
            state = SandboxSessionState.RUNNING,
            requestedPolicy = SandboxPolicy(),
            createdAt = info.startedAtEpochMs?.let { Instant.ofEpochMilli(it) } ?: Instant.now(),
            startedAt = info.startedAtEpochMs?.let { Instant.ofEpochMilli(it) },
        )
        if (existing == null) repository.save(session)
        else if (existing.state != SandboxSessionState.RUNNING) {
            // A STALE_WORK_SESSION case (item 2): Personal's own record is already terminal — Work is the
            // one out of date here. Never overwrite Personal's terminal record's history; the End Session
            // sequence below talks to Work directly regardless of what Personal's row currently says.
            return endOrphanWithoutPersonalRow(activity, info)
        }
        // Dynamic evidence is intentionally not imported during orphan recovery. If the recovered
        // session is shown in Personal, the user can explicitly save it from the cleanup screen.
        return end(activity, info.sessionId)
    }

    /** Item 2's STALE_WORK_SESSION branch: Personal already has a real, terminal record for this sessionId — [end] itself requires RUNNING, so this talks to Work directly instead of mutating Personal's already-final history. */
    private suspend fun endOrphanWithoutPersonalRow(
        activity: Activity,
        info: OrphanSessionInfo
    ): SandboxOperationResult {
        val controlFile =
            File(context.filesDir, "sandbox/control/${info.sessionId}-end.json").apply {
                parentFile?.mkdirs()
                writeText(
                    JSONObject().put("sessionId", info.sessionId)
                        .put("packageName", info.packageName.orEmpty()).toString()
                )
            }
        return try {
            Handoff.send(
                activity,
                controlFile,
                CrossProfileContract.ACTION_END_SESSION,
                info.sessionId,
                SANDBOX_FILE_PROVIDER_AUTHORITY
            )
            if (!awaitWorkSessionClosed(activity, info.sessionId)) {
                val error = SandboxError(
                    SandboxErrorCode.WORK_SESSION_STATE_UNKNOWN,
                    "Could not confirm that the stale sandbox session closed. Please retry.",
                    "endOrphanWithoutPersonalRow: Work active-session query did not confirm inactive for session=${info.sessionId}",
                    Recoverability.RETRYABLE,
                )
                return SandboxOperationResult.Failure(
                    repository.get(info.sessionId) ?: notFound(info.sessionId).session,
                    error,
                )
            }
            SandboxOperationResult.Success(
                repository.get(info.sessionId) ?: notFound(info.sessionId).session
            )
        } catch (e: Exception) {
            val error = SandboxError(
                SandboxErrorCode.HANDOFF_FAILED,
                "Could not start ending the stale Work session.",
                e.toString(),
                Recoverability.RETRYABLE
            )
            SandboxOperationResult.Failure(
                repository.get(info.sessionId) ?: notFound(info.sessionId).session, error
            )
        }
    }

    /**
     * The End Session handoff is asynchronous. Before reporting success to Personal, require a
     * fresh Work-profile readback that the authoritative VPN session is gone. Without this barrier,
     * the user can start a new session after the Personal row changes while Work is still forwarding
     * the previous target.
     */
    private suspend fun awaitWorkSessionClosed(activity: Activity, sessionId: String): Boolean {
        return kotlinx.coroutines.withTimeoutOrNull<Boolean>(WORK_SESSION_CLOSE_TIMEOUT_MS) {
            var closed = false
            while (!closed) {
                val snapshot = try {
                    queryWorkActiveSession(activity)
                } catch (_: Exception) {
                    null
                }
                if (snapshot == null) {
                    kotlinx.coroutines.delay(250)
                    continue
                }
                if (!snapshot.active) {
                    closed = true
                    continue
                }
                if (snapshot.sessionId != sessionId) return@withTimeoutOrNull false
                kotlinx.coroutines.delay(250)
            }
            true
        } ?: false
    }

    override suspend fun reconcileRunningSession(
        activity: Activity,
        sessionId: String
    ): com.nadeem.apkscope.core.model.SessionReconciliation.Outcome {
        val session = repository.get(sessionId)
            ?: return com.nadeem.apkscope.core.model.SessionReconciliation.Outcome.NO_ACTION
        val snapshot = try {
            queryWorkActiveSession(activity)
        } catch (_: Exception) {
            // A failed query must never itself fabricate an outcome — matches importEvidence/importRuntimeArtifact's own tolerance for a failed pull.
            return com.nadeem.apkscope.core.model.SessionReconciliation.Outcome.NO_ACTION
        }
        val outcome = com.nadeem.apkscope.core.model.SessionReconciliation.reconcile(
            personalSessionId = session.id, personalState = session.state,
            workActive = snapshot.active, workActiveSessionId = snapshot.sessionId,
        )
        when (outcome) {
            com.nadeem.apkscope.core.model.SessionReconciliation.Outcome.INTERRUPTED -> failClosed(
                session, SandboxErrorCode.WORK_SESSION_INTERRUPTED,
                "The sandboxed app's network isolation is no longer active. This session cannot continue.",
                "reconcileRunningSession: workActive=${snapshot.active} workSessionId=${snapshot.sessionId} personalSessionId=${session.id}",
                Recoverability.TERMINAL,
            )

            com.nadeem.apkscope.core.model.SessionReconciliation.Outcome.CONFLICT -> failClosed(
                session, SandboxErrorCode.ANOTHER_SESSION_ACTIVE,
                "A different sandbox session is active. This session's state could not be confirmed.",
                "reconcileRunningSession: workSessionId=${snapshot.sessionId} personalSessionId=${session.id}",
                Recoverability.REQUIRES_USER_ACTION,
            )
            // RECOVER_RUNNING/NO_ACTION/ORPHAN_DETECTED/STALE_WORK_SESSION: none of these call for changing
            // *this* session's own row — a real RECOVER_RUNNING match needs no correction, and the other two
            // outcomes are checkForOrphanWorkSession's own territory, not this already-known session's.
            else -> {}
        }
        return outcome
    }

    /** The actual foreground-initiated cross-profile round trip for [importEvidence]. */
    private suspend fun pullEvidenceFacts(
        activity: Activity,
        session: SandboxSession
    ): SandboxStatusReport? {
        val intent = Handoff.buildQueryIntent(
            activity,
            SandboxWorkQueryActivity.QUERY_TYPE_EXPORT_EVIDENCE,
            session.id
        )
        val result = CrossProfileQueryBridge.launchForResult(intent)
        val envelope = readBoundedResultJson(context, result) ?: return null
        return validateEnvelope(envelope, session)
    }

    /** Item 16's security validation, delegated to the pure/testable [com.nadeem.apkscope.sandbox.WorkEvidenceEnvelope.isValidFor] — reject silently (never merge) on any mismatch. */
    private fun validateEnvelope(
        envelope: JSONObject,
        session: SandboxSession
    ): SandboxStatusReport? {
        val parsed = com.nadeem.apkscope.sandbox.WorkEvidenceEnvelope.fromJson(envelope)
        if (!parsed.isValidFor(session.id, session.packageName)) return null
        return parsed.report
    }

    /** Checkpoint 5, item 19-24: the pull/validate/persist/ack sequence for the runtime-observation artifact — a genuinely separate concern from [importEvidence]'s small status-report pull, sharing only the same foreground-query transport shape. */
    override suspend fun importRuntimeArtifact(
        activity: Activity,
        sessionId: String
    ): SandboxOperationResult {
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        try {
            val intent = Handoff.buildQueryIntent(
                activity,
                SandboxWorkQueryActivity.QUERY_TYPE_EXPORT_RUNTIME_ARTIFACT,
                sessionId
            )
            val result = CrossProfileQueryBridge.launchForResult(intent)
            val json = readBoundedResultJson(
                context,
                result,
                RuntimeObservationArtifact.MAX_ARTIFACT_BYTES
            ) ?: return SandboxOperationResult.Success(session)
            if (json.has("error")) return SandboxOperationResult.Success(session) // no runtime summary recorded work-side yet — nothing to import, not a failure.
            val artifact = RuntimeObservationArtifact.fromJson(json)
            // Item 21: reject silently (never persist) on any validation failure — never trusted merely
            // because it arrived via the same package's cross-profile channel.
            val rejection =
                RuntimeObservationArtifact.validate(artifact, sessionId, session.packageName)
            if (rejection != null) return SandboxOperationResult.Success(session)
            persistRuntimeArtifact(artifact)
            sendRuntimeArtifactAck(activity, sessionId)
        } catch (_: Exception) {
            // A failed pull/parse/persist must never itself fail or regress the session (matches importEvidence).
        }
        return SandboxOperationResult.Success(session)
    }

    /**
     * Item 22/23/26: writes [artifact] into Personal's own durable Room store — the DAO's
     * `(sessionId, sequence)` unique index with `OnConflictStrategy.IGNORE` is what makes re-importing
     * the same artifact a safe no-op (item 23) without any extra dedup logic here.
     */
    private suspend fun persistRuntimeArtifact(artifact: RuntimeObservationArtifact) {
        val dao = com.nadeem.apkscope.core.database.SandboxDatabaseProvider.get(context).observationDao()
        val rows = artifact.observations.map { e ->
            com.nadeem.apkscope.core.database.NetworkObservationEntity(
                sessionId = artifact.sessionId,
                sequence = e.sequence,
                timestampEpochMs = e.timestampEpochMs,
                type = e.type,
                protocol = e.protocol,
                destinationIp = e.destinationIp,
                destinationPort = e.destinationPort,
                connectionId = e.connectionId,
                startTimeEpochMs = e.startTimeEpochMs,
                endTimeEpochMs = e.endTimeEpochMs,
                uploadedBytes = e.uploadedBytes,
                downloadedBytes = e.downloadedBytes,
                failureReason = e.failureReason,
                failureDetail = e.failureDetail,
                hostname = e.hostname,
                resolvedAddressesCsv = e.resolvedAddressesCsv,
                transactionId = e.transactionId,
                sourcePort = e.sourcePort,
                limitName = e.limitName,
                currentValue = e.currentValue,
                limitValue = e.limitValue,
            )
        }
        dao.insertAll(rows)
        dao.upsertSummary(
            com.nadeem.apkscope.core.database.RuntimeObservationSummaryEntity(
                sessionId = artifact.sessionId,
                startedAtEpochMs = artifact.startedAtEpochMs,
                endedAtEpochMs = artifact.endedAtEpochMs,
                connectionCount = artifact.summary.connectionCount,
                dnsQueryCount = artifact.summary.dnsQueryCount,
                uniqueObservedDomains = artifact.summary.uniqueObservedDomains,
                uploadedBytes = artifact.summary.uploadedBytes,
                downloadedBytes = artifact.summary.downloadedBytes,
                blockedConnectionCount = artifact.summary.blockedConnectionCount,
                failedConnectionCount = artifact.summary.failedConnectionCount,
                droppedObservationCount = artifact.summary.droppedObservationCount,
                schemaVersion = artifact.schemaVersion,
                truncated = artifact.truncated,
                exportedObservationCount = artifact.exportedObservationCount,
                totalObservationCount = artifact.totalObservationCount,
                importedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        val session = repository.get(artifact.sessionId)
        if (session != null) {
            try {
                com.nadeem.apkscope.domain.report.ReportCoordinator(context)
                    .generateOrUpdateSessionReport(
                        sessionId = artifact.sessionId,
                        analysisId = session.analysisId,
                    )
            } catch (_: Exception) {
            }
        }
    }

    /** Item 24: best-effort, sent only after [persistRuntimeArtifact] has already returned successfully — a lost ack is explicitly tolerated (the artifact simply stays available Work-side for a later retry), so any failure here is swallowed rather than surfaced as a session failure. */
    private fun sendRuntimeArtifactAck(activity: Activity, sessionId: String) {
        try {
            val controlFile = File(context.filesDir, "sandbox/control/$sessionId-ack.json").apply {
                parentFile?.mkdirs()
                writeText(JSONObject().put("sessionId", sessionId).toString())
            }
            Handoff.send(
                activity,
                controlFile,
                CrossProfileContract.ACTION_ACK_RUNTIME_ARTIFACT,
                sessionId,
                SANDBOX_FILE_PROVIDER_AUTHORITY
            )
        } catch (_: Exception) { /* best-effort only — see doc comment above */
        }
    }

    /** Checkpoint 6: the pull/validate/persist/ack sequence for the dedicated Android-evidence artifact. */
    override suspend fun importAndroidEvidence(
        activity: Activity,
        sessionId: String
    ): SandboxOperationResult {
        val session = repository.get(sessionId) ?: return notFound(sessionId)
        try {
            val intent = Handoff.buildQueryIntent(
                activity,
                SandboxWorkQueryActivity.QUERY_TYPE_EXPORT_ANDROID_EVIDENCE,
                sessionId
            )
            val result = CrossProfileQueryBridge.launchForResult(intent)
            val json = readBoundedResultJson(
                context,
                result,
                com.nadeem.apkscope.sandbox.AndroidEvidenceArtifact.MAX_ARTIFACT_BYTES
            ) ?: return SandboxOperationResult.Success(session)
            if (json.has("error")) return SandboxOperationResult.Success(session)
            val artifact = com.nadeem.apkscope.sandbox.AndroidEvidenceArtifact.fromJson(json.toString())
            val rejection = com.nadeem.apkscope.sandbox.AndroidEvidenceArtifact.validate(
                artifact,
                sessionId,
                session.packageName
            )
            if (rejection != null) return SandboxOperationResult.Success(session)
            persistAndroidEvidence(artifact)
            sendAndroidEvidenceAck(activity, sessionId)
            try {
                com.nadeem.apkscope.domain.report.ReportCoordinator(context)
                    .generateOrUpdateSessionReport(
                        sessionId = sessionId,
                        analysisId = session.analysisId,
                        overrideAndroidStatus = com.nadeem.apkscope.core.model.AndroidEvidenceStatus.READY,
                    )
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
            // A failed pull/parse/persist must never itself fail or regress the session.
        }
        return SandboxOperationResult.Success(session)
    }

    /**
     * Checkpoint 8.9: pull/validate/correlate sequence for bounded URL evidence artifact.
     *
     * Milestone 9 (Pixel 8 acceptance, fifth pass, item 2/3): the whole body is now inside one
     * try/catch — previously `repository.get(sessionId)` sat *outside* it, so a transient Room
     * read failure (a real, not hypothetical, risk on a real device under memory pressure) would
     * propagate uncaught out of this function entirely, silently abandoning the rest of the import
     * sequence with no trace anywhere. `UrlEvidencePipelineDiagnostics` records stage (b) "cross
     * profile query dispatch" before the query launches and immediately after it returns, and (g)
     * "personal side read and validation" at each decision point below — a genuinely completed
     * physical-device session that still shows no [UrlEvidenceImportStatusStore] outcome can now be
     * traced to the exact stage it stopped at, instead of guessed at from absence alone.
     */
    override suspend fun importUrlEvidence(
        activity: Activity,
        sessionId: String,
        operationId: String
    ): SandboxOperationResult {
        val diag = com.nadeem.apkscope.core.crossprofile.UrlEvidencePipelineDiagnostics
        try {
            val session = repository.get(sessionId) ?: run {
                diag.record(
                    context,
                    operationId,
                    "personal",
                    sessionId,
                    "personal_session_not_found_before_dispatch"
                )
                return notFound(sessionId)
            }
            // Milestone 9 (Pixel 8 acceptance, fifth pass, item 3): a durable "attempt started" marker,
            // written *before* the cross-profile query dispatches — if this process (or the coroutine
            // running it) dies before any later `record()` call below overwrites it, this PENDING row is
            // exactly what a later read finds: a genuine "started but never concluded" signal, distinct from
            // both "never attempted" (no row) and any concluded EMPTY/IMPORTED/FAILED outcome.
            UrlEvidenceImportStatusStore.markPending(context, session.analysisId)
            // Milestone 9: every early-return path below now records a durable outcome via
            // [UrlEvidenceImportStatusStore] — a failed/rejected/empty pull previously left no trace at all
            // beyond an occasional logcat line (item 5: "expose import failures and truncation through
            // durable application state, not logcat alone"). The session itself is never regressed by any
            // of these — matching the pre-existing "non-critical overlay" tolerance — but the analysis's own
            // Embedded URLs screen can now show the user *why* no new evidence appeared, instead of a
            // silent no-op indistinguishable from "nothing new happened."
            diag.record(
                context,
                operationId,
                "personal",
                sessionId,
                "cross_profile_query_dispatch_attempted",
                "analysisId=${session.analysisId}"
            )
            val intent = Handoff.buildQueryIntent(
                activity,
                SandboxWorkQueryActivity.QUERY_TYPE_EXPORT_URL_EVIDENCE,
                sessionId,
                operationId = operationId
            )
            val result = CrossProfileQueryBridge.launchForResult(intent)
            diag.record(
                context,
                operationId,
                "personal",
                sessionId,
                "cross_profile_query_dispatch_returned"
            )
            val json =
                readBoundedResultJson(context, result, UrlEvidenceArtifact.MAX_ARTIFACT_BYTES)
            if (json == null) {
                diag.record(
                    context,
                    operationId,
                    "personal",
                    sessionId,
                    "personal_read_result_null"
                )
                UrlEvidenceImportStatusStore.record(
                    context, session.analysisId,
                    UrlEvidenceImportStatusStore.Outcome(
                        System.currentTimeMillis(),
                        0,
                        false,
                        "No result returned from the Work profile query (cross-profile pull failed or returned nothing)"
                    )
                )
                diag.record(
                    context,
                    operationId,
                    "personal",
                    sessionId,
                    "durable_import_outcome_recorded",
                    "error=result_null"
                )
                return SandboxOperationResult.Success(session)
            }
            if (json.has("error")) {
                diag.record(
                    context,
                    operationId,
                    "personal",
                    sessionId,
                    "personal_read_result_error",
                    json.optString("error")
                )
                UrlEvidenceImportStatusStore.record(
                    context, session.analysisId,
                    UrlEvidenceImportStatusStore.Outcome(
                        System.currentTimeMillis(),
                        0,
                        false,
                        "Work profile reported an error: ${json.optString("error")}"
                    )
                )
                diag.record(
                    context,
                    operationId,
                    "personal",
                    sessionId,
                    "durable_import_outcome_recorded",
                    "error=work_reported_error"
                )
                return SandboxOperationResult.Success(session)
            }
            val artifact = UrlEvidenceArtifact.fromJson(json)
            diag.record(
                context,
                operationId,
                "personal",
                sessionId,
                "personal_artifact_parsed",
                "exportedEntryCount=${artifact.exportedEntryCount} totalEntryCount=${artifact.totalEntryCount} truncated=${artifact.truncated}"
            )
            // Validate before trust — same as RuntimeObservationArtifact and AndroidEvidenceArtifact
            val rejection = UrlEvidenceArtifact.validate(artifact, sessionId, session.packageName)
            if (rejection != null) {
                diag.record(
                    context,
                    operationId,
                    "personal",
                    sessionId,
                    "personal_artifact_validation_failed",
                    rejection
                )
                UrlEvidenceImportStatusStore.record(
                    context, session.analysisId,
                    UrlEvidenceImportStatusStore.Outcome(
                        System.currentTimeMillis(),
                        artifact.exportedEntryCount,
                        artifact.truncated,
                        "Artifact failed validation: $rejection"
                    )
                )
                diag.record(
                    context,
                    operationId,
                    "personal",
                    sessionId,
                    "durable_import_outcome_recorded",
                    "error=validation_failed"
                )
                return SandboxOperationResult.Success(session)
            }
            diag.record(
                context,
                operationId,
                "personal",
                sessionId,
                "personal_artifact_validated_ok"
            )
            // Correlate URL evidence with persisted analysis
            correlateUrlEvidenceWithAnalysis(session.analysisId, artifact, operationId)
            return SandboxOperationResult.Success(session)
        } catch (e: Exception) {
            // Non-critical overlay — a failed pull/parse/correlate must never regress the session — but it
            // must still be durably recorded, not silently swallowed. Also the safety net for the
            // `repository.get` call above, which — unlike every branch after it — was not previously
            // guarded by this same try/catch at all.
            diag.record(
                context,
                operationId,
                "personal",
                sessionId,
                "import_url_evidence_exception",
                "${e.javaClass.simpleName}: ${e.message}"
            )
            val fallback = try {
                repository.get(sessionId)
            } catch (_: Exception) {
                null
            }
                ?: SandboxSession(
                    sessionId,
                    "",
                    "",
                    SandboxSessionState.FAILED,
                    SandboxPolicy(),
                    createdAt = Instant.now()
                )
            UrlEvidenceImportStatusStore.record(
                context, fallback.analysisId,
                UrlEvidenceImportStatusStore.Outcome(
                    System.currentTimeMillis(),
                    0,
                    false,
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            )
            diag.record(
                context,
                operationId,
                "personal",
                sessionId,
                "durable_import_outcome_recorded",
                "error=exception"
            )
            return SandboxOperationResult.Success(fallback)
        }
    }

    private suspend fun persistAndroidEvidence(artifact: com.nadeem.apkscope.sandbox.AndroidEvidenceArtifact) {
        val db = com.nadeem.apkscope.core.database.SandboxDatabaseProvider.get(context)
        val dao = db.androidEvidenceDao()
        val dnsRows = artifact.dnsEvents.map { e ->
            com.nadeem.apkscope.core.database.AndroidDnsEvidenceEntity(
                sessionId = artifact.sessionId,
                eventId = e.eventId,
                batchToken = e.batchToken,
                packageName = e.packageName,
                timestampEpochMs = e.timestampEpochMs,
                receivedAtEpochMs = e.receivedAtEpochMs,
                hostname = e.hostname,
                resolvedAddressesCsv = e.resolvedAddressesCsv,
                totalResolvedAddressCount = e.totalResolvedAddressCount,
            )
        }
        val connectRows = artifact.connectEvents.map { e ->
            com.nadeem.apkscope.core.database.AndroidConnectEvidenceEntity(
                sessionId = artifact.sessionId,
                eventId = e.eventId,
                batchToken = e.batchToken,
                packageName = e.packageName,
                timestampEpochMs = e.timestampEpochMs,
                receivedAtEpochMs = e.receivedAtEpochMs,
                destinationAddress = e.destinationAddress,
                destinationPort = e.destinationPort,
            )
        }
        if (dnsRows.isNotEmpty()) dao.insertDnsEvents(dnsRows)
        if (connectRows.isNotEmpty()) dao.insertConnectEvents(connectRows)

        dao.upsertSummary(
            com.nadeem.apkscope.core.database.AndroidEvidenceSummaryEntity(
                sessionId = artifact.sessionId,
                dnsCount = dnsRows.size,
                connectCount = connectRows.size,
                status = artifact.status,
                firstEventTimestampEpochMs = artifact.firstEventTimestampEpochMs,
                lastEventTimestampEpochMs = artifact.lastEventTimestampEpochMs,
                importedAtEpochMs = System.currentTimeMillis(),
            )
        )
    }

    private fun sendAndroidEvidenceAck(activity: Activity, sessionId: String) {
        try {
            val controlFile =
                File(context.filesDir, "sandbox/control/$sessionId-android-ack.json").apply {
                    parentFile?.mkdirs()
                    writeText(JSONObject().put("sessionId", sessionId).toString())
                }
            Handoff.send(
                activity,
                controlFile,
                CrossProfileContract.ACTION_ACK_ANDROID_EVIDENCE,
                sessionId,
                SANDBOX_FILE_PROVIDER_AUTHORITY
            )
        } catch (_: Exception) { /* best-effort only */
        }
    }

    /**
     * Checkpoint 8.9: correlates URL evidence from captured traffic with persisted DexUrlCandidate
     * entries. The actual matching/merge decision (exact vs. host-only, idempotency, retention policy)
     * lives in [UrlEvidenceCorrelator] — a pure function extracted specifically so it is unit-testable
     * without Robolectric or private-method reflection (see `UrlEvidenceCorrelatorTest`). The real
     * read-modify-write sequence around it (`get` → `correlate` → `put`, inside
     * `StaticAnalysisResultStore.withLock(analysisId)`) is itself extracted into [UrlEvidenceImporter]
     * (Milestone 9, fourth acceptance rigor pass) so *repeated-import* behavior is directly testable
     * against real Android storage — see `RepeatedUrlEvidenceImportInstrumentedTest`. This function is
     * now only the thin shell around that: delegate, then record a durable import outcome via
     * [UrlEvidenceImportStatusStore] — surfaced in the Embedded URLs UI, not logcat alone.
     *
     * Without the lock [UrlEvidenceImporter] takes, two concurrent imports for the *same* analysis
     * (e.g. a retry racing the original attempt, or two sessions ending close together) could each read
     * the same starting state, compute their own update independently, and whichever `put()` runs last
     * would silently discard the other's legitimate evidence — no corruption, no error, just a quietly
     * lost update. The lock closes that race (see `StaticAnalysisResultStoreLockTest` for direct proof
     * it serializes concurrent access per key).
     */
    private suspend fun correlateUrlEvidenceWithAnalysis(
        analysisId: String,
        artifact: UrlEvidenceArtifact,
        operationId: String
    ) {
        val diag = com.nadeem.apkscope.core.crossprofile.UrlEvidencePipelineDiagnostics
        diag.record(
            context,
            operationId,
            "personal",
            analysisId,
            "correlation_started",
            "exportedEntryCount=${artifact.exportedEntryCount}"
        )
        try {
            val result = UrlEvidenceImporter.correlate(context, analysisId, artifact)

            if (result == null) {
                android.util.Log.w("UrlEvidenceCorrelation", "No analysis found for $analysisId")
                diag.record(
                    context,
                    operationId,
                    "personal",
                    analysisId,
                    "correlation_no_persisted_analysis"
                )
                UrlEvidenceImportStatusStore.record(
                    context, analysisId,
                    UrlEvidenceImportStatusStore.Outcome(
                        importedAtEpochMs = System.currentTimeMillis(),
                        entryCount = artifact.exportedEntryCount,
                        truncated = artifact.truncated,
                        error = "No persisted analysis found for this id — evidence could not be correlated",
                    )
                )
                diag.record(
                    context,
                    operationId,
                    "personal",
                    analysisId,
                    "durable_import_outcome_recorded",
                    "error=no_persisted_analysis"
                )
                return
            }

            if (result.changed) {
                android.util.Log.i(
                    "UrlEvidenceCorrelation",
                    "Correlated ${artifact.exportedEntryCount} entries for $analysisId: " +
                            "${result.candidates.count { it.runtimeEvidence != null }} exact RUNTIME_OBSERVED URLs, " +
                            "${result.candidates.count { it.hostCorrelation != null }} host-only correlations"
                )
                diag.record(
                    context,
                    operationId,
                    "personal",
                    analysisId,
                    "correlation_persisted_change",
                    "exactMatches=${result.candidates.count { it.runtimeEvidence != null }} hostOnly=${result.candidates.count { it.hostCorrelation != null }}"
                )
            } else {
                android.util.Log.i(
                    "UrlEvidenceCorrelation",
                    "Imported ${artifact.exportedEntryCount} entries for $analysisId — no new matches (idempotent no-op or no correlation found)"
                )
                diag.record(
                    context,
                    operationId,
                    "personal",
                    analysisId,
                    "correlation_idempotent_no_op"
                )
            }
            UrlEvidenceImportStatusStore.record(
                context, analysisId,
                UrlEvidenceImportStatusStore.Outcome(
                    importedAtEpochMs = System.currentTimeMillis(),
                    entryCount = artifact.exportedEntryCount,
                    truncated = artifact.truncated,
                    error = null,
                )
            )
            diag.record(
                context,
                operationId,
                "personal",
                analysisId,
                "durable_import_outcome_recorded",
                "error=null"
            )
        } catch (e: Exception) {
            android.util.Log.e(
                "UrlEvidenceCorrelation",
                "Failed to correlate URL evidence for $analysisId: ${e.message}",
                e
            )
            diag.record(
                context,
                operationId,
                "personal",
                analysisId,
                "correlation_exception",
                "${e.javaClass.simpleName}: ${e.message}"
            )
            UrlEvidenceImportStatusStore.record(
                context, analysisId,
                UrlEvidenceImportStatusStore.Outcome(
                    importedAtEpochMs = System.currentTimeMillis(),
                    entryCount = artifact.exportedEntryCount,
                    truncated = artifact.truncated,
                    error = "${e.javaClass.simpleName}: ${e.message}",
                )
            )
            diag.record(
                context,
                operationId,
                "personal",
                analysisId,
                "durable_import_outcome_recorded",
                "error=exception"
            )
        }
    }

    /** Applies a pulled [report] (if any) using the exact same [SandboxSessionReportMerger] the push path uses — a report is a report regardless of transport. */
    private fun applyImportedReport(
        session: SandboxSession,
        report: SandboxStatusReport?
    ): SandboxSession {
        if (report == null) return session
        if (com.nadeem.apkscope.sandbox.InstallRetryMarker.isSuperseded(context, session.id, report)) return session
        if (SandboxSessionReportMerger.isStaleInstallReport(session, report)) return session
        var next = SandboxSessionReportMerger.mergeFacts(session, report)
        next = when {
            report.error != null -> safeTransition(
                next,
                SandboxSessionState.FAILED
            )?.copy(error = report.error) ?: next

            report.cleanup != null -> {
                val personalCleanupDone = performPersonalCleanup(next)
                next.copy(
                    cleanupSummary = SandboxSessionReportMerger.mergeCleanupSummary(
                        report.cleanup,
                        personalCleanupDone
                    )
                ).let(::applyCleanupOutcome)
            }

            else -> {
                var advanced = SandboxSessionReportMerger.advanceThroughOperationalStates(
                    next,
                    report.state
                ) ?: next
                if (report.state == SandboxSessionState.INSTALLED) {
                    val readiness =
                        SandboxEnvironmentPreflight.computeLaunchReadiness(context, advanced)
                    if (readiness.launchAllowed) advanced =
                        safeTransition(advanced, SandboxSessionState.READY) ?: advanced
                }
                advanced
            }
        }
        return next
    }

    /** Item 15: "a successfully completed real session should normally be capable of reaching COMPLETED even when a transient BAL-blocked status message occurred" — walks forward through whatever legal intermediate states are needed to reach `CLEANUP_REQUIRED`, then on to `COMPLETED` once [SandboxSession.cleanupSummary] genuinely says every fact is verified. Never fabricates a fact — it only ever acts on whatever [SandboxSession.cleanupSummary] is already attached to [session]. */
    private fun applyCleanupOutcome(session: SandboxSession): SandboxSession {
        val summary = session.cleanupSummary ?: return session
        var walked = session
        if (walked.state == SandboxSessionState.ENDING) walked =
            safeTransition(walked, SandboxSessionState.CLEARING_DATA) ?: walked
        var next = safeTransition(walked, SandboxSessionState.CLEANUP_REQUIRED) ?: walked
        if (summary.isComplete) next = safeTransition(next, SandboxSessionState.COMPLETED) ?: next
        return next
    }

    private fun performPersonalCleanup(session: SandboxSession): Boolean {
        val path = session.personalApkPath ?: return true
        val file = File(path)
        if (!file.exists()) return true
        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                SANDBOX_FILE_PROVIDER_AUTHORITY,
                file
            )
            context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    private fun safeTransition(
        session: SandboxSession,
        target: SandboxSessionState
    ): SandboxSession? =
        SandboxSessionReportMerger.safeTransition(session, target)

    private suspend fun notFound(sessionId: String): SandboxOperationResult.Failure {
        val placeholder = SandboxSession(
            sessionId,
            "",
            "",
            SandboxSessionState.FAILED,
            SandboxPolicy(),
            createdAt = Instant.now()
        )
        return SandboxOperationResult.Failure(
            placeholder,
            SandboxError(
                SandboxErrorCode.UNKNOWN,
                "This session no longer exists.",
                "sessionId=$sessionId not found",
                Recoverability.TERMINAL
            )
        )
    }

    private suspend fun failClosed(
        session: SandboxSession,
        code: SandboxErrorCode,
        userMessage: String,
        detail: String?,
        recoverability: Recoverability
    ): SandboxOperationResult.Failure {
        val error = SandboxError(code, userMessage, detail, recoverability)
        val next = safeTransition(session, SandboxSessionState.FAILED)?.copy(error = error)
            ?: session.copy(error = error)
        repository.save(next)
        return SandboxOperationResult.Failure(next, error)
    }
}

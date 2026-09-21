package com.nadeem.apkscope.core.model

import java.time.Instant

/**
 * Checkpoint 4, item 1: the production sandbox-session lifecycle, independent of Compose and of
 * Android — every field here is a plain fact, never a UI concept. One [SandboxSession] always
 * traces back to exactly one completed static analysis ([analysisId]) — but one analysis may have
 * zero or many sessions over time (item 22): re-running the same APK through the sandbox creates a
 * new [SandboxSession] row, never overwrites or is merged into the analysis history.
 */
enum class SandboxSessionState {
 CREATED,

 PREPARING,
 WAITING_FOR_INSTALL_CONFIRMATION,
 INSTALLING,
 INSTALLED,

 READY,
 LAUNCHING,
 RUNNING,

 ENDING,
 CLEARING_DATA,
 WAITING_FOR_UNINSTALL_CONFIRMATION,
 CLEANUP,

 COMPLETED,
 /** Cleanup was attempted but did not fully succeed (item 18) — a session must never report [COMPLETED] from here; the user (or a retry) must drive it back through [CLEANUP] before it can complete. */
 CLEANUP_REQUIRED,
 FAILED,
 CANCELLED,
}

/**
 * The explicit transition table (item 1's "define legal transitions... invalid transitions should
 * fail explicitly rather than silently mutating state"). Modeled as one linear happy path plus a
 * small number of named exceptions, rather than one flat from→to set per state, so the "normal"
 * lifecycle stays readable and every deviation from it is a deliberate, visible addition.
 */
object SandboxStateMachine {
 private val terminal = setOf(SandboxSessionState.COMPLETED, SandboxSessionState.FAILED, SandboxSessionState.CANCELLED)

 private val happyPath: Map<SandboxSessionState, SandboxSessionState> = mapOf(
  SandboxSessionState.CREATED to SandboxSessionState.PREPARING,
  SandboxSessionState.PREPARING to SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION,
  SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION to SandboxSessionState.INSTALLING,
  SandboxSessionState.INSTALLING to SandboxSessionState.INSTALLED,
  SandboxSessionState.INSTALLED to SandboxSessionState.READY,
  SandboxSessionState.READY to SandboxSessionState.LAUNCHING,
  SandboxSessionState.LAUNCHING to SandboxSessionState.RUNNING,
  SandboxSessionState.RUNNING to SandboxSessionState.ENDING,
  SandboxSessionState.ENDING to SandboxSessionState.CLEARING_DATA,
  SandboxSessionState.CLEARING_DATA to SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION,
  SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION to SandboxSessionState.CLEANUP,
  SandboxSessionState.CLEANUP to SandboxSessionState.COMPLETED,
 )

 /** Legal branches beyond the single happy-path successor above — each one a real, named lifecycle event (item 18/20), not a shortcut. */
 private val extraTransitions: Map<SandboxSessionState, Set<SandboxSessionState>> = mapOf(
  // Cancellation from the Preparing screen is a real Work-side teardown, not just a Personal
  // Room update. These states may already have established the VPN before installation finishes.
  SandboxSessionState.CREATED to setOf(SandboxSessionState.ENDING),
  SandboxSessionState.PREPARING to setOf(SandboxSessionState.ENDING),
  SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION to setOf(SandboxSessionState.ENDING, SandboxSessionState.INSTALLED),
  SandboxSessionState.INSTALLING to setOf(SandboxSessionState.ENDING),
  SandboxSessionState.INSTALLED to setOf(SandboxSessionState.ENDING),
  SandboxSessionState.LAUNCHING to setOf(SandboxSessionState.ENDING),
  // A prepared session can be ended before the target app is launched. The same cleanup
  // sequence still has to run so the Work-profile APK, data, and VPN state are released.
  SandboxSessionState.READY to setOf(SandboxSessionState.ENDING),
  SandboxSessionState.CLEARING_DATA to setOf(SandboxSessionState.CLEANUP_REQUIRED),
  SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION to setOf(SandboxSessionState.CLEANUP_REQUIRED),
  SandboxSessionState.CLEANUP to setOf(SandboxSessionState.CLEANUP_REQUIRED),
  // INSTALLING is a transient Work-side report. Completion must come from the current
  // PackageInstaller callback; package presence alone can describe an older APK with the same
  // package name and therefore cannot be an install-completion fact.
  SandboxSessionState.CLEANUP_REQUIRED to setOf(SandboxSessionState.CLEANUP, SandboxSessionState.COMPLETED),
 )

 /** States a user-initiated cancellation is meaningful from — once the sandboxed app has actually run, "cancel" no longer makes sense; use the normal end/cleanup path instead. */
 private val cancellable = setOf(
  SandboxSessionState.CREATED, SandboxSessionState.PREPARING, SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION,
  SandboxSessionState.INSTALLING, SandboxSessionState.INSTALLED, SandboxSessionState.READY, SandboxSessionState.LAUNCHING,
 )

 fun legalNextStates(from: SandboxSessionState): Set<SandboxSessionState> {
  if (from in terminal) return emptySet()
  val next = mutableSetOf<SandboxSessionState>()
  happyPath[from]?.let { next += it }
  extraTransitions[from]?.let { next += it }
  next += SandboxSessionState.FAILED
  if (from in cancellable) next += SandboxSessionState.CANCELLED
  return next
 }

 fun canTransition(from: SandboxSessionState, to: SandboxSessionState): Boolean = to in legalNextStates(from)
 fun isTerminal(state: SandboxSessionState): Boolean = state in terminal
}

/** Thrown by [SandboxSession.transitionTo] for an illegal transition — callers (the coordinator) catch this and turn it into a [SandboxOperationResult.Failure] with [SandboxErrorCode.INVALID_STATE_TRANSITION], never letting it silently mutate state (item 1). */
class IllegalSandboxTransitionException(val from: SandboxSessionState, val to: SandboxSessionState) :
 IllegalStateException("Illegal sandbox session transition: $from -> $to (legal: ${SandboxStateMachine.legalNextStates(from)})")

enum class SandboxErrorCode {
 ENVIRONMENT_NOT_READY,
 WORK_PROFILE_MISSING,
 PROFILE_OWNER_INVALID,
 HANDOFF_UNAVAILABLE,
 NETWORK_ISOLATION_UNAVAILABLE,
 TEMP_APK_MISSING,
 HANDOFF_FAILED,
 INSTALL_USER_CANCELLED,
 INSTALL_FAILED,
 PACKAGE_MISMATCH,
 NO_LAUNCHABLE_ACTIVITY,
 LAUNCH_FAILED,
 DATA_CLEAR_FAILED,
 UNINSTALL_USER_CANCELLED,
 UNINSTALL_FAILED,
 PACKAGE_ALREADY_ABSENT,
 TEMP_APK_ALREADY_MISSING,
 INVALID_STATE_TRANSITION,
 /** Checkpoint 5.3, item 4's one-active-session invariant: Work already has a different sandbox session RUNNING/PREPARING/ENDING — this new one must not proceed until that one is reconciled. */
 ANOTHER_SESSION_ACTIVE,
 /**
  * Milestone 9 (second acceptance rigor pass, round 3): the one-active-session guard in
  * [com.nadeem.apkscope.domain.sandbox.SandboxSessionCoordinator.prepare] depends on a real cross-profile
  * query ([com.nadeem.apkscope.sandbox.CrossProfileQueryBridge.launchForResult]) that has no timeout of
  * its own — a real, reproducible defect: if that query is silently dropped (the same
  * background-activity-launch platform behavior already documented elsewhere in this codebase for
  * other cross-profile pushes), the guard's own `queryWorkActiveSession` call never returns, and
  * `prepare()` hangs forever with no error and no state transition, rather than either confirming or
  * rejecting the new session. This code is the fail-closed outcome of a bounded wait added around
  * that specific query: an inconclusive answer must never be treated as "no other session active"
  * (which would silently allow a second concurrently-monitored target — explicitly out of scope to
  * introduce), so it is surfaced here as a distinct, honestly-labeled, retryable failure instead of
  * either hanging or fabricating a definite [ANOTHER_SESSION_ACTIVE] the query never actually confirmed.
  */
 WORK_SESSION_STATE_UNKNOWN,
 /** Checkpoint 5.5, item 5: [SessionReconciliation.Outcome.INTERRUPTED] — Personal's Room row says RUNNING but Work's own live session state disagrees (no active session, or a different one) and a fresh cross-profile check confirmed it. A real, factual state — never fabricated as COMPLETED. */
 WORK_SESSION_INTERRUPTED,
 ALREADY_INSTALLED_IN_PERSONAL,
 UNKNOWN,
}

/** How the coordinator/UI should treat a [SandboxError] (item 20 — "avoid raw exception strings as the primary UI"). */
enum class Recoverability {
 /** The same operation can just be retried, no user decision needed (e.g. a transient DPM readback failure). */
 RETRYABLE,
 /** The user must do something outside this app first (grant a permission, provision the Work Profile, approve a system dialog) before retrying makes sense. */
 REQUIRES_USER_ACTION,
 /** This session cannot proceed; start over with a new session. */
 TERMINAL,
}

data class SandboxError(
 val code: SandboxErrorCode,
 val userMessage: String,
 val technicalDetail: String?,
 val recoverability: Recoverability,
)

/** What the user asked for — kept strictly separate from [PolicyEnforcementResult] (what Android actually did) per item 5's "a requested restriction is not an applied restriction." */
data class SandboxPolicy(
 val denyCamera: Boolean = true,
 val denyMicrophone: Boolean = true,
 val denyLocation: Boolean = true,
 val alwaysOnVpnLockdown: Boolean = true,
 val disposableSession: Boolean = true,
)

/** One line item in a completed cleanup (item 18's worked example: "App data cleared PASS / APK removed PASS / ..."). [isComplete] is what actually gates [SandboxSessionState.COMPLETED] — a session must never claim completion while any of these is false. */
data class CleanupSummary(
 val appDataCleared: Boolean = false,
 val apkRemoved: Boolean = false,
 val workTempApkDeleted: Boolean = false,
 val personalTempApkDeleted: Boolean = false,
 val uriGrantReleased: Boolean = false,
 val networkSessionClosed: Boolean = false,
) {
 val isComplete: Boolean get() = appDataCleared && apkRemoved && workTempApkDeleted && personalTempApkDeleted && uriGrantReleased && networkSessionClosed
}

data class SandboxSession(
 val id: String,
 val analysisId: String,
 val packageName: String,
 val state: SandboxSessionState,
 val requestedPolicy: SandboxPolicy,
 val enforcementResults: List<PolicyEnforcementResult> = emptyList(),
 val createdAt: Instant,
 val startedAt: Instant? = null,
 val endedAt: Instant? = null,
 val error: SandboxError? = null,
 /** The personal-profile temp copy of the APK this session was prepared from — item 4's "selected APK temp copy still exists" preflight checks this path. */
 val personalApkPath: String? = null,
 /** The `PackageInstaller` session id created work-side, kept so [continueInstallation] can reopen and commit the *same* session rather than creating a new one (item 8's two-phase "show our own screen, then invoke Android's UI on explicit user action"). */
 val installSessionId: Int? = null,
 val installedVersionCode: Long? = null,
 val dataClearRequestedAt: Instant? = null,
 val dataClearCompletedAt: Instant? = null,
 val dataClearResult: Boolean? = null,
 val cleanupSummary: CleanupSummary? = null,
) {
 /** The only way [state] ever changes — throws [IllegalSandboxTransitionException] rather than silently applying an illegal transition (item 1). */
 fun transitionTo(next: SandboxSessionState): SandboxSession {
  if (!SandboxStateMachine.canTransition(state, next)) throw IllegalSandboxTransitionException(state, next)
  return copy(state = next)
 }
}

/** The result shape every [com.nadeem.apkscope.core.model] coordinator-style operation returns (item 3/20) — never a raw exception as the primary UI signal. */
sealed interface SandboxOperationResult {
 data class Success(val session: SandboxSession) : SandboxOperationResult
 data class Failure(val session: SandboxSession, val error: SandboxError) : SandboxOperationResult
}

/** Item 6's explicit, unit-tested security invariant. Every input is a plain boolean the coordinator has already determined from real Android state — this function itself has no Android dependency, so it is directly unit-testable without a device/emulator. */
data class LaunchReadiness(
 val installationConfirmed: Boolean,
 val environmentValid: Boolean,
 val requiredPoliciesSatisfied: Boolean,
 val networkIsolationActive: Boolean,
) {
 val launchAllowed: Boolean get() = installationConfirmed && environmentValid && requiredPoliciesSatisfied && networkIsolationActive
}

package com.nadeem.apkscope.domain.sandbox

import com.nadeem.apkscope.core.model.CleanupSummary
import com.nadeem.apkscope.core.model.IllegalSandboxTransitionException
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.core.model.SandboxStateMachine
import com.nadeem.apkscope.sandbox.SandboxStatusReport
import java.time.Instant

/**
 * Checkpoint 4.1: the one place a [SandboxStatusReport]'s facts are applied onto a [SandboxSession]
 * — pure, Android-free, unit-testable. Used identically by both delivery paths a report can now
 * arrive by: the original **push** (`SandboxReportActivity`, a best-effort cross-profile
 * `Handoff.send()` from Work that may or may not survive Android's background-activity-launch
 * restrictions) and the new **pull** (`DefaultSandboxSessionCoordinator.importEvidence`, a
 * foreground-initiated `startActivityForResult` round trip that reads Work's own durable evidence
 * store on demand). Neither path duplicates this merge logic — a report is a report, regardless of
 * which transport delivered it.
 */
object SandboxSessionReportMerger {
 private val operationalStates = setOf(
  SandboxSessionState.CREATED,
  SandboxSessionState.PREPARING,
  SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION,
  SandboxSessionState.INSTALLING,
  SandboxSessionState.INSTALLED,
  SandboxSessionState.READY,
  SandboxSessionState.LAUNCHING,
  SandboxSessionState.RUNNING,
 )

 /** Applies every non-null fact in [report] onto [session]. Enforcement reports are policy patches:
  * a newer result replaces the same policy, while policies omitted by that report remain known.
  * Never transitions state itself — the caller decides the target state (a plain status update vs.
  * an error vs. a cleanup outcome need different transition rules), via [safeTransition]. */
 fun mergeFacts(session: SandboxSession, report: SandboxStatusReport): SandboxSession {
  return session.copy(
   enforcementResults = if (report.enforcements.isEmpty()) session.enforcementResults
    else mergeEnforcements(session.enforcementResults, report.enforcements),
   installSessionId = report.installSessionId ?: session.installSessionId,
   installedVersionCode = report.installedVersionCode ?: session.installedVersionCode,
   dataClearRequestedAt = report.dataClearRequestedAtEpochMs?.let(Instant::ofEpochMilli) ?: session.dataClearRequestedAt,
   dataClearCompletedAt = report.dataClearCompletedAtEpochMs?.let(Instant::ofEpochMilli) ?: session.dataClearCompletedAt,
   dataClearResult = report.dataClearResult ?: session.dataClearResult,
  )
 }

 private fun mergeEnforcements(
  existing: List<PolicyEnforcementResult>,
  patch: List<PolicyEnforcementResult>,
 ): List<PolicyEnforcementResult> {
  val merged = linkedMapOf<com.nadeem.apkscope.core.model.SandboxPolicyType, PolicyEnforcementResult>()
  existing.forEach { merged[it.policy] = it }
  patch.forEach { merged[it.policy] = it }
  return merged.values.toList()
 }

 /** Item 18/checkpoint 4.1 item 15: merges the work-verifiable half of a [CleanupSummary] with the one fact only the personal side can verify about itself (its own temp-file/URI-grant cleanup) — `networkSessionClosed` is always true once this runs, since reaching this merge at all means the personal-side coordinator/report-handler is the one closing out the session. */
 fun mergeCleanupSummary(workCleanup: CleanupSummary, personalCleanupDone: Boolean): CleanupSummary =
  workCleanup.copy(personalTempApkDeleted = personalCleanupDone, uriGrantReleased = personalCleanupDone, networkSessionClosed = true)

 /** Applies a legal transition, or returns the session unchanged if already at [target], or `null` if the transition is illegal — never throws, never silently mutates past what the state machine allows. */
 fun safeTransition(session: SandboxSession, target: SandboxSessionState): SandboxSession? =
  if (session.state == target) session
  else try { session.transitionTo(target) } catch (_: IllegalSandboxTransitionException) { null }

 /**
  * Reconciles a durable status report that may have skipped transient callbacks.
  *
  * Work can truthfully report a later state after the Personal process missed one or
  * more cross-profile callbacks. We still apply every intermediate transition through
  * the state machine instead of weakening [SandboxSession.transitionTo] or mutating the
  * state directly. Failed/cancelled/cleanup branches are intentionally excluded: those
  * have separate reconciliation rules and must not be inferred from a normal status
  * report.
  */
 fun advanceThroughOperationalStates(
  session: SandboxSession,
  target: SandboxSessionState,
 ): SandboxSession? {
  if (session.state == target) return session

  if (session.state !in operationalStates || target !in operationalStates) return null

  // Most reports are the next step; avoid allocating a search for that common case.
  if (SandboxStateMachine.canTransition(session.state, target)) return safeTransition(session, target)

  // Find the shortest legal path. This includes the explicit WAITING -> INSTALLED
  // recovery edge while preferring the normal happy path when it is available.
  val queue = ArrayDeque<List<SandboxSessionState>>()
  val visited = mutableSetOf(session.state)
  queue.addLast(listOf(session.state))
  var path: List<SandboxSessionState>? = null
  while (queue.isNotEmpty() && path == null) {
   val candidate = queue.removeFirst()
   val current = candidate.last()
   for (next in SandboxStateMachine.legalNextStates(current)) {
    if (next !in operationalStates || !visited.add(next)) continue
    val nextPath = candidate + next
    if (next == target) {
     path = nextPath
     break
    }
    queue.addLast(nextPath)
   }
  }
  val states = path ?: return null
  return states.drop(1).fold(session) { current, next ->
   safeTransition(current, next) ?: return null
  }
 }
}

package com.nadeem.apkscope.sandbox

import android.content.Context
import android.content.Intent
import android.util.Log
import com.nadeem.apkscope.core.model.SandboxSessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Checkpoint 5.3 root-cause fix — the concrete orphan Checkpoint 5.2 found: `runPrepareSequence`
 * calls `SandboxVpnService.ACTION_ESTABLISH` to bring up network isolation before a session ever
 * reaches `RUNNING`, but until this fix, nothing on any failure path between `PREPARING` and
 * `RUNNING` ever called `ACTION_CLOSE` — only the full End Session sequence
 * (`SandboxWorkerService.runEndSessionSequence`) did, and that is reachable only from `RUNNING`.
 * Every install-denial, install-failure, notification-unavailable-gate, or environment-preflight
 * failure left Work's VPN tunnel and its foreground notification running forever, attributed to a
 * session Personal had already recorded as terminal.
 *
 * Called from every FAILED/CANCELLED report site in [SandboxWorkerService] and
 * [SandboxInstallResultReceiver] (their two `report()` functions, not each call site individually —
 * this is deliberately centralized so no future failure path can forget it). Safe/idempotent to call
 * for a session that never established a tunnel, or one already torn down: [SandboxVpnService]'s own
 * `closeAll()` no-ops on already-null state, and this function itself only fires the intent when the
 * live tunnel is actually still attributed to *this* [sessionId] — never a blind close that could
 * tear down some other, legitimately active session.
 */
object WorkSessionTeardown {
 /** True exactly for the states this fix must react to — reached from a lifecycle branch that never itself runs the End Session sequence. */
 private val statesRequiringEarlyTeardown = setOf(SandboxSessionState.FAILED, SandboxSessionState.CANCELLED)
 private const val CLOSE_TIMEOUT_MS = 5_000L
 private const val CLOSE_POLL_INTERVAL_MS = 50L

 /**
  * Requests the VPN close and does not return until the Work-side authoritative state says that
  * forwarding is inactive. The old implementation only queued ACTION_CLOSE and immediately
  * reported the failure; a fast retry could therefore query the old tunnel before Android had
  * delivered ACTION_CLOSE, producing the intermittent `ANOTHER_SESSION_ACTIVE` screen.
  *
  * [expectedSessionId] is a safety fence: a cleanup request must never close a different session
  * that may have started after this request was created. An already-inactive expected session is a
  * successful no-op, which makes repeated cleanup safe.
  */
 suspend fun closeAndAwait(context: Context, expectedSessionId: String? = null): Boolean {
  val activeSessionId = SandboxVpnService.activeSessionId
  val forwardingActive = SandboxVpnService.isForwardingActive
  if (!forwardingActive && activeSessionId == null) return true

  if (expectedSessionId != null && activeSessionId != expectedSessionId) {
   Log.w(
    "ApkScopeSandbox",
    "refusing VPN close for session=$expectedSessionId; liveSession=$activeSessionId forwarding=$forwardingActive",
   )
   return false
  }

  return try {
   context.startForegroundService(
    Intent(context, SandboxVpnService::class.java).setAction(SandboxVpnService.ACTION_CLOSE),
   )
   val closed = withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
    while (SandboxVpnService.isForwardingActive ||
     (expectedSessionId != null && SandboxVpnService.activeSessionId == expectedSessionId)
    ) {
     delay(CLOSE_POLL_INTERVAL_MS)
    }
    true
   } ?: false
   if (!closed) {
    Log.e(
     "ApkScopeSandbox",
     "VPN close timed out for session=$expectedSessionId liveSession=${SandboxVpnService.activeSessionId} forwarding=${SandboxVpnService.isForwardingActive}",
    )
   }
   closed
  } catch (e: Exception) {
   Log.e("ApkScopeSandbox", "VPN close request failed for session=$expectedSessionId", e)
   false
  }
 }

 suspend fun tearDownIfEarlyTermination(context: Context, sessionId: String, reportedState: SandboxSessionState) {
  if (reportedState !in statesRequiringEarlyTeardown) return
  if (SandboxVpnService.activeSessionId != sessionId) return
  closeAndAwait(context, expectedSessionId = sessionId)
 }
}

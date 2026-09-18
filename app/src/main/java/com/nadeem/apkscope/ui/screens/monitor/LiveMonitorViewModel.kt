package com.nadeem.apkscope.ui.screens.monitor

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.domain.SessionRepository
import com.nadeem.apkscope.domain.sandbox.DefaultSandboxSessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

data class LiveMonitorUiState(
 val appName: String? = null,
 val packageName: String = "",
 val durationLabel: String = "—",
 val showEndConfirmation: Boolean = false,
 val isEnding: Boolean = false,
 val isLaunchingApp: Boolean = false,
 val launchError: String? = null,
 val isVpnTargetApp: Boolean = false,
 val navigateToCleanupSessionId: String? = null,
)

/**
 * live_sandbox_monitor (checkpoint 4, item 12/13). Real fields only: package name and elapsed
 * time are computed from the session's actual [SandboxSession.startedAt] — no fake connection/
 * traffic counters (that data does not exist until the NetworkObservation→Live Monitor
 * integration checkpoint, explicitly out of scope here). "End Dynamic Session" shows our own
 * confirmation first (item 13's required copy) and only calls [DefaultSandboxSessionCoordinator.end]
 * once the user taps through it — that call never claims a final report is ready.
 *
 * If this screen is reopened (activity recreation, or a fresh process after item 19's process-death
 * scenario B) and the session has already moved past RUNNING — e.g. a previous "End Session" tap
 * completed while this process was dead — it hands off to the cleanup screen instead of pretending
 * the session is still live.
 */
class LiveMonitorViewModel(application: Application, private val sessionId: String) : AndroidViewModel(application) {
 private val coordinator = DefaultSandboxSessionCoordinator(application)
 private val sessionRepository = SessionRepository(application)
 private val _uiState = MutableStateFlow(LiveMonitorUiState())
 val uiState: StateFlow<LiveMonitorUiState> = _uiState.asStateFlow()
 private var startedAt: Instant? = null

 init {
  viewModelScope.launch {
   coordinator.reconcile(sessionId)
   coordinator.observe(sessionId).collect { session -> if (session != null) applySession(session) }
  }
  viewModelScope.launch {
   // Real elapsed time, ticked locally — not a fake progress animation (item 12): the only thing
   // recomputed each tick is now() - the session's own real startedAt.
   while (true) {
    startedAt?.let { updateDuration(it) }
    delay(1000)
   }
  }
 }

 // Item 19 scenario B: RUNNING is the only state this screen represents; anything after it
 // (End Session already progressed while this process was dead) belongs to the cleanup screen.
 private suspend fun applySession(session: SandboxSession) {
  if (session.state != SandboxSessionState.RUNNING) {
   _uiState.value = _uiState.value.copy(navigateToCleanupSessionId = sessionId)
   return
  }
  startedAt = session.startedAt
  val isVpn = com.nadeem.apkscope.domain.sandbox.VpnTargetAppDetector.isVpnTargetApp(getApplication(), session)
  val appName = withContext(Dispatchers.IO) { sessionRepository.getPersisted(session.analysisId)?.appName }
  _uiState.value = _uiState.value.copy(
   appName = appName ?: _uiState.value.appName,
   packageName = session.packageName,
   isVpnTargetApp = isVpn,
  )
  session.startedAt?.let { updateDuration(it) }
 }

 fun openSandboxedApp(activity: Activity) {
  _uiState.value = _uiState.value.copy(isLaunchingApp = true, launchError = null)
  viewModelScope.launch {
   val result = coordinator.launch(activity, sessionId)
   _uiState.value = _uiState.value.copy(isLaunchingApp = false)
   if (result is com.nadeem.apkscope.core.model.SandboxOperationResult.Failure) {
    _uiState.value = _uiState.value.copy(launchError = result.error.userMessage)
   }
  }
 }

 fun dismissLaunchError() {
  _uiState.value = _uiState.value.copy(launchError = null)
 }

 private fun updateDuration(startedAt: Instant) {
  val elapsed = Duration.between(startedAt, Instant.now()).coerceAtLeast(Duration.ZERO)
  val minutes = elapsed.toMinutes()
  val seconds = elapsed.minusMinutes(minutes).seconds
  _uiState.value = _uiState.value.copy(durationLabel = "%dm %02ds".format(minutes, seconds))
 }

 fun requestEndSession() { _uiState.value = _uiState.value.copy(showEndConfirmation = true) }
 fun dismissEndConfirmation() { _uiState.value = _uiState.value.copy(showEndConfirmation = false) }

 fun confirmEndSession(activity: Activity) {
  _uiState.value = _uiState.value.copy(showEndConfirmation = false, isEnding = true)
  viewModelScope.launch {
   coordinator.end(activity, sessionId)
   _uiState.value = _uiState.value.copy(isEnding = false, navigateToCleanupSessionId = sessionId)
  }
 }

 /**
  * Checkpoint 5.5, item 5: the live, Activity-backed half of RUNNING reconciliation [reconcile]
  * itself cannot do. Never special-cases RUNNING before consulting the reconciliation model — a
  * genuine `INTERRUPTED`/`CONFLICT` transitions the session to FAILED (via [observe]'s own
  * collection, already wired below), which [applySession] already routes to the cleanup screen
  * exactly like any other non-RUNNING state.
  */
  private var hasReconciled = false

   fun reconcileWithActivity(activity: Activity) {
    if (hasReconciled) return
    hasReconciled = true
    val start = startedAt
    if (start != null && Duration.between(start, Instant.now()).seconds < 15) return
    viewModelScope.launch { coordinator.reconcileRunningSession(activity, sessionId) }
   }

 fun onNavigationConsumed() { _uiState.value = _uiState.value.copy(navigateToCleanupSessionId = null) }
}

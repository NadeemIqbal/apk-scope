package com.nadeem.apkscope.ui.screens.sandbox

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.SandboxOperationResult
import com.nadeem.apkscope.core.model.SandboxPolicyType
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.domain.EnvironmentRepository
import com.nadeem.apkscope.domain.SessionRepository
import com.nadeem.apkscope.domain.sandbox.DefaultSandboxSessionCoordinator
import com.nadeem.apkscope.ui.common.UiStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SandboxReadyUiState(
 val appName: String? = null,
 val packageName: String? = null,
 val isolation: UiStatus = UiStatus.CHECKING,
 val networkMonitoring: UiStatus = UiStatus.CHECKING,
 val vpnLockdown: UiStatus = UiStatus.CHECKING,
 val ipv6Blocked: UiStatus = UiStatus.CHECKING,
 val appliedCount: Int = 0,
 val totalCount: Int = 0,
 val unsupportedCount: Int = 0,
 val launchError: String? = null,
 val isVerifyingIsolation: Boolean = false,
 val isVpnTargetApp: Boolean = false,
 val showEndConfirmation: Boolean = false,
 val isEnding: Boolean = false,
 val navigateToCleanupSessionId: String? = null,
) {
 val allReady get() = appliedCount == totalCount && totalCount > 0
}

/**
 * ready_to_run (UI checkpoint item 14; checkpoint 4 item 10/11). Every field here is real: the
 * three restriction rows come from the session's actual [com.nadeem.apkscope.core.model.PolicyEnforcementResult]s
 * (item 5 — never the *requested* policy), and "Launch in Sandbox" calls the coordinator, which
 * itself gates on item 6's fail-closed `launchAllowed` invariant before ever starting the app.
 */
class SandboxReadyViewModel(application: Application, private val sessionId: String) : AndroidViewModel(application) {
 private val coordinator = DefaultSandboxSessionCoordinator(application)
 private val environmentRepository = EnvironmentRepository(application)
 private val sessionRepository = SessionRepository(application)
 private val _uiState = MutableStateFlow(SandboxReadyUiState())
 val uiState: StateFlow<SandboxReadyUiState> = _uiState.asStateFlow()

 init {
  viewModelScope.launch {
   coordinator.reconcile(sessionId)
   coordinator.observe(sessionId).collect { session ->
    if (session != null) {
     val appName = withContext(Dispatchers.IO) { sessionRepository.getPersisted(session.analysisId)?.appName }
     _uiState.value = buildState(session, appName).copy(
      appName = appName ?: _uiState.value.appName,
      isVerifyingIsolation = _uiState.value.isVerifyingIsolation,
      showEndConfirmation = _uiState.value.showEndConfirmation,
      isEnding = _uiState.value.isEnding,
     )
    }
   }
  }
 }

 /** Checkpoint 4.1 §14 lost-report test A: pulls whatever policy-enforcement facts the Work-local evidence store durably knows, independent of whether the original push report ever arrived. Safe to call opportunistically (e.g. once this screen is shown) — a no-op if nothing new is known. */
 fun importEvidence(activity: Activity) {
  if (_uiState.value.allReady) return
  viewModelScope.launch { coordinator.importEvidence(activity, sessionId) }
 }

 private fun buildState(session: SandboxSession, appName: String?): SandboxReadyUiState {
  val vpn = session.enforcementResults.firstOrNull { it.policy == SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN }
  // READY is a terminal preparation result for this screen. A missing Work-side fact is not an
  // in-flight check, so do not render it as "Checking…" forever; the Work monitor is the live
  // source of truth for current network activity.
  val vpnStatus = vpn?.toUiStatus() ?: UiStatus.UNAVAILABLE
  val isolationStatus = environmentRepository.currentState().workProfile
  val requested = listOf(SandboxPolicyType.CAMERA_RUNTIME_PERMISSION_DENIAL, SandboxPolicyType.MICROPHONE_RUNTIME_PERMISSION_DENIAL, SandboxPolicyType.LOCATION_RUNTIME_PERMISSION_DENIAL, SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN)
  val results = requested.mapNotNull { type -> session.enforcementResults.firstOrNull { it.policy == type } }
  val isVpn = com.nadeem.apkscope.domain.sandbox.VpnTargetAppDetector.isVpnTargetApp(getApplication(), session)
  return SandboxReadyUiState(
   appName = appName,
   packageName = session.packageName,
   isolation = isolationStatus,
   networkMonitoring = vpnStatus,
   vpnLockdown = vpnStatus,
   ipv6Blocked = vpnStatus,
   appliedCount = results.count { it.status == EnforcementStatus.ENFORCED },
   totalCount = results.size,
   unsupportedCount = results.count { it.status != EnforcementStatus.ENFORCED },
   isVpnTargetApp = isVpn,
  )
 }

 fun launch(activity: Activity, onLaunched: () -> Unit) {
  _uiState.value = _uiState.value.copy(isVerifyingIsolation = true, launchError = null)
  viewModelScope.launch {
   val result = coordinator.launch(activity, sessionId)
   _uiState.value = _uiState.value.copy(isVerifyingIsolation = false)
   when (result) {
    is SandboxOperationResult.Success -> onLaunched()
    is SandboxOperationResult.Failure -> _uiState.value = _uiState.value.copy(launchError = result.error.userMessage)
   }
  }
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

 fun dismissLaunchError() {
  _uiState.value = _uiState.value.copy(launchError = null)
 }

 fun onNavigationConsumed() {
  _uiState.value = _uiState.value.copy(navigateToCleanupSessionId = null)
 }
}

/** Item 21's "cannot report an unsupported restriction as enforced" made structural: [EnforcementStatus.NOT_SUPPORTED]/[EnforcementStatus.FAILED] can never map to [UiStatus.READY] — see [SandboxEnforcementStatusMappingTest]. */
internal fun com.nadeem.apkscope.core.model.PolicyEnforcementResult?.toUiStatus(): UiStatus = when (this?.status) {
 EnforcementStatus.ENFORCED -> UiStatus.READY
 EnforcementStatus.NOT_SUPPORTED -> UiStatus.UNAVAILABLE
 EnforcementStatus.FAILED -> UiStatus.ERROR
 null -> UiStatus.CHECKING
}

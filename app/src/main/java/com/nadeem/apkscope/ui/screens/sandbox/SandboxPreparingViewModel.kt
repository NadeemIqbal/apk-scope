package com.nadeem.apkscope.ui.screens.sandbox

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxOperationResult
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.domain.SessionRepository
import com.nadeem.apkscope.domain.sandbox.DefaultSandboxSessionCoordinator
import com.nadeem.apkscope.ui.common.StepState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SandboxPreparingUiState(
 val environmentCheck: StepState = StepState.PENDING,
 val restrictionsApplied: StepState = StepState.PENDING,
 val networkIsolation: StepState = StepState.PENDING,
 val apkHandoff: StepState = StepState.PENDING,
 val awaitingInstallConfirmation: StepState = StepState.PENDING,
 val showContinueInstallation: Boolean = false,
 val showContinueToReady: Boolean = false,
 val blockedReason: String? = null,
 val technicalDetail: String? = null,
 val errorCode: SandboxErrorCode? = null,
 val appName: String? = null,
 val packageName: String? = null,
 /**
  * Milestone 9 (Pixel 8 acceptance, fifth pass): non-null only once
  * `requestOpenWorkInstallSettings` has been tried and Android could not resolve/launch the Work
  * profile's own install-unknown-sources settings screen at all — the UI must show this exact
  * manual-navigation text instead of the button, never silently open Personal's own Settings.
  */
 val installSettingsGuidance: String? = null,
 /** True only while the open-settings + recheck round trip (a real, possibly slow user interaction with Settings) is in flight — lets the UI disable the button rather than allow a second overlapping attempt. */
 val openingInstallSettings: Boolean = false,
 val launchingSandboxedApp: Boolean = false,
 val showEndConfirmation: Boolean = false,
 val isEnding: Boolean = false,
 val endError: String? = null,
 val navigateHome: Boolean = false,
 /**
  * Root-cause fix, confirmed on-device: [SandboxSessionCoordinator.prepare] is a no-op for any
  * session not in [SandboxSessionState.CREATED] (by design — it must not restart an already
  * in-flight preparation), so calling it again on a `FAILED`/`CANCELLED` session's own id — which is
  * exactly what tapping "Retry" used to do — silently did nothing and left the identical error on
  * screen forever, indistinguishable from a broken button. `retryPrepare` now creates a genuinely
  * fresh session for the same analysis/APK instead and sets this field once that new session's own
  * `prepare()` call has been dispatched; the screen observes it once and navigates to the new
  * session's own `SandboxPreparingRoute`, replacing itself so the dead session is not left on the
  * back stack.
  */
 val retriedAsNewSessionId: String? = null,
) {
 companion object {
  /** The entire mapping from a real [SandboxSession] to this stepper — item 10/12's "no fake delay animation, only real state" made concrete: every field here is derived, never independently set. */
  fun from(session: SandboxSession): SandboxPreparingUiState {
   val base = when (session.state) {
    SandboxSessionState.CREATED -> SandboxPreparingUiState()
    SandboxSessionState.PREPARING -> SandboxPreparingUiState(environmentCheck = StepState.ACTIVE)
    SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION -> SandboxPreparingUiState(
     environmentCheck = StepState.COMPLETE, restrictionsApplied = StepState.COMPLETE, networkIsolation = StepState.COMPLETE,
     apkHandoff = StepState.COMPLETE, awaitingInstallConfirmation = StepState.ACTIVE, showContinueInstallation = true,
    )
    SandboxSessionState.INSTALLING -> SandboxPreparingUiState(
     environmentCheck = StepState.COMPLETE, restrictionsApplied = StepState.COMPLETE, networkIsolation = StepState.COMPLETE,
     apkHandoff = StepState.COMPLETE, awaitingInstallConfirmation = StepState.ACTIVE,
    )
    SandboxSessionState.INSTALLED -> SandboxPreparingUiState(
     environmentCheck = StepState.COMPLETE, restrictionsApplied = StepState.COMPLETE, networkIsolation = StepState.COMPLETE,
     apkHandoff = StepState.COMPLETE, awaitingInstallConfirmation = StepState.COMPLETE,
    )
    SandboxSessionState.READY -> SandboxPreparingUiState(
     environmentCheck = StepState.COMPLETE, restrictionsApplied = StepState.COMPLETE, networkIsolation = StepState.COMPLETE,
     apkHandoff = StepState.COMPLETE, awaitingInstallConfirmation = StepState.COMPLETE, showContinueToReady = true,
    )
    SandboxSessionState.FAILED, SandboxSessionState.CANCELLED -> fromError(session.error)
    else -> SandboxPreparingUiState(environmentCheck = StepState.COMPLETE, restrictionsApplied = StepState.COMPLETE, networkIsolation = StepState.COMPLETE, apkHandoff = StepState.COMPLETE, showContinueToReady = true)
   }
   return base.copy(packageName = session.packageName)
  }

  private fun fromError(error: SandboxError?): SandboxPreparingUiState {
   val environmentCodes = setOf(
    SandboxErrorCode.WORK_PROFILE_MISSING,
    SandboxErrorCode.HANDOFF_UNAVAILABLE,
    SandboxErrorCode.TEMP_APK_MISSING,
    SandboxErrorCode.PROFILE_OWNER_INVALID,
    SandboxErrorCode.ALREADY_INSTALLED_IN_PERSONAL,
   )
   val networkCodes = setOf(SandboxErrorCode.NETWORK_ISOLATION_UNAVAILABLE)
   val code = error?.code
   return SandboxPreparingUiState(
    environmentCheck = if (code in environmentCodes) StepState.FAILED else StepState.COMPLETE,
    restrictionsApplied = if (code in networkCodes) StepState.FAILED else if (code in environmentCodes) StepState.PENDING else StepState.COMPLETE,
    networkIsolation = if (code in networkCodes) StepState.FAILED else if (code in environmentCodes) StepState.PENDING else StepState.COMPLETE,
    apkHandoff = if (code in environmentCodes || code in networkCodes) StepState.PENDING else StepState.FAILED,
    blockedReason = error?.userMessage ?: "Something went wrong preparing the sandbox.",
    technicalDetail = error?.technicalDetail,
    errorCode = code,
   )
  }
 }
}

/**
 * preparing_sandbox (UI checkpoint item 12; checkpoint 4 item 3/4/8/10). Every stage reflects a
 * real [SandboxSession] the coordinator has already verified — no fake delay animation, and
 * `showContinueInstallation`/`showContinueToReady` only ever appear once the underlying state
 * genuinely reached [SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION]/[SandboxSessionState.READY].
 */
class SandboxPreparingViewModel(
 application: Application,
 private val sessionId: String,
 /** Test seam matching [com.nadeem.apkscope.ui.screens.sandbox.SandboxCleanupViewModel]'s own — every real caller leaves this `null`, so production always uses a real [DefaultSandboxSessionCoordinator]. Exists so [retryPrepare]'s create-a-fresh-session behavior can be verified deterministically against a real Room-backed coordinator without needing a real cross-profile Work Profile environment for `prepare()` itself to succeed. */
 coordinatorOverride: com.nadeem.apkscope.domain.sandbox.SandboxSessionCoordinator? = null,
) : AndroidViewModel(application) {
 private val coordinator = coordinatorOverride ?: DefaultSandboxSessionCoordinator(application)
 private val sessionRepository = SessionRepository(application)
 private val _uiState = MutableStateFlow(SandboxPreparingUiState())
 val uiState: StateFlow<SandboxPreparingUiState> = _uiState.asStateFlow()
 private var prepareStarted = false
 private var statusRecoveryJob: Job? = null

 init {
  viewModelScope.launch {
   // Item 9/19: catches up on a cross-profile report that may have arrived (or an install/uninstall
   // that completed) while this process was not running, using real authoritative Android state —
   // not just whatever this Room row happened to say last.
   coordinator.reconcile(sessionId)
   coordinator.observe(sessionId).collect { session ->
    // Preserve the install-settings remediation's own transient fields across a session-state
    // update — `from(session)` rebuilds every other field from scratch and would otherwise wipe
    // them out from under an in-flight or just-finished settings round trip.
    if (session != null) {
     val appName = withContext(Dispatchers.IO) {
      sessionRepository.getPersisted(session.analysisId)?.appName
     }
     _uiState.value = SandboxPreparingUiState.from(session).copy(
      appName = appName ?: _uiState.value.appName,
      installSettingsGuidance = _uiState.value.installSettingsGuidance,
      openingInstallSettings = _uiState.value.openingInstallSettings,
      launchingSandboxedApp = _uiState.value.launchingSandboxedApp,
      showEndConfirmation = _uiState.value.showEndConfirmation,
      isEnding = _uiState.value.isEnding,
      endError = _uiState.value.endError,
      navigateHome = _uiState.value.navigateHome,
     )
    }
   }
  }
 }

 fun startPrepareIfNeeded(activity: Activity) {
  if (prepareStarted) {
   // Checkpoint 4.1 §14 lost-report test A: opportunistically pulls durable Work-local evidence
   // when re-entering this screen — recovers a policy-enforcement-result/install-status fact
   // whose push was lost.
   startStatusRecovery(activity)
   return
  }
  prepareStarted = true
  viewModelScope.launch {
   coordinator.prepare(activity, sessionId)
   startStatusRecovery(activity)
  }
 }

 fun refreshInstallation(activity: Activity) {
  viewModelScope.launch {
   coordinator.importEvidence(activity, sessionId)
   coordinator.reconcile(sessionId)
  }
 }

 fun continueInstallation(activity: Activity) {
  viewModelScope.launch {
   coordinator.continueInstallation(activity, sessionId)
   startStatusRecovery(activity)
  }
 }

 fun launchSandboxedApp(activity: Activity) {
  if (_uiState.value.launchingSandboxedApp) return
  _uiState.value = _uiState.value.copy(launchingSandboxedApp = true)
  viewModelScope.launch {
   coordinator.launch(activity, sessionId)
   _uiState.value = _uiState.value.copy(launchingSandboxedApp = false)
  }
 }

 fun requestEndSession() {
  if (!_uiState.value.isEnding) {
   _uiState.value = _uiState.value.copy(showEndConfirmation = true, endError = null)
  }
 }

 fun dismissEndConfirmation() {
  _uiState.value = _uiState.value.copy(showEndConfirmation = false)
 }

 fun confirmEndSession() {
  if (_uiState.value.isEnding) return
  _uiState.value = _uiState.value.copy(showEndConfirmation = false, isEnding = true, endError = null)
  viewModelScope.launch {
   when (val result = coordinator.cancel(sessionId)) {
    is SandboxOperationResult.Success -> {
     _uiState.value = _uiState.value.copy(isEnding = false, navigateHome = true)
    }
    is SandboxOperationResult.Failure -> {
     _uiState.value = _uiState.value.copy(isEnding = false, endError = result.error.userMessage)
    }
   }
  }
 }

 fun dismissEndError() {
  _uiState.value = _uiState.value.copy(endError = null)
 }

 fun onNavigationConsumed() {
  _uiState.value = _uiState.value.copy(navigateHome = false)
 }

 private fun startStatusRecovery(activity: Activity) {
  if (statusRecoveryJob?.isActive == true) return
  statusRecoveryJob = viewModelScope.launch {
   repeat(12) {
    delay(1_000)
    val current = coordinator.observe(sessionId).first() ?: return@launch
    if (current.state != SandboxSessionState.PREPARING && current.state != SandboxSessionState.INSTALLING) return@launch
    coordinator.importEvidence(activity, sessionId)
    coordinator.reconcile(sessionId)
   }
  }
 }

 /**
  * Milestone 9 (Pixel 8 acceptance, fifth pass): the actual remediation flow, replacing the
  * previous button's raw `context.startActivity(ACTION_APPLICATION_DETAILS_SETTINGS)` (confirmed to
  * open the wrong — Personal — profile). Opens the Work profile's own settings screen via the
  * cross-profile mechanism, then — "after returning from Settings, recheck permission from the Work
  * Profile instance before resuming preparation" — re-checks the real, current value there before
  * doing anything else. A denial or cancellation (Android reports no distinct signal between the
  * two for this settings screen) simply leaves the blocked state as-is, with a fresh, correct
  * `technicalDetail`, ready for the user to try again or tap Retry; a genuine grant resumes
  * preparation automatically, with no extra tap required. If the settings screen could not be
  * opened at all, shows accurate manual guidance instead — never silently opens Personal's own
  * settings as a fallback.
  */
 fun openInstallSettings(activity: Activity) {
  if (_uiState.value.openingInstallSettings) return
  _uiState.value = _uiState.value.copy(openingInstallSettings = true, installSettingsGuidance = null)
  viewModelScope.launch {
   val opened = coordinator.requestOpenWorkInstallSettings(activity, sessionId)
   if (!opened) {
    _uiState.value = _uiState.value.copy(
     openingInstallSettings = false,
     installSettingsGuidance = "Could not open the setting automatically. Open Settings yourself, switch to the Work tab, choose APK Scope, then allow \"Install unknown apps\" — and tap Retry here afterward.",
    )
    return@launch
   }
   // Fresh, from the Work profile itself — never assumed from the value this same tap started with.
   val granted = coordinator.checkWorkInstallPermission(activity, sessionId)
   _uiState.value = _uiState.value.copy(openingInstallSettings = false)
   if (granted == true) {
    retryPrepare(activity)
   }
   // granted == false or null: leave the existing blocked state visible — Retry remains available,
   // and the next Retry's own prepare() attempt will hit the real, current Work-side check again.
  }
 }

 /**
  * Root-cause fix (confirmed on-device, not hypothetical): [SandboxSessionCoordinator.prepare] only
  * ever does real work for a session in [SandboxSessionState.CREATED] — calling it again with this
  * screen's own [sessionId] once the session has reached [SandboxSessionState.FAILED] (e.g. this
  * device's Work Profile had not yet granted "install unknown apps", surfaced as an honest error —
  * exactly the scenario this fix's own error-reporting work was built to surface, not hide again) is
  * a silent no-op: `prepare()` returns the *same* already-failed session unchanged, so the screen
  * looked identical after tapping Retry, with zero new activity in the cross-profile trace — proven
  * by direct on-device reproduction, not assumed. A brand-new [SandboxSession] (same analysis, same
  * staged APK, same requested policy — nothing the user configured is lost) is the only way to reach
  * [SandboxSessionState.CREATED] again, so that is what this does now, then dispatches `prepare()`
  * against *that* new session and reports its id back via [SandboxPreparingUiState.retriedAsNewSessionId]
  * so the screen can navigate to it — this ViewModel is fixed to this screen's original [sessionId]
  * and cannot itself start observing a different one.
  */
 fun retryPrepare(activity: Activity) {
  viewModelScope.launch {
   val current = coordinator.observe(sessionId).first()
   if (current != null && (current.state == SandboxSessionState.FAILED || current.state == SandboxSessionState.CANCELLED)) {
    val apkPath = current.personalApkPath
    if (apkPath == null) {
     // Nothing this fresh a session could actually be prepared from — leave the existing blocked
     // state visible rather than creating a session doomed to fail identically for a different reason.
     return@launch
    }
    val created = coordinator.create(current.analysisId, current.packageName, apkPath, current.requestedPolicy)
    val newSession = (created as? SandboxOperationResult.Success)?.session ?: return@launch
    coordinator.prepare(activity, newSession.id)
    _uiState.value = _uiState.value.copy(retriedAsNewSessionId = newSession.id)
   } else {
    // Not (yet) terminal — a real in-flight prepare() re-entry is meaningful here and reflects
    // whatever the current state genuinely is once it resolves; unchanged from before this fix.
    prepareStarted = true
    coordinator.prepare(activity, sessionId)
   }
  }
 }
}

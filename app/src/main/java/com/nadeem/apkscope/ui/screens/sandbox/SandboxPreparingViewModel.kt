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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SandboxPreparingUiState(
 val environmentCheck: StepState = StepState.PENDING,
 val restrictionsApplied: StepState = StepState.PENDING,
 val networkIsolation: StepState = StepState.PENDING,
 val apkHandoff: StepState = StepState.PENDING,
 val awaitingInstallConfirmation: StepState = StepState.PENDING,
 val showContinueInstallation: Boolean = false,
 val showReinstall: Boolean = false,
 val isReinstalling: Boolean = false,
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
     showReinstall = true,
    )
    SandboxSessionState.INSTALLING -> SandboxPreparingUiState(
     environmentCheck = StepState.COMPLETE, restrictionsApplied = StepState.COMPLETE, networkIsolation = StepState.COMPLETE,
     apkHandoff = StepState.COMPLETE, awaitingInstallConfirmation = StepState.ACTIVE,
     showReinstall = true,
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
   return base.copy(
    packageName = session.packageName,
    showReinstall = base.showReinstall || session.error?.code in setOf(
     SandboxErrorCode.INSTALL_FAILED,
     SandboxErrorCode.INSTALL_USER_CANCELLED,
     SandboxErrorCode.PACKAGE_MISMATCH,
    ),
   )
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
 private var lastReconciliationStartedAtMs = 0L
 private var cachedAppName: String? = null
 private var cachedAnalysisId: String? = null

 init {
  viewModelScope.launch {
   // A newly-created session is reconciled by prepare() itself. Running another hidden
   // cross-profile Activity here at the same time races the handoff and can leave the Work
   // process handling two query Activities while it is installing the APK. Only reconcile an
   // already-progressing session when this screen is being recreated.
   coordinator.observe(sessionId).first()?.let { session ->
    if (session.state != SandboxSessionState.CREATED && session.state != SandboxSessionState.PREPARING) {
     coordinator.reconcile(sessionId)
    }
   }
   coordinator.observe(sessionId).collect { session ->
    // Preserve the install-settings remediation's own transient fields across a session-state
    // update — `from(session)` rebuilds every other field from scratch and would otherwise wipe
    // them out from under an in-flight or just-finished settings round trip.
    if (session != null) {
     // Completed analysis metadata is immutable for this analysis id. Do not reload and
     // deserialize the whole analysis on every install/policy update just for its label.
     if (cachedAnalysisId != session.analysisId) {
      val analysis = withContext(Dispatchers.IO) {
       sessionRepository.getPersisted(session.analysisId)
      }
      if (analysis != null) {
       cachedAppName = analysis.appName
       cachedAnalysisId = session.analysisId
      }
     }
     _uiState.value = SandboxPreparingUiState.from(session).copy(
      appName = cachedAppName ?: _uiState.value.appName,
      installSettingsGuidance = _uiState.value.installSettingsGuidance,
      openingInstallSettings = _uiState.value.openingInstallSettings,
      launchingSandboxedApp = _uiState.value.launchingSandboxedApp,
      showEndConfirmation = _uiState.value.showEndConfirmation,
      isEnding = _uiState.value.isEnding,
      endError = _uiState.value.endError,
      navigateHome = _uiState.value.navigateHome,
      isReinstalling = _uiState.value.isReinstalling,
     )
    }
   }
  }
 }

 fun startPrepareIfNeeded(activity: Activity) {
  if (prepareStarted) return
  prepareStarted = true
  viewModelScope.launch {
   val session = coordinator.observe(sessionId).first()
   if (session?.state == SandboxSessionState.PREPARING) {
    recoverAbandonedPreparing(activity)
   } else {
    coordinator.prepare(activity, sessionId)
   }
  }
 }

 /**
  * Root-cause fix, confirmed on-device: [SandboxSessionCoordinator.prepare] no-ops for any session
  * already past `CREATED`, so re-entering this screen for a session still stuck at `PREPARING`
  * (its own driving coroutine died with a *previous* screen instance, before Work ever reported
  * `WAITING_FOR_INSTALL_CONFIRMATION`) left the screen spinning on "Sandbox environment checked"
  * forever with no error -- recoverable only if the user happened to guess to tap
  * "Check installation status" themselves. Reuses that exact same pull automatically first; only if
  * it finds nothing new does it give up and surface a retryable failure via
  * [SandboxSessionCoordinator.failAbandonedPreparing], which is what actually unlocks "Retry".
  */
 private suspend fun recoverAbandonedPreparing(activity: Activity) {
  withTimeoutOrNull(10_000) {
   coordinator.importEvidence(activity, sessionId)
   coordinator.reconcile(sessionId)
  }
  if (coordinator.observe(sessionId).first()?.state == SandboxSessionState.PREPARING) {
   coordinator.failAbandonedPreparing(sessionId)
  }
 }

 fun refreshInstallation(activity: Activity) {
  requestReconciliation(activity)
 }

 fun continueInstallation(activity: Activity) {
  viewModelScope.launch {
   coordinator.continueInstallation(activity, sessionId)
  }
 }

 fun reinstall(activity: Activity) {
  if (_uiState.value.isReinstalling || !_uiState.value.showReinstall) return
  _uiState.value = _uiState.value.copy(isReinstalling = true)
  viewModelScope.launch {
   try {
    coordinator.reinstall(activity, sessionId)
   } finally {
    _uiState.value = _uiState.value.copy(isReinstalling = false)
   }
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

 fun confirmEndSession(activity: Activity) {
  if (_uiState.value.isEnding) return
  _uiState.value = _uiState.value.copy(showEndConfirmation = false, isEnding = true, endError = null)
  viewModelScope.launch {
   when (val result = coordinator.end(activity, sessionId)) {
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

 /** A user-requested pull only: opening a Work query Activity can obscure Android's installer. */
 private fun requestReconciliation(activity: Activity): Job? {
  if (activity.isFinishing || activity.isDestroyed || _uiState.value.isReinstalling) return null
  statusRecoveryJob?.takeIf { it.isActive }?.let { return it }
  val now = android.os.SystemClock.elapsedRealtime()
  if (now - lastReconciliationStartedAtMs < 3_000L) return null
  lastReconciliationStartedAtMs = now
  return viewModelScope.launch { reconcileInstallation(activity) }.also { statusRecoveryJob = it }
 }

 private suspend fun reconcileInstallation(activity: Activity) {
  // A dropped cross-profile query must not leave this screen suspended forever. Cancellation
  // clears the bridge continuation so another explicit status check can retry the read.
  withTimeoutOrNull(5_000) {
   coordinator.importEvidence(activity, sessionId)
   coordinator.reconcile(sessionId)
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

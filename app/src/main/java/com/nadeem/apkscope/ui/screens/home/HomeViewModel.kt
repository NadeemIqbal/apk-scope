package com.nadeem.apkscope.ui.screens.home

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.domain.ApkImportUseCase
import com.nadeem.apkscope.domain.EnvironmentRepository
import com.nadeem.apkscope.domain.EnvironmentState
import com.nadeem.apkscope.domain.PersistedAnalysisSummary
import com.nadeem.apkscope.domain.SessionRepository
import com.nadeem.apkscope.domain.sandbox.DefaultSandboxSessionCoordinator
import com.nadeem.apkscope.domain.sandbox.OrphanSessionInfo
import com.nadeem.apkscope.domain.storage.StorageRepository
import com.nadeem.apkscope.domain.storage.StorageSummary
import com.nadeem.apkscope.ui.common.UiStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ActiveSessionCardState(
 val sessionId: String,
 val analysisId: String,
 val packageName: String,
 val appName: String,
 val state: com.nadeem.apkscope.core.model.SandboxSessionState,
 val connectionCount: Int = 0,
)

data class HomeUiState(
 val environment: EnvironmentState = EnvironmentState(UiStatus.CHECKING, UiStatus.CHECKING, UiStatus.CHECKING),
 val recentAnalyses: List<PersistedAnalysisSummary> = emptyList(),
 val isImporting: Boolean = false,
 val navigateToSessionId: String? = null,
 /** Checkpoint 5.3, item 3: non-null only when Work reports a session Personal cannot account for — the fail-safe "Unfinished sandbox session detected" state. */
 val orphanSession: OrphanSessionInfo? = null,
 val resolvingOrphan: Boolean = false,
 val activeSession: ActiveSessionCardState? = null,
 val launchingActiveSandboxedApp: Boolean = false,
 val storageSummary: StorageSummary? = null,
)

/** Checkpoint 3, item 4: [HomeUiState.recentAnalyses] is now Room-backed via [SessionRepository.observeHistory] — every entry here is a *completed* analysis, never an in-progress one (those live only in the in-memory active-session state `AnalysisScreen` reads). */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
 private val environmentRepository = EnvironmentRepository(application)
 private val sessionRepository = SessionRepository(application)
 private val sandboxSessionRepository = com.nadeem.apkscope.domain.sandbox.SandboxSessionRepository(application)
 private val database = com.nadeem.apkscope.core.database.SandboxDatabaseProvider.get(application)
 private val importUseCase = ApkImportUseCase(application)
 private val storageRepository = StorageRepository(application)
 private val coordinator = DefaultSandboxSessionCoordinator(application)

 private val _uiState = MutableStateFlow(HomeUiState())
 val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
 private var environmentRefreshJob: Job? = null

 init {
  refreshEnvironment()
  refreshStorage()
  viewModelScope.launch {
   sessionRepository.observeHistory().collect { history -> _uiState.value = _uiState.value.copy(recentAnalyses = history) }
  }
  viewModelScope.launch {
   sandboxSessionRepository.observeActiveSession().collect { session ->
    if (session == null) {
     _uiState.value = _uiState.value.copy(activeSession = null)
    } else {
     val appName = try {
      sessionRepository.getPersisted(session.analysisId)?.appName ?: session.packageName
     } catch (_: Exception) {
      session.packageName
     }
     val count = try {
      database.observationDao().getSummary(session.id)?.connectionCount ?: 0
     } catch (_: Exception) {
      0
     }
     _uiState.value = _uiState.value.copy(
      activeSession = ActiveSessionCardState(
       sessionId = session.id,
       analysisId = session.analysisId,
       packageName = session.packageName,
       appName = appName,
       state = session.state,
       connectionCount = count,
      ),
     )
    }
   }
  }
 }

 fun refreshEnvironment() {
  _uiState.value = _uiState.value.copy(environment = environmentRepository.currentState())
 }

 fun refreshStorage() {
  viewModelScope.launch {
   runCatching { storageRepository.snapshot() }
    .onSuccess { summary -> _uiState.value = _uiState.value.copy(storageSummary = summary) }
  }
 }

 fun refreshEnvironmentUntilReady() {
  environmentRefreshJob?.cancel()
  environmentRefreshJob = viewModelScope.launch {
   repeat(15) {
    refreshEnvironment()
    val environment = _uiState.value.environment
    if (environment.allReady || environment.workProfile == UiStatus.READY) return@launch
    delay(1_000)
   }
   refreshEnvironment()
  }
 }

 fun onApkSelected(uri: Uri) {
  _uiState.value = _uiState.value.copy(isImporting = true)
  viewModelScope.launch {
   val sessionId = importUseCase.importAndAnalyze(uri)
   _uiState.value = _uiState.value.copy(isImporting = false, navigateToSessionId = sessionId)
  }
 }

 fun onNavigationConsumed() {
  _uiState.value = _uiState.value.copy(navigateToSessionId = null)
 }

 fun launchActiveSandboxedApp(activity: Activity) {
  val sessionId = _uiState.value.activeSession?.sessionId ?: return
  _uiState.value = _uiState.value.copy(launchingActiveSandboxedApp = true)
  viewModelScope.launch {
   coordinator.launch(activity, sessionId)
   _uiState.value = _uiState.value.copy(launchingActiveSandboxedApp = false)
  }
 }

 /**
  * Checkpoint 5.3, item 1/3: call once per Dashboard entry — never launches another sandbox APK
  * while an unresolved Work-side session is still open (item 3's "do not launch another
  * suspicious APK while an unresolved Work-side session exists").
  *
  * Checkpoint 5.5's real root cause for "Recent Analysis renders, then reverts to empty a couple
  * of seconds later": [coordinator.checkForOrphanWorkSession] is a slow (real cross-profile IPC,
  * ~1-2s) suspend call. The previous version inlined it directly as a `.copy()` argument —
  * `_uiState.value = _uiState.value.copy(orphanSession = coordinator.checkForOrphanWorkSession(activity))`
  * — and Kotlin evaluates a call's receiver *before* its arguments, so `_uiState.value` (the
  * receiver) was captured **before** that slow call even started, not after it finished. Any
  * update the concurrent Room-history-collecting coroutine in [init] made to `_uiState.value`
  * while this one was suspended got silently clobbered the moment this one finally wrote back —
  * a plain, non-atomic read-modify-write race between two coroutines sharing one `MutableStateFlow`,
  * nothing to do with ViewModel/NavBackStackEntry ownership (that theory was a red herring this
  * checkpoint spent real effort ruling out). Fixed by resolving the suspend call into a local
  * first, so `_uiState.value` is read fresh — *after* the slow call completes — immediately before
  * the single, minimal `.copy()` write.
  */
  private var hasCheckedOrphan = false

  fun checkForOrphan(activity: Activity) {
   if (hasCheckedOrphan) return
  hasCheckedOrphan = true
  viewModelScope.launch {
    // Always ask Work for its live session, even when Personal has a non-terminal row.  A
    // different or stale Personal row is exactly how an orphan can be hidden: the old guard
    // returned early on any local active row, so the user never saw the recovery action for the
    // Work session that was actually blocking the next prepare.
    val orphan = coordinator.checkForOrphanWorkSession(activity)
    _uiState.value = _uiState.value.copy(orphanSession = orphan)
  }
  }

 /** Item 3/5's safe recovery action — drives the orphan through the normal End Session lifecycle. */
 fun resolveOrphan(activity: Activity) {
  val orphan = _uiState.value.orphanSession ?: return
  _uiState.value = _uiState.value.copy(resolvingOrphan = true)
  viewModelScope.launch {
   coordinator.resolveOrphanWorkSession(activity, orphan)
   _uiState.value = _uiState.value.copy(resolvingOrphan = false, orphanSession = null)
  }
 }
}

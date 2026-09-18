package com.nadeem.apkscope.ui.screens.sandbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.core.model.SandboxOperationResult
import com.nadeem.apkscope.core.model.SandboxPolicy
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.domain.EnvironmentRepository
import com.nadeem.apkscope.domain.SessionRepository
import com.nadeem.apkscope.domain.sandbox.DefaultSandboxSessionCoordinator
import com.nadeem.apkscope.ui.common.UiStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Item 2/22's full "recoverable after navigation away/back" made concrete: resuming an in-flight session must land on the screen that actually matches its real current state, not always back at Preparing. */
enum class SandboxLifecycleStage { PREPARING, READY, RUNNING, ENDING }

private fun SandboxSessionState.toLifecycleStage(): SandboxLifecycleStage = when (this) {
 SandboxSessionState.READY -> SandboxLifecycleStage.READY
 SandboxSessionState.RUNNING -> SandboxLifecycleStage.RUNNING
 SandboxSessionState.ENDING, SandboxSessionState.CLEARING_DATA, SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION,
 SandboxSessionState.CLEANUP, SandboxSessionState.CLEANUP_REQUIRED, SandboxSessionState.COMPLETED -> SandboxLifecycleStage.ENDING
 else -> SandboxLifecycleStage.PREPARING
}

data class SandboxConfigUiState(
 val appName: String? = null,
 val packageName: String? = null,
 // Item 5's non-negotiable boundary made visible in the UI itself: nothing on this screen is an
 // EnforcementStatus, because nothing has been attempted yet — that only happens once the real
 // work-profile-side sequence runs during Prepare. This screen only ever shows the *requested*
 // policy (item 5's SandboxPolicy), never a premature/simulated outcome.
 val network: UiStatus = UiStatus.CHECKING,
 val disposableSession: Boolean = true,
 val isCreating: Boolean = false,
 val navigateToSandboxSessionId: String? = null,
 val navigateToStage: SandboxLifecycleStage = SandboxLifecycleStage.PREPARING,
)

/** configure_sandbox (item 11 of the UI checkpoint; checkpoint 4 item 5/24). Reviews the *requested* [SandboxPolicy] and creates the [com.nadeem.apkscope.core.model.SandboxSession] row — it deliberately no longer calls `core:sandbox`'s `PolicyEnforcer` directly, since doing so from the personal profile could only ever report a premature, structurally-guaranteed failure (real application now genuinely happens work-side during Prepare — see `SandboxWorkerService`). */
class SandboxConfigViewModel(application: Application, private val sessionId: String) : AndroidViewModel(application) {
 private val environmentRepository = EnvironmentRepository(application)
 private val sessionRepository = SessionRepository(application)
 private val coordinator = DefaultSandboxSessionCoordinator(application)
 private val _uiState = MutableStateFlow(SandboxConfigUiState())
 val uiState: StateFlow<SandboxConfigUiState> = _uiState.asStateFlow()

 init {
  viewModelScope.launch {
   // By the time Configure Sandbox is reachable, the session's analysis is always complete and
   // persisted (checkpoint 3, item 2/3) — this reads the same durable record Static Result does.
   val session = withContext(Dispatchers.IO) { sessionRepository.getPersisted(sessionId) }
   _uiState.value = _uiState.value.copy(
    appName = session?.appName,
    packageName = session?.packageName,
    network = environmentRepository.currentState().networkIsolation,
   )
  }
 }

 fun setDisposableSession(value: Boolean) { _uiState.value = _uiState.value.copy(disposableSession = value) }

 /**
  * "Prepare Sandbox" — item 22: an analysis may already have a sandbox session in flight (the
  * personal process died mid-lifecycle, or the user simply navigated back and returned). Rather
  * than creating a second, orphaned [com.nadeem.apkscope.core.model.SandboxSession] every time this is
  * tapped, this resumes the most recent non-terminal session for the analysis if one exists —
  * the destination screen's own `reconcile(sessionId)` call then recovers the real state (item 2's
  * "the lifecycle must be recoverable... do not assume the process remains alive"). Only creates a
  * genuinely new row when no such session exists.
  */
 fun onPrepareSandbox() {
  val packageName = _uiState.value.packageName ?: return
  _uiState.value = _uiState.value.copy(isCreating = true)
  viewModelScope.launch {
   val existing = coordinator.observeForAnalysis(sessionId).first()
    .firstOrNull { it.state != SandboxSessionState.COMPLETED && it.state != SandboxSessionState.FAILED && it.state != SandboxSessionState.CANCELLED }
   if (existing != null) {
    _uiState.value = _uiState.value.copy(isCreating = false, navigateToSandboxSessionId = existing.id, navigateToStage = existing.state.toLifecycleStage())
    return@launch
   }
   // Matches ApkImportUseCase's own temp-copy path convention exactly — the one place that path is decided.
   val apkPath = File(getApplication<Application>().filesDir, "analysis/$sessionId.apk").absolutePath
   val policy = SandboxPolicy(disposableSession = _uiState.value.disposableSession)
   when (val result = coordinator.create(analysisId = sessionId, packageName = packageName, personalApkPath = apkPath, policy = policy)) {
    is SandboxOperationResult.Success -> _uiState.value = _uiState.value.copy(isCreating = false, navigateToSandboxSessionId = result.session.id)
    is SandboxOperationResult.Failure -> _uiState.value = _uiState.value.copy(isCreating = false)
   }
  }
 }

 fun onNavigationConsumed() { _uiState.value = _uiState.value.copy(navigateToSandboxSessionId = null) }
}

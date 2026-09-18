package com.nadeem.apkscope.ui.screens.securityaudit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.core.risk.audit.SecurityAuditReport
import com.nadeem.apkscope.domain.AnalysisNotFoundException
import com.nadeem.apkscope.domain.SecurityAuditRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MS10-UI05: every non-clean state is explicit and distinct — a screen reading [SecurityAuditUiState]
 * never has to infer "cancelled" from "no report and not loading", or confuse "loading the persisted
 * audit for the first time" with "running a fresh audit right now".
 */
enum class SecurityAuditPhase { LOADING_PERSISTED, EMPTY, RUNNING, LOADED, CANCELLED, ERROR }

data class SecurityAuditUiState(
 val phase: SecurityAuditPhase = SecurityAuditPhase.LOADING_PERSISTED,
 val report: SecurityAuditReport? = null,
 val errorMessage: String? = null,
)

/**
 * Milestone 10 (Security Audit), Phase 10.2 — mirrors [com.nadeem.apkscope.ui.screens.staticresult.StaticResultViewModel]'s
 * shape: reads/writes exclusively through [SecurityAuditRepository], identical whether the audit was
 * just run in this process or is being reopened after a process restart (MS10-UI04).
 */
class SecurityAuditViewModel(
 application: Application,
 private val sessionId: String,
 private val repository: SecurityAuditRepository = SecurityAuditRepository(application),
) : AndroidViewModel(application) {
 private val _uiState = MutableStateFlow(SecurityAuditUiState())
 val uiState: StateFlow<SecurityAuditUiState> = _uiState.asStateFlow()
 private var runJob: Job? = null

 init {
  viewModelScope.launch {
   val existing = repository.getAudit(sessionId)
   _uiState.value = if (existing != null) {
    SecurityAuditUiState(phase = SecurityAuditPhase.LOADED, report = existing)
   } else {
    SecurityAuditUiState(phase = SecurityAuditPhase.EMPTY)
   }
  }
 }

 /**
  * Runs a fresh audit. If cancelled via [cancel] before the repository call completes, no partial or
  * misleading state is persisted — [SecurityAuditRepository.runAndPersistAudit] does one atomic write
  * at the very end, and a cancelled coroutine simply never reaches it (MS10-UI05).
  */
 fun runAudit() {
  runJob?.cancel()
  _uiState.value = SecurityAuditUiState(phase = SecurityAuditPhase.RUNNING)
  runJob = viewModelScope.launch {
   try {
    val report = repository.runAndPersistAudit(sessionId)
    _uiState.value = SecurityAuditUiState(phase = SecurityAuditPhase.LOADED, report = report)
   } catch (_: kotlinx.coroutines.CancellationException) {
    // cancel() already set CANCELLED state — do not overwrite it with an error.
    throw kotlinx.coroutines.CancellationException()
   } catch (e: AnalysisNotFoundException) {
    _uiState.value = SecurityAuditUiState(phase = SecurityAuditPhase.ERROR, errorMessage = "The underlying analysis could not be found — it may have been deleted.")
   } catch (e: Exception) {
    _uiState.value = SecurityAuditUiState(phase = SecurityAuditPhase.ERROR, errorMessage = e.message ?: "Security Audit failed for an unknown reason.")
   }
  }
 }

 /** Explicit cancellation — distinct from [SecurityAuditPhase.ERROR]; the user chose to stop, nothing failed. */
 fun cancel() {
  runJob?.cancel()
  runJob = null
  _uiState.value = SecurityAuditUiState(phase = SecurityAuditPhase.CANCELLED)
 }
}

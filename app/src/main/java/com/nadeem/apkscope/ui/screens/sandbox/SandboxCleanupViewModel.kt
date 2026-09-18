package com.nadeem.apkscope.ui.screens.sandbox

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.core.database.RuntimeObservationSummaryEntity
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.core.model.CleanupSummary
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.domain.sandbox.DefaultSandboxSessionCoordinator
import com.nadeem.apkscope.domain.sandbox.SandboxSessionCoordinator
import com.nadeem.apkscope.domain.sandbox.UrlEvidenceImportStatusStore
import com.nadeem.apkscope.ui.common.StepState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DynamicDataSaveState {
 NOT_SAVED,
 SAVING,
 SAVED,
 NO_DATA,
 FAILED,
}

data class SandboxCleanupUiState(
 val stoppingApp: StepState = StepState.PENDING,
 val clearingData: StepState = StepState.PENDING,
 val awaitingUninstallConfirmation: StepState = StepState.PENDING,
 val cleaningUp: StepState = StepState.PENDING,
 val showUninstallConfirmationNotice: Boolean = false,
 val cleanupSummary: CleanupSummary? = null,
 val isComplete: Boolean = false,
 val isPartial: Boolean = false,
 val blockedReason: String? = null,
 /** Checkpoint 5, item 35: Personal's own durable runtime-observation summary for this session — null until [DefaultSandboxSessionCoordinator.importRuntimeArtifact] has actually pulled and persisted it (never fabricated/estimated in the meantime). */
 val runtimeSummary: RuntimeObservationSummaryEntity? = null,
 /** Checkpoint 6: Personal's own durable Android-evidence summary for this session (DPM network logs). */
 val androidEvidenceSummary: com.nadeem.apkscope.core.database.AndroidEvidenceSummaryEntity? = null,
 /** Dynamic evidence is intentionally opt-in: cleanup reconciliation never imports it. */
 val dynamicDataSaveState: DynamicDataSaveState = DynamicDataSaveState.NOT_SAVED,
 val dynamicDataSaveError: String? = null,
) {
 companion object {
  /** Every field here is derived from a real [SandboxSession] — no fake delay animation (item 13/18), matching [SandboxPreparingUiState.from]'s pattern for the mirrored end-of-life half of the lifecycle. */
  fun from(session: SandboxSession): SandboxCleanupUiState = when (session.state) {
   SandboxSessionState.ENDING -> SandboxCleanupUiState(stoppingApp = StepState.ACTIVE)
   SandboxSessionState.CLEARING_DATA -> SandboxCleanupUiState(stoppingApp = StepState.COMPLETE, clearingData = StepState.ACTIVE)
   SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION -> SandboxCleanupUiState(
    stoppingApp = StepState.COMPLETE, clearingData = StepState.COMPLETE,
    awaitingUninstallConfirmation = StepState.ACTIVE, showUninstallConfirmationNotice = true,
   )
   SandboxSessionState.CLEANUP -> SandboxCleanupUiState(
    stoppingApp = StepState.COMPLETE, clearingData = StepState.COMPLETE,
    awaitingUninstallConfirmation = StepState.COMPLETE, cleaningUp = StepState.ACTIVE,
   )
   SandboxSessionState.CLEANUP_REQUIRED -> SandboxCleanupUiState(
    stoppingApp = StepState.COMPLETE, clearingData = StepState.COMPLETE, awaitingUninstallConfirmation = StepState.COMPLETE,
    cleaningUp = StepState.FAILED, cleanupSummary = session.cleanupSummary, isPartial = true,
   )
   SandboxSessionState.COMPLETED -> SandboxCleanupUiState(
    stoppingApp = StepState.COMPLETE, clearingData = StepState.COMPLETE, awaitingUninstallConfirmation = StepState.COMPLETE,
    cleaningUp = StepState.COMPLETE, cleanupSummary = session.cleanupSummary, isComplete = true,
   )
   SandboxSessionState.FAILED -> SandboxCleanupUiState(blockedReason = session.error?.userMessage ?: "Something went wrong ending this session.")
   else -> SandboxCleanupUiState() // RUNNING or earlier: this screen shouldn't be reached, but show a neutral pending state rather than crash.
  }
 }
}

/**
 * end_session (checkpoint 4, item 13/14/15/16/17/18). Every step reflects the real
 * [SandboxSession] the coordinator/work-profile sequence has already performed — stopping the app,
 * clearing its data, the real Android uninstall confirmation, then cleanup. [SandboxCleanupUiState.isComplete]
 * only ever becomes true once the session's own [SandboxSession.cleanupSummary] is actually complete
 * (item 18 — never claimed early). This screen never shows a final security report (item 13/23).
 */
class SandboxCleanupViewModel(
 application: Application,
 private val sessionId: String,
 /**
  * Milestone 9 (Pixel 8 acceptance, sixth pass — import guard lifecycle verification): test seam
  * only, matching [DefaultSandboxSessionCoordinator]'s own `activeSessionQueryOverride` precedent —
  * every real production call site ([SandboxCleanupScreen]) leaves this `null`, in which case
  * behavior is completely unchanged from before this parameter existed. Lets a focused test
  * substitute a coordinator whose import calls can be made to fail, hang (for a timeout), or be
  * cancelled, to verify [isImporting] is genuinely released in every one of those cases — not just
  * the success path a real device session happens to exercise.
  */
 coordinatorOverride: SandboxSessionCoordinator? = null,
) : AndroidViewModel(application) {
 private val coordinator: SandboxSessionCoordinator = coordinatorOverride ?: DefaultSandboxSessionCoordinator(application)
 private val sessionDao = SandboxDatabaseProvider.get(application).sandboxSessionDao()
 private val observationDao = SandboxDatabaseProvider.get(application).observationDao()
 private val androidEvidenceDao = SandboxDatabaseProvider.get(application).androidEvidenceDao()
 private val _uiState = MutableStateFlow(SandboxCleanupUiState())
 val uiState: StateFlow<SandboxCleanupUiState> = _uiState.asStateFlow()

 init { observeOnly(); observeRuntimeSummary(); observeAndroidEvidenceSummary() }

 private fun observeOnly() {
  viewModelScope.launch {
   coordinator.observe(sessionId).collect { session ->
    if (session != null) _uiState.value = SandboxCleanupUiState.from(session).copy(
     runtimeSummary = _uiState.value.runtimeSummary,
     androidEvidenceSummary = _uiState.value.androidEvidenceSummary,
     dynamicDataSaveState = _uiState.value.dynamicDataSaveState,
     dynamicDataSaveError = _uiState.value.dynamicDataSaveError,
    )
   }
  }
 }

 /** The independently persisted runtime summary is surfaced without being overwritten by a later session-state emission. */
 private fun observeRuntimeSummary() {
  viewModelScope.launch {
   observationDao.observeSummary(sessionId).collect { summary ->
    _uiState.value = _uiState.value.copy(
     runtimeSummary = summary,
     dynamicDataSaveState = if (summary != null) DynamicDataSaveState.SAVED else _uiState.value.dynamicDataSaveState,
    )
   }
  }
 }

 /** Checkpoint 6: independent flow for Android evidence summary. */
 private fun observeAndroidEvidenceSummary() {
  viewModelScope.launch {
   androidEvidenceDao.observeSummary(sessionId).collect { summary ->
    _uiState.value = _uiState.value.copy(
     androidEvidenceSummary = summary,
     dynamicDataSaveState = if (summary != null) DynamicDataSaveState.SAVED else _uiState.value.dynamicDataSaveState,
    )
   }
  }
 }

 /**
  * Checkpoint 4.1 §8/15: pulls durable Work-local evidence **before** reconciling — a genuinely
  * successful `clearApplicationUserData()` whose own status report was lost to a BAL-blocked push
  * is recovered here, not merely inferred from the app's later absence (item 15's explicit "do not
  * infer successful clearing merely because uninstall later succeeded"). `reconcile()` runs second
  * so its `CleanupSummary`/state-advance logic sees whatever `dataClearResult` this import just
  * merged in, exactly as if the original push had arrived.
  *
  * Dynamic runtime, Android, and URL evidence are deliberately not imported here. They are
  * user-owned data and are saved only from [saveDynamicAnalysis].
  */
  private var isImporting = false

  fun reconcileLocalState() {
   viewModelScope.launch {
    coordinator.reconcile(sessionId)
   }
  }

 fun importAndReconcile(activity: Activity) {
   // Keep cleanup-status recovery independently traceable from an explicit dynamic-data save.
   val operationId = java.util.UUID.randomUUID().toString()
   if (isImporting) {
    com.nadeem.apkscope.core.crossprofile.UrlEvidencePipelineDiagnostics.record(
     activity.applicationContext, operationId, "personal", sessionId, "import_scheduling_skipped_already_in_flight",
    )
    return
   }
   isImporting = true
   com.nadeem.apkscope.core.crossprofile.UrlEvidencePipelineDiagnostics.record(
    activity.applicationContext, operationId, "personal", sessionId, "import_scheduled",
   )
   viewModelScope.launch {
    val diag = com.nadeem.apkscope.core.crossprofile.UrlEvidencePipelineDiagnostics
    val ctx = activity.applicationContext
    try {
     diag.record(ctx, operationId, "personal", sessionId, "import_launched")
     coordinator.importEvidence(activity, sessionId)
     // Item 19 scenario C: a real uninstall confirmation may have completed while this process was
     // dead — reconcile against authoritative Android state before trusting the last Room row.
     coordinator.reconcile(sessionId)
     diag.record(ctx, operationId, "personal", sessionId, "import_and_reconcile_sequence_completed")
    } catch (e: Exception) {
     // Keep an interrupted cleanup-status recovery visible instead of indistinguishable from
     // "the import simply never ran."
     diag.record(ctx, operationId, "personal", sessionId, "import_and_reconcile_sequence_interrupted", "${e.javaClass.simpleName}: ${e.message}")
    } finally {
     isImporting = false
   }
  }
 }

 /**
  * Explicitly saves the dynamic evidence collected by the sandbox. Repeated taps are safe because
  * each import is idempotent on the persisted evidence keys and the in-flight guard suppresses
  * concurrent pulls.
  */
 fun saveDynamicAnalysis(activity: Activity) {
  if (isImporting) return
  val operationId = java.util.UUID.randomUUID().toString()
  isImporting = true
  _uiState.value = _uiState.value.copy(
   dynamicDataSaveState = DynamicDataSaveState.SAVING,
   dynamicDataSaveError = null,
  )
  viewModelScope.launch {
   val ctx = activity.applicationContext
   try {
    coordinator.importRuntimeArtifact(activity, sessionId)
    coordinator.importAndroidEvidence(activity, sessionId)
    coordinator.importUrlEvidence(activity, sessionId, operationId)
    coordinator.reconcile(sessionId)

    val session = sessionDao.getSession(sessionId)
    val hasRuntimeEvidence = observationDao.getSummary(sessionId) != null
    val hasAndroidEvidence = androidEvidenceDao.getSummary(sessionId) != null
    val hasImportedUrls = session?.analysisId?.let { analysisId ->
     UrlEvidenceImportStatusStore.get(ctx, analysisId)?.status == UrlEvidenceImportStatusStore.Status.IMPORTED
    } == true

    if (session?.analysisId != null) {
     try {
      com.nadeem.apkscope.domain.report.ReportCoordinator(getApplication()).generateOrUpdateSessionReport(
       sessionId = sessionId,
       analysisId = session.analysisId,
      )
     } catch (_: Exception) {
      // Evidence remains saved even if the derived report refresh is unavailable.
     }
    }

    _uiState.value = _uiState.value.copy(
     dynamicDataSaveState = if (hasRuntimeEvidence || hasAndroidEvidence || hasImportedUrls) {
      DynamicDataSaveState.SAVED
     } else {
      DynamicDataSaveState.NO_DATA
     },
    )
   } catch (e: Exception) {
    _uiState.value = _uiState.value.copy(
     dynamicDataSaveState = DynamicDataSaveState.FAILED,
     dynamicDataSaveError = e.message ?: "The dynamic evidence could not be saved.",
    )
   } finally {
    isImporting = false
   }
  }
 }

 fun requestUninstallConfirmation(activity: Activity) {
  viewModelScope.launch {
   coordinator.requestUninstallConfirmation(activity, sessionId)
   importAndReconcile(activity)
  }
 }

 fun retryEndSession(activity: Activity) {
  viewModelScope.launch {
   coordinator.end(activity, sessionId)
   coordinator.requestUninstallConfirmation(activity, sessionId)
   importAndReconcile(activity)
  }
 }
}

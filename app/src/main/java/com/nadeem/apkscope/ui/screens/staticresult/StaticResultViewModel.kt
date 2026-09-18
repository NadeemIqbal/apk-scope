package com.nadeem.apkscope.ui.screens.staticresult

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.domain.PersistedAnalysis
import com.nadeem.apkscope.domain.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One real, deterministic declared-capability call-out (item 8/9 of the UI checkpoint) — never a risk-scored "finding" (those are `PersistedAnalysis.riskAssessment.findings`, checkpoint 3's real risk engine output); severity/HIGH-MEDIUM-LOW ranking on THIS list would still be risk-engine territory. */
data class DeclaredCapabilityNote(val title: String, val description: String, val manifestConstant: String)

data class StaticResultUiState(
 val isLoading: Boolean = true,
 val analysis: PersistedAnalysis? = null,
 val notes: List<DeclaredCapabilityNote> = emptyList(),
)

/**
 * Checkpoint 3: reads exclusively from [SessionRepository.observePersisted] — the single durable
 * read path for a *completed* analysis (item 2/3), identical whether this session finished
 * analyzing moments ago in this same process or is being reopened after a process restart. Also
 * backs `PermissionsDetailScreen`/`ComponentsDetailScreen`, which construct this same ViewModel.
 */
class StaticResultViewModel(application: Application, sessionId: String) : AndroidViewModel(application) {
 private val sessionRepository = SessionRepository(application)
 private val _uiState = MutableStateFlow(StaticResultUiState())
 val uiState: StateFlow<StaticResultUiState> = _uiState.asStateFlow()

 init {
  viewModelScope.launch {
   sessionRepository.observePersisted(sessionId).collect { analysis ->
    if (analysis == null) return@collect
    _uiState.value = StaticResultUiState(isLoading = false, analysis = analysis, notes = buildNotes(analysis))
   }
  }
 }

 /** Every note here is a direct, reliable presence check against parsed manifest data — "declared capability", never "observed behavior" (item 9). No severity is assigned here; severity now lives on the real risk engine's findings instead. */
 private fun buildNotes(analysis: PersistedAnalysis): List<DeclaredCapabilityNote> {
  val notes = mutableListOf<DeclaredCapabilityNote>()
  val perms = analysis.permissions
  if (perms.contains("android.permission.CAMERA")) notes += DeclaredCapabilityNote("Camera permission declared", "Can request direct hardware camera access.", "android.permission.CAMERA")
  if (perms.contains("android.permission.RECORD_AUDIO")) notes += DeclaredCapabilityNote("Microphone permission declared", "Can request direct hardware microphone access.", "android.permission.RECORD_AUDIO")
  if (perms.contains("android.permission.ACCESS_FINE_LOCATION") || perms.contains("android.permission.ACCESS_COARSE_LOCATION")) {
   notes += DeclaredCapabilityNote("Location permission declared", "Can request device location.", "android.permission.ACCESS_FINE_LOCATION")
  }
  if (perms.contains("android.permission.BIND_ACCESSIBILITY_SERVICE")) notes += DeclaredCapabilityNote("Accessibility service declared", "Can observe and interact with content displayed by other applications. Requires explicit user authorization to activate.", "android.permission.BIND_ACCESSIBILITY_SERVICE")
  if (perms.contains("android.permission.SYSTEM_ALERT_WINDOW")) notes += DeclaredCapabilityNote("Display over other apps declared", "May request permission to display windows above other applications.", "android.permission.SYSTEM_ALERT_WINDOW")
  if (perms.contains("android.permission.RECEIVE_BOOT_COMPLETED")) notes += DeclaredCapabilityNote("Starts after device boot", "Can automatically start background components when the device boots.", "android.permission.RECEIVE_BOOT_COMPLETED")
  if (analysis.nativeLibraryAbis.isNotEmpty()) notes += DeclaredCapabilityNote("Native libraries present", "Contains native executable code (${analysis.nativeLibraryAbis.joinToString(", ")}).", "lib/")
  return notes
 }
}

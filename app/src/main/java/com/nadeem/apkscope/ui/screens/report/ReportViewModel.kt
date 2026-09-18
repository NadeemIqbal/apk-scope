package com.nadeem.apkscope.ui.screens.report

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.core.database.AnalysisSessionWithDetails
import com.nadeem.apkscope.core.database.AndroidConnectEvidenceEntity
import com.nadeem.apkscope.core.database.AndroidDnsEvidenceEntity
import com.nadeem.apkscope.core.database.NetworkObservationEntity
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.core.model.ApkScopeReport
import com.nadeem.apkscope.domain.report.ReportCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReportUiState(
 val isLoading: Boolean = true,
 val report: ApkScopeReport? = null,
 val staticDetails: AnalysisSessionWithDetails? = null,
 val vpnObservations: List<NetworkObservationEntity> = emptyList(),
 val dpmDnsEvents: List<AndroidDnsEvidenceEntity> = emptyList(),
 val dpmConnectEvents: List<AndroidConnectEvidenceEntity> = emptyList(),
 val expandedFindingIds: Set<String> = emptySet(),
)

class ReportViewModel(
 application: Application,
 private val targetId: String,
) : AndroidViewModel(application) {

 private val coordinator = ReportCoordinator(application)
 private val db = SandboxDatabaseProvider.get(application)
 private val analysisDao = db.analysisSessionDao()
 private val observationDao = db.observationDao()
 private val androidEvidenceDao = db.androidEvidenceDao()

 private val _uiState = MutableStateFlow(ReportUiState())
 val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

 init {
  loadAndObserve()
 }

 private fun loadAndObserve() {
  viewModelScope.launch {
   // Resolve initial report
   val initialReport = coordinator.getReportForSession(targetId)
    ?: coordinator.getLatestReportForAnalysis(targetId)
    ?: coordinator.getReport(targetId)
    ?: coordinator.generateStaticOnlyReport(targetId)

   if (initialReport != null) {
    _uiState.value = _uiState.value.copy(
     isLoading = false,
     report = initialReport,
    )
    observeDetails(initialReport)
   } else {
    _uiState.value = _uiState.value.copy(isLoading = false)
   }
  }
 }

 private fun observeDetails(report: ApkScopeReport) {
  // Observe static details
  viewModelScope.launch {
   analysisDao.observeDetails(report.analysisId).collect { details ->
    _uiState.value = _uiState.value.copy(staticDetails = details)
   }
  }

  // If dynamic session exists, observe VPN observations and DPM evidence
  val sessionId = report.sessionId
  if (sessionId != null) {
   viewModelScope.launch {
    observationDao.observeForSession(sessionId).collect { obs ->
     _uiState.value = _uiState.value.copy(vpnObservations = obs)
    }
   }

   viewModelScope.launch {
    androidEvidenceDao.observeDnsEventsForSession(sessionId).collect { dns ->
     _uiState.value = _uiState.value.copy(dpmDnsEvents = dns)
    }
   }

   viewModelScope.launch {
    androidEvidenceDao.observeConnectEventsForSession(sessionId).collect { conn ->
     _uiState.value = _uiState.value.copy(dpmConnectEvents = conn)
    }
   }

   // Observe report updates (e.g. late DPM arrivals)
   viewModelScope.launch {
    coordinator.observeReportForSession(sessionId).collect { updated ->
     if (updated != null) {
      _uiState.value = _uiState.value.copy(report = updated)
     }
    }
   }
  } else {
   // Observe static report updates
   viewModelScope.launch {
    coordinator.observeLatestReportForAnalysis(report.analysisId).collect { updated ->
     if (updated != null) {
      _uiState.value = _uiState.value.copy(report = updated)
     }
    }
   }
  }
 }

 fun toggleFindingExpansion(findingId: String) {
  val current = _uiState.value.expandedFindingIds
  _uiState.value = _uiState.value.copy(
   expandedFindingIds = if (findingId in current) current - findingId else current + findingId,
  )
 }
}

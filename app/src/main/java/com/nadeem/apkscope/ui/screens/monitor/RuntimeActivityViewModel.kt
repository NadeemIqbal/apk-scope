package com.nadeem.apkscope.ui.screens.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.core.database.NetworkObservationEntity
import com.nadeem.apkscope.core.database.RuntimeObservationSummaryEntity
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class RuntimeActivityUiState(
 val summary: RuntimeObservationSummaryEntity? = null,
 val rows: List<NetworkObservationEntity> = emptyList(),
)

/**
 * Checkpoint 5, item 35: the "View Runtime Activity" destination — Personal's own **durable,
 * already-imported** copy of a completed session's runtime observations (never a live Work-profile
 * stream — that boundary is item 2's, and this session has already ended by the time this screen is
 * reachable). Read-only: nothing here is polled/live the way [com.nadeem.apkscope.ui.screens.workmonitor.WorkLiveMonitorScreen]
 * is, since a completed session's Personal-side rows never change again once imported (item 22/23).
 */
class RuntimeActivityViewModel(application: Application, sessionId: String) : AndroidViewModel(application) {
 private val dao = SandboxDatabaseProvider.get(application).observationDao()
 private val _uiState = MutableStateFlow(RuntimeActivityUiState())
 val uiState: StateFlow<RuntimeActivityUiState> = _uiState.asStateFlow()

 init {
  viewModelScope.launch {
   combine(dao.observeSummary(sessionId), dao.observeForSession(sessionId)) { summary, rows -> RuntimeActivityUiState(summary, rows) }
    .collect { _uiState.value = it }
  }
 }
}

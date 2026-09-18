package com.nadeem.apkscope.ui.screens.analysis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.core.staticanalysis.ApkAnalyzer
import com.nadeem.apkscope.domain.AppIdentity
import com.nadeem.apkscope.domain.SessionRepository
import com.nadeem.apkscope.domain.SessionStage
import com.nadeem.apkscope.ui.common.StepState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AnalysisUiState(
 val isLoading: Boolean = true,
 val appIdentity: AppIdentity? = null,
 val stage: ApkAnalyzer.Stage? = null,
 val failed: Boolean = false,
 val errorMessage: String? = null,
 val complete: Boolean = false,
)

private val STAGE_ORDER = ApkAnalyzer.Stage.entries

fun stageStateFor(target: ApkAnalyzer.Stage, current: ApkAnalyzer.Stage?, complete: Boolean, failed: Boolean): StepState = when {
 complete -> StepState.COMPLETE
 failed && current == target -> StepState.FAILED
 current == null -> StepState.PENDING
 STAGE_ORDER.indexOf(target) < STAGE_ORDER.indexOf(current) -> StepState.COMPLETE
 target == current -> StepState.ACTIVE
 else -> StepState.PENDING
}

class AnalysisViewModel(application: Application, private val sessionId: String) : AndroidViewModel(application) {
 private val sessionRepository = SessionRepository(application)
 private val _uiState = MutableStateFlow(AnalysisUiState())
 val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

 init {
  viewModelScope.launch {
   sessionRepository.observeActive(sessionId).collect { session ->
    if (session == null) return@collect
    _uiState.value = when (val stage = session.stage) {
     is SessionStage.Copying -> AnalysisUiState(isLoading = true, appIdentity = session.appIdentity)
     is SessionStage.Analyzing -> AnalysisUiState(isLoading = true, appIdentity = session.appIdentity, stage = stage.stage)
     is SessionStage.AnalysisComplete -> AnalysisUiState(isLoading = false, appIdentity = session.appIdentity, complete = true, stage = ApkAnalyzer.Stage.entries.last())
     is SessionStage.Failed -> AnalysisUiState(isLoading = false, appIdentity = session.appIdentity, failed = true, errorMessage = stage.message, stage = stage.stage ?: _uiState.value.stage)
    }
   }
  }
 }
}

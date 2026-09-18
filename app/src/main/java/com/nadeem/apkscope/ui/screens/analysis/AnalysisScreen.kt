package com.nadeem.apkscope.ui.screens.analysis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.nadeem.apkscope.core.staticanalysis.ApkAnalyzer
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AnalysisProgressStep
import com.nadeem.apkscope.ui.components.AnalyzingRing
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.CompleteRing
import com.nadeem.apkscope.ui.components.FailedRing
import com.nadeem.apkscope.ui.components.InfoCard
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.ApkScopeColors
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing

private val STAGE_TITLES = mapOf(
 ApkAnalyzer.Stage.READING_APK to "Reading APK",
 ApkAnalyzer.Stage.PARSING_MANIFEST to "Parsing manifest",
 ApkAnalyzer.Stage.CHECKING_SIGNATURE to "Checking signature",
 ApkAnalyzer.Stage.ANALYZING_PERMISSIONS to "Analyzing permissions",
 ApkAnalyzer.Stage.INSPECTING_COMPONENTS to "Inspecting components",
 ApkAnalyzer.Stage.PREPARING_ASSESSMENT to "Preparing assessment",
)

/** analyzing_apk (item 7) — every stage below reflects [ApkAnalyzer.Stage] real completion, never a timer. */
@Composable
fun AnalysisScreen(
 sessionId: String,
 onComplete: (String) -> Unit,
 onBack: () -> Unit,
 modifier: Modifier = Modifier,
) {
 val context = androidx.compose.ui.platform.LocalContext.current
 val viewModel = sessionViewModel { AnalysisViewModel(context.applicationContext as android.app.Application, sessionId) }
 val state by viewModel.uiState.collectAsState()
 var showExitConfirmation by rememberSaveable(sessionId) { mutableStateOf(false) }

 fun requestExit() {
  if (state.complete) onBack() else showExitConfirmation = true
 }

 LaunchedEffect(state.complete) { if (state.complete) onComplete(sessionId) }

 BackHandler(onBack = ::requestExit)

 if (showExitConfirmation) {
  AlertDialog(
   onDismissRequest = { showExitConfirmation = false },
   title = { Text("Leave analysis?") },
   text = { Text("The APK analysis is still in progress. Leaving now will return to the Home screen.") },
   confirmButton = {
    TextButton(onClick = { showExitConfirmation = false; onBack() }) { Text("Leave") }
   },
   dismissButton = {
    TextButton(onClick = { showExitConfirmation = false }) { Text("Continue analysis") }
   },
  )
 }

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "New analysis", onBack = ::requestExit, onOverflow = {}) },
  bottomBar = {
   if (!state.complete) {
    StickyActionBar {
     SecondaryActionButton(text = "Cancel Analysis", onClick = ::requestExit)
    }
   }
  },
 ) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
   val currentIndex = state.stage?.let { ApkAnalyzer.Stage.entries.indexOf(it) } ?: -1
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text("Step ${(currentIndex + 1).coerceAtLeast(1)} of ${ApkAnalyzer.Stage.entries.size}", style = MaterialTheme.typography.labelLarge)
    Text("Static review", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    ApkAnalyzer.Stage.entries.forEachIndexed { index, _ ->
     val color = when {
      state.complete || index < currentIndex -> MaterialTheme.colorScheme.primary
      index == currentIndex -> ApkScopeColors.Lime
      else -> MaterialTheme.colorScheme.outlineVariant
     }
     Spacer(Modifier.weight(1f).height(4.dp).background(color, RoundedCornerShape(50)))
    }
   }
   BaseCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
     Column(Modifier.weight(1f)) {
      Text("Target app", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(state.appIdentity?.name ?: "Selected APK", style = MaterialTheme.typography.titleMedium)
      Text(state.appIdentity?.packageName ?: sessionId, style = com.nadeem.apkscope.ui.theme.MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
     }
     androidx.compose.material3.Surface(
      color = MaterialTheme.colorScheme.secondaryContainer,
      shape = RoundedCornerShape(Radii.pill),
     ) {
      Text("Target", modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
     }
    }
    Text("Personal source · fingerprint recorded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
   }
   BaseCard {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
     when {
      state.failed -> FailedRing()
      state.complete -> CompleteRing()
      else -> AnalyzingRing()
     }
    }
    Spacer(Modifier.height(Spacing.base))
    Text(if (state.failed) "Analysis failed" else "Analyzing APK", style = MaterialTheme.typography.headlineMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxSize())
    Text(
     state.errorMessage ?: "Parsing package manifest and auditing declared security capabilities",
     style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
     textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxSize(),
    )
   }
   BaseCard {
    Text("ANALYSIS PIPELINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Spacing.sm))
    ApkAnalyzer.Stage.entries.forEachIndexed { index, stage ->
     val stepState = stageStateFor(stage, state.stage, state.complete, state.failed)
     AnalysisProgressStep(
      title = STAGE_TITLES.getValue(stage),
      state = stepState,
      trailingLabel = when (stepState) {
       com.nadeem.apkscope.ui.common.StepState.COMPLETE -> "PASS"
       com.nadeem.apkscope.ui.common.StepState.ACTIVE -> "SCANNING"
       com.nadeem.apkscope.ui.common.StepState.FAILED -> "FAILED"
       com.nadeem.apkscope.ui.common.StepState.PENDING -> "Queued"
      },
      connectToNext = index < ApkAnalyzer.Stage.entries.lastIndex,
     )
    }
   }
   InfoCard(text = "Your APK is analyzed locally on this device. No binary is uploaded.")
  }
 }
}

@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun AnalysisScreenActiveStepPreview() {
 ApkScopeTheme {
  Column {
   AnalysisProgressStep("Checking Digital Signature", com.nadeem.apkscope.ui.common.StepState.ACTIVE, detail = "SHA-256: 4f98…d71c", trailingLabel = "SCANNING")
  }
 }
}

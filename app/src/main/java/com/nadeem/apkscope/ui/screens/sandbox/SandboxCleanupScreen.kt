package com.nadeem.apkscope.ui.screens.sandbox

import android.app.Activity
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.nadeem.apkscope.core.model.CleanupSummary
import com.nadeem.apkscope.ui.common.StepState
import com.nadeem.apkscope.ui.common.UiStatus
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AnalysisProgressStep
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.InfoCard
import com.nadeem.apkscope.ui.components.PolicyStatusRow
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.components.WarningCard
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.Spacing

/**
 * end_session (UI checkpoint item 24, checkpoint 4 item 13/16/17/18). No Stitch design exists for
 * this exact real lifecycle — the Stitch "End Session" mock shows a fabricated audit trace and an
 * "End & Generate Report" action, both of which item 13/23 explicitly forbid this checkpoint (no
 * final report yet). This screen instead reuses the app's existing stepper/card visual language
 * (matching Preparing Sandbox's pattern) for the real stop/clear/uninstall/cleanup sequence, and
 * never claims more than the session's own real [CleanupSummary] supports.
 */
@Composable
fun SandboxCleanupScreen(
 sessionId: String,
 onDone: () -> Unit,
 onBack: () -> Unit,
 onViewRuntimeActivity: (String) -> Unit,
 onViewReport: (String) -> Unit = {},
 modifier: Modifier = Modifier,
) {
 val context = LocalContext.current
 val viewModel = sessionViewModel { SandboxCleanupViewModel(context.applicationContext as Application, sessionId) }
 val state by viewModel.uiState.collectAsState()

 var uninstallDialogPrompted by rememberSaveable(sessionId) { mutableStateOf(false) }
 LaunchedEffect(state.awaitingUninstallConfirmation) {
  if (state.awaitingUninstallConfirmation == StepState.ACTIVE && !uninstallDialogPrompted) {
   uninstallDialogPrompted = true
   viewModel.requestUninstallConfirmation(context as Activity)
  }
 }

 // Checkpoint 4.1 §8/15: pull durable Work-local evidence (data-clear result, cleanup facts) before
 // reconciling — the authoritative recovery path for a lost push, not merely inferring success.
 LaunchedEffect(sessionId, state.isComplete) {
  if (!state.isComplete) {
   viewModel.importAndReconcile(context as Activity)
   while (!state.isComplete && state.blockedReason == null) {
    kotlinx.coroutines.delay(2000)
    viewModel.reconcileLocalState()
   }
  }
  if (state.isComplete) {
   viewModel.importAndReconcile(context as Activity)
  }
 }
 LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reconcileLocalState() }

 androidx.activity.compose.BackHandler(onBack = onBack)

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "Ending Session", onBack = onBack, onOverflow = {}) },
  bottomBar = {
   StickyActionBar {
    if (state.stoppingApp == StepState.ACTIVE) {
     SecondaryActionButton(text = "Retry ending session", onClick = { viewModel.retryEndSession(context as Activity) })
    }
    if (state.showUninstallConfirmationNotice) {
     PrimaryActionButton(text = "Uninstall Sandboxed App", onClick = { viewModel.requestUninstallConfirmation(context as Activity) })
     SecondaryActionButton(text = "Check removal status", onClick = { viewModel.importAndReconcile(context as Activity) })
     SecondaryActionButton(text = "Re-send removal notification", onClick = { viewModel.retryEndSession(context as Activity) })
    }
    if (state.isPartial) {
     SecondaryActionButton(text = "Retry", onClick = { viewModel.importAndReconcile(context as Activity) })
    }
    if (state.isComplete) {
     when (state.dynamicDataSaveState) {
      DynamicDataSaveState.SAVED -> PrimaryActionButton(text = "View Final Report", onClick = { onViewReport(sessionId) })
      DynamicDataSaveState.SAVING -> PrimaryActionButton(
       text = "Saving dynamic analysis…",
       loading = true,
       onClick = {},
      )
      DynamicDataSaveState.FAILED -> PrimaryActionButton(
       text = "Retry saving dynamic analysis",
       onClick = { viewModel.saveDynamicAnalysis(context as Activity) },
      )
      DynamicDataSaveState.NOT_SAVED, DynamicDataSaveState.NO_DATA -> PrimaryActionButton(
       text = "Save dynamic analysis data",
       onClick = { viewModel.saveDynamicAnalysis(context as Activity) },
      )
     }
     if (state.dynamicDataSaveState != DynamicDataSaveState.SAVED) {
      SecondaryActionButton(text = "View Static Report", onClick = { onViewReport(sessionId) })
     }
     SecondaryActionButton(text = "Done", onClick = onDone)
     if (state.runtimeSummary != null) SecondaryActionButton(text = "View Raw Network Activity", onClick = { onViewRuntimeActivity(sessionId) })
    }
   }
  },
 ) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
   Text(if (state.isComplete) "Session complete" else "Ending sandbox session", style = MaterialTheme.typography.headlineMedium)
   Text("Stopping the sandboxed app, clearing its data, and removing it from the sandbox.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

   BaseCard {
    Text("CLEANUP LIFECYCLE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AnalysisProgressStep(title = "Sandboxed app stopped", state = state.stoppingApp, detail = "Suspended using the strongest mechanism this device supports")
    AnalysisProgressStep(title = "Application data cleared", state = state.clearingData, detail = "Waits for Android's own completion callback")
    AnalysisProgressStep(title = "Android removal confirmation", state = state.awaitingUninstallConfirmation, detail = "Android itself must confirm this removal")
    AnalysisProgressStep(title = "Temporary files cleaned up", state = state.cleaningUp, detail = "Sandbox and personal-profile temporary copies released", connectToNext = false)
   }

   if (state.blockedReason != null) InfoCard(title = "Could not end session", text = state.blockedReason!!)

   if (state.showUninstallConfirmationNotice) {
    InfoCard(title = "Android confirmation required", text = "Confirm removal of the sandboxed app when Android asks.")
   }

   state.cleanupSummary?.let { summary -> CleanupSummaryCard(summary) }

   if (state.isPartial) {
    WarningCard(title = "Cleanup incomplete", text = "Some cleanup steps could not be verified. This session will remain listed until cleanup is confirmed complete.")
   }

   if (state.isComplete) {
    when (state.dynamicDataSaveState) {
     DynamicDataSaveState.NOT_SAVED -> InfoCard(
      title = "Dynamic analysis not saved",
      text = "Runtime observations, Android network evidence, and observed URL evidence are not imported automatically. Tap Save dynamic analysis data when you want to keep them in this report.",
     )
     DynamicDataSaveState.SAVING -> InfoCard(
      title = "Saving dynamic analysis",
      text = "Importing the sandbox's collected runtime evidence. This may take a moment.",
     )
     DynamicDataSaveState.NO_DATA -> InfoCard(
      title = "No dynamic data found",
      text = "The save operation completed, but the sandbox did not provide dynamic evidence for this session. You can retry if the sandbox export was interrupted.",
     )
     DynamicDataSaveState.FAILED -> InfoCard(
      title = "Could not save dynamic analysis",
      text = state.dynamicDataSaveError ?: "The dynamic evidence could not be imported. You can retry without changing the static analysis.",
     )
     DynamicDataSaveState.SAVED -> Unit
    }
    state.runtimeSummary?.let { summary -> RuntimeSummaryCard(summary) }
   }
  }
 }
}

@Composable
private fun CleanupSummaryCard(summary: CleanupSummary) {
 BaseCard {
  Text("CLEANUP SUMMARY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  PolicyStatusRow(icon = Icons.Filled.CheckCircle, label = "App data cleared", status = summary.appDataCleared.toUiStatus())
  PolicyStatusRow(icon = Icons.Filled.CheckCircle, label = "APK removed", status = summary.apkRemoved.toUiStatus())
  PolicyStatusRow(icon = Icons.Filled.CheckCircle, label = "Sandbox-side temporary APK deleted", status = summary.workTempApkDeleted.toUiStatus())
  PolicyStatusRow(icon = Icons.Filled.CheckCircle, label = "Personal-side temporary APK deleted", status = summary.personalTempApkDeleted.toUiStatus())
  PolicyStatusRow(icon = Icons.Filled.CheckCircle, label = "Temporary file access revoked", status = summary.uriGrantReleased.toUiStatus())
  PolicyStatusRow(icon = Icons.Filled.CheckCircle, label = "Network session closed", status = summary.networkSessionClosed.toUiStatus())
 }
}

private fun Boolean.toUiStatus(): UiStatus = if (this) UiStatus.READY else UiStatus.ERROR

/** Item 35: "Session complete / 47 connections observed / 8 DNS domains / 1.2 MB uploaded / 4.8 MB downloaded / Runtime activity saved" — every value real, from Personal's own durably-imported [com.nadeem.apkscope.core.database.RuntimeObservationSummaryEntity]. No combined/new risk score — the existing static analysis score, if any, remains exactly as static as before. */
@Composable
private fun RuntimeSummaryCard(summary: com.nadeem.apkscope.core.database.RuntimeObservationSummaryEntity) {
 BaseCard {
  Text("RUNTIME ACTIVITY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text(
   "${summary.connectionCount} connections observed · ${summary.uniqueObservedDomains} DNS domains\n" +
    "${formatSummaryBytes(summary.uploadedBytes)} uploaded · ${formatSummaryBytes(summary.downloadedBytes)} downloaded",
   style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
  )
  if (summary.blockedConnectionCount > 0) {
   Text("${summary.blockedConnectionCount} destination(s) blocked by sandbox policy", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
  }
  Text("Runtime activity saved", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
 }
}

private fun formatSummaryBytes(bytes: Long): String = when {
 bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
 bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
 else -> "$bytes B"
}

// Preview-only fixture state (item 19) — a real screen always goes through sessionViewModel/SandboxCleanupViewModel.
@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun SandboxCleanupScreenPreview() {
 ApkScopeTheme {
  Scaffold(topBar = { AppTopBar(title = "Ending Session", onBack = {}, onOverflow = {}) }) { padding ->
   Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
    Text("Session complete", style = MaterialTheme.typography.headlineMedium)
    BaseCard {
     Text("CLEANUP LIFECYCLE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     AnalysisProgressStep(title = "Sandboxed app stopped", state = StepState.COMPLETE)
     AnalysisProgressStep(title = "Application data cleared", state = StepState.COMPLETE)
     AnalysisProgressStep(title = "Android removal confirmation", state = StepState.COMPLETE)
     AnalysisProgressStep(title = "Temporary files cleaned up", state = StepState.COMPLETE, connectToNext = false)
    }
    CleanupSummaryCard(CleanupSummary(true, true, true, true, true, true))
    PrimaryActionButton(text = "Done", onClick = {})
   }
  }
 }
}

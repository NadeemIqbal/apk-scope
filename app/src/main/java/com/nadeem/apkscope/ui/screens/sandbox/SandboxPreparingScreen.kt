package com.nadeem.apkscope.ui.screens.sandbox

import android.app.Activity
import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.domain.EnvironmentRepository
import com.nadeem.apkscope.ui.common.StepState
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AlreadyInstalledInPersonalDialog
import com.nadeem.apkscope.ui.components.AnalysisProgressStep
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.DestructiveActionButton
import com.nadeem.apkscope.ui.components.InfoCard
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.components.isPackageInstalledInPersonal
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.Spacing

/**
 * preparing_sandbox / installation-confirmation (UI checkpoint item 12/13; checkpoint 4 item 3/4/
 * 8/10). Uses product language ("Set up secure sandbox") never "Create Work Profile" (item 12's
 * explicit instruction) — that wording lives only in the retained spike/debug UI. The fifth step
 * and the "Android confirmation required" card are new this checkpoint: item 8's explicit two-phase
 * flow — this screen shows the informational card first, and only *our own* "Continue Installation"
 * tap ([SandboxPreparingViewModel.continueInstallation]) ever invokes Android's real system UI.
 */
@Composable
fun SandboxPreparingScreen(
 sessionId: String,
 onRetryAsNewSession: (String) -> Unit,
 onOpenWorkSandbox: () -> Unit,
 onBack: () -> Unit,
 modifier: Modifier = Modifier,
) {
 val context = LocalContext.current
 val environmentRepository = remember { EnvironmentRepository(context.applicationContext) }
 val viewModel = sessionViewModel { SandboxPreparingViewModel(context.applicationContext as Application, sessionId) }
 val state by viewModel.uiState.collectAsState()

 val provisioningLauncher = rememberLauncherForActivityResult(
  contract = ActivityResultContracts.StartActivityForResult(),
 ) {
  if (environmentRepository.isWorkProfileConfigured()) {
   viewModel.retryPrepare(context as Activity)
  }
 }

 val isInstalledInPersonal = remember(state.packageName) {
  isPackageInstalledInPersonal(context, state.packageName)
 }
 var showAlreadyInstalledDialog by remember { androidx.compose.runtime.mutableStateOf(false) }

 LaunchedEffect(state.blockedReason, state.errorCode, isInstalledInPersonal) {
  if (isInstalledInPersonal && (state.blockedReason != null || state.errorCode == SandboxErrorCode.ALREADY_INSTALLED_IN_PERSONAL)) {
   showAlreadyInstalledDialog = true
  }
 }

 // Auto-open Work Profile Settings if permission error detected
 LaunchedEffect(state.technicalDetail) {
  if (state.technicalDetail?.contains("canRequestPackageInstalls") == true) {
   try {
    viewModel.openInstallSettings(context as Activity)
   } catch (_: Exception) {}
  }
 }

 // Retry root-cause fix: a Retry on a FAILED/CANCELLED session now creates a genuinely fresh
 // session (see SandboxPreparingViewModel.retryPrepare's own doc) — this screen is fixed to its
 // original sessionId and cannot itself observe the new one, so it navigates to the new session's
 // own route as soon as retryPrepare reports one, replacing itself rather than leaving the dead
 // session on the back stack.
 LaunchedEffect(state.retriedAsNewSessionId) {
  state.retriedAsNewSessionId?.let(onRetryAsNewSession)
 }

 LaunchedEffect(state.navigateHome) {
  if (state.navigateHome) {
   onBack()
   viewModel.onNavigationConsumed()
  }
 }

 LaunchedEffect(Unit) { viewModel.startPrepareIfNeeded(context as Activity) }
 LaunchedEffect(state.showContinueInstallation) {
  if (state.showContinueInstallation) viewModel.continueInstallation(context as Activity)
 }
 // Once the real Work-side preparation reaches READY, hand off to the Work monitor automatically.
 // The profile switch and target launch are implementation details of the Frida flow, not steps
 // the user should have to discover from a secondary button.
 LaunchedEffect(state.showContinueToReady) {
  if (state.showContinueToReady) onOpenWorkSandbox()
 }
 BackHandler(onBack = onBack)

 if (state.showEndConfirmation) {
  AlertDialog(
   onDismissRequest = viewModel::dismissEndConfirmation,
   title = { Text("End Sandbox Session?") },
   text = { Text("This will cancel the in-progress sandbox setup and release the session. No dynamic analysis data will be saved.") },
   confirmButton = { TextButton(onClick = { viewModel.confirmEndSession(context as Activity) }) { Text("End Session") } },
   dismissButton = { TextButton(onClick = viewModel::dismissEndConfirmation) { Text("Keep Session") } },
  )
 }

 state.endError?.let { error ->
  AlertDialog(
   onDismissRequest = viewModel::dismissEndError,
   title = { Text("Could Not End Session") },
   text = { Text(error) },
   confirmButton = { TextButton(onClick = viewModel::dismissEndError) { Text("OK") } },
  )
 }

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "Preparing Sandbox", onBack = onBack) },
  bottomBar = {
   StickyActionBar {
    // A status pull opens a Work Activity. Keep it explicit so it cannot cover Android's installer.
    if (!state.showContinueToReady) {
     SecondaryActionButton(
      text = "Check installation status",
      onClick = { viewModel.refreshInstallation(context as Activity) },
      enabled = !state.isReinstalling && !state.isEnding,
     )
    }
    if (state.errorCode == SandboxErrorCode.WORK_PROFILE_MISSING && environmentRepository.isProvisioningAllowed()) {
     PrimaryActionButton(
      text = "Set Up Work Profile",
      onClick = {
       try {
        provisioningLauncher.launch(environmentRepository.createProvisioningIntent())
       } catch (_: Exception) {}
      },
     )
    }
    if (environmentRepository.needsHandoffRepair()) {
     PrimaryActionButton(
      text = "Open Sandbox App",
      onClick = {
       try {
        environmentRepository.openWorkProfileApp()
       } catch (_: Exception) {}
      },
     )
    }
    if (state.blockedReason != null) {
     if (state.technicalDetail?.contains("notification") == true || state.technicalDetail?.contains("canRequestPackageInstalls") == true) {
      SecondaryActionButton(
       text = if (state.openingInstallSettings) "Opening Settings…" else "Open Work Profile Settings",
       onClick = { viewModel.openInstallSettings(context as Activity) },
      )
     }
     if (isInstalledInPersonal && state.packageName != null) {
      PrimaryActionButton(
       text = "Uninstall from Personal Profile",
       onClick = { showAlreadyInstalledDialog = true },
      )
     }
     PrimaryActionButton(
      text = "Retry",
      onClick = {
       if (isInstalledInPersonal) showAlreadyInstalledDialog = true else viewModel.retryPrepare(context as Activity)
      },
     )
     if (state.errorCode == SandboxErrorCode.ANOTHER_SESSION_ACTIVE) {
      SecondaryActionButton(text = "Recover previous session", onClick = onBack)
     }
     SecondaryActionButton(text = "Back to start a new session", onClick = onBack)
    }
    if (state.showContinueToReady) {
     SecondaryActionButton(text = "Open Work Sandbox", onClick = onOpenWorkSandbox)
    }
    if (state.showReinstall && !state.showContinueToReady) {
     SecondaryActionButton(
      text = if (state.isReinstalling) "Reinstalling…" else "Reinstall",
      onClick = { viewModel.reinstall(context as Activity) },
      enabled = !state.isReinstalling,
     )
    }
    DestructiveActionButton(
     text = if (state.isEnding) "Ending…" else "End Session",
     onClick = viewModel::requestEndSession,
     enabled = !state.isEnding,
    )
   }
  },
 ) { padding ->
  Box(Modifier.fillMaxSize().padding(padding)) {
   Column(Modifier.fillMaxSize().padding(Spacing.base).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
    Text("Preparing secure environment", style = MaterialTheme.typography.headlineMedium)
    Text("Configuring an isolated sandbox and applying supported restrictions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    BaseCard {
     Text("VERIFICATION LIFECYCLE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     AnalysisProgressStep(title = "Sandbox environment checked", state = state.environmentCheck, detail = "Isolated profile presence and ownership verified")
     AnalysisProgressStep(title = "Supported restrictions applied", state = state.restrictionsApplied, detail = "Every restriction is verified by reading its state back, not assumed")
     AnalysisProgressStep(title = "Network isolation ready", state = state.networkIsolation, detail = "Always-on VPN lockdown for this sandbox")
     AnalysisProgressStep(title = "APK handoff", state = state.apkHandoff, detail = "Cross-profile transfer to the sandbox")
     AnalysisProgressStep(title = "Android installation confirmation", state = state.awaitingInstallConfirmation, detail = "Android itself must confirm this install", connectToNext = false)
    }

    if (state.blockedReason != null) {
     InfoCard(title = "Set up secure sandbox", text = state.blockedReason!!)
     if (state.errorCode == SandboxErrorCode.ANOTHER_SESSION_ACTIVE) {
      Text(
       "Go back to Home and choose Stop and clean up. Then start this APK again.",
       style = MaterialTheme.typography.bodySmall,
       color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
     }
     state.technicalDetail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (state.showContinueInstallation) {
     InfoCard(title = "Android confirmation required", text = "Android requires you to confirm installation of this APK.")
    }

    if (state.awaitingInstallConfirmation == StepState.ACTIVE && !state.showContinueInstallation) {
     InfoCard(title = "Confirm installation in Android", text = "Tap the installation notification in your Work Profile and confirm the install. When you return here, tap Check installation status if this screen has not updated.")
    }
    if (state.errorCode == SandboxErrorCode.WORK_PROFILE_MISSING && environmentRepository.isProvisioningAllowed()) {
    }
    if (environmentRepository.needsHandoffRepair()) {
     InfoCard(title = "Sandbox profile needs refresh", text = "Open APK Scope in the Work Profile once, then return here and retry.")
    }
    if (state.blockedReason != null) {
     if (state.technicalDetail?.contains("notification") == true || state.technicalDetail?.contains("canRequestPackageInstalls") == true) {
      // Milestone 9 (Pixel 8 acceptance, fifth pass): opens the Work profile's own install-settings
      // screen via the cross-profile mechanism and rechecks the permission there afterward — the
      // previous version of this button opened Personal's own App Info page instead, a confirmed
      // defect, since the failing permission belongs to the Work-profile instance.
      state.installSettingsGuidance?.let { guidance ->
       InfoCard(title = "Couldn't open Settings automatically", text = guidance)
      }
     }
    }

    if (state.showContinueToReady) {
    }
   }

   AlreadyInstalledInPersonalDialog(
    isOpen = showAlreadyInstalledDialog,
    packageName = state.packageName ?: "",
    onDismiss = { showAlreadyInstalledDialog = false },
   )
  }
}
}

// Preview-only fixture state (item 19) — a real screen always goes through sessionViewModel/SandboxPreparingViewModel.
@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun SandboxPreparingScreenPreview() {
 ApkScopeTheme {
  Scaffold(topBar = { AppTopBar(title = "Preparing Sandbox", onBack = {}, onOverflow = {}) }) { padding ->
   Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
    Text("Preparing secure environment", style = MaterialTheme.typography.headlineMedium)
    Text("Configuring an isolated sandbox and applying supported restrictions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    BaseCard {
     Text("VERIFICATION LIFECYCLE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     AnalysisProgressStep(title = "Sandbox environment checked", state = StepState.COMPLETE, detail = "Isolated profile presence and ownership verified")
     AnalysisProgressStep(title = "Supported restrictions applied", state = StepState.COMPLETE, detail = "Every restriction is verified by reading its state back, not assumed")
     AnalysisProgressStep(title = "Network isolation ready", state = StepState.COMPLETE, detail = "Always-on VPN lockdown for this sandbox")
     AnalysisProgressStep(title = "APK handoff", state = StepState.COMPLETE, detail = "Cross-profile transfer to the sandbox")
     AnalysisProgressStep(title = "Android installation confirmation", state = StepState.ACTIVE, detail = "Android itself must confirm this install", connectToNext = false)
    }
    InfoCard(title = "Android confirmation required", text = "Android requires you to confirm installation of this APK.")
    PrimaryActionButton(text = "Continue Installation", onClick = {})
   }
  }
 }
}

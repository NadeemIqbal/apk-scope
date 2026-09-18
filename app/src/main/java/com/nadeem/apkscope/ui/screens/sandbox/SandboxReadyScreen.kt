package com.nadeem.apkscope.ui.screens.sandbox

import android.app.Activity
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.nadeem.apkscope.ui.common.UiStatus
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.DestructiveActionButton
import com.nadeem.apkscope.ui.components.EnvironmentStatusCard
import com.nadeem.apkscope.ui.components.PolicyStatusRow
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.components.WarningCard
import com.nadeem.apkscope.ui.components.openSandboxAppLabel
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.Spacing

/**
 * ready_to_run (UI checkpoint item 14; checkpoint 4 item 10/11). Every row is a real
 * [com.nadeem.apkscope.core.model.PolicyEnforcementResult] from the session's own work-profile-side
 * Prepare sequence — no fake green checks (item 10's explicit instruction).
 */
@Composable
fun SandboxReadyScreen(sessionId: String, onLaunch: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
 val context = LocalContext.current
 val viewModel = sessionViewModel { SandboxReadyViewModel(context.applicationContext as Application, sessionId) }
 val state by viewModel.uiState.collectAsState()

 // Checkpoint 4.1 §14 lost-report test A: opportunistically pulls durable Work-local evidence for
 // this session once the screen is shown — recovers a policy-enforcement-result fact whose
 // original push never arrived, without waiting for the user to do anything.
 LaunchedEffect(sessionId) { viewModel.importEvidence(context as Activity) }

 LaunchedEffect(state.navigateToCleanupSessionId) {
  state.navigateToCleanupSessionId?.let { onBack(); viewModel.onNavigationConsumed() }
 }

 androidx.activity.compose.BackHandler(onBack = onBack)

 if (state.showEndConfirmation) {
  AlertDialog(
   onDismissRequest = viewModel::dismissEndConfirmation,
   title = { Text("End Sandbox Session?") },
   text = { Text("The target app will be stopped and its temporary data will be cleared.\nAndroid may ask you to confirm app removal.") },
   confirmButton = { TextButton(onClick = { viewModel.confirmEndSession(context as Activity) }) { Text("End Session") } },
   dismissButton = { TextButton(onClick = viewModel::dismissEndConfirmation) { Text("Cancel") } },
  )
 }

 state.launchError?.let { errorMsg ->
  AlertDialog(
   onDismissRequest = viewModel::dismissLaunchError,
   title = { Text(if (errorMsg.contains("activity", ignoreCase = true)) "No Launchable Activity" else "Could Not Launch App") },
   text = { Text(errorMsg) },
   confirmButton = { TextButton(onClick = viewModel::dismissLaunchError) { Text("OK") } },
  )
 }

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "Sandbox Ready", onBack = onBack, onOverflow = {}) },
  bottomBar = {
   StickyActionBar {
    PrimaryActionButton(
     text = if (state.isVerifyingIsolation) "Verifying & Opening…" else openSandboxAppLabel(state.appName, state.packageName),
     icon = Icons.Filled.RocketLaunch,
     loading = state.isVerifyingIsolation,
     onClick = { viewModel.launch(context as Activity) { onLaunch(sessionId) } },
    )
    SecondaryActionButton(
     text = "Open Work Live Monitor",
     icon = Icons.Filled.Shield,
     onClick = { com.nadeem.apkscope.sandbox.SandboxProfileSwitcher.openSandboxProfile(context) },
    )
    DestructiveActionButton(
     text = if (state.isEnding) "Ending…" else "End Sandbox Session",
     onClick = viewModel::requestEndSession,
     enabled = !state.isEnding,
    )
   }
  },
 ) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
   Text("Sandbox Ready", style = MaterialTheme.typography.headlineLarge)
   Text("This app will run inside an isolated sandbox with monitored network access.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

   val rows = listOf(
    Triple(Icons.Filled.Work, "Isolation", state.isolation),
    Triple(Icons.Filled.Lan, "Network Monitoring", state.networkMonitoring),
    Triple(Icons.Filled.VpnKey, "VPN Lockdown", state.vpnLockdown),
    Triple(Icons.Filled.Block, "IPv6 Blocked", state.ipv6Blocked),
   )
   EnvironmentStatusCard(title = "Isolation Parameters", subtitle = "${state.appliedCount} of ${state.totalCount} active", allReady = state.allReady) {
    rows.forEach { (icon, label, status) -> PolicyStatusRow(icon = icon, label = label, status = status) }
   }

   if (state.unsupportedCount > 0) {
    WarningCard(title = "${state.unsupportedCount} restriction(s) unavailable", text = "This device could not apply every requested restriction. Other protections remain fully active — you may still continue.")
   }

   if (state.isVpnTargetApp) {
    WarningCard(
     title = "VPN Architecture Limitation",
     text = "APK Scope owns the Work Profile VPN slot for network isolation and monitoring. This target app cannot establish its own VPN simultaneously. Its UI and other features remain fully accessible.",
    )
   }

  }
 }
}

// Preview-only fixture state (item 19) — a real screen always observes SandboxReadyViewModel.
@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun SandboxReadyScreenPreview() {
 ApkScopeTheme {
  Scaffold(topBar = { AppTopBar(title = "Ready to Run", onBack = {}, onOverflow = {}) }) { padding ->
   Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
    Text("Ready to run", style = MaterialTheme.typography.headlineLarge)
    Text("This app will run inside an isolated sandbox with monitored network access.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val rows = listOf(
     Triple(Icons.Filled.Work, "Isolation", UiStatus.NOT_CONFIGURED),
     Triple(Icons.Filled.Lan, "Network Monitoring", UiStatus.NOT_CONFIGURED),
     Triple(Icons.Filled.VpnKey, "VPN Lockdown", UiStatus.NOT_CONFIGURED),
    )
    EnvironmentStatusCard(title = "Isolation Parameters", subtitle = "0 of ${rows.size} active", allReady = false) {
     rows.forEach { (icon, label, status) -> PolicyStatusRow(icon = icon, label = label, status = status) }
    }
    WarningCard(title = "${rows.size} restriction(s) unavailable", text = "Set up the secure sandbox from Home before launching, or continue — other protections remain fully active.")
    PrimaryActionButton(text = "Launch in Sandbox", icon = Icons.Filled.RocketLaunch, onClick = {})
   }
  }
 }
}

package com.nadeem.apkscope.ui.screens.monitor

import android.app.Activity
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.DestructiveActionButton
import com.nadeem.apkscope.ui.components.NetworkEventRow
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.Spacing

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.components.WarningCard
import com.nadeem.apkscope.ui.components.openSandboxAppLabel
import com.nadeem.apkscope.ui.theme.MonoCodeStyle

/**
 * live_sandbox_monitor, Personal side (checkpoint 5, item 2/11/33/34): "Work Profile owns the live
 * monitor — no real-time Work→Personal streaming; Personal may show only 'Sandbox session running.'"
 * This screen therefore deliberately shows *no* connection/domain/traffic counters and *no* event
 * feed of its own — those are real now (see `com.nadeem.apkscope.ui.screens.workmonitor.WorkLiveMonitorScreen`
 * on the Work-profile side), but item 2 forbids streaming them here.
 *
 * BackHandler ensures system/gesture back safely navigates to Dashboard without destroying the
 * session, stopping VPN, or touching application data.
 */
@Composable
fun LiveMonitorScreen(sessionId: String, onEnded: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
 val context = LocalContext.current
 val viewModel = sessionViewModel { LiveMonitorViewModel(context.applicationContext as Application, sessionId) }
 val state by viewModel.uiState.collectAsState()
 var showExitConfirmation by remember { mutableStateOf(false) }

 fun requestExit() {
  if (state.isEnding || state.navigateToCleanupSessionId != null) onBack()
  else showExitConfirmation = true
 }

 BackHandler(onBack = ::requestExit)

 LaunchedEffect(state.navigateToCleanupSessionId) {
  state.navigateToCleanupSessionId?.let { onEnded(it); viewModel.onNavigationConsumed() }
 }

 // Checkpoint 5.5, item 5: once per entry to this screen — the live, Activity-backed reconciliation
 // check `reconcile()` alone cannot do (see LiveMonitorViewModel.reconcileWithActivity's doc comment).
 LaunchedEffect(Unit) { viewModel.reconcileWithActivity(context as Activity) }

 if (showExitConfirmation) {
  AlertDialog(
   onDismissRequest = { showExitConfirmation = false },
   title = { Text("Leave sandbox session?") },
   text = { Text("The dynamic analysis session is still in progress. Leaving this screen will return to Home without ending the session.") },
   confirmButton = {
    TextButton(onClick = { showExitConfirmation = false; onBack() }) { Text("Leave") }
   },
   dismissButton = {
    TextButton(onClick = { showExitConfirmation = false }) { Text("Continue session") }
   },
  )
 }

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

 Scaffold(modifier = modifier, topBar = { AppTopBar(title = "Sandbox Session", onBack = ::requestExit, onOverflow = {}) }) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
   SessionConsoleHeader(packageName = state.packageName, durationLabel = state.durationLabel)

   if (state.isVpnTargetApp) {
    WarningCard(
     title = "VPN Architecture Limitation",
     text = "APK Scope owns the Work Profile VPN slot for network isolation and monitoring. This target app cannot establish its own VPN simultaneously. Its UI and other features remain fully accessible.",
    )
   }

   PrimaryActionButton(
    text = if (state.isLaunchingApp) "Opening…" else openSandboxAppLabel(state.appName, state.packageName),
    icon = Icons.Filled.RocketLaunch,
    loading = state.isLaunchingApp,
    onClick = { viewModel.openSandboxedApp(context as Activity) },
   )

   SecondaryActionButton(
    text = "Open Work Sandbox Monitor",
    icon = Icons.Filled.Shield,
    onClick = { com.nadeem.apkscope.sandbox.SandboxProfileSwitcher.openSandboxProfile(context) },
   )

   DestructiveActionButton(
    text = if (state.isEnding) "Ending…" else "End Sandbox Session",
    onClick = viewModel::requestEndSession,
    enabled = !state.isEnding,
   )
  }
 }
}

@Composable
private fun SessionConsoleHeader(packageName: String, durationLabel: String) {
 BaseCard {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
   Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    Box(
     Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
     contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
     Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
    androidx.compose.foundation.layout.Spacer(Modifier.size(Spacing.sm))
    Column {
     Text("Work Sandbox", style = MaterialTheme.typography.headlineSmall)
     Text(packageName, style = MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
   }
   Badge("SANDBOX", MaterialTheme.colorScheme.secondary)
  }
  androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.sm))
  Row(
   Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(Spacing.sm),
   horizontalArrangement = Arrangement.SpaceBetween,
   verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
  ) {
   RuntimeCell(icon = Icons.Filled.Timeline, label = "SESSION", value = durationLabel)
   RuntimeCell(icon = Icons.Filled.VpnKey, label = "VPN", value = "ACTIVE")
   RuntimeCell(icon = Icons.Filled.Shield, label = "PROFILE", value = "WORK")
  }
  androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.sm))
  Text(
   "Network activity is monitored inside the Work Profile where the target runs. Use this screen to launch the target or jump into the Work monitor.",
   style = MaterialTheme.typography.bodySmall,
   color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
 }
}

@Composable
private fun RuntimeCell(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
 Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
  Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
  androidx.compose.foundation.layout.Spacer(Modifier.size(Spacing.xs))
  Column {
   Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
  }
 }
}

@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun LiveMonitorEventsPreview() {
 ApkScopeTheme {
  LazyColumn(Modifier.padding(Spacing.base)) {
   item { NetworkEventRow(protocol = "DNS", detail = "api.example.com", subDetail = "resolved to 104.20.23.154", ageLabel = "1s ago") }
   item { androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.sm)) }
   item { NetworkEventRow(protocol = "TCP", detail = "1.1.1.1:443", subDetail = "Direct IP connection established without preceding DNS resolution.", ageLabel = "7s ago", suspicious = true) }
   item { androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.sm)) }
   item { NetworkEventRow(protocol = "BLOCKED", detail = "192.168.1.1:80", subDetail = "Blocked local LAN subnet probing attempt.", ageLabel = "11s ago", blocked = true) }
  }
 }
}

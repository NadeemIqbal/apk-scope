package com.nadeem.apkscope.ui.screens.sandbox

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.domain.EnvironmentRepository
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AlreadyInstalledInPersonalDialog
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.InfoCard
import com.nadeem.apkscope.ui.components.PolicyStatusRow
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.components.SectionHeader
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.components.WorkProfileSetupDialog
import com.nadeem.apkscope.ui.components.isPackageInstalledInPersonal
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Spacing

/**
 * configure_sandbox — Network / Device Access / Session sections (UI checkpoint item 11; checkpoint
 * 4 item 5/24). Device Access is now a plain statement of the *requested* policy — item 5's "a
 * requested restriction is not an applied restriction" made visible: this screen shows no
 * [com.nadeem.apkscope.core.model.EnforcementStatus] pill at all, because nothing has been attempted
 * yet. The real outcome (ENFORCED/NOT_SUPPORTED/FAILED per restriction) only exists once
 * [SandboxReadyScreen] shows it, sourced from the real work-profile-side [com.nadeem.apkscope.core.sandbox.PolicyEnforcer]
 * run during Prepare.
 */
@Composable
fun SandboxConfigScreen(
 sessionId: String,
 onPrepare: (String, SandboxLifecycleStage) -> Unit,
 onViewStaticAnalysis: (String) -> Unit = {},
 onBack: () -> Unit,
 modifier: Modifier = Modifier,
 autoPrepare: Boolean = false,
) {
 val context = LocalContext.current
 val environmentRepository = remember { EnvironmentRepository(context.applicationContext) }
 var showSetupDialog by remember { mutableStateOf(false) }

 val viewModel = sessionViewModel { SandboxConfigViewModel(context.applicationContext as Application, sessionId) }
 val state by viewModel.uiState.collectAsState()

 val isInstalledInPersonal = remember(state.packageName) {
  isPackageInstalledInPersonal(context, state.packageName)
 }
 var showAlreadyInstalledDialog by remember { mutableStateOf(false) }
 var autoPrepareRequested by remember(sessionId) { mutableStateOf(false) }

 val provisioningLauncher = rememberLauncherForActivityResult(
  contract = ActivityResultContracts.StartActivityForResult(),
 ) {
  if (environmentRepository.isWorkProfileConfigured()) {
   viewModel.onPrepareSandbox()
  }
 }


 LaunchedEffect(state.navigateToSandboxSessionId) {
  state.navigateToSandboxSessionId?.let { onPrepare(it, state.navigateToStage); viewModel.onNavigationConsumed() }
 }

 // Once the APK is known, begin sandbox setup without making the user press through an internal
 // "Configure" screen. The explicit button remains for other callers and recovery states.
 LaunchedEffect(autoPrepare, state.packageName) {
  if (!autoPrepare || autoPrepareRequested || state.packageName == null) return@LaunchedEffect
  autoPrepareRequested = true
  when {
   isInstalledInPersonal -> showAlreadyInstalledDialog = true
   environmentRepository.isWorkProfileConfigured() -> viewModel.onPrepareSandbox()
   else -> showSetupDialog = true
  }
 }

 androidx.activity.compose.BackHandler(onBack = onBack)

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "Configure Sandbox", onBack = onBack, onOverflow = {}) },
  bottomBar = {
  StickyActionBar {
    SecondaryActionButton(
     text = "View static analysis",
     onClick = { onViewStaticAnalysis(sessionId) },
    )
    PrimaryActionButton(
     text = if (state.isCreating) "Starting…" else "Start sandbox session",
     onClick = {
      if (isInstalledInPersonal) {
       showAlreadyInstalledDialog = true
      } else if (environmentRepository.isWorkProfileConfigured()) {
       viewModel.onPrepareSandbox()
      } else {
       showSetupDialog = true
      }
     },
     loading = state.isCreating,
    )
   }
  },
 ) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
   SetupHeaderCard(appName = state.appName, packageName = state.packageName)

   SectionHeader(title = "Required Permissions")
   BaseCard {
    SetupPermissionRow(icon = Icons.Filled.Shield, title = "Work Profile", status = "Required", statusIsReady = false)
    SetupPermissionRow(icon = Icons.Filled.Badge, title = "Install Apps", status = "Required", statusIsReady = true)
    SetupPermissionRow(icon = Icons.Filled.VpnKey, title = "Local VPN Gateway", status = "Runtime", statusIsReady = state.network.name == "READY")
    SetupPermissionRow(icon = Icons.Filled.Notifications, title = "Notifications", status = "Optional", statusIsReady = false)
   }

   SectionHeader(title = "Network Isolation")
   BaseCard {
    PolicyStatusRow(icon = Icons.Filled.Public, label = "Monitored Internet", status = state.network, sublabel = "Routed through the sandbox local VPN")
    PolicyStatusRow(icon = Icons.Filled.CellTower, label = "DNS Monitoring", status = state.network, sublabel = "Destination evidence and host attribution")
    PolicyStatusRow(icon = Icons.Filled.VpnKey, label = "IPv6 Blocked", status = state.network, sublabel = "Prevents bypassing the monitored path")
   }

   SectionHeader(title = "Session Strategy")
   BaseCard {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
     Column(Modifier.weight(1f)) {
      Text("Disposable session", style = MaterialTheme.typography.bodyLarge)
      Text("App data, cache, and sandbox keys are cleared when the session ends.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     }
     Switch(checked = state.disposableSession, onCheckedChange = viewModel::setDisposableSession)
    }
   }

   if (isInstalledInPersonal) {
    InfoCard(
     title = "App already installed in personal profile",
     text = "Check if app is already installed in personal profile then uninstall and retry.",
    )
   }

  }

  WorkProfileSetupDialog(
   isOpen = showSetupDialog,
   isProvisioningAllowed = environmentRepository.isProvisioningAllowed(),
   onDismiss = { showSetupDialog = false },
   onConfirmSetup = {
    showSetupDialog = false
    try {
     provisioningLauncher.launch(environmentRepository.createProvisioningIntent())
    } catch (_: Exception) {}
   },
  )

  AlreadyInstalledInPersonalDialog(
   isOpen = showAlreadyInstalledDialog,
   packageName = state.packageName ?: "",
   appName = state.appName,
   onDismiss = { showAlreadyInstalledDialog = false },
  )
 }
}

@Composable
private fun SetupHeaderCard(appName: String?, packageName: String?) {
 BaseCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
  Badge("STEP 02 / 03", MaterialTheme.colorScheme.primary)
  Spacer(Modifier.height(Spacing.xs))
  Text("Required Permissions", style = MaterialTheme.typography.headlineLarge)
  Text(
   "Set up isolation, app installation, and capture controls before launching the target.",
   style = MaterialTheme.typography.bodyMedium,
   color = MaterialTheme.colorScheme.onPrimaryContainer,
  )
  Spacer(Modifier.height(Spacing.sm))
  Text(appName ?: "Selected APK", style = MaterialTheme.typography.headlineSmall)
  Text(packageName ?: "Package pending", style = MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
 }
}

@Composable
private fun SetupPermissionRow(
 icon: androidx.compose.ui.graphics.vector.ImageVector,
 title: String,
 status: String,
 statusIsReady: Boolean,
) {
 Row(
  Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
  horizontalArrangement = Arrangement.SpaceBetween,
  verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
 ) {
  Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
   androidx.compose.foundation.layout.Box(
    Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
    contentAlignment = androidx.compose.ui.Alignment.Center,
   ) {
    Icon(icon, contentDescription = null, tint = if (statusIsReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
   }
   androidx.compose.foundation.layout.Spacer(Modifier.size(Spacing.sm))
   Column {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  if (statusIsReady) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
 }
}

@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun SandboxConfigDeviceAccessPreview() {
 ApkScopeTheme {
  Column(Modifier.padding(Spacing.base)) {
   InfoCard(title = "Will be requested", text = "Camera, microphone, and location access will be denied for the sandboxed app where this device supports it.")
  }
 }
}

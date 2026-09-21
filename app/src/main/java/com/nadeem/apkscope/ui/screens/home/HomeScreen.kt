package com.nadeem.apkscope.ui.screens.home

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nadeem.apkscope.core.model.RiskLevel
import com.nadeem.apkscope.domain.EnvironmentState
import com.nadeem.apkscope.domain.PersistedAnalysisSummary
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.BottomNavigationBar
import com.nadeem.apkscope.ui.components.BottomNavTab
import com.nadeem.apkscope.ui.components.EmptyState
import com.nadeem.apkscope.ui.components.PlatformBadge
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.RiskBadge
import com.nadeem.apkscope.ui.components.RiskTier
import com.nadeem.apkscope.ui.components.SectionHeader
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.ApkScopeColors
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing

import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.ui.components.ProfileIndicatorChip
import com.nadeem.apkscope.ui.theme.ProfileContext
import com.nadeem.apkscope.ui.theme.extendedColors

/**
 * home_dashboard (item 6 of the UI checkpoint). [onOpenFreshAnalysis] fires once a brand-new
 * import+analysis produces a session id and should show the Analysis progress screen;
 * [onOpenCompletedAnalysis] is the distinct path for tapping an already-completed Recent Analysis
 * entry — it goes straight to Static Result, never back through the progress screen for a session
 * that finished analyzing, potentially in a previous process (checkpoint 3, item 3).
 * [onOpenActiveSession] restores any active/uncompleted Sandbox session directly to its corresponding
 * lifecycle screen.
 */
@Composable
fun HomeScreen(
 onOpenFreshAnalysis: (String) -> Unit,
 onOpenCompletedAnalysis: (String) -> Unit,
 onTabSelected: (BottomNavTab) -> Unit,
 modifier: Modifier = Modifier,
 onOpenActiveSession: (String, com.nadeem.apkscope.core.model.SandboxSessionState) -> Unit = { _, _ -> },
 onOpenPocRepack: () -> Unit = {},
 onOpenClearMemory: () -> Unit = {},
 viewModel: HomeViewModel = viewModel(),
) {
 val state by viewModel.uiState.collectAsState()
 val context = LocalContext.current
 val environmentRepository = androidx.compose.runtime.remember { com.nadeem.apkscope.domain.EnvironmentRepository(context.applicationContext) }
 var showSetupDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

 val provisioningLauncher = rememberLauncherForActivityResult(
  contract = ActivityResultContracts.StartActivityForResult(),
 ) {
  viewModel.refreshEnvironmentUntilReady()
 }

 val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if (uri != null) viewModel.onApkSelected(uri)
 }

 LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
  viewModel.refreshEnvironmentUntilReady()
  viewModel.refreshStorage()
 }

 LaunchedEffect(state.navigateToSessionId) {
  state.navigateToSessionId?.let { onOpenFreshAnalysis(it); viewModel.onNavigationConsumed() }
 }

 // Checkpoint 5.3, item 1/3: checked once per Dashboard entry, never silently — an unresolved
 // Work-side session must be visible here before the user can start another one.
 LaunchedEffect(Unit) { viewModel.checkForOrphan(context as Activity) }

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "APK Scope", eyebrow = "PERSONAL WORKSPACE") },
  bottomBar = { BottomNavigationBar(selected = BottomNavTab.HOME, onSelect = onTabSelected) },
 ) { padding ->
  LazyColumn(
   modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.base),
   verticalArrangement = Arrangement.spacedBy(Spacing.base),
  ) {
   item { Spacer(Modifier.height(Spacing.lg)) }
   item { SimpleHomeHeader() }
   state.storageSummary?.takeIf { it.isLowStorage }?.let { storage ->
    item {
     LowStorageBanner(
      availableBytes = storage.availableBytes,
      onOpenClearMemory = onOpenClearMemory,
     )
    }
   }
   item {
    SelectApkCard(
     isImporting = state.isImporting,
     onSelect = onOpenPocRepack,
    )
   }
   item {
    state.recentAnalyses.firstOrNull()?.let { summary ->
     Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      Text("Recent session", style = MaterialTheme.typography.titleLarge)
      RecentAnalysisCard(summary = summary, onClick = { onOpenCompletedAnalysis(summary.sessionId) })
     }
    } ?: BaseCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
     Text("No reports yet", style = MaterialTheme.typography.titleMedium)
     Text(
      "Your first completed analysis will appear here.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
     )
    }
   }
   item {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
     HomeStatCard(value = state.recentAnalyses.size.toString(), label = "saved reports", modifier = Modifier.weight(1f))
     HomeStatCard(
      value = if (state.activeSession?.state == com.nadeem.apkscope.core.model.SandboxSessionState.CLEANUP_REQUIRED) "1" else "0",
      label = "pending cleanups",
      modifier = Modifier.weight(1f),
     )
    }
   }
   item {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
     Text("Spaces", style = MaterialTheme.typography.titleLarge)
     SpaceCard(
      title = "Personal",
      subtitle = "APK files and reports",
      status = "Private",
      statusColor = MaterialTheme.colorScheme.secondary,
     )
     SpaceCard(
      title = "Sandbox",
      subtitle = "Managed profile + VPN capture",
      status = if (state.environment.allReady) "Ready" else "Setup",
      statusColor = if (state.environment.allReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.extendedColors.warning,
     )
    }
   }
   item {
    SystemReadinessSection(
     environment = state.environment,
     onSetupWorkProfile = { showSetupDialog = true },
     canProvision = environmentRepository.isProvisioningAllowed(),
    )
   }
   state.activeSession?.let { active ->
    item {
     ActiveSessionCard(
      session = active,
      onOpen = { onOpenActiveSession(active.sessionId, active.state) },
      onOpenSandboxApp = { viewModel.launchActiveSandboxedApp(context as Activity) },
      onOpenWorkProfile = { com.nadeem.apkscope.sandbox.SandboxProfileSwitcher.openSandboxProfile(context) },
      launchingSandboxedApp = state.launchingActiveSandboxedApp,
     )
    }
   }

   state.orphanSession?.let { orphan ->
    item {
     OrphanSessionCard(
      packageName = orphan.packageName ?: "(unknown package)",
      startedAtEpochMs = orphan.startedAtEpochMs,
      networkIsolationActive = orphan.networkIsolationActive,
      resolving = state.resolvingOrphan,
     onResolve = { viewModel.resolveOrphan(context as Activity) },
     )
    }
   }
   item { Spacer(Modifier.height(Spacing.xl)) }
  }

  com.nadeem.apkscope.ui.components.WorkProfileSetupDialog(
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
 }
}

@Composable
private fun LowStorageBanner(
 availableBytes: Long,
 onOpenClearMemory: () -> Unit,
) {
 BaseCard(
  modifier = Modifier.clickable(onClick = onOpenClearMemory),
  containerColor = MaterialTheme.colorScheme.errorContainer,
 ) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
   Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
   Column(Modifier.weight(1f)) {
    Text("Storage is running low", style = MaterialTheme.typography.titleMedium)
    Text(
     "Only ${com.nadeem.apkscope.domain.storage.formatStorageBytes(availableBytes)} is available. Review saved APKs and analysis data.",
     style = MaterialTheme.typography.bodySmall,
     color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
   }
   Text("Review", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
  }
 }
}

@Composable
private fun SimpleHomeHeader() {
 Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
  Badge("PERSONAL WORKSPACE", MaterialTheme.colorScheme.primary)
  Text("APK Scope", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
  Text(
   "Select an APK, then choose how you want to inspect traffic.",
   style = MaterialTheme.typography.bodyMedium,
   color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
 }
}

@Composable
private fun ActiveSessionCard(
 session: ActiveSessionCardState,
 onOpen: () -> Unit,
 onOpenSandboxApp: () -> Unit,
 onOpenWorkProfile: () -> Unit,
 launchingSandboxedApp: Boolean,
 modifier: Modifier = Modifier,
) {
 val isCleanupRequired = session.state == com.nadeem.apkscope.core.model.SandboxSessionState.CLEANUP_REQUIRED
 val isCleaning = session.state in listOf(
  com.nadeem.apkscope.core.model.SandboxSessionState.ENDING,
  com.nadeem.apkscope.core.model.SandboxSessionState.CLEARING_DATA,
  com.nadeem.apkscope.core.model.SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION,
  com.nadeem.apkscope.core.model.SandboxSessionState.CLEANUP,
 )
 val isReady = session.state in listOf(
  com.nadeem.apkscope.core.model.SandboxSessionState.READY,
  com.nadeem.apkscope.core.model.SandboxSessionState.LAUNCHING,
 )
 val isPreparing = session.state in listOf(
  com.nadeem.apkscope.core.model.SandboxSessionState.PREPARING,
  com.nadeem.apkscope.core.model.SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION,
  com.nadeem.apkscope.core.model.SandboxSessionState.INSTALLING,
  com.nadeem.apkscope.core.model.SandboxSessionState.INSTALLED,
 )

 val badgeTitle = when {
  isCleanupRequired -> "Cleanup required"
  isCleaning -> "Cleaning Sandbox"
  isReady -> "Sandbox Ready"
  isPreparing -> "Preparing Sandbox"
  else -> "Sandbox session active"
 }

 val statusText = when {
  isCleanupRequired -> "The target application is still installed in the Sandbox.\nAndroid still needs confirmation to remove the target APK."
  isCleaning -> "Removing temporary application data..."
  isReady -> "Ready to launch in isolated Sandbox"
  isPreparing -> if (session.state == com.nadeem.apkscope.core.model.SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION) {
   "Android installation confirmation required."
  } else {
   "Preparing isolated sandbox environment..."
  }
  session.connectionCount > 0 -> "Running · ${session.connectionCount} connections observed"
  else -> "Running in Sandbox"
 }

 val containerColor = if (isCleanupRequired) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer

 val badgeColor = if (isCleanupRequired) {
  MaterialTheme.colorScheme.error
 } else if (isCleaning) {
  MaterialTheme.extendedColors.warning
 } else {
  MaterialTheme.colorScheme.primary
 }

 BaseCard(containerColor = containerColor, modifier = modifier) {
  Row(
   Modifier.fillMaxWidth(),
   horizontalArrangement = Arrangement.SpaceBetween,
   verticalAlignment = Alignment.CenterVertically,
  ) {
   Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
   ) {
    androidx.compose.material3.Icon(
     if (isCleanupRequired) Icons.Filled.Warning else Icons.Filled.Shield,
     contentDescription = null,
     tint = badgeColor,
     modifier = Modifier.size(16.dp),
    )
    Text(badgeTitle, style = MaterialTheme.typography.labelMedium, color = badgeColor, fontWeight = FontWeight.Bold)
   }
   ProfileIndicatorChip(profileContext = ProfileContext.SANDBOX)
  }

  Spacer(Modifier.height(Spacing.xs))
  Text(session.appName, style = MaterialTheme.typography.headlineSmall)
  Text(session.packageName, style = com.nadeem.apkscope.ui.theme.MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(Spacing.xs))
  Text(statusText, style = MaterialTheme.typography.bodyMedium, color = if (isCleanupRequired) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
  if (!isCleanupRequired) {
   Spacer(Modifier.height(Spacing.sm))
   Row(
    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(Spacing.sm),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
   ) {
    TelemetryMetric("CONNECTIONS", session.connectionCount.toString(), "observed")
    TelemetryMetric("PROFILE", "WORK", "isolated")
    TelemetryMetric("STATE", if (isReady) "READY" else "LIVE", "session")
   }
  }
  Spacer(Modifier.height(Spacing.base))
  if (isCleanupRequired || isCleaning) {
   PrimaryActionButton(
    text = if (isCleanupRequired) "Continue Cleanup" else "View Cleanup Progress",
    icon = Icons.Filled.Warning,
    onClick = onOpen,
   )
   Spacer(Modifier.height(Spacing.xs))
  } else {
   // READY/RUNNING/PREPARING sessions need an explicit way back to their own
   // lifecycle screen; opening the Work Profile is not the same as ending the session.
   PrimaryActionButton(
    text = if (isReady) "Open Sandbox Session" else "Open Active Session",
    icon = Icons.Filled.Shield,
    onClick = onOpen,
   )
   Spacer(Modifier.height(Spacing.xs))
  }
  SecondaryActionButton(
   text = "Open Work Profile",
   icon = Icons.Filled.VpnKey,
   onClick = onOpenWorkProfile,
  )
 }
}

@Composable
private fun TelemetryMetric(label: String, value: String, subtext: String) {
 Column {
  Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
  Text(subtext, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
 }
}

@Composable
private fun SelectApkCard(
 isImporting: Boolean,
 onSelect: () -> Unit,
) {
 BaseCard(containerColor = MaterialTheme.colorScheme.primary) {
  Text("READY TO INSPECT", style = MaterialTheme.typography.labelSmall, color = ApkScopeColors.PrimaryContainer)
  Text(
   "Inspect an APK with Frida",
   style = MaterialTheme.typography.headlineMedium,
   color = MaterialTheme.colorScheme.onPrimary,
  )
  Spacer(Modifier.height(Spacing.xs))
  Text(
   "APK Scope prepares the sandbox and opens a command console for the selected app.",
   style = MaterialTheme.typography.bodyMedium,
   color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
  )
  Spacer(Modifier.height(Spacing.md))
  Button(
   onClick = onSelect,
   enabled = !isImporting,
   shape = RoundedCornerShape(Radii.md),
   colors = ButtonDefaults.buttonColors(
    containerColor = ApkScopeColors.Lime,
    contentColor = ApkScopeColors.OnLime,
   ),
   modifier = Modifier.fillMaxWidth().height(48.dp),
  ) {
   if (isImporting) {
    androidx.compose.material3.CircularProgressIndicator(
     modifier = Modifier.size(18.dp),
     color = ApkScopeColors.OnLime,
     strokeWidth = 2.dp,
    )
    Spacer(Modifier.width(Spacing.sm))
   }
   Text(if (isImporting) "Starting…" else "Start Frida inspection  →", fontWeight = FontWeight.Bold)
  }
 }
}

@Composable
private fun HomeStatCard(value: String, label: String, modifier: Modifier = Modifier) {
 BaseCard(modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
  Text(value, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
  Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
 }
}

@Composable
private fun SpaceCard(title: String, subtitle: String, status: String, statusColor: androidx.compose.ui.graphics.Color) {
 BaseCard {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Column(Modifier.weight(1f)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
   Badge(status, statusColor)
  }
 }
}

@Composable
private fun ModeOption(
 title: String,
 subtitle: String,
 badge: String,
 icon: androidx.compose.ui.graphics.vector.ImageVector,
 color: androidx.compose.ui.graphics.Color,
 onClick: () -> Unit,
) {
 Row(
  Modifier
   .fillMaxWidth()
   .clickable(onClick = onClick)
   .background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
   .padding(Spacing.md),
  horizontalArrangement = Arrangement.SpaceBetween,
  verticalAlignment = Alignment.CenterVertically,
 ) {
  Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
   Box(Modifier.size(40.dp).background(color.copy(alpha = 0.14f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
    Icon(icon, contentDescription = null, tint = color)
   }
   Column(Modifier.weight(1f)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
     Text(title, style = MaterialTheme.typography.labelLarge)
     Badge(badge, color)
    }
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = color)
 }
}

@Composable
private fun SystemReadinessSection(
 environment: EnvironmentState,
 onSetupWorkProfile: () -> Unit,
 canProvision: Boolean,
) {
 BaseCard {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    Text("System Readiness & Isolation Guard", style = MaterialTheme.typography.headlineSmall)
   }
   Badge("LIVE CHECKS", if (environment.allReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, showDot = environment.allReady)
  }
  Spacer(Modifier.height(Spacing.sm))
  ReadinessRow(Icons.Filled.Badge, "Work Profile Container", "Managed profile space isolated from personal files", environment.workProfile)
  ReadinessRow(Icons.Filled.Dns, "DNS Loopback Engine", "Resolution and host-evidence logging", environment.networkIsolation)
  ReadinessRow(Icons.Filled.VpnKey, "VPN Lockdown Gateway", "Traffic path enforced through the Work Profile VPN", environment.vpnLockdown)
  Spacer(Modifier.height(Spacing.sm))
  Row(
   Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)).padding(Spacing.sm),
   verticalAlignment = Alignment.Top,
   horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
   Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
   Text(
    "Personal and Sandbox profiles stay explicit. Static declarations, runtime observations, and Android evidence remain separate.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onPrimaryContainer,
   )
  }
  if (environment.workProfile == com.nadeem.apkscope.ui.common.UiStatus.NOT_CONFIGURED && canProvision) {
   Spacer(Modifier.height(Spacing.sm))
   SecondaryActionButton(text = "Set Up Work Profile", icon = Icons.Filled.Shield, onClick = onSetupWorkProfile)
  }
 }
}

@Composable
private fun ReadinessRow(
 icon: androidx.compose.ui.graphics.vector.ImageVector,
 title: String,
 subtitle: String,
 status: com.nadeem.apkscope.ui.common.UiStatus,
) {
 val color = when (status) {
  com.nadeem.apkscope.ui.common.UiStatus.READY -> MaterialTheme.colorScheme.tertiary
  com.nadeem.apkscope.ui.common.UiStatus.CHECKING -> MaterialTheme.colorScheme.primary
  com.nadeem.apkscope.ui.common.UiStatus.ERROR -> MaterialTheme.colorScheme.error
  else -> MaterialTheme.colorScheme.onSurfaceVariant
 }
 Row(
  Modifier.fillMaxWidth().padding(vertical = Spacing.xs).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(Spacing.sm),
  horizontalArrangement = Arrangement.SpaceBetween,
  verticalAlignment = Alignment.CenterVertically,
 ) {
  Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
   Box(Modifier.size(32.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
   }
   Column(Modifier.weight(1f)) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  Badge(statusLabel(status), color, filled = status == com.nadeem.apkscope.ui.common.UiStatus.READY || status == com.nadeem.apkscope.ui.common.UiStatus.CHECKING)
 }
}

private fun statusLabel(status: com.nadeem.apkscope.ui.common.UiStatus) = when (status) {
 com.nadeem.apkscope.ui.common.UiStatus.READY -> "Ready"
 com.nadeem.apkscope.ui.common.UiStatus.CHECKING -> "Armed"
 com.nadeem.apkscope.ui.common.UiStatus.NOT_CONFIGURED -> "Setup"
 com.nadeem.apkscope.ui.common.UiStatus.UNAVAILABLE -> "Unavailable"
 com.nadeem.apkscope.ui.common.UiStatus.ERROR -> "Error"
}

@Composable
private fun GuidedWorkflowSection() {
 Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
  Column {
   Text("Guided Inspection Workflow", style = MaterialTheme.typography.headlineSmall)
   Text("Methodology for dissecting and executing untrusted Android packages.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  WorkflowStepCard(
   number = "1",
   title = "Static Manifest & DEX Analysis",
   badge = "Safe Preview",
   body = "Extracts AndroidManifest.xml, DEX string references, declared permissions, exported components, and deep-link schemes without running code.",
   pro = "Zero execution risk",
   con = "Runtime behavior unknown",
   icon = Icons.Filled.Code,
  )
  WorkflowStepCard(
   number = "2",
   title = "Prepare Work Profile Sandbox",
   badge = "Container Setup",
   body = "Creates or refreshes the managed Work Profile and prepares installation through Android's normal confirmation flow.",
   pro = "Zero personal data leak",
   con = "Android confirmation required",
   icon = Icons.Filled.Shield,
  )
  TrafficStrategyCard()
  WorkflowStepCard(
   number = "4",
   title = "Observe Live Runtime Telemetry",
   badge = "Execution Phase",
   body = "Launch the app inside Sandbox, then inspect Work Profile VPN observations and decoded traffic where supported.",
   pro = "Evidence-backed timeline",
   con = "Opaque streams may not decode",
   icon = Icons.Filled.CellTower,
  )
 }
}

@Composable
private fun WorkflowStepCard(
 number: String,
 title: String,
 badge: String,
 body: String,
 pro: String,
 con: String,
 icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
 BaseCard {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
     Text(number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Text(title, style = MaterialTheme.typography.headlineSmall)
   }
   Badge(badge.uppercase(), MaterialTheme.colorScheme.onSurfaceVariant, filled = false)
  }
  Spacer(Modifier.height(Spacing.xs))
  Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(Spacing.sm))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
   WorkflowNote(Icons.Filled.Check, pro, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
   WorkflowNote(Icons.Filled.Info, con, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
  }
 }
}

@Composable
private fun WorkflowNote(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
 Row(modifier.background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
  Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
  Text(text, style = MaterialTheme.typography.labelSmall, color = color)
 }
}

@Composable
private fun TrafficStrategyCard() {
 BaseCard {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
     Text("3", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
    Text("Choose Traffic Flow Strategy", style = MaterialTheme.typography.headlineSmall)
   }
   Badge("DECISIVE", MaterialTheme.colorScheme.primary)
  }
  Spacer(Modifier.height(Spacing.xs))
  Text("Select TLS observation based on SSL pinning and native obfuscation posture.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(Spacing.sm))
  FlowOption(Icons.Filled.Key, "Option 3A: CA Certificate Proxy", "Standard Apps", "Inspects compatible HTTPS/WSS through Work Profile CA.", "Fails on strict SSL pinning", MaterialTheme.colorScheme.primary)
  Spacer(Modifier.height(Spacing.sm))
  FlowOption(Icons.Filled.PrecisionManufacturing, "Option 3B: Frida Gadget Injection", "Anti-Pinning", "Hooks instrumented APK traffic when CA interception is not viable.", "Requires repackaging and re-signing", MaterialTheme.colorScheme.secondary)
 }
}

@Composable
private fun FlowOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, badge: String, body: String, warning: String, color: androidx.compose.ui.graphics.Color) {
 Column(Modifier.fillMaxWidth().background(color.copy(alpha = 0.07f), RoundedCornerShape(8.dp)).padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    Text(title, style = MaterialTheme.typography.labelLarge)
   }
   Badge(badge, color)
  }
  Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text(warning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.warning)
 }
}

@Composable
private fun ShortcutsSection(onOpenPocRepack: () -> Unit, onOpenReports: () -> Unit) {
 Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
  Text("Direct Workbench Shortcuts", style = MaterialTheme.typography.headlineSmall)
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
   ShortcutCard(Icons.Filled.History, "Inspection History", "Archived sessions & exported logs", onOpenReports, Modifier.weight(1f), MaterialTheme.colorScheme.primary)
   ShortcutCard(Icons.Filled.Terminal, "Frida Patching", "Gadget pipeline & diagnostics", onOpenPocRepack, Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
  }
 }
}

@Composable
private fun ShortcutCard(
 icon: androidx.compose.ui.graphics.vector.ImageVector,
 title: String,
 subtitle: String,
 onClick: () -> Unit,
 modifier: Modifier = Modifier,
 color: androidx.compose.ui.graphics.Color,
) {
 BaseCard(modifier = modifier.clickable(onClick = onClick)) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Box(Modifier.size(36.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
   }
   Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
  }
  Spacer(Modifier.height(Spacing.sm))
  Text(title, style = MaterialTheme.typography.labelLarge)
  Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
 }
}

/** Checkpoint 5.3, item 3: the fail-safe "Unfinished sandbox session detected" state — shown whenever Work reports a live sandbox session Personal cannot account for. Carries exactly the facts item 3 requires (package, start time, network isolation state) and one recovery action that drives the orphan through the normal End Session lifecycle, never a bespoke shortcut. */
@Composable
private fun OrphanSessionCard(packageName: String, startedAtEpochMs: Long?, networkIsolationActive: Boolean, resolving: Boolean, onResolve: () -> Unit) {
 val startedText = startedAtEpochMs?.let {
  val elapsedMs = System.currentTimeMillis() - it
  val minutes = (elapsedMs / 60_000).coerceAtLeast(0)
  "started $minutes min ago"
 } ?: "start time unknown"
 BaseCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
  Text("Unfinished sandbox session detected", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
  Spacer(Modifier.height(Spacing.xs))
  Text(
   "$packageName — $startedText — network isolation ${if (networkIsolationActive) "active" else "inactive"}. This must be resolved before starting a new sandbox session.",
   style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
  )
  Spacer(Modifier.height(Spacing.base))
  PrimaryActionButton(text = if (resolving) "Resolving…" else "Stop and clean up", onClick = onResolve, loading = resolving)
 }
}

private fun RiskLevel.toTier() = when (this) {
 RiskLevel.LOW -> RiskTier.LOW
 RiskLevel.MODERATE -> RiskTier.MODERATE
 RiskLevel.HIGH -> RiskTier.HIGH
 RiskLevel.CRITICAL -> RiskTier.CRITICAL
}

@Composable
private fun RecentAnalysisCard(summary: PersistedAnalysisSummary, onClick: () -> Unit) {
 BaseCard(modifier = Modifier.clickable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Open analysis")) {
  Row(
   Modifier.fillMaxWidth(),
   horizontalArrangement = Arrangement.SpaceBetween,
   verticalAlignment = Alignment.CenterVertically,
  ) {
   Column(modifier = Modifier.weight(1f)) {
    Text(summary.appName ?: summary.packageName, style = MaterialTheme.typography.bodyLarge)
    Text(summary.packageName, style = com.nadeem.apkscope.ui.theme.MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
   Spacer(Modifier.width(Spacing.sm))
   Column(
    horizontalAlignment = Alignment.End,
    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
   ) {
    if (!summary.platform.isNullOrBlank()) {
     PlatformBadge(platformName = summary.platform, details = summary.platformDetails)
    }
    RiskBadge(tier = summary.riskLevel.toTier(), score = summary.riskScore)
   }
  }
 }
}

@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun HomeScreenPreview() {
 ApkScopeTheme {
  Column {
   SelectApkCard(isImporting = false, onSelect = {})
  }
 }
}

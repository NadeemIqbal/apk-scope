package com.nadeem.apkscope.ui.screens.settings

import android.content.pm.CrossProfileApps
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.domain.EnvironmentRepository
import com.nadeem.apkscope.sandbox.CrossProfileQueryBridge
import com.nadeem.apkscope.ui.common.UiStatus
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.BottomNavTab
import com.nadeem.apkscope.ui.components.BottomNavigationBar
import com.nadeem.apkscope.ui.components.DestructiveActionButton
import com.nadeem.apkscope.ui.components.InfoCard
import com.nadeem.apkscope.ui.components.PolicyStatusRow
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.components.SectionHeader
import com.nadeem.apkscope.ui.screens.tutorial.TutorialDialog
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onTabSelected: (BottomNavTab) -> Unit,
    onOpenAdvancedDiagnostics: () -> Unit,
    onOpenTrafficInspector: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
 val context = LocalContext.current
 val scope = rememberCoroutineScope()
 var environment by remember { mutableStateOf(EnvironmentRepository(context.applicationContext).currentState()) }

 val crossProfileApps = remember { context.getSystemService(CrossProfileApps::class.java) }
 val hasWorkProfile = remember(environment) {
  val targetProfiles = crossProfileApps?.targetUserProfiles.orEmpty()
  targetProfiles.isNotEmpty() && Handoff.isConfigured(context)
 }

 var showDeleteDialog by remember { mutableStateOf(false) }
 var isDeletingProfile by remember { mutableStateOf(false) }
 var showTutorial by remember { mutableStateOf(false) }

 if (showTutorial) {
  TutorialDialog(onFinished = { showTutorial = false })
 }

 if (showDeleteDialog) {
  AlertDialog(
   onDismissRequest = { if (!isDeletingProfile) showDeleteDialog = false },
   title = { Text("Delete Sandbox Work Profile?") },
   text = { Text("This will permanently remove the APK Scope Work Profile and all installed test apps and data. Your Personal Profile will remain unaffected.") },
   confirmButton = {
    Button(
     onClick = {
      scope.launch {
       isDeletingProfile = true
       try {
        val intent = Handoff.buildQueryIntent(context, CrossProfileContract.QUERY_TYPE_DESTROY_WORK_PROFILE, "profile_destroy")
        CrossProfileQueryBridge.launchForResult(intent)
       } catch (_: Exception) {}
       isDeletingProfile = false
       showDeleteDialog = false
       environment = EnvironmentRepository(context.applicationContext).currentState()
      }
     },
     colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
     enabled = !isDeletingProfile,
    ) {
     Text("Delete Work Profile")
    }
   },
   dismissButton = {
    TextButton(onClick = { showDeleteDialog = false }, enabled = !isDeletingProfile) {
     Text("Cancel")
    }
   },
  )
 }

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "More", eyebrow = "PERSONAL") },
  bottomBar = { BottomNavigationBar(selected = BottomNavTab.SETTINGS, onSelect = onTabSelected) },
 ) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
   SectionHeader(title = "Getting started")
   BaseCard {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
     Row(Modifier.weight(1f), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      androidx.compose.material3.Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
      Column {
       Text("Learn the inspection workflow", style = MaterialTheme.typography.bodyLarge)
       Text("Review APK selection, sandboxing, and evidence provenance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
     }
     SecondaryActionButton(text = "View", onClick = { showTutorial = true }, modifier = Modifier.fillMaxWidth(0.24f))
    }
   }

   SectionHeader(title = "Workspace")
   BaseCard {
    PolicyStatusRow(icon = Icons.Filled.Badge, label = "Work Profile Status", status = environment.workProfile)
    PolicyStatusRow(icon = Icons.Filled.CellTower, label = "Network Isolation Engine", status = environment.networkIsolation)
   }

   if (hasWorkProfile) {
    SectionHeader(title = "Work Profile Management")
    BaseCard(modifier = Modifier.fillMaxWidth()) {
     Column(Modifier.fillMaxWidth()) {
      Text("APK Scope Work Profile", style = MaterialTheme.typography.bodyLarge)
      Spacer(Modifier.height(Spacing.xxs))
      Text(
       "A dedicated Android Work Profile is currently active for isolated sandbox execution. You can delete this Work Profile and all its isolated apps.",
       style = MaterialTheme.typography.bodySmall,
       color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(Spacing.sm))
      DestructiveActionButton(
       text = "Delete Sandbox Work Profile",
       icon = Icons.Filled.DeleteForever,
       loading = isDeletingProfile,
       onClick = { showDeleteDialog = true },
      )
     }
    }
   }

   SectionHeader(title = "Evidence retention")
   BaseCard {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
     Column(Modifier.weight(1f)) {
      Text("Clear memory", style = MaterialTheme.typography.bodyLarge)
      Text("Review imported APKs, static findings, and saved dynamic evidence before deleting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     }
     SecondaryActionButton(text = "Open", onClick = onOpenStorage, modifier = Modifier.fillMaxWidth(0.28f), icon = Icons.Filled.DeleteForever)
    }
   }
   BaseCard {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
     Column(Modifier.weight(1f)) {
      Text("Keep reports on device", style = MaterialTheme.typography.bodyLarge)
      Text("Reports stay local until you explicitly export them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     }
     Text("LOCAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
   }
   BaseCard {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
     Column(Modifier.weight(1f)) {
      Text("HTTPS inspection", style = MaterialTheme.typography.bodyLarge)
      Text("Capture metadata only by default.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     }
     SecondaryActionButton(text = "Open", onClick = onOpenTrafficInspector, modifier = Modifier.fillMaxWidth(0.28f))
    }
   }

   SectionHeader(title = "About analysis")
   BaseCard {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
     Text("Verified protocols", style = MaterialTheme.typography.bodyMedium)
     Text("HTTP/1.1 · HTTP/2", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(Spacing.sm))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
     Text("Unsupported", style = MaterialTheme.typography.bodyMedium)
     Text("gRPC · SSE", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.warning)
    }
   }

   SectionHeader(title = "Privacy")
   InfoCard(title = "Zero-Cloud Guarantee", text = "APK Scope operates entirely offline and locally on this device. No APK files, network payloads, or package metadata are transmitted anywhere.")

   SectionHeader(title = "Advanced Diagnostics")
   BaseCard(modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
     Column {
      Text("Engineering diagnostics", style = MaterialTheme.typography.bodyLarge)
      Text("Forwarding-engine and provisioning test tools used during development — not part of the normal product flow.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     }
    }
    Spacer(Modifier.height(Spacing.sm))
    SecondaryActionButton(text = "Open diagnostics", onClick = onOpenAdvancedDiagnostics, icon = Icons.Filled.BugReport)
    Spacer(Modifier.height(Spacing.xs))
    SecondaryActionButton(text = "Open Traffic Inspector", onClick = onOpenTrafficInspector, icon = Icons.Filled.CellTower)
   }
  }
 }
}

@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun SettingsScreenPreview() {
 ApkScopeTheme { SettingsScreen(onTabSelected = {}, onOpenAdvancedDiagnostics = {}) }
}

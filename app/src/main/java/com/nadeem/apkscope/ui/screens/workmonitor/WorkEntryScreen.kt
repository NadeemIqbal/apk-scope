package com.nadeem.apkscope.ui.screens.workmonitor

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.os.UserManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.MainActivity
import com.nadeem.apkscope.poc.apkrepack.FridaTrafficMonitor
import com.nadeem.apkscope.sandbox.SandboxVpnService
import com.nadeem.apkscope.spike.SandboxAdminReceiver
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.DestructiveActionButton
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Checkpoint 5, item 11: "reachable when the app launches; user can switch between sandboxed APK
 * and APK Scope Live Monitor inside Work Profile." This is that entry point — what
 * [com.nadeem.apkscope.MainActivity] shows instead of the Personal-side [com.nadeem.apkscope.ui.navigation.AppNavHost]
 * when it detects it is running as the Work profile's own profile-owner process. No overlay of the
 * sandboxed app, no Accessibility service — this is a completely ordinary launcher Activity content
 * the user reaches through Android's own app switcher, exactly like any other app icon inside a
 * Work Profile.
 *
 * Polls [SandboxVpnService]'s `@Volatile` companion state — the same in-process-flag idiom this
 * codebase already uses elsewhere (`SandboxVpnService.isForwardingActive` itself) — since there is
 * no session to observe from Room until one is actually running; once [SandboxVpnService.activeSessionId]
 * is non-null, the real [WorkLiveMonitorScreen] takes over.
 */
@Composable
 fun WorkEntryScreen(openTrafficInspector: Boolean = false, modifier: Modifier = Modifier) {
 val context = LocalContext.current
 var sessionId by remember { mutableStateOf(SandboxVpnService.activeSessionId) }
 var packageName by remember { mutableStateOf(SandboxVpnService.activePackageName) }
 var showHttpsInspection by remember(openTrafficInspector) { mutableStateOf(openTrafficInspector) }
 val fridaStatus by FridaTrafficMonitor.shared.status.collectAsState()

 LaunchedEffect(sessionId, packageName) {
  FridaTrafficMonitor.shared.start(sessionId, packageName)
 }

 LaunchedEffect(Unit) {
  while (true) {
   sessionId = SandboxVpnService.activeSessionId
   packageName = SandboxVpnService.activePackageName
   delay(1000)
  }
 }

 if (showHttpsInspection) {
  BackHandler { showHttpsInspection = false }
  com.nadeem.apkscope.ui.screens.traffic.TrafficInspectorScreen(
   onBack = { showHttpsInspection = false },
   workSessionId = sessionId,
   modifier = modifier
  )
 } else {
  val currentSessionId = sessionId
  if (currentSessionId != null) {
   WorkLiveMonitorScreen(
    sessionId = currentSessionId,
    packageName = packageName.orEmpty(),
    onBack = {},
    onOpenHttpsInspection = {
     android.util.Log.i("LiveMonitorNav", "onOpenHttpsInspection invoked, setting showHttpsInspection=true")
     showHttpsInspection = true
    },
    onOpenSandboxApp = { openSandboxedTargetApp(context, packageName.orEmpty()) },
    modifier = modifier
   )
  } else {
   NoActiveSessionScreen(
    onOpenHttpsInspection = { showHttpsInspection = true },
    fridaStatus = fridaStatus,
    modifier = modifier
   )
  }
 }
}

private fun openSandboxedTargetApp(context: android.content.Context, packageName: String) {
 if (packageName.isBlank() || packageName == context.packageName) {
  Toast.makeText(context, "Target app is not available yet.", Toast.LENGTH_SHORT).show()
  return
 }
 val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
 if (launchIntent == null) {
  Toast.makeText(context, "No launchable activity found for $packageName.", Toast.LENGTH_SHORT).show()
  return
 }
 try {
  context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
 } catch (e: Exception) {
  Toast.makeText(context, "Could not open $packageName.", Toast.LENGTH_SHORT).show()
 }
}

@Composable
private fun NoActiveSessionScreen(
 onOpenHttpsInspection: () -> Unit = {},
 fridaStatus: FridaTrafficMonitor.Status,
 modifier: Modifier = Modifier
) {
 val context = LocalContext.current
 val dpm = remember { context.getSystemService(DevicePolicyManager::class.java) }
 val um = remember { context.getSystemService(UserManager::class.java) }
 val isApkScopeManaged = remember {
  val admin = ComponentName(context, SandboxAdminReceiver::class.java)
  um?.isManagedProfile == true && dpm?.isProfileOwnerApp(context.packageName) == true && dpm.isAdminActive(admin)
 }
 var showConfirmDialog by remember { mutableStateOf(false) }

 if (showConfirmDialog) {
  AlertDialog(
   onDismissRequest = { showConfirmDialog = false },
   title = { Text("Delete Sandbox Work Profile?") },
   text = { Text("This will permanently remove the APK Scope Work Profile and all installed sandboxed applications and data. Your Personal Profile will remain unaffected.") },
   confirmButton = {
    Button(
     onClick = {
      showConfirmDialog = false
      try {
       dpm?.wipeData(0)
      } catch (_: Exception) {}
     },
     colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) {
     Text("Delete Work Profile")
    }
   },
   dismissButton = {
    TextButton(onClick = { showConfirmDialog = false }) {
     Text("Cancel")
    }
   },
  )
 }

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "APK Scope", eyebrow = "SANDBOX") },
  bottomBar = {
   StickyActionBar {
    SecondaryActionButton(
     text = "Traffic Inspector",
     icon = Icons.Filled.Radar,
     onClick = onOpenHttpsInspection,
    )
    if (isApkScopeManaged) {
     SecondaryActionButton(
      text = "Return to Personal Profile",
      icon = Icons.Filled.Person,
      onClick = {
       try {
        val crossProfileApps = context.getSystemService(CrossProfileApps::class.java)
        val personalUser = crossProfileApps?.targetUserProfiles?.firstOrNull()
        if (personalUser != null) {
         crossProfileApps.startMainActivity(ComponentName(context, MainActivity::class.java), personalUser)
        }
       } catch (_: Exception) {}
      },
     )
     DestructiveActionButton(
      text = "Delete Sandbox Work Profile",
      icon = Icons.Filled.DeleteForever,
      onClick = { showConfirmDialog = true },
     )
    }
   }
  },
 ) { padding ->
  Column(
   Modifier
    .fillMaxSize()
    .verticalScroll(rememberScrollState())
    .padding(padding)
    .padding(horizontal = Spacing.base, vertical = Spacing.base),
   verticalArrangement = Arrangement.spacedBy(Spacing.base),
  ) {
   BaseCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
    Row(verticalAlignment = Alignment.CenterVertically) {
     Box(
      Modifier
       .size(52.dp)
       .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
      contentAlignment = Alignment.Center,
     ) {
      Icon(
       Icons.Filled.Shield,
       contentDescription = null,
       tint = MaterialTheme.colorScheme.primary,
       modifier = Modifier.size(28.dp),
      )
     }
     Spacer(Modifier.width(Spacing.md))
     Column {
      Text(
       "SANDBOX MONITOR",
       style = MaterialTheme.typography.labelSmall,
       color = MaterialTheme.colorScheme.primary,
       fontWeight = FontWeight.Bold,
      )
      Text("Ready for a session", style = MaterialTheme.typography.headlineSmall)
     }
    }
    Spacer(Modifier.height(Spacing.md))
    Text(
     "Start an analysis in Personal Profile. Once the app is running in the sandbox, its live activity will appear here.",
     style = MaterialTheme.typography.bodyMedium,
     color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.md))
    Row(
     Modifier.fillMaxWidth(),
     verticalAlignment = Alignment.CenterVertically,
     horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
     SandboxFlowStep("Personal", "Start", Modifier.weight(1f))
     Icon(
      Icons.AutoMirrored.Filled.ArrowForward,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(16.dp),
     )
     SandboxFlowStep("Sandbox", "Run", Modifier.weight(1f))
     Icon(
      Icons.AutoMirrored.Filled.ArrowForward,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(16.dp),
     )
     SandboxFlowStep("Monitor", "View", Modifier.weight(1f))
    }
   }

   BaseCard {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
     Box(
      Modifier
       .size(40.dp)
       .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(com.nadeem.apkscope.ui.theme.Radii.md)),
      contentAlignment = Alignment.Center,
     ) {
      Icon(Icons.Filled.Radar, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
     }
     Spacer(Modifier.width(Spacing.md))
     Column(Modifier.weight(1f)) {
      Text("INSTRUMENTATION CHANNEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
      Text("Frida Receiver", style = MaterialTheme.typography.titleMedium)
     }
     Badge(
      text = if (fridaStatus.isListening) "LISTENING" else "OFFLINE",
      color = if (fridaStatus.isListening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
     )
    }
    Spacer(Modifier.height(Spacing.md))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
     Column {
      Text("ENDPOINT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(
       if (fridaStatus.isListening) "127.0.0.1:${fridaStatus.port}" else "Receiver not listening",
       style = MonoCodeStyle,
       color = if (fridaStatus.isListening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
      )
     }
     Column(horizontalAlignment = Alignment.End) {
      Text("CAPTURED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text("${fridaStatus.capturedCount} chunks", style = MonoCodeStyle, color = MaterialTheme.colorScheme.onSurface)
     }
    }
    Spacer(Modifier.height(Spacing.sm))
    Text(
     "Connected package: ${fridaStatus.connectedPackage ?: "none"}",
     style = MaterialTheme.typography.labelSmall,
     color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    fridaStatus.lastError?.let {
     Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
   }
  }
 }
}

@Composable
private fun SandboxFlowStep(label: String, detail: String, modifier: Modifier = Modifier) {
 Surface(
  modifier = modifier,
  color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
  shape = RoundedCornerShape(com.nadeem.apkscope.ui.theme.Radii.md),
  border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
 ) {
  Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
   Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
   Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
  }
 }
}

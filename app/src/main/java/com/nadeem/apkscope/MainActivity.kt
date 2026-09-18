package com.nadeem.apkscope

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nadeem.apkscope.sandbox.CrossProfileQueryBridge
import com.nadeem.apkscope.spike.GateActivity
import com.nadeem.apkscope.spike.SandboxAdminReceiver
import com.nadeem.apkscope.ui.navigation.AppNavHost
import com.nadeem.apkscope.ui.screens.workmonitor.WorkEntryScreen
import com.nadeem.apkscope.ui.theme.ApkScopeTheme

/**
 * The production entry point (item 4/23) — this, not `com.nadeem.apkscope.spike.GateActivity`,
 * is the app's launcher activity. `GateActivity` and the rest of the spike/debug surface remain
 * fully present and independently launchable (`adb shell am start -n
 * com.nadeem.apkscope/.spike.GateActivity`, still exactly how the Physical Pixel 8 Validation Gate
 * drove it) — they are simply no longer what a normal user sees, and are reachable in-product
 * only through Settings → Advanced Diagnostics → "Open diagnostics", never from the main flow.
 *
 * Checkpoint 5, item 11: this same installed app runs in *both* profiles (Android gives every
 * profile-owner app its own launcher icon per profile) — the Personal-side [AppNavHost] and the
 * Work-side [WorkEntryScreen]/Live Monitor are genuinely different experiences behind the same
 * entry point, selected by which profile's process this actually is (`isProfileOwnerApp` — the
 * exact same real check `ImportActivity`/`SandboxWorkerService` already use, never a naming
 * convention or build flavor).
 */
class MainActivity : ComponentActivity() {
 companion object {
  const val EXTRA_WORK_DESTINATION = "com.nadeem.apkscope.extra.WORK_DESTINATION"
  const val WORK_DESTINATION_TRAFFIC_INSPECTOR = "traffic_inspector"
 }

 private var requestedWorkDestination by mutableStateOf<String?>(null)

 // Checkpoint 5.2, item 3's root cause fix: `dumpsys notification` showed this app's own
 // notifications at `importance=NONE` in *both* profiles — the manifest declares
 // `POST_NOTIFICATIONS` (required, API 33+) but nothing in the app ever actually requested it at
 // runtime, so every notification this app posts — including `SandboxInstallResultReceiver`'s
 // "Installation needs your confirmation" notification, which the whole install-confirmation flow
 // structurally depends on the user seeing and tapping — was silently suppressed by the OS with no
 // error anywhere in this app's own code. This is the actual reason the real Android install
 // dialog never appeared: it was never launched, because the notification meant to prompt the user
 // to launch it never showed.
 private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  // Checkpoint 4.1: must be registered before onCreate returns (before STARTED) — see
  // CrossProfileQueryBridge's doc comment. This is the one Activity that ever exists in this
  // single-Activity app, so it is the only place this registration can live.
  CrossProfileQueryBridge.register(this)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
   ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
  ) {
   requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
  }
  enableEdgeToEdge()
  val dpm = getSystemService(DevicePolicyManager::class.java)
  val isWorkProfile = dpm?.isProfileOwnerApp(packageName) == true
  requestedWorkDestination = if (isWorkProfile) intent?.getStringExtra(EXTRA_WORK_DESTINATION) else null
  if (isWorkProfile) {
   try {
    com.nadeem.apkscope.core.crossprofile.Handoff.configure(this, ComponentName(this, SandboxAdminReceiver::class.java))
   } catch (e: Exception) {
    android.util.Log.w("MainActivity", "Could not refresh cross-profile handoff filters", e)
   }
  }
  val profileContext = if (isWorkProfile) com.nadeem.apkscope.ui.theme.ProfileContext.SANDBOX else com.nadeem.apkscope.ui.theme.ProfileContext.PERSONAL
  setContent {
   ApkScopeTheme(profileContext = profileContext) {
    if (isWorkProfile) {
     WorkEntryScreen(openTrafficInspector = requestedWorkDestination == WORK_DESTINATION_TRAFFIC_INSPECTOR)
    } else {
     AppNavHost(onOpenAdvancedDiagnostics = { startActivity(Intent(this, GateActivity::class.java)) })
    }
   }
  }
 }

 override fun onNewIntent(intent: Intent) {
  super.onNewIntent(intent)
  setIntent(intent)
  val dpm = getSystemService(DevicePolicyManager::class.java)
  if (dpm?.isProfileOwnerApp(packageName) == true) {
   requestedWorkDestination = intent.getStringExtra(EXTRA_WORK_DESTINATION)
  }
 }
}

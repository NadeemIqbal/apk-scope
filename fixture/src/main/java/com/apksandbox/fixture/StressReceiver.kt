package com.apksandbox.fixture
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * `adb shell am broadcast -a com.apksandbox.fixture.RUN_STRESS_SUITE --user <workProfileId>`
 * Lets the hardening-gate stress suite be driven from the host without any UI automation
 * (tap-coordinate/focus races were the whole methodology trap documented in SPIKE_B_RESULTS.md).
 */
class StressReceiver : BroadcastReceiver() {
 override fun onReceive(context: Context, intent: Intent) {
  Log.i("SandboxStress", "SUITE_STARTED")
  // A goAsync() thread alone gets killed by background-execution limits partway through a
  // multi-minute run — start a foreground service instead so the process survives to the end.
  context.startForegroundService(Intent(context, StressForegroundService::class.java))
 }
}

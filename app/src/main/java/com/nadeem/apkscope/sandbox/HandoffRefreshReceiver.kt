package com.nadeem.apkscope.sandbox

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.spike.SandboxAdminReceiver

class HandoffRefreshReceiver : BroadcastReceiver() {
 override fun onReceive(context: Context, intent: Intent) {
  if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
  val dpm = context.getSystemService(DevicePolicyManager::class.java)
  if (dpm?.isProfileOwnerApp(context.packageName) != true) return
  try {
   Handoff.configure(context, ComponentName(context, SandboxAdminReceiver::class.java))
  } catch (e: Exception) {
   Log.w("HandoffRefreshReceiver", "Could not refresh cross-profile handoff filters", e)
  }
 }
}

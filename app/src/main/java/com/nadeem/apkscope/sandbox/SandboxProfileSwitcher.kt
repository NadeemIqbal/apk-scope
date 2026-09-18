package com.nadeem.apkscope.sandbox

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import com.nadeem.apkscope.MainActivity
import com.nadeem.apkscope.domain.sandbox.SandboxEnvironmentPreflight

/**
 * Supported, platform-level cross-profile navigation helper.
 * Uses [CrossProfileApps] / [LauncherApps] to switch between Personal and Sandbox instances of APK Scope.
 */
object SandboxProfileSwitcher {

 enum class Destination { MONITOR, TRAFFIC_INSPECTOR }

 fun openSandboxProfile(context: Context, destination: Destination = Destination.MONITOR): Boolean {
  val crossProfile = context.getSystemService(CrossProfileApps::class.java)
  val targetUser = crossProfile?.targetUserProfiles?.firstOrNull()
  if (crossProfile != null && targetUser != null) {
    try {
     if (destination == Destination.TRAFFIC_INSPECTOR) {
     // Compose may expose a ContextThemeWrapper through LocalContext. CrossProfileApps needs the
     // actual foreground Activity as the launch origin, so unwrap instead of failing silently on a
     // direct cast.
     val activity = context.findActivity() ?: return false
     crossProfile.startActivity(
      Intent(context, MainActivity::class.java)
       .putExtra(MainActivity.EXTRA_WORK_DESTINATION, MainActivity.WORK_DESTINATION_TRAFFIC_INSPECTOR),
      targetUser,
      activity,
     )
    } else {
     crossProfile.startMainActivity(ComponentName(context, MainActivity::class.java), targetUser)
    }
    return true
   } catch (e: Exception) {
    android.util.Log.w("SandboxProfileSwitcher", "Could not open Work profile destination=$destination", e)
   }
  }

  val launcherApps = context.getSystemService(LauncherApps::class.java)
  val workHandle = SandboxEnvironmentPreflight.workProfileHandle(context)
  if (destination == Destination.MONITOR && launcherApps != null && workHandle != null) {
   try {
    val activity = launcherApps.getActivityList(context.packageName, workHandle).firstOrNull()
    if (activity != null) {
     launcherApps.startMainActivity(activity.componentName, workHandle, null, null)
     return true
    }
   } catch (_: Exception) {}
  }
  return false
 }

 private fun Context.findActivity(): Activity? {
  var current: Context = this
  while (current is ContextWrapper) {
   if (current is Activity) return current
   val base = current.baseContext
   if (base === current) break
   current = base
  }
  return current as? Activity
 }

 fun returnToPersonalProfile(context: Context): Boolean {
  val crossProfile = context.getSystemService(CrossProfileApps::class.java)
  val personalUser = crossProfile?.targetUserProfiles?.firstOrNull()
  if (crossProfile != null && personalUser != null) {
   try {
    crossProfile.startMainActivity(ComponentName(context, MainActivity::class.java), personalUser)
    return true
   } catch (_: Exception) {}
  }
  return false
 }
}

package com.nadeem.apkscope.sandbox

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.nadeem.apkscope.core.model.InstallLifecycle

object InstallConfirmationAvailability {
 const val CHANNEL_ID = "sandbox-action-required"
 fun available(context: Context): Boolean {
  val manager = context.getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sandbox action required", NotificationManager.IMPORTANCE_HIGH))
  return InstallLifecycle.confirmationAvailable(
   Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
   manager.areNotificationsEnabled(),
   manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE,
  )
 }
}

package com.apksandbox.fixture
import android.app.*
import android.content.Intent
import android.util.Log

/** Same foreground-service trick as [StressForegroundService] (a bare background thread gets killed by Android's background-execution limits partway through a run). */
class RootCauseForegroundService : Service() {
 override fun onBind(intent: Intent?) = null
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  val workloads = intent?.getStringExtra("workloads") ?: "concurrentTcp20"
  val manager = getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel("fixture-rootcause", "Root cause gate suite", NotificationManager.IMPORTANCE_LOW))
  val notification = Notification.Builder(this, "fixture-rootcause").setSmallIcon(android.R.drawable.stat_sys_download)
   .setContentTitle("Root cause gate").setContentText(workloads).build()
  startForeground(44, notification)
  Thread({
   try { RootCauseSuite.run(workloads) } catch (e: Exception) { Log.i("SandboxRootCause", "BATCH_FATAL ${e.javaClass.simpleName}: ${e.message}") }
   finally { stopSelf() }
  }, "rootcause-suite").start()
  return START_NOT_STICKY
 }
}

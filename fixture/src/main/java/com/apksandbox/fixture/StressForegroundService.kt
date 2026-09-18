package com.apksandbox.fixture
import android.app.*
import android.content.Intent
import android.util.Log

/**
 * A bare [StressReceiver.goAsync] thread gets killed by Android's background-execution limits
 * partway through a multi-minute run (observed directly: the fixture process disappeared mid-suite
 * with no error, just silence). A foreground service is the same trick [com.nadeem.apkscope.spike.GateVpnService]
 * already relies on to survive as a background app — so the stress suite gets one too.
 */
class StressForegroundService : Service() {
 override fun onBind(intent: Intent?) = null
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  val manager = getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel("fixture-stress", "Fixture stress suite", NotificationManager.IMPORTANCE_LOW))
  val notification = Notification.Builder(this, "fixture-stress").setSmallIcon(android.R.drawable.stat_sys_download)
   .setContentTitle("Hardening gate stress suite").setContentText("Running network load scenarios").build()
  startForeground(43, notification)
  Thread({
   try { StressSuite.run(applicationContext) } catch (e: Exception) { Log.i("SandboxStress", "SUITE_FATAL ${e.javaClass.simpleName}: ${e.message}") }
   finally { stopSelf() }
  }, "stress-suite").start()
  return START_NOT_STICKY
 }
}

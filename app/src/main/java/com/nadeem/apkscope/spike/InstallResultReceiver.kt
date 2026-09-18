package com.nadeem.apkscope.spike
import android.content.*
import android.content.pm.PackageInstaller
class InstallResultReceiver:BroadcastReceiver() {
 override fun onReceive(c:Context,i:Intent) {
  val status=i.getIntExtra(PackageInstaller.EXTRA_STATUS,Int.MIN_VALUE)
  Evidence.record(c,"PackageInstaller.status",if(status==0) "PASS" else if(status==-1) "PENDING_USER_ACTION" else "FAIL", "$status: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
  if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
   val confirmation=if(android.os.Build.VERSION.SDK_INT>=33) i.getParcelableExtra(Intent.EXTRA_INTENT,Intent::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
   if(confirmation!=null) c.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
 }
}

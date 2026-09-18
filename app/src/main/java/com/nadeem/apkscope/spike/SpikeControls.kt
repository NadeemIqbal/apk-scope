package com.nadeem.apkscope.spike

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*
import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.core.model.ReportState
import java.io.File

object SpikeControls {
 const val FIXTURE="com.apksandbox.fixture"
 private var callback:LauncherApps.Callback?=null
 fun attach(a:Activity,layout:LinearLayout,owner:Boolean) {
  val dpm=a.getSystemService(DevicePolicyManager::class.java)
  val admin=ComponentName(a,SandboxAdminReceiver::class.java)
  fun button(label:String, action:()->Unit) { layout.addView(Button(a).apply { text=label; setOnClickListener {
    try { action() } catch(e:Exception) { Evidence.record(a,label,"FAIL",e.toString()); Toast.makeText(a,e.toString(),Toast.LENGTH_LONG).show() }
  } }) }
  if(!owner) {
   val la=a.getSystemService(LauncherApps::class.java)
   if(callback==null) {
    callback=object:LauncherApps.Callback() {
     override fun onPackageAdded(p:String,u:UserHandle) { if(p==FIXTURE) Evidence.record(a.applicationContext,"LauncherApps.add","PASS","$p $u") }
     override fun onPackageRemoved(p:String,u:UserHandle) { if(p==FIXTURE) Evidence.record(a.applicationContext,"LauncherApps.remove","PASS","$p $u") }
     override fun onPackageChanged(p:String,u:UserHandle) {}
     override fun onPackagesAvailable(p:Array<out String>,u:UserHandle,r:Boolean) {}
     override fun onPackagesUnavailable(p:Array<out String>,u:UserHandle,r:Boolean) {}
    }
    la.registerCallback(callback!!)
   }
   button("Open Work Profile controller") {
    val cp=a.getSystemService(android.content.pm.CrossProfileApps::class.java)
    cp.startMainActivity(ComponentName(a,GateActivity::class.java),cp.targetUserProfiles.first())
   }
   button("Pick harmless APK with SAF") {
    @Suppress("DEPRECATION")
    a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/vnd.android.package-archive").addCategory(Intent.CATEGORY_OPENABLE),90)
   }
   button("Launch work fixture from personal") {
    val work=la.profiles.first { it!=Process.myUserHandle() }
    val target=la.getActivityList(FIXTURE,work).first()
    la.startMainActivity(target.componentName,work,null,null)
    Evidence.record(a,"LauncherApps.launch","PASS",target.componentName.toString())
   }
   button("Delete sent temporary files and revoke grants") {
    File(a.filesDir,"handoff").listFiles()?.forEach { f ->
     a.revokeUriPermission(androidx.core.content.FileProvider.getUriForFile(a,"com.nadeem.apkscope.files",f),Intent.FLAG_GRANT_READ_URI_PERMISSION)
     check(f.delete())
    }; Evidence.record(a,"personalTemporaryCleanup","PASS")
   }
   return
  }
  button("Configure cross profile handoff") { Handoff.configure(a, admin) }
  button("Allow installer in Work Profile settings") {
   dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
   Evidence.record(a,"clearUnknownSourcesRestriction","PASS")
   a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${a.packageName}")))
  }
  button("Install imported harmless APK") {
   check(dpm.isProfileOwnerApp(a.packageName))
   check(a.packageManager.canRequestPackageInstalls()) { "Allow installation in Work Profile settings first" }
   val file=File(requireNotNull(a.getSharedPreferences("spike",0).getString("import",null)))
   val pi=a.packageManager.getPackageArchiveInfo(file.absolutePath,0)
   require(pi?.packageName==FIXTURE) { "Spike accepts only the harmless fixture package" }
   val installer=a.packageManager.packageInstaller
   val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
   if(Build.VERSION.SDK_INT>=31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
   val id=installer.createSession(params)
   installer.openSession(id).use { session ->
    session.openWrite("base.apk",0,file.length()).use { out -> file.inputStream().use { it.copyTo(out) };session.fsync(out) }
    val callback=PendingIntent.getBroadcast(a,id,Intent(a,InstallResultReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    session.commit(callback.intentSender)
   }
  }
  button("Open harmless fixture") {
   val probe=File(a.filesDir,"handoff/other-session-${java.util.UUID.randomUUID()}.json")
   probe.parentFile!!.mkdirs();probe.writeText("{\"probe\":true}")
   val uri=androidx.core.content.FileProvider.getUriForFile(a,"com.nadeem.apkscope.files",probe)
   Evidence.record(a,"probeReportExists","VERIFIED", "${probe.length()} bytes")
   // Deliberately a STRING extra, no data/ClipData/URI grant to the fixture.
   a.startActivity(requireNotNull(a.packageManager.getLaunchIntentForPackage(FIXTURE)).putExtra("probeUri",uri.toString()))
  }
  button("Test policy controls") { PolicyChecks.run(a) }
  button("Clear fixture data") { dpm.clearApplicationUserData(admin,FIXTURE,a.mainExecutor) { p,ok -> Evidence.record(a,"clearApplicationUserData",if(ok) "PASS" else "FAIL",p) } }
  button("Configure always on lockdown VPN") {
   dpm.setAlwaysOnVpnPackage(admin,a.packageName,true)
   Evidence.record(a,"setAlwaysOnVpnPackage",if(dpm.getAlwaysOnVpnPackage(admin)==a.packageName && dpm.isAlwaysOnVpnLockdownEnabled(admin)) "PASS" else "FAIL")
   Evidence.record(a,"VpnService.prepare",if(android.net.VpnService.prepare(a)==null) "PASS" else "FAIL")
   dpm.addUserRestriction(admin,UserManager.DISALLOW_CONFIG_VPN)
   dpm.setUninstallBlocked(admin,a.packageName,true)
   Evidence.record(a,"controllerUninstallBlocked",if(dpm.isUninstallBlocked(admin,a.packageName)) "PASS" else "FAIL")
  }
  button("Establish non forwarding test VPN") { a.startForegroundService(Intent(a,GateVpnService::class.java).setAction("ESTABLISH")) }
  button("Establish forwarding VPN (Spike B, IPv4 only)") { a.startForegroundService(Intent(a,GateVpnService::class.java).setAction("ESTABLISH_FORWARDING")) }
  // Checkpoint 5.1, item 11's isolated DNS experiment — same tunnel, only difference is an explicit
  // VPN DNS server, to test whether that alone routes *ordinary* app DNS resolution through the
  // observable TUN. Temporary, disposable test control.
  button("Establish forwarding VPN (DNS server ON)") { a.startForegroundService(Intent(a,GateVpnService::class.java).setAction("ESTABLISH_FORWARDING").putExtra("useDnsServer", true)) }
  button("Generate ordinary DNS traffic (HttpsURLConnection)") {
   Thread {
    val outcome = try {
     val c = java.net.URL("https://example.com/").openConnection() as javax.net.ssl.HttpsURLConnection
     c.connectTimeout = 5000; c.readTimeout = 5000
     try { "HTTP ${c.responseCode}" } finally { c.disconnect() }
    } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message}" }
    Evidence.record(a, "dnsExperimentOrdinaryTraffic", "OBSERVED", outcome)
   }.start()
  }
  button("Close VPN TUN retaining lockdown") { a.startForegroundService(Intent(a,GateVpnService::class.java).setAction("CLOSE")) }
  // Checkpoint 4.1 item 11's required negative test: a real, adb-reachable way to force
  // `com.nadeem.apkscope.sandbox.SandboxVpnService.isForwardingActive` false without needing to kill
  // the whole Work-profile process (which this Android build's `am force-stop` does not permit
  // for a process holding an active foreground VPN service, and adb itself cannot start a
  // `BIND_VPN_SERVICE`-protected component directly). Calling this from the app's own process
  // needs no special permission — only *binding* to a VpnService from another package does.
  button("Kill production sandbox VPN (checkpoint 4.1 test)") {
   a.startForegroundService(Intent(a, com.nadeem.apkscope.sandbox.SandboxVpnService::class.java).setAction(com.nadeem.apkscope.sandbox.SandboxVpnService.ACTION_CLOSE))
   Evidence.record(a,"sandboxVpnKilledForTest","PASS")
  }
  button("Delete Work temporary report and revoke grant") {
   File(a.filesDir,"handoff").listFiles()?.forEach { f ->
    a.revokeUriPermission(androidx.core.content.FileProvider.getUriForFile(a,"com.nadeem.apkscope.files",f),Intent.FLAG_GRANT_READ_URI_PERMISSION)
    check(f.delete())
   };Evidence.record(a,"workTemporaryCleanup","PASS")
  }
  button("Destroy this test Work Profile LAST") {
   check(a.getSystemService(UserManager::class.java).isManagedProfile && dpm.isProfileOwnerApp(a.packageName))
   android.app.AlertDialog.Builder(a).setTitle("Delete APK Scope test Work Profile?")
    .setMessage("Deletes this managed profile and its test data. Run only after exporting evidence. Personal Profile is preserved.")
    .setPositiveButton("Delete test Work Profile") { _,_ -> Evidence.record(a,"wipeData","STARTED"); dpm.wipeData(0) }
    .setNegativeButton("Cancel",null).show()
  }
  button("Export temporary report to personal") {
   val session=requireNotNull(a.getSharedPreferences("spike",0).getString("session",null))
   val report=File(a.filesDir,"handoff/$session.json");report.parentFile!!.mkdirs()
   report.writeText(org.json.JSONObject().put("schemaVersion",1).put("sessionId",session).put("state",ReportState.WAITING_FOR_ANDROID_EVIDENCE.name).put("evidence",File(a.filesDir,"gate.jsonl").readText()).toString())
   Handoff.send(a,report,CrossProfileContract.ACTION_REPORT_READY,session,"com.nadeem.apkscope.files")
  }
 }
}
object PolicyChecks {
 fun run(a:Activity) {
  val dpm=a.getSystemService(DevicePolicyManager::class.java); check(dpm.isProfileOwnerApp(a.packageName))
  val admin=ComponentName(a,SandboxAdminReceiver::class.java);val p=SpikeControls.FIXTURE
  fun test(name:String,block:()->Boolean) { try { Evidence.record(a,name,if(block()) "PASS" else "FAIL") } catch(e:Exception) { Evidence.record(a,name,"FAIL",e.toString()) } }
  test("setApplicationHidden") { check(dpm.setApplicationHidden(admin,p,true)); val hidden=dpm.isApplicationHidden(admin,p);check(dpm.setApplicationHidden(admin,p,false)); hidden && !dpm.isApplicationHidden(admin,p) }
  test("setPackagesSuspended") { check(dpm.setPackagesSuspended(admin,arrayOf(p),true).isEmpty()); val suspended=dpm.isPackageSuspended(admin,p);check(dpm.setPackagesSuspended(admin,arrayOf(p),false).isEmpty()); suspended && !dpm.isPackageSuspended(admin,p) }
  test("setPermissionPolicy") { dpm.setPermissionPolicy(admin,DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY);dpm.getPermissionPolicy(admin)==DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY }
  listOf("CAMERA","RECORD_AUDIO","ACCESS_FINE_LOCATION").forEach { permission -> test("deny.$permission") {
   val full="android.permission.$permission"
   dpm.setPermissionGrantState(admin,p,full,DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED) && dpm.getPermissionGrantState(admin,p,full)==DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
  } }
  test("setCameraDisabled") { dpm.setCameraDisabled(admin,true);val disabled=dpm.getCameraDisabled(admin);dpm.setCameraDisabled(admin,false);disabled && !dpm.getCameraDisabled(admin) }
  test("setUninstallBlocked") { dpm.setUninstallBlocked(admin,p,true);val blocked=dpm.isUninstallBlocked(admin,p);dpm.setUninstallBlocked(admin,p,false);blocked && !dpm.isUninstallBlocked(admin,p) }
  test("setUserControlDisabledPackages") { dpm.setUserControlDisabledPackages(admin,listOf(a.packageName)); dpm.getUserControlDisabledPackages(admin).contains(a.packageName) }
  test("setNetworkLoggingEnabled") { dpm.setNetworkLoggingEnabled(admin,true);dpm.isNetworkLoggingEnabled(admin) }
 }
}

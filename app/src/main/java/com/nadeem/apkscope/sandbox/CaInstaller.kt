package com.nadeem.apkscope.sandbox

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.nadeem.apkscope.core.network.https.CaManager
import com.nadeem.apkscope.core.network.https.HttpsInspectionConfig
import com.nadeem.apkscope.core.network.https.HttpsInspectionStore
import com.nadeem.apkscope.spike.SandboxAdminReceiver
import java.io.File

/**
 * Handles Work Profile certificate installation, uninstallation, and status querying via
 * Android's [DevicePolicyManager] for the Profile Owner app.
 */
object CaInstaller {

 private fun getAdminComponent(context: Context): ComponentName =
  ComponentName(context, SandboxAdminReceiver::class.java)

 fun getCaManager(context: Context): CaManager =
  CaManager(File(context.filesDir, "poc_ca"))

 fun isCaInstalled(context: Context): Boolean {
  val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
  val admin = getAdminComponent(context)
  val certDer = getCaManager(context).getCaCertDer() ?: return false
  return try {
   dpm.hasCaCertInstalled(admin, certDer)
  } catch (_: Exception) {
   false
  }
 }

 fun ensureInstalled(context: Context): Boolean {
  if (isCaInstalled(context)) return true
  return installCa(context)
 }

 fun installCa(context: Context): Boolean {
  val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
  val admin = getAdminComponent(context)
  val caMgr = getCaManager(context)
  var certDer = caMgr.getCaCertDer()
  if (certDer == null) {
   caMgr.generateNewCa()
   certDer = caMgr.getCaCertDer() ?: return false
  }
  return try {
   dpm.installCaCert(admin, certDer)
  } catch (_: Exception) {
   false
  }
 }

 fun uninstallCa(context: Context): Boolean {
  val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
  val admin = getAdminComponent(context)
  val certDer = getCaManager(context).getCaCertDer() ?: return false
  return try {
   dpm.uninstallCaCert(admin, certDer)
   true
  } catch (_: Exception) {
   false
  }
 }

 fun resetPoc(context: Context) {
  HttpsInspectionConfig.isEnabled = false
  HttpsInspectionStore.clear()
  uninstallCa(context)
  getCaManager(context).reset()
 }
}

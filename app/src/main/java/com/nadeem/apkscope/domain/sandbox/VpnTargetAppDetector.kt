package com.nadeem.apkscope.domain.sandbox

import android.content.Context
import android.content.pm.PackageManager
import com.nadeem.apkscope.core.model.SandboxSession

object VpnTargetAppDetector {
 fun isVpnTargetApp(context: Context, session: SandboxSession): Boolean {
  return isVpnTargetApp(context, session.packageName, session.personalApkPath)
 }

 fun isVpnTargetApp(context: Context, packageName: String, apkPath: String? = null): Boolean {
  if (packageName.contains("adguard", ignoreCase = true) ||
   packageName.contains("wireguard", ignoreCase = true) ||
   packageName.contains("openvpn", ignoreCase = true) ||
   packageName.endsWith(".vpn", ignoreCase = true) ||
   packageName.contains(".vpn.", ignoreCase = true)
  ) {
   return true
  }
  if (!apkPath.isNullOrEmpty()) {
   try {
    val archive = context.packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_SERVICES)
    val hasVpnService = archive?.services?.any { it.permission == "android.permission.BIND_VPN_SERVICE" } == true
    if (hasVpnService) return true
   } catch (_: Exception) {}
  }
  return false
 }
}

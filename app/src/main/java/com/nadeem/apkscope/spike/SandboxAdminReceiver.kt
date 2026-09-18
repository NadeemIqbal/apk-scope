package com.nadeem.apkscope.spike

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager

class SandboxAdminReceiver : DeviceAdminReceiver() {
    override fun onNetworkLogsAvailable(context: Context, intent: Intent, batchToken: Long, networkLogsCount: Int) {
        val dpm=context.getSystemService(DevicePolicyManager::class.java)
        try {
            val events=dpm.retrieveNetworkLogs(ComponentName(context,SandboxAdminReceiver::class.java),batchToken)
            Evidence.record(context,"onNetworkLogsAvailable","PASS","count=$networkLogsCount")
            com.nadeem.apkscope.sandbox.WorkAndroidEvidenceSink.recordEvents(context, batchToken, events)
            events?.forEach { event ->
                val detail=when(event) {
                    is android.app.admin.DnsEvent -> "package=${event.packageName}; hostname=${event.hostname}; addresses=${event.inetAddresses}; total=${event.totalResolvedAddressCount}; timestamp=${event.timestamp}"
                    is android.app.admin.ConnectEvent -> "package=${event.packageName}; ip=${event.inetAddress}; port=${event.port}; timestamp=${event.timestamp}"
                    else -> "type=${event.javaClass.simpleName}"
                }
                Evidence.record(context,"AndroidEvidence","OBSERVED",detail)
            }
        } catch(e:Exception) { Evidence.record(context,"retrieveNetworkLogs","FAIL",e.toString()) }
    }
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Evidence.record(context, "onProfileProvisioningComplete", "PASS")
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, SandboxAdminReceiver::class.java)
        Evidence.record(context, "isProfileOwnerApp", if (dpm.isProfileOwnerApp(context.packageName)) "PASS" else "FAIL")
        listOf<Pair<String, () -> Unit>>(
            "setProfileName" to { dpm.setProfileName(admin, "APK Scope Spike") },
            "setProfileEnabled" to { dpm.setProfileEnabled(admin) },
            // Checkpoint 5.1, item 4: THE root-cause fix for the Prepare stall — this is the real,
            // automatic callback Android fires at the end of the *supported* provisioning path
            // (`ACTION_PROVISION_MANAGED_PROFILE` → Managed Google Play/clouddpc →
            // here), and until this line existed, cross-profile handoff was **never** configured
            // for any real user who provisioned through that path — only a developer manually
            // tapping the debug-only "Configure Cross Profile Handoff" button in the retained spike
            // surface ever called `Handoff.configure()`. Confirmed on a genuinely fresh, wizard-
            // provisioned Work Profile: `SandboxEnvironmentPreflight.checkEnvironment()` failed
            // closed with `HANDOFF_UNAVAILABLE` ("no cross-profile intent forwarder resolved for
            // ACTION_IMPORT_APK") before this fix, every single time.
            "handoffConfigure" to { com.nadeem.apkscope.core.crossprofile.Handoff.configure(context, admin) },
            // Checkpoint 5.1, item 5's E2E surfaced a second, distinct provisioning gap while
            // verifying the fix above: a genuinely fresh, wizard-provisioned Work Profile applies
            // `UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES` to this profile by default (confirmed
            // via `dumpsys user` listing it under "Device policy restrictions", and via Settings ->
            // Work apps -> APK Scope -> Install unknown apps showing "Disabled by admin" — not
            // just unset, actively blocked, so no user-facing toggle can clear it). Since installing
            // the sandboxed APK on the user's behalf is this app's own core function (not a
            // restriction *of* the sandbox — that's `PolicyEnforcer`'s job, applied to the sandboxed
            // target app, never to this app itself), clearing this one restriction for our own
            // profile-owner app here is the correct one-time fix, not a security regression.
            "clearInstallUnknownSourcesRestriction" to { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) },
            "grantRequestInstallPackages" to {
                dpm.setPermissionGrantState(admin, context.packageName, android.Manifest.permission.REQUEST_INSTALL_PACKAGES, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            },
            "grantPostNotifications" to {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    dpm.setPermissionGrantState(admin, context.packageName, android.Manifest.permission.POST_NOTIFICATIONS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                }
            },
        ).forEach { (name, operation) ->
            try { operation(); Evidence.record(context, name, "PASS") }
            catch (e: Exception) { Evidence.record(context, name, "FAIL", e.toString()) }
        }
    }
}

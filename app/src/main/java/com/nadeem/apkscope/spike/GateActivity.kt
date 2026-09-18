package com.nadeem.apkscope.spike

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.nadeem.apkscope.BuildConfig
import com.nadeem.apkscope.core.common.MAX_APK_TRANSFER_BYTES
import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.crossprofile.Handoff

class GateActivity : Activity() {
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        handleHeadlessTriggers(newIntent)
    }
    private fun handleHeadlessTriggers(i: Intent) {
        if (!BuildConfig.DEBUG) return
        if (!getSystemService(DevicePolicyManager::class.java).isProfileOwnerApp(packageName)) return
        if (i.getBooleanExtra("closeVpn", false)) startForegroundService(Intent(this, GateVpnService::class.java).setAction("CLOSE"))
        if (i.getBooleanExtra("establishForwarding", false)) startForegroundService(
         Intent(this, GateVpnService::class.java).setAction("ESTABLISH_FORWARDING")
          .putExtra("useDnsServer", i.getBooleanExtra("useDnsServer", false)),
        )
        // Checkpoint 5.1, item 11: ordinary (non-raw-socket) DNS-triggering traffic, run from
        // wherever this Activity currently is (Personal or, via CrossProfileApps.startMainActivity,
        // Work) — the exact API surface item 11 asks about (InetAddress/HttpsURLConnection), not the
        // raw UDP/53 socket Checkpoint 5 already proved works.
        if (i.getBooleanExtra("generateOrdinaryDnsTraffic", false)) Thread {
         val outcome = try {
          val c = java.net.URL("https://example.com/").openConnection() as javax.net.ssl.HttpsURLConnection
          c.connectTimeout = 5000; c.readTimeout = 5000
          try { "HTTP ${c.responseCode}" } finally { c.disconnect() }
         } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message}" }
         Evidence.record(this, "dnsExperimentOrdinaryTraffic", "OBSERVED", outcome)
        }.start()
        // Checkpoint 5, item 29: the same headless-trigger idiom above, extended to the *production*
        // com.nadeem.apkscope.sandbox.SandboxVpnService (exported=false, requires BIND_VPN_SERVICE — not
        // directly startable via `adb shell am start-foreground-service`, only from inside this
        // app's own process) — so the forwarding-regression benchmark can establish a real,
        // observation-collection-ON tunnel and compare it against GateVpnService's observation-OFF
        // baseline above, without driving the full Personal-side install/launch UI just to get a
        // tunnel up for a load-test comparison. Never used by the production sandbox flow itself.
        i.getStringExtra("establishProductionForwardingSessionId")?.let { sessionId ->
         startForegroundService(
          Intent(this, com.nadeem.apkscope.sandbox.SandboxVpnService::class.java).setAction(com.nadeem.apkscope.sandbox.SandboxVpnService.ACTION_ESTABLISH)
           .putExtra(com.nadeem.apkscope.sandbox.SandboxVpnService.EXTRA_SESSION_ID, sessionId)
           .putExtra(com.nadeem.apkscope.sandbox.SandboxVpnService.EXTRA_PACKAGE_NAME, i.getStringExtra("establishProductionForwardingPackageName") ?: "com.apksandbox.fixture"),
         )
        }
        if (i.getBooleanExtra("closeProductionVpn", false)) startForegroundService(Intent(this, com.nadeem.apkscope.sandbox.SandboxVpnService::class.java).setAction(com.nadeem.apkscope.sandbox.SandboxVpnService.ACTION_CLOSE))
        i.getStringExtra("unsuspendPackage")?.let { pkg ->
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(this, SandboxAdminReceiver::class.java)
            dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
        }
    }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 90 && result == RESULT_OK && data?.data != null) {
            try {
                val session = java.util.UUID.randomUUID().toString()
                val local = java.io.File(filesDir, "handoff/$session.apk")
                Handoff.copy(this, data.data!!, local, MAX_APK_TRANSFER_BYTES)
                Handoff.send(this, local, CrossProfileContract.ACTION_IMPORT_APK, session, "com.nadeem.apkscope.files")
            } catch (e: Exception) { Evidence.record(this, "APK transfer", "FAIL", e.toString()) }
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val users = getSystemService(UserManager::class.java)
        val supported = packageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)
        val allowed = try { dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE) }
            catch (e: Exception) { Evidence.record(this, "isProvisioningAllowed", "FAIL", e.toString()); false }
        val owner = dpm.isProfileOwnerApp(packageName)
        Evidence.record(this, "FEATURE_MANAGED_USERS", if (supported) "PASS" else "FAIL")
        Evidence.record(this, "isProvisioningAllowed", if (allowed) "PASS" else "FAIL")
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        layout.addView(TextView(this).apply {
            text = "APK Scope — provisioning gate\nAPI ${android.os.Build.VERSION.SDK_INT}\nManaged users: $supported\nProvisioning allowed: $allowed\nProfile Owner: $owner\nCurrent managed profile: ${users.isManagedProfile}\n\nDo not continue if an existing Work Profile is present. Private Space must remain untouched."
        })
        layout.addView(Button(this).apply {
            text = "Start Android Work Profile setup"
            isEnabled = supported && allowed && !owner && !users.isManagedProfile
            setOnClickListener {
                try {
                    Evidence.record(this@GateActivity, "provisioningLaunch", "STARTED")
                    startActivity(Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).putExtra(
                        DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                        ComponentName(this@GateActivity, SandboxAdminReceiver::class.java)))
                } catch (e: Exception) { Evidence.record(this@GateActivity, "provisioningLaunch", "FAIL", e.toString()); isEnabled = false }
            }
        })
        SpikeControls.attach(this, layout, owner)
        setContentView(android.widget.ScrollView(this).apply { addView(layout) })
        handleHeadlessTriggers(intent)
    }
}

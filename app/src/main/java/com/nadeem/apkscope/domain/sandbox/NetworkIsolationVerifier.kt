package com.nadeem.apkscope.domain.sandbox

import android.app.Activity
import android.content.Context
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.sandbox.CrossProfileQueryBridge
import com.nadeem.apkscope.sandbox.SandboxWorkQueryActivity
import org.json.JSONObject

/** Checkpoint 4.1 §9/10: the one fact `launch()` is allowed to trust — a *fresh*, work-side-verified answer to "is the tunnel actually up right now", never a cached policy-configured flag. */
data class NetworkIsolationState(
 val tunnelActive: Boolean,
 val recoveryAttempted: Boolean,
 val packageUnsuspended: Boolean = true,
 /**
  * Phase 9.1: whether the tunnel is proven-scoped to the launching target
  * (`VpnService.Builder.addAllowedApplication` actually succeeded for this exact package), not
  * merely that some tunnel is up. `tunnelActive == true && scoped == false` is a real, distinct
  * state — the VPN exists but currently captures the whole Work Profile, not just this target —
  * and must gate launch on its own, not be inferred from `tunnelActive`.
  */
 val scoped: Boolean = false,
) {
 companion object { val UNKNOWN = NetworkIsolationState(tunnelActive = false, recoveryAttempted = false, packageUnsuspended = false, scoped = false) }
}

/**
 * Checkpoint 4.1 §9: "do not rely only on `session.enforcementResults`... perform a fresh
 * authoritative verification immediately before `LauncherApps.startMainActivity()`." An equivalent
 * clean design to the suggested `suspend fun verify(sessionId: String)` shape — this one takes an
 * [Activity] for the same reason `SandboxSessionCoordinator.prepare`/`end`/`continueInstallation`
 * already do: the cross-profile round trip this needs (`CrossProfileQueryBridge`) requires a real
 * foreground Activity to launch `startActivityForResult` from.
 */
interface NetworkIsolationVerifier {
 suspend fun verify(activity: Activity, sessionId: String, packageName: String? = null): NetworkIsolationState
}

/**
 * Real implementation: launches [SandboxWorkQueryActivity] (`QUERY_TYPE_VERIFY_VPN`) via
 * [CrossProfileQueryBridge] and waits for its real answer. Distinguishes "configured" from
 * "established and forwarding" exactly as item 10 requires — the fact checked here is
 * `SandboxVpnService.isForwardingActive`, a work-side in-process flag only ever set `true` once
 * `ForwardingEngine.start()` has actually run on a real established TUN (see that class's own doc
 * comment) — never merely that `DevicePolicyManager` has an always-on VPN package configured.
 * Fails closed (`UNKNOWN` = not active) on any exception — a failed round trip must never be
 * mistaken for a verified tunnel.
 */
class CrossProfileNetworkIsolationVerifier(private val context: Context) : NetworkIsolationVerifier {
 override suspend fun verify(activity: Activity, sessionId: String, packageName: String?): NetworkIsolationState {
  return try {
   val intent = Handoff.buildQueryIntent(activity, SandboxWorkQueryActivity.QUERY_TYPE_VERIFY_VPN, sessionId, packageName)
   val result = CrossProfileQueryBridge.launchForResult(intent)
   val json = readBoundedResultJson(context, result) ?: return NetworkIsolationState.UNKNOWN
   NetworkIsolationState(
    tunnelActive = json.optBoolean("tunnelActive", false),
    recoveryAttempted = json.optBoolean("recoveryAttempted", false),
    packageUnsuspended = json.optBoolean("packageUnsuspended", true),
    scoped = json.optBoolean("scoped", false),
   )
  } catch (_: Exception) {
   NetworkIsolationState.UNKNOWN
  }
 }
}

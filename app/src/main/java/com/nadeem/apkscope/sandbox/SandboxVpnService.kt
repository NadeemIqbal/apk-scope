package com.nadeem.apkscope.sandbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.nadeem.apkscope.core.model.NetworkObservation
import com.nadeem.apkscope.core.model.NetworkObservationSink
import com.nadeem.apkscope.core.model.RuntimeObservationSummary
import com.nadeem.apkscope.core.network.DestinationPolicy
import com.nadeem.apkscope.core.network.EngineLimits
import com.nadeem.apkscope.core.network.ForwardingEngine
import com.nadeem.apkscope.core.report.NetworkObservationLog
import java.time.Instant

/**
 * Checkpoint 4, item 6/25: the production always-on lockdown VPN — runs only in the Work profile
 * process (the profile owner assigns it via `DevicePolicyManager.setAlwaysOnVpnPackage`, and a
 * `VpnService` established by a secondary-user process intercepts traffic for that user only, the
 * same real Android multi-user networking behavior the spike's `GateVpnService` already validated).
 *
 * Deliberately **not** a rewrite of the spike's `GateVpnService` — it reuses the exact same
 * `core:network` building blocks (`ForwardingEngine`, `EngineLimits`, `DestinationPolicy`,
 * `core:report`'s `NetworkObservationLog`) unchanged, per the explicit "the forwarding engine is
 * already validated, do not rewrite it" instruction. What's new here is only the production
 * lifecycle: a single `ESTABLISH_FORWARDING`/`CLOSE` action pair (no Spike A "no forwarding" test
 * mode), and [isForwardingActive] — a `@Volatile` flag `SandboxWorkerActivity`'s prepare sequence
 * polls to confirm the tunnel is actually up before ever reporting network isolation as active
 * (item 6's fail-closed invariant: nothing may claim `networkIsolationActive` from merely having
 * *started* this service — only from this flag actually turning true).
 */
class SandboxVpnService : VpnService() {
 private var tun: ParcelFileDescriptor? = null
 private var forwarding: ForwardingEngine? = null
 private var observationSink: WorkNetworkObservationSink? = null
 private var sessionStartedAt: Instant? = null
 private val forwardingTunAddress = byteArrayOf(10, 124, 0, 1)
 private val networkObservationLog by lazy { NetworkObservationLog(this) }

 companion object {
  const val ACTION_ESTABLISH = "com.nadeem.apkscope.sandbox.action.ESTABLISH"
  const val ACTION_CLOSE = "com.nadeem.apkscope.sandbox.action.CLOSE"
  /** Checkpoint 5, item 4: extras carried on [ACTION_ESTABLISH] so the new per-session [WorkNetworkObservationSink] can be constructed with the session it belongs to — never inferred after the fact. */
  const val EXTRA_SESSION_ID = "com.nadeem.apkscope.sandbox.extra.SESSION_ID"
  const val EXTRA_PACKAGE_NAME = "com.nadeem.apkscope.sandbox.extra.PACKAGE_NAME"
  private const val CHANNEL_ID = "sandbox-vpn"
  private const val NOTIFICATION_ID = 43

  /** Set only once `ForwardingEngine.start()` has actually been called on a real, established TUN — never merely once the service has been asked to start (item 6). */
  @Volatile var isForwardingActive: Boolean = false
   private set

  /** Checkpoint 5, item 4: the session the currently-running (or most recently established) tunnel belongs to — set on [ACTION_ESTABLISH], read by [WorkNetworkObservationSink]'s construction. Not itself used to attribute observations (the sink instance already carries its own sessionId); exposed only so other components can confirm which session is live. */
  @Volatile var activeSessionId: String? = null
   private set

  /** Companion to [activeSessionId] — the expected target package for the active session (item 4), exposed for the Work Live Monitor UI header (item 12) without a separate `WorkEvidenceStore` lookup. Established at forwarding-engine start, never inferred later. */
  @Volatile var activePackageName: String? = null
   private set

  /** Companion to [activeSessionId] — real session start time (item 12's header elapsed-time display), never a fabricated/estimated one. */
  @Volatile var activeSessionStartedAt: Instant? = null
   private set

  /**
   * Phase 9.1 (capture ownership): the package this tunnel's [Builder.addAllowedApplication] call
   * actually succeeded for, or null if the tunnel is currently unscoped (whole-Work-Profile
   * capture). Distinct from [activePackageName] — that is the *intended* target from session
   * attribution; this is the *proven* Android-enforced routing scope, which can lag behind intent
   * during the real window between VPN establish (part of Prepare Sequence, before the target is
   * installed — `addAllowedApplication` requires the package to already resolve for this user, per
   * https://developer.android.com/reference/android/net/VpnService.Builder#addAllowedApplication(java.lang.String))
   * and the later point where the target is actually installed and about to launch. Callers that
   * need a real ownership guarantee (not just "a session is attributed") must check this against
   * the package they intend to launch, not merely that a tunnel is active.
   */
  @Volatile var scopedPackageName: String? = null
   private set

  /**
   * Checkpoint 5, item 17: null while a session is active or before any session has run; set
   * exactly once `closeAll()` has fully stopped the engine, closed+flushed the observation sink,
   * and computed+persisted the summary — the ordering [SandboxWorkerService.runEndSessionSequence]
   * polls for after triggering [ACTION_CLOSE]. Cleared at the *start* of the next [ACTION_ESTABLISH]
   * (not at the end of the previous session) so a summary is never visible before its own session
   * has actually finished, and never mistaken for a still-stale prior session's result.
   */
  @Volatile var lastClosedSummary: RuntimeObservationSummary? = null
   private set
 }

 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  val manager = getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sandbox network isolation", NotificationManager.IMPORTANCE_LOW))
  // Required within a few ms of service start — updated with real session identity below, once
  // (if) ACTION_ESTABLISH actually succeeds; this generic notification is the honest interim state.
  startForeground(NOTIFICATION_ID, buildNotification(null))

  when (intent?.action) {
   // Checkpoint 5.3: leave the idle baseline this service's own doc comment promises — closeAll()
   // alone reset every field but never actually exited the foreground/started state, so the
   // "network isolation active" notification (item 6's misleading-idle-state finding) lingered
   // indefinitely after every session that ended this way. Actually stopping foreground + the
   // service itself here is what makes "no active runtime session" also mean "no lingering
   // foreground service", matching Checkpoint 5.3 item 6's idle-baseline requirement.
   ACTION_CLOSE -> { closeAll(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
   ACTION_ESTABLISH -> {
    closeAll()
    // Checkpoint 5, item 17: cleared at the start of this new session, not the end of the last one —
    // a poller must never observe a summary from N-1 while session N is still spinning up.
    lastClosedSummary = null
    val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
    val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
    val consentIntent = prepare(this)
    if (consentIntent != null) {
     // Consent has not been granted for this app/profile yet — fail closed rather than proceed
     // without a real tunnel (item 6). The coordinator surfaces this as NETWORK_ISOLATION_UNAVAILABLE.
     isForwardingActive = false
     return START_NOT_STICKY
    }
    val localSubnets = currentLocalSubnets()
    val builder = Builder().setSession("APK Scope network isolation").setMtu(1500)
     .addAddress("10.124.0.1", 32).addRoute("0.0.0.0", 0)
     .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
    // Phase 9.1 (capture ownership): attempted scoping the tunnel to just the target package via
    // `Builder.addAllowedApplication(packageName)`, intending to stop this VPN from capturing (and
    // this session from attributing) every app sharing the Work Profile user, not just the target.
    // DISABLED after on-device verification (physical Pixel 8, 2026-09-12): with
    // `addAllowedApplication` active, `builder.establish()` still returned a non-null tun (no
    // exception surfaced anywhere — confirmed via full logcat of the establishing process, no
    // NameNotFoundException or other exception ever logged), but the *allowed* target package lost
    // all DNS resolution — every fixture request failed with
    // `UnknownHostException: Unable to resolve host ... No address associated with hostname`,
    // reproduced identically across multiple distinct hosts (jsonplaceholder.typicode.com,
    // dummyjson.com) and confirmed NOT transient by clearing and retrying. This VPN's tunnel is
    // established alongside `DevicePolicyManager.setAlwaysOnVpnPackage(..., lockdownEnabled = true)`
    // (see SandboxWorkerService.runPrepareSequence's `enableAlwaysOnVpnLockdown` call) — the working
    // hypothesis is that combination doesn't compose the way `Builder.addAllowedApplication`'s own
    // documentation describes in isolation, though the exact mechanism is not yet root-caused.
    // Restoring the proven-working unscoped tunnel here rather than shipping a mechanism that
    // silently breaks the product's actual capture path. `scopedPackageName` stays permanently null
    // until this is either fixed or a different scoping mechanism is verified — every reader of that
    // field (verifyVpn's rescope check, NetworkIsolationState.scoped,
    // SandboxSessionCoordinator.launch's fail-closed gate) was written to already treat "not scoped"
    // as a real, valid, non-fatal-by-default state pending this decision — see MS9-CAP01 in
    // REQUIREMENTS.md and this date's STATE.md entry for the full reproduction.
    // try { builder.addAllowedApplication(packageName!!) } catch (e: Exception) { ... } — see above.
    // Phase 9.1 investigation (2026-09-12): pure diagnostic logging, no behavior change — see
    // logPhase91IdentityDiagnostics's own doc comment. Tests hypothesis 2 (wrong-user package/UID
    // resolution) from STATE.md's "Phase 9.1 attempt" section without re-enabling the disabled
    // addAllowedApplication call above.
    logPhase91IdentityDiagnostics(packageName)
    val builtTun = builder.establish()
    if (builtTun == null) {
     isForwardingActive = false
    } else {
     scopedPackageName = null // see the disabled-scoping note above `builder.establish()`
     tun = builtTun
     val startedAt = Instant.now()
     sessionStartedAt = startedAt
     activeSessionId = sessionId
     activePackageName = packageName
     activeSessionStartedAt = startedAt
     // Checkpoint 5, item 3/4: a brand-new sink scoped to this session only, fanned out alongside
     // (never replacing) the existing diagnostic NetworkObservationLog — see WorkNetworkObservationSink's
     // doc comment for why a new instance per session, never a shared/reused one, is what makes
     // item 4's "session A's events never leak into session B" true structurally rather than by convention.
     val workSink = if (sessionId != null) WorkNetworkObservationSink(this, sessionId) else null
     observationSink = workSink
     val fanOut = NetworkObservationSink { obs: NetworkObservation ->
      networkObservationLog.onObservation(obs)
      workSink?.onObservation(obs)
     }
     if (packageName != null) {
      com.nadeem.apkscope.core.network.https.HttpsInspectionConfig.targetPackage = packageName
     }
     // TEMP DIAGNOSTIC (Checkpoint 8.9 acceptance scenario — not for commit): enable the existing
     // dormant per-connection lifecycle trace so a single request's actual path through
     // TcpProxy/HttpsInspectionEngine can be observed via logcat tag "ConnDiag".
     com.nadeem.apkscope.core.network.ConnDiag.enabled = true
     val engine = ForwardingEngine(
      this, builtTun, forwardingTunAddress,
      limits = EngineLimits.DEFAULT,
      localSubnets = localSubnets,
      observationSink = fanOut,
      caStorageDir = java.io.File(filesDir, "poc_ca"),
      onEvidenceCb = { _, _, _ -> },
      sessionId = sessionId,
      targetPackage = packageName
     )
     forwarding = engine
     engine.start()
     isForwardingActive = true
     // Item 11/33: once a real session is actually up, the persistent notification names it and
     // opens the real monitor on tap — never before forwarding has genuinely started (item 6).
     manager.notify(NOTIFICATION_ID, buildNotification(packageName))
    }
   }
  }
  return START_NOT_STICKY
 }

 /**
  * Item 11/33: this app's own foreground-service notification doubles as the "persistent Work
  * Profile notification" item 11 explicitly accepts as the Live Monitor's entry point — no separate
  * notification channel/id is created. [packageName] null means "no session established yet/right
  * now" (the generic wording); non-null uses item 11's exact "Monitoring `<App>`" copy and a tap
  * target of this app's own launcher activity in the Work profile (opening the app fresh here always
  * lands on the real Live Monitor — see `MainActivity`'s Work-profile branch — never a fabricated
  * "reading your encrypted traffic" claim, per item 33's privacy wording constraint).
  */
 private fun buildNotification(packageName: String?): Notification {
  val contentIntent = packageManager.getLaunchIntentForPackage(this.packageName)?.let {
   PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
  val builder = Notification.Builder(this, CHANNEL_ID)
   .setSmallIcon(android.R.drawable.ic_lock_lock)
   .setContentTitle("APK Scope · Sandbox")
   .setSubText("Sandbox")
  return if (packageName != null) {
   builder
    .setContentText("Sandbox session active · Monitoring network activity")
    .setStyle(Notification.BigTextStyle().bigText("$packageName\nMonitoring network activity"))
    .setContentIntent(contentIntent)
    .build()
  } else {
   builder
    .setContentText("Sandbox session active")
    .setStyle(Notification.BigTextStyle().bigText("Monitoring network activity in isolated Sandbox profile."))
    .setContentIntent(contentIntent)
    .build()
  }
 }

 /**
  * Phase 9.1 investigation diagnostic (2026-09-12) — **not production behavior**. Pure logging, no
  * change to `Builder`/tunnel state, no re-enabling of the disabled `addAllowedApplication` call.
  * Collects the exact facts needed to test hypothesis 2 ("wrong-user package/UID resolution inside
  * the scoping call") from `.planning/STATE.md`'s "Phase 9.1 attempt" section: this process's own
  * user identity, the target package's resolved UID/user (if resolvable at all right now, before
  * install completes it may not be — that itself is informative), and the live
  * always-on-VPN/lockdown policy state read back from `DevicePolicyManager` (not assumed from what
  * this process asked for earlier). Safe to remove once the Phase 9.1 investigation concludes —
  * logcat tag `Phase91Diag`.
  */
 private fun logPhase91IdentityDiagnostics(targetPackageName: String?) {
  val tag = "Phase91Diag"
  try {
   val myUid = android.os.Process.myUid()
   val myUserHandle = android.os.Process.myUserHandle()
   android.util.Log.i(tag, "processUid=$myUid processUserHandle=$myUserHandle callingContextPackage=${this.packageName}")
   if (targetPackageName.isNullOrEmpty()) {
    android.util.Log.w(tag, "targetPackageName=null-or-empty — nothing to resolve")
   } else {
    val resolvedUid = try {
     packageManager.getPackageUid(targetPackageName, 0)
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
     android.util.Log.w(tag, "targetPackage=$targetPackageName NOT resolvable via getPackageUid in this process's user right now: ${e.message}")
     null
    }
    if (resolvedUid != null) {
     val resolvedUserHandle = android.os.UserHandle.getUserHandleForUid(resolvedUid)
     android.util.Log.i(tag, "targetPackage=$targetPackageName resolvedUid=$resolvedUid resolvedUserHandle=$resolvedUserHandle sameUserAsThisProcess=${resolvedUserHandle == myUserHandle}")
    }
    val installed = try { packageManager.getApplicationInfo(targetPackageName, 0); true } catch (e: Exception) { false }
    android.util.Log.i(tag, "targetPackage=$targetPackageName installedInThisProfile=$installed")
   }
   val admin = android.content.ComponentName(this, com.nadeem.apkscope.spike.SandboxAdminReceiver::class.java)
   val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
   if (dpm == null) {
    android.util.Log.w(tag, "DevicePolicyManager unavailable — cannot read always-on/lockdown state")
   } else {
    val alwaysOnPkg = try { dpm.getAlwaysOnVpnPackage(admin) } catch (e: Exception) { "ERROR:${e.message}" }
    val lockdown = try { dpm.isAlwaysOnVpnLockdownEnabled(admin) } catch (e: Exception) { "ERROR:${e.message}" }
    android.util.Log.i(tag, "alwaysOnVpnPackage=$alwaysOnPkg lockdownEnabled=$lockdown (read back from DevicePolicyManager, not assumed)")
   }
   android.util.Log.i(tag, "builderAllowlistInputs=NONE (addAllowedApplication is currently disabled — see the note above this call site)")
  } catch (e: Exception) {
   android.util.Log.e(tag, "diagnostic collection itself failed: ${e.message}", e)
  }
 }

 private fun currentLocalSubnets(): List<DestinationPolicy.LocalSubnet> = try {
  val cm = getSystemService(ConnectivityManager::class.java)
  val network = cm.activeNetwork ?: return emptyList()
  val props = cm.getLinkProperties(network) ?: return emptyList()
  props.linkAddresses.mapNotNull { la -> la.address.address.takeIf { it.size == 4 }?.let { DestinationPolicy.LocalSubnet(it, la.prefixLength) } }
 } catch (_: Exception) { emptyList() }

 /**
  * Checkpoint 5, item 17's required ordering: stop forwarding first (no more observations can be
  * produced once `engine.stop()` has returned), only *then* close+flush the observation sink
  * (drains whatever forwarding already handed it), only *then* compute+persist the summary — and
  * only once all of that is done does [lastClosedSummary] get set, which is the sole signal
  * `SandboxWorkerService.runEndSessionSequence()` polls on.
  */
 private fun closeAll() {
  isForwardingActive = false
  val engine = forwarding
  if (engine != null) { forwarding = null; engine.stop(); tun = null } else { tun?.close(); tun = null }
  val sink = observationSink
  val startedAt = sessionStartedAt
  observationSink = null
  sessionStartedAt = null
  // Item 11: a Work-side reader (the Live Monitor entry point/notification) must see "no active
  // session" the instant a session actually ends, not the just-ended session's stale identity.
  activeSessionId = null
  activePackageName = null
  activeSessionStartedAt = null
  scopedPackageName = null
  if (sink != null && startedAt != null) {
   lastClosedSummary = sink.closeAndFinalize(startedAt, Instant.now())
  }
 }

 override fun onDestroy() { closeAll(); super.onDestroy() }
 override fun onRevoke() { closeAll(); stopSelf() }
}

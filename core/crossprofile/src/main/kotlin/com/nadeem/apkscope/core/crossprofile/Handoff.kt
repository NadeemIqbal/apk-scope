package com.nadeem.apkscope.core.crossprofile

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.nadeem.apkscope.core.common.BoundedCopy
import com.nadeem.apkscope.core.common.MAX_APK_TRANSFER_BYTES
import java.io.File
import java.util.UUID

/**
 * Cross-profile action/extra names — validated end-to-end (both directions: personal→work APK
 * import, work→personal report handoff) on the Physical Pixel 8 Validation Gate. See
 * `PIXEL8_PHYSICAL_VALIDATION.md`.
 *
 * Checkpoint 4 adds four more, following the exact same proven pattern (a small file sent via
 * [Handoff.send], never a bare-extras cross-profile `Intent` — that path has never been validated
 * in this codebase, so it is not used here). [ACTION_SANDBOX_IMPORT_APK] is a **deliberately
 * separate** action from [ACTION_IMPORT_APK] — both carry an APK personal→work, but
 * [ACTION_IMPORT_APK] is already registered, in the app's manifest, to the spike's
 * `SpikeImportActivity`; giving the production flow the exact same action would make two activities
 * match the identical action+data-type filter and force Android to show a disambiguation chooser
 * instead of delivering automatically (item 25's "production must not route through/collide with
 * the retained spike surface"). [ACTION_END_SESSION], [ACTION_CONTINUE_INSTALL], and
 * [ACTION_REINSTALL] (personal→work,
 * same direction) each carry a tiny JSON control file identifying which session to act on;
 * [ACTION_SANDBOX_READY]/[ACTION_SANDBOX_ERROR] (work→personal, same direction as
 * [ACTION_REPORT_READY]) — declared since checkpoint 1 but never actually registered in
 * [Handoff.configure] until now — carry a small JSON status report the personal-side
 * `SandboxSessionCoordinator` applies to its own Room row.
 */
object CrossProfileContract {
    const val ACTION_IMPORT_APK = "com.nadeem.apkscope.action.IMPORT_APK"
    const val ACTION_SANDBOX_IMPORT_APK = "com.nadeem.apkscope.action.SANDBOX_IMPORT_APK"
    const val ACTION_PATCHED_APK_INSTALL = "com.nadeem.apkscope.action.PATCHED_APK_INSTALL"
    const val ACTION_SANDBOX_READY = "com.nadeem.apkscope.action.SANDBOX_READY"
    const val ACTION_REPORT_READY = "com.nadeem.apkscope.action.REPORT_READY"
    const val ACTION_SANDBOX_ERROR = "com.nadeem.apkscope.action.SANDBOX_ERROR"
    /** User-selected Work storage items, handed to the Personal profile for Downloads export. */
    const val ACTION_STORAGE_EXPORT = "com.nadeem.apkscope.action.STORAGE_EXPORT"
    const val ACTION_END_SESSION = "com.nadeem.apkscope.action.END_SESSION"
    const val ACTION_CONTINUE_INSTALL = "com.nadeem.apkscope.action.CONTINUE_INSTALL"
    const val ACTION_REINSTALL = "com.nadeem.apkscope.action.REINSTALL"
    /**
     * Checkpoint 4.1: the one personal→work **query** action, always launched with
     * `startActivityForResult` from a foreground Personal Activity — never pushed from a background
     * Work process. Replaces "Work tries to force-open Personal" with "Personal asks Work, while
     * Personal is already visible" for every non-interactive fact (see `V0.1_CHECKPOINT_4_1.md`
     * §"Cross-profile transport decision"). Distinct from [ACTION_SANDBOX_IMPORT_APK]/
     * [ACTION_END_SESSION]/[ACTION_CONTINUE_INSTALL], which are one-way personal→work triggers with
     * no result; this one's whole purpose is the result.
     */
    const val ACTION_WORK_QUERY = "com.nadeem.apkscope.action.WORK_QUERY"
    /**
     * Checkpoint 5, item 24: personal→work, one-way, fire-and-forget — sent only once Personal has
     * already durably persisted an imported runtime-observation artifact, telling Work it may now
     * delete its own exported copy (and, per item 25's retention policy, the underlying Room
     * observation rows too). Uses the same push-from-a-foreground-user-action shape as
     * [ACTION_END_SESSION]/[ACTION_CONTINUE_INSTALL] (both already reliable in practice — every
     * push that has actually been lost to background-activity-launch restrictions in this codebase
     * was one fired from a *background* Work process, never one fired from a live Personal
     * foreground action) rather than [ACTION_WORK_QUERY]'s request/result shape, since no answer is
     * needed: if this is lost, the artifact simply stays available for Personal to re-import and
     * re-acknowledge later (item 24's own explicit tolerance for a lost ack).
     */
    const val ACTION_ACK_RUNTIME_ARTIFACT = "com.nadeem.apkscope.action.ACK_RUNTIME_ARTIFACT"
    /** Checkpoint 6: personal→work fire-and-forget acknowledgement for AndroidEvidenceArtifact. */
    const val ACTION_ACK_ANDROID_EVIDENCE = "com.nadeem.apkscope.action.ACK_ANDROID_EVIDENCE"
    const val SESSION_ID = "sessionId"
    const val PACKAGE_NAME = "packageName"
    const val CONTENT_URI = "contentUri"
    const val ERROR_CODE = "errorCode"
    const val ERROR_MESSAGE = "errorMessage"
    /** [ACTION_WORK_QUERY] extra: which question is being asked — see `SandboxWorkQueryActivity`. */
    const val QUERY_TYPE = "queryType"
    const val QUERY_TYPE_REQUEST_UNINSTALL = "REQUEST_UNINSTALL"
    const val QUERY_TYPE_CHECK_PROFILE_OWNER = "CHECK_PROFILE_OWNER"
    const val QUERY_TYPE_DESTROY_WORK_PROFILE = "DESTROY_WORK_PROFILE"
    /** Milestone 9 (Pixel 8 acceptance, fifth pass, item 2): an opaque id minted fresh per import/query attempt, carried alongside [SESSION_ID] purely so [UrlEvidencePipelineDiagnostics] can correlate the Personal- and Work-side halves of one specific attempt after the fact — never used for any routing/authorization decision. */
    const val OPERATION_ID = "operationId"
    /** Maximum compressed Work-to-Personal selected-storage archive (the uncompressed payload is capped at 200 MiB). */
    const val MAX_STORAGE_EXPORT_TRANSFER_BYTES = 202L * 1024 * 1024
}

/**
 * Promoted from the spike's `com.nadeem.apkscope.spike.Handoff` unchanged in behavior. Two signature
 * changes were required by the module boundary itself (not a rewrite of the underlying logic):
 * [configure] now takes [admin] as a parameter instead of hardcoding `SandboxAdminReceiver` (an
 * app-specific class this module must not depend on), and [ImportActivity] looks up the host app's
 * own launcher activity via [PackageManager.getLaunchIntentForPackage] instead of hardcoding
 * `GateActivity` for the same reason.
 */
object Handoff {
 const val MIME="application/octet-stream"
 fun configure(context:Context, admin: ComponentName) {
  val dpm=context.getSystemService(DevicePolicyManager::class.java)
  check(dpm.isProfileOwnerApp(context.packageName))
  HandoffDiagnostics.log("configure_started package=${context.packageName}")
  dpm.clearCrossProfileIntentFilters(admin)
  HandoffDiagnostics.log("configure_cleared_existing_filters package=${context.packageName}")
  listOf(
   CrossProfileContract.ACTION_IMPORT_APK to DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
   CrossProfileContract.ACTION_REPORT_READY to DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED,
   // Checkpoint 4 additions — same two directions, same proven flag pattern (see this file's doc comment).
	   CrossProfileContract.ACTION_SANDBOX_IMPORT_APK to DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
	   CrossProfileContract.ACTION_PATCHED_APK_INSTALL to DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
	   CrossProfileContract.ACTION_END_SESSION to DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
   CrossProfileContract.ACTION_CONTINUE_INSTALL to DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
   CrossProfileContract.ACTION_REINSTALL to DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
   CrossProfileContract.ACTION_SANDBOX_READY to DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED,
   CrossProfileContract.ACTION_SANDBOX_ERROR to DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED,
   CrossProfileContract.ACTION_STORAGE_EXPORT to DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED,
   // Checkpoint 4.1: personal→work query/result round trip (see CrossProfileContract.ACTION_WORK_QUERY).
   CrossProfileContract.ACTION_WORK_QUERY to DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
   // Checkpoint 5, item 24: personal→work fire-and-forget artifact ack (see CrossProfileContract.ACTION_ACK_RUNTIME_ARTIFACT).
   CrossProfileContract.ACTION_ACK_RUNTIME_ARTIFACT to DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
   // Checkpoint 6: personal→work fire-and-forget android evidence ack (see CrossProfileContract.ACTION_ACK_ANDROID_EVIDENCE).
   CrossProfileContract.ACTION_ACK_ANDROID_EVIDENCE to DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT,
  ).forEach { (action, direction) ->
    val filter=IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataType(MIME) }
    dpm.addCrossProfileIntentFilter(admin, filter, direction)
    HandoffDiagnostics.log("configure_filter_added action=$action direction=$direction")
  }
  HandoffDiagnostics.log("configure_finished package=${context.packageName}")
 }

 /**
  * Item 4's "cross-profile handoff available" preflight check, callable from the **personal**
  * profile without profile-owner authority — [configure] itself can only ever run work-side (it
  * requires profile ownership), so this is how a personal-side coordinator finds out whether that
  * one-time setup already happened. Looks for the same real system forwarder [send] itself selects
  * — if it resolves, the intent filters [configure] would have registered are in place.
  */
 fun isConfigured(context: Context, action: String = CrossProfileContract.ACTION_SANDBOX_IMPORT_APK): Boolean {
  val probe = Intent(action).setType(MIME).addCategory(Intent.CATEGORY_DEFAULT)
  val matches = context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
  return matches.any { it.activityInfo.packageName == "android" && it.activityInfo.name.contains("ForwardIntentTo") }
 }
 fun copy(context:Context,uri:Uri,dest:File,limit:Long) {
  require(uri.scheme=="content")
  dest.parentFile!!.mkdirs()
  try {
   context.contentResolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { out ->
    BoundedCopy.copy(input,out,limit)
   } }
  } catch(e:Exception) { dest.delete(); throw e }
 }
 /**
  * Checkpoint 4 widens this from `Activity` to `Context` (a safe, backward-compatible widening —
  * every existing call site already passes an `Activity`, which *is* a `Context`, so nothing at
  * those call sites changes) specifically so the work-side lifecycle sequence — which must run
  * from a `Service`, not an `Activity`, since it can outlive the transient relay Activity that
  * triggered it — can send its status reports back through this exact same validated path, rather
  * than duplicating the forwarder-lookup logic for a service caller.
  *
  * Checkpoint 5.1, item 4's root-cause fix: [Intent.FLAG_ACTIVITY_NEW_TASK] is now only added when
  * [context] is **not** already an `Activity` — this file's own prior doc comment claimed adding it
  * unconditionally was "a no-op when the caller already is an Activity with its own task", but that
  * assumption was never actually verified on-device and turned out to be false on a genuinely fresh,
  * wizard-provisioned Work Profile (API 37): forcing a *foreground Activity's own* call through
  * `FLAG_ACTIVITY_NEW_TASK` made the system spin up a **second, separate, `android`-owned task**
  * for `ForwardIntentToManagedProfile` — confirmed via `dumpsys activity activities` showing that
  * task stuck at `visible=false / visibleRequested=false / app=null / state=INITIALIZING` forever,
  * while the one already-working cross-profile call in this codebase (`ACTION_WORK_QUERY`, which
  * never sets this flag) stayed in the *existing* foreground task and forwarded correctly in the
  * same log window. The flag is still added for the `SandboxWorkerService` (non-`Activity`) caller,
  * exactly as the original comment intended for that case.
  */
 fun send(context:Context,file:File,action:String,session:String, fileProviderAuthority: String) {
  // Checkpoint 5.1, item 2: handoff-lifecycle diagnostics — kept permanently (low-noise, one line
  // per real send), since a cross-profile push that silently resolves nowhere (this exact class of
  // bug — see HandoffDiagnostics's own doc comment) is otherwise invisible until a session stalls
  // minutes later with no error attached anywhere.
  HandoffDiagnostics.log("dispatch_attempted action=$action session=$session file=${file.name} exists=${file.exists()} bytes=${file.length()}")
  val uri=FileProvider.getUriForFile(context,fileProviderAuthority,file)
  HandoffDiagnostics.log("file_provider_uri_created action=$action session=$session uri=$uri")
  val intent=Intent(action).setDataAndType(uri,MIME).addCategory(Intent.CATEGORY_DEFAULT)
   .putExtra(CrossProfileContract.SESSION_ID,session)
   .putExtra(CrossProfileContract.CONTENT_URI,uri.toString())
   .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  intent.clipData=ClipData.newRawUri("APK Scope handoff",uri)
  val resolved = resolveForwarder(context, intent)
  try {
   context.startActivity(resolved)
   HandoffDiagnostics.log("dispatch_returned action=$action session=$session result=started")
  } catch (e: Exception) {
   HandoffDiagnostics.log("dispatch_returned action=$action session=$session result=threw detail=$e")
   throw e
  }
 }

 /** Explicitly select Android's cross-profile forwarder for [intent], not a same-profile receiver — the one piece of forwarder-lookup logic every cross-profile send in this codebase shares. */
 fun resolveForwarder(context: Context, intent: Intent): Intent {
  val matches=context.packageManager.queryIntentActivities(intent,PackageManager.MATCH_DEFAULT_ONLY)
  // Diagnostic note (item 3): this generic "android/...ForwardIntentTo..." component resolving here
  // proves only that cross-profile forwarding infrastructure exists on the device at all — NOT that
  // `DevicePolicyManager.addCrossProfileIntentFilter` was ever actually called for this specific
  // action+direction. An intent can resolve to this exact component and still be silently dropped
  // by the OS on the other side if no matching filter was registered — this is the single fact that
  // made the Checkpoint 5 Prepare stall so hard to see: nothing here throws when that's the case.
  val forwarder=matches.firstOrNull { it.activityInfo.packageName=="android" && it.activityInfo.name.contains("ForwardIntentTo") }
  HandoffDiagnostics.log("forwarder_resolved action=${intent.action} candidates=${matches.size} forwarder=${forwarder?.activityInfo?.name ?: "NONE"}")
  if (forwarder == null) error("Android cross-profile activity forwarder unavailable")
  intent.component=ComponentName(forwarder.activityInfo.packageName,forwarder.activityInfo.name)
  return intent
 }

 /**
  * Checkpoint 4.1: builds the [CrossProfileContract.ACTION_WORK_QUERY] request intent for
  * `startActivityForResult` — deliberately carries **no file/URI of its own** (this is a question,
  * not a payload); the *answer* comes back as the `ActivityResult`'s data Intent, whose URI (if any)
  * the caller must copy with a bounded-size copy before the grant can be assumed to still be valid.
  * No `FLAG_ACTIVITY_NEW_TASK` — this must run in the calling Activity's own task so the OS can
  * route the result back to it.
  */
 fun buildQueryIntent(context: Context, queryType: String, sessionId: String, packageName: String? = null, operationId: String? = null): Intent {
  val intent = Intent(CrossProfileContract.ACTION_WORK_QUERY).setType(MIME).addCategory(Intent.CATEGORY_DEFAULT)
   .putExtra(CrossProfileContract.SESSION_ID, sessionId)
   .putExtra(CrossProfileContract.QUERY_TYPE, queryType)
  if (!packageName.isNullOrEmpty()) {
   intent.putExtra(CrossProfileContract.PACKAGE_NAME, packageName)
  }
  if (!operationId.isNullOrEmpty()) {
   intent.putExtra(CrossProfileContract.OPERATION_ID, operationId)
  }
  return resolveForwarder(context, intent)
 }
}

/**
 * Authority passed by the app's `FileProvider` — kept as a parameter (see [Handoff.send]) rather
 * than hardcoded, since this module has no fixed applicationId of its own.
 *
 * Checkpoint 4 broadens which actions are legal on which side: [CrossProfileContract.ACTION_END_SESSION]/
 * [CrossProfileContract.ACTION_CONTINUE_INSTALL]/[CrossProfileContract.ACTION_REINSTALL] join [CrossProfileContract.ACTION_IMPORT_APK] as
 * work-side actions (a small JSON control file, not an APK, for the two new ones), and
 * [CrossProfileContract.ACTION_SANDBOX_READY]/[CrossProfileContract.ACTION_SANDBOX_ERROR] join
 * [CrossProfileContract.ACTION_REPORT_READY] as personal-side actions (all three are small JSON
 * status reports) — every action still goes through the exact same copy-then-callback shape.
 * [onImportComplete] now also receives the triggering [action] itself, since "work-side" alone no
 * longer identifies a single meaning once there are three different work-side actions.
 */
open class ImportActivity:Activity() {
 private val workActions = setOf(CrossProfileContract.ACTION_IMPORT_APK, CrossProfileContract.ACTION_SANDBOX_IMPORT_APK, CrossProfileContract.ACTION_PATCHED_APK_INSTALL, CrossProfileContract.ACTION_END_SESSION, CrossProfileContract.ACTION_CONTINUE_INSTALL, CrossProfileContract.ACTION_REINSTALL, CrossProfileContract.ACTION_ACK_RUNTIME_ARTIFACT, CrossProfileContract.ACTION_ACK_ANDROID_EVIDENCE)
 private val personalActions = setOf(CrossProfileContract.ACTION_REPORT_READY, CrossProfileContract.ACTION_SANDBOX_READY, CrossProfileContract.ACTION_SANDBOX_ERROR, CrossProfileContract.ACTION_STORAGE_EXPORT)
 private val apkActions = setOf(CrossProfileContract.ACTION_IMPORT_APK, CrossProfileContract.ACTION_SANDBOX_IMPORT_APK, CrossProfileContract.ACTION_PATCHED_APK_INSTALL)

 override fun onCreate(s:android.os.Bundle?) {
  super.onCreate(s)
  // Checkpoint 5.1, item 2: the Work-side half of the handoff-lifecycle trace — this is the one
  // place that proves whether the cross-profile push actually arrived at all, independent of
  // whatever [Handoff.send] logged on the way out.
  HandoffDiagnostics.log("import_activity_created action=${intent.action} hasData=${intent.data != null} hasSessionExtra=${intent.hasExtra(CrossProfileContract.SESSION_ID)}")
  // Captured outside the try so a failure *after* parsing the session id (e.g. the copy itself
  // failing — the exact "APK too large" bug this field's introduction fixes) still lets
  // [onImportFailed] attribute the failure to a real session and report it back, instead of the
  // failure being attributable to nothing and silently swallowed.
  var session: String? = null
  try {
   val action=requireNotNull(intent.action)
   val work=getSystemService(DevicePolicyManager::class.java).isProfileOwnerApp(packageName)
   HandoffDiagnostics.log("import_activity_intent_received action=$action work=$work sessionIdExtra=${intent.getStringExtra(CrossProfileContract.SESSION_ID)}")
   require((action in workActions && work) || (action in personalActions && !work))
   session = UUID.fromString(intent.getStringExtra(CrossProfileContract.SESSION_ID)).toString()
   val uri=requireNotNull(intent.data)
   require(uri.authority?.substringAfter('@')?.endsWith(".files") == true)
   HandoffDiagnostics.log("import_activity_uri_validated action=$action session=$session uri=$uri")
   val isApk=action in apkActions
   val isStorageExport = action == CrossProfileContract.ACTION_STORAGE_EXPORT
   val ext=when { isApk -> "apk"; isStorageExport -> "zip"; else -> "json" }
   val local=File(filesDir,"imports/$session.$ext")
   HandoffDiagnostics.log("import_activity_copy_started action=$action session=$session dest=${local.absolutePath}")
   if (isStorageExport) {
    val validSession = requireNotNull(session)
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    executor.execute {
     try {
      Handoff.copy(this, uri, local, CrossProfileContract.MAX_STORAGE_EXPORT_TRANSFER_BYTES)
      HandoffDiagnostics.log("import_activity_copy_completed action=$action session=$validSession bytes=${local.length()}")
      onImportComplete(action, validSession, local)
     } catch (e: Exception) {
      HandoffDiagnostics.log("import_activity_failed action=$action detail=$e")
      runOnUiThread { onImportFailed(e, validSession) }
     } finally {
      runOnUiThread { finish() }
      executor.shutdown()
     }
    }
    return
   }
   Handoff.copy(this,uri,local,if(isApk) MAX_APK_TRANSFER_BYTES else 1024L*1024)
   HandoffDiagnostics.log("import_activity_copy_completed action=$action session=$session bytes=${local.length()}")
   getSharedPreferences("spike",0).edit().putString("session",session).putString("import",local.absolutePath).apply()
   onImportComplete(action, session, local)
  } catch(e:Exception) {
   HandoffDiagnostics.log("import_activity_failed action=${intent.action} detail=$e")
   onImportFailed(e, session)
  }
  finish()
 }
 /** Default: return to whatever this app's own launcher activity is — no hardcoded class reference, since this module doesn't know which app hosts it. Override for different post-import navigation. */
 open fun onImportComplete(action: String, session: String, importedFile: File) {
  packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
 }
 /**
  * @param sessionId the session id parsed from the intent, if parsing got that far before [e] was
  * thrown — `null` only when the intent itself was malformed before a session could even be
  * identified (e.g. a missing/invalid session extra). A work-side override with a real sessionId
  * should report the failure back to the personal profile (see `SandboxWorkerActivity`'s override)
  * rather than leaving the personal side to infer failure from silence.
  */
 open fun onImportFailed(e: Exception, sessionId: String?) {}
}

/**
 * Checkpoint 5.1, item 2: structured, single-line-per-event diagnostics for the cross-profile
 * handoff lifecycle — added specifically because the Checkpoint 5 Prepare stall had **no error
 * anywhere**: `Handoff.send()` never threw, `resolveForwarder()` always found a candidate, and the
 * session just sat in `PREPARING` forever with nothing to look at except two empty Room tables.
 * Kept permanently (not a one-off trace deleted after root-causing) because that exact failure mode
 * — a cross-profile push silently resolving nowhere — has genuine, ongoing diagnostic value: it is
 * the one class of bug this transport can have that produces zero exceptions on either side.
 *
 * Deliberately minimal: one tagged `Log.i` line per lifecycle event, monotonic
 * [android.os.SystemClock.elapsedRealtime] millis prefixed so events from both profiles' independent
 * logcat streams can be correlated by relative timing, never the APK's own contents.
 */
object HandoffDiagnostics {
 private const val TAG = "ApkScopeHandoff"
 fun log(message: String) { android.util.Log.i(TAG, "t=${android.os.SystemClock.elapsedRealtime()} $message") }
}

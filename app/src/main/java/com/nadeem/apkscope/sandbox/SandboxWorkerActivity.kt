package com.nadeem.apkscope.sandbox

import android.content.Intent
import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.core.crossprofile.HandoffDiagnostics
import com.nadeem.apkscope.core.crossprofile.ImportActivity
import com.nadeem.apkscope.core.model.Recoverability
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxSessionState
import org.json.JSONObject
import java.io.File

/**
 * The work-profile-side relay Activity (checkpoint 4, item 3/7/13/16) — the target of every
 * personal→work cross-profile action ([CrossProfileContract.ACTION_IMPORT_APK],
 * [CrossProfileContract.ACTION_CONTINUE_INSTALL], [CrossProfileContract.ACTION_END_SESSION]).
 *
 * This class's only job is receiving the forwarded file (its base class, `core:crossprofile`'s
 * `ImportActivity`, already does that) and handing off to [SandboxWorkerService] — it deliberately
 * does **not** perform the actual DPM/VPN/`PackageInstaller` sequence itself, because
 * `ImportActivity.onCreate()` calls `finish()` immediately after `onImportComplete` returns, and
 * that sequence is genuinely asynchronous (VPN establishment, `clearApplicationUserData`'s
 * completion callback). A foreground `Service` — the same shape Android already requires for the
 * VPN itself — is the correct home for it, not a transient relay Activity racing its own `finish()`.
 */
class SandboxWorkerActivity : ImportActivity() {
 override fun onImportComplete(action: String, session: String, importedFile: File) {
  HandoffDiagnostics.log("worker_activity_import_complete action=$action session=$session")
  val intent = Intent(this, SandboxWorkerService::class.java).apply {
   putExtra(SandboxWorkerService.EXTRA_ACTION, action)
   putExtra(SandboxWorkerService.EXTRA_SESSION_ID, session)
  }
  when (action) {
	   CrossProfileContract.ACTION_SANDBOX_IMPORT_APK,
	   CrossProfileContract.ACTION_PATCHED_APK_INSTALL -> intent.putExtra(SandboxWorkerService.EXTRA_APK_PATH, importedFile.absolutePath)
   CrossProfileContract.ACTION_CONTINUE_INSTALL -> {
    val json = JSONObject(importedFile.readText())
    intent.putExtra(SandboxWorkerService.EXTRA_INSTALL_SESSION_ID, json.getInt("installSessionId"))
    importedFile.delete()
   }
   CrossProfileContract.ACTION_REINSTALL -> {
    val json = JSONObject(importedFile.readText())
    intent.putExtra(SandboxWorkerService.EXTRA_INSTALL_SESSION_ID, json.optInt("installSessionId", -1))
    importedFile.delete()
   }
   CrossProfileContract.ACTION_END_SESSION -> {
    val json = JSONObject(importedFile.readText())
    intent.putExtra(SandboxWorkerService.EXTRA_PACKAGE_NAME, json.getString("packageName"))
    importedFile.delete()
   }
   // Checkpoint 5/6: fire-and-forget acks, not control files carrying further instructions.
   CrossProfileContract.ACTION_ACK_RUNTIME_ARTIFACT, CrossProfileContract.ACTION_ACK_ANDROID_EVIDENCE -> importedFile.delete()
  }
  startForegroundService(intent)
  HandoffDiagnostics.log("worker_service_start_requested action=$action session=$session")
 }

 /**
  * Root-cause fix (was: a no-op that left the personal-side "Preparing" screen spinning forever
  * with no error, confirmed on-device — a 131 MiB real APK exceeded the cross-profile handoff's
  * old, lower 128 MiB limit, threw here, and was silently swallowed). [sessionId] is non-null for
  * exactly the case that matters most — the copy itself failing after the session id was already
  * parsed (e.g. [MAX_APK_TRANSFER_BYTES] still exceeded, or a genuine I/O error) — so the failure
  * can be attributed to a real session and reported back, the same [CrossProfileContract.ACTION_SANDBOX_ERROR]
  * path [SandboxWorkerService.report] already uses for every other work-side failure. `reconcile()`'s
  * own recovery paths do not cover `PREPARING` (this failure's own state), so this report is not
  * merely best-effort belt-and-suspenders here — it is the only path that unsticks the screen.
  */
 override fun onImportFailed(e: Exception, sessionId: String?) {
  HandoffDiagnostics.log("worker_activity_import_failed sessionId=$sessionId detail=$e")
  if (sessionId == null) return // Intent itself was malformed before a session could be identified — nothing to attribute this to.
  val error = SandboxError(
   code = SandboxErrorCode.HANDOFF_FAILED,
   userMessage = "The APK could not be transferred into the sandbox: ${e.message ?: e.javaClass.simpleName}.",
   technicalDetail = e.toString(),
   recoverability = Recoverability.RETRYABLE,
  )
  val report = SandboxStatusReport(sessionId = sessionId, state = SandboxSessionState.FAILED, error = error)
  try {
   val file = File(filesDir, "reports/$sessionId-${System.nanoTime()}.json")
   file.parentFile?.mkdirs()
   file.writeText(report.toJson().toString())
   Handoff.send(this, file, CrossProfileContract.ACTION_SANDBOX_ERROR, sessionId, SANDBOX_FILE_PROVIDER_AUTHORITY)
   HandoffDiagnostics.log("worker_activity_import_failed_reported sessionId=$sessionId")
  } catch (sendException: Exception) {
   // Best-effort only, matching SandboxWorkerService.report's own tolerance — if even this push is
   // lost, there is currently no further recovery for a session stuck in PREPARING.
   HandoffDiagnostics.log("worker_activity_import_failed_report_send_failed sessionId=$sessionId detail=$sendException")
  }
 }
}

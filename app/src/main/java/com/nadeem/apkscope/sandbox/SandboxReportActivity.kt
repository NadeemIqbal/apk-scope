package com.nadeem.apkscope.sandbox

import android.content.Intent
import androidx.core.content.FileProvider
import com.nadeem.apkscope.core.crossprofile.ImportActivity
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.domain.sandbox.SandboxEnvironmentPreflight
import com.nadeem.apkscope.domain.sandbox.SandboxSessionRepository
import com.nadeem.apkscope.domain.sandbox.SandboxSessionReportMerger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * The personal-profile-side handler for [com.nadeem.apkscope.core.crossprofile.CrossProfileContract.ACTION_SANDBOX_READY]/
 * [com.nadeem.apkscope.core.crossprofile.CrossProfileContract.ACTION_SANDBOX_ERROR] (checkpoint 4, item
 * 9) — every real fact [SandboxWorkerService]/[SandboxInstallResultReceiver] discovered work-side
 * (policy enforcement results, install/uninstall outcome, data-clear result) is applied to the
 * **personal** profile's own Room row here, since that is the only copy of a [SandboxSession] this
 * app's UI ever reads. A `runBlocking` Room write is proportionate here — unlike the work-side
 * sequence, everything this Activity does is a small database read/write plus a couple of file
 * operations, not a multi-second VPN/PackageInstaller wait.
 */
class SandboxReportActivity : ImportActivity() {
 override fun onImportComplete(action: String, session: String, importedFile: File) {
  runBlocking {
   val repository = SandboxSessionRepository(applicationContext)
   val current = repository.get(session) ?: return@runBlocking
   val report = SandboxStatusReport.fromJson(JSONObject(importedFile.readText()))
   importedFile.delete()

   var next = SandboxSessionReportMerger.mergeFacts(current, report)

   if (report.error != null) {
    next = SandboxSessionReportMerger.safeTransition(next, SandboxSessionState.FAILED)?.copy(error = report.error) ?: next
   } else {
    next = SandboxSessionReportMerger.safeTransition(next, report.state) ?: next
    // Item 10: only ever enter READY from a real, freshly-verified INSTALLED report — never assumed.
    if (report.state == SandboxSessionState.INSTALLED) {
     val readiness = SandboxEnvironmentPreflight.computeLaunchReadiness(applicationContext, next)
     if (readiness.launchAllowed) next = SandboxSessionReportMerger.safeTransition(next, SandboxSessionState.READY) ?: next
    }
   }

   if (report.cleanup != null) {
    // Merge the work-side facts with what only the personal side can verify about itself, then
    // decide COMPLETED vs CLEANUP_REQUIRED from the *merged* summary (item 18) — never from the
    // work-side report alone, which can never see this profile's own temp file/URI grant.
    val personalCleanupDone = performPersonalCleanup(next)
    val merged = SandboxSessionReportMerger.mergeCleanupSummary(report.cleanup, personalCleanupDone)
    next = next.copy(cleanupSummary = merged)
    val target = if (merged.isComplete) SandboxSessionState.COMPLETED else SandboxSessionState.CLEANUP_REQUIRED
    next = SandboxSessionReportMerger.safeTransition(next, target) ?: next
   }

   repository.save(next)
  }
  // Deliberately no navigation here (unlike the base class's default) — the user may be anywhere
  // in the app when a background status report arrives; the observing ViewModel/Compose screen
  // picks up the new Room state on its own via the session Flow.
 }

 override fun onImportFailed(e: Exception, sessionId: String?) {}

 /** Personal-side cleanup (item 17): delete this session's own temp APK copy and revoke its `FileProvider` read grant — the two facts only this profile can verify about itself. */
 private fun performPersonalCleanup(session: com.nadeem.apkscope.core.model.SandboxSession): Boolean {
  val path = session.personalApkPath ?: return true
  val file = File(path)
  if (!file.exists()) return true
  return try {
   val uri = FileProvider.getUriForFile(this, SANDBOX_FILE_PROVIDER_AUTHORITY, file)
   revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
   file.delete()
  } catch (_: Exception) { false }
 }
}

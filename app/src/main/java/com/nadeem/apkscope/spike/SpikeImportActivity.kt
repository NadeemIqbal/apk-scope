package com.nadeem.apkscope.spike

import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.crossprofile.ImportActivity
import java.io.File

/**
 * The spike's concrete `ImportActivity`: `core:crossprofile`'s base class has no `Evidence`
 * dependency (that class is this harness's own test-outcome log, not something a reusable
 * cross-profile module should know about) and looks up this app's own launcher activity generically
 * instead of hardcoding [GateActivity] — see that module's doc comment. This subclass restores the
 * exact evidence lines the original combined `com.nadeem.apkscope.spike.Handoff.ImportActivity` recorded.
 * Only ever registered for [CrossProfileContract.ACTION_IMPORT_APK]/[CrossProfileContract.ACTION_REPORT_READY]
 * in the manifest, so `action` here is always one of those two — checkpoint 4's three new actions
 * never reach this spike-only class.
 */
class SpikeImportActivity : ImportActivity() {
 override fun onImportComplete(action: String, session: String, importedFile: File) {
  val work = action == CrossProfileContract.ACTION_IMPORT_APK
  Evidence.record(this, if (work) "PersonalToWorkCopy" else "WorkToPersonalCopy", "PASS", "session=$session; bytes=${importedFile.length()}")
  super.onImportComplete(action, session, importedFile)
 }
 override fun onImportFailed(e: Exception, sessionId: String?) { Evidence.record(this, "handoffImport", "FAIL", "session=$sessionId; $e") }
}

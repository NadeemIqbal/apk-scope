package com.nadeem.apkscope.sandbox

import android.content.ContentValues
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.crossprofile.ImportActivity
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.domain.sandbox.SandboxEnvironmentPreflight
import com.nadeem.apkscope.domain.sandbox.SandboxSessionRepository
import com.nadeem.apkscope.domain.sandbox.SandboxSessionReportMerger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipInputStream

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
  if (action == CrossProfileContract.ACTION_STORAGE_EXPORT) {
   try {
    val count = importStorageExport(importedFile)
    runOnUiThread { Toast.makeText(this, "Exported $count items to Downloads/APKScopeExports", Toast.LENGTH_LONG).show() }
   } catch (e: Exception) {
    runOnUiThread { Toast.makeText(this, "Storage export failed: ${e.message ?: "invalid archive"}", Toast.LENGTH_LONG).show() }
   }
   return
  }
  runBlocking {
   val repository = SandboxSessionRepository(applicationContext)
   val report = SandboxStatusReport.fromJson(JSONObject(importedFile.readText()))
   importedFile.delete()
   repository.update(session) { current ->
   if (InstallRetryMarker.isSuperseded(applicationContext, session, report)) return@update current
   if (SandboxSessionReportMerger.isStaleInstallReport(current, report)) return@update current

   var next = SandboxSessionReportMerger.mergeFacts(current, report)

   if (report.error != null) {
    next = SandboxSessionReportMerger.safeTransition(next, SandboxSessionState.FAILED)?.copy(error = report.error) ?: next
   } else {
    next = SandboxSessionReportMerger.advanceThroughOperationalStates(next, report.state) ?: next
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

   next
   }
  }
  // Deliberately no navigation here (unlike the base class's default) — the user may be anywhere
  // in the app when a background status report arrives; the observing ViewModel/Compose screen
  // picks up the new Room state on its own via the session Flow.
 }

 private fun importStorageExport(archive: File): Int {
  val maxFileBytes = 100L * 1024 * 1024
  val maxTotalBytes = 200L * 1024 * 1024
  val createdUris = mutableListOf<android.net.Uri>()
  try {
   require(archive.length() <= CrossProfileContract.MAX_STORAGE_EXPORT_TRANSFER_BYTES) { "archive exceeds its size limit" }
   ZipInputStream(archive.inputStream().buffered()).use { zip ->
    val manifestEntry = requireNotNull(zip.nextEntry) { "archive is empty" }
    require(manifestEntry.name == "manifest.json") { "archive manifest is missing" }
    val manifestBuffer = ByteArray(64 * 1024 + 1)
    var manifestLength = 0
    while (manifestLength < manifestBuffer.size) {
     val read = zip.read(manifestBuffer, manifestLength, manifestBuffer.size - manifestLength)
     if (read < 0) break
     if (read == 0) break
     manifestLength += read
    }
    require(manifestLength <= 64 * 1024) { "archive manifest is too large" }
    val manifest = JSONObject(String(manifestBuffer, 0, manifestLength, Charsets.UTF_8))
    require(manifest.optInt("schemaVersion") == 1) { "unsupported archive version" }
    val targetPackage = manifest.optString("targetPackage")
    val timestamp = manifest.optString("timestamp")
    require(targetPackage.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "invalid target package" }
    require(timestamp.matches(Regex("\\d{8}_\\d{6}_\\d{3}"))) { "invalid export timestamp" }
    val files = manifest.optJSONArray("files") ?: JSONArray()
    require(files.length() in 1..180) { "archive item count is invalid" }
    val expected = linkedMapOf<String, Pair<Long, String>>()
    for (index in 0 until files.length()) {
     val item = files.getJSONObject(index)
     val name = item.optString("name")
     val size = item.optLong("byteLength", -1L)
     val kind = item.optString("kind")
     require(isSafeExportName(name)) { "archive contains an invalid file name" }
     require(size in 0..maxFileBytes) { "archive item exceeds its size limit" }
     require(kind in setOf("preference", "file", "image", "database")) { "archive contains an unsupported item" }
     require(expected.put(name, size to kind) == null) { "archive contains duplicate names" }
    }

    val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/APKScopeExports/$targetPackage/$timestamp"
    val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    var totalBytes = 0L
    var importedCount = 0
    val importedNames = mutableSetOf<String>()
    while (true) {
     val entry = zip.nextEntry ?: break
     require(importedNames.add(entry.name)) { "archive contains a duplicate item" }
     val spec = expected[entry.name] ?: error("archive has an unexpected item")
     val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
      put(MediaStore.MediaColumns.MIME_TYPE, exportMimeType(entry.name, spec.second))
      put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
      put(MediaStore.MediaColumns.IS_PENDING, 1)
     }
     val uri = contentResolver.insert(downloads, values) ?: error("could not create a Downloads item")
     createdUris += uri
     var itemBytes = 0L
     contentResolver.openOutputStream(uri, "w")?.use { output ->
      val buffer = ByteArray(16 * 1024)
      while (true) {
       val read = zip.read(buffer)
       if (read < 0) break
       itemBytes += read
       totalBytes += read
       require(itemBytes <= maxFileBytes && totalBytes <= maxTotalBytes) { "archive contents exceed the export limit" }
       output.write(buffer, 0, read)
      }
     } ?: error("could not open a Downloads item")
     require(itemBytes == spec.first) { "archive item size did not match its manifest" }
     importedCount++
     zip.closeEntry()
    }
    require(importedCount == expected.size && importedNames == expected.keys) { "archive is missing selected items" }
    createdUris.forEach { uri ->
     val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
     require(contentResolver.update(uri, publish, null, null) == 1) { "could not publish all Downloads items" }
    }
    return importedCount
   }
  } catch (e: Exception) {
   createdUris.forEach { runCatching { contentResolver.delete(it, null, null) } }
   throw e
  } finally {
   archive.delete()
  }
 }

 private fun isSafeExportName(name: String): Boolean =
  name.isNotBlank() && name.length <= 180 && name !in setOf(".", "..", "manifest.json") &&
   name.none { it == '/' || it == '\\' || it.code < 32 }

 private fun exportMimeType(name: String, kind: String): String {
  if (kind == "preference") return "application/json"
  return when (name.substringAfterLast('.', "").lowercase()) {
   "txt", "log", "md", "csv", "xml", "json", "html", "htm" -> "text/plain"
   "png" -> "image/png"
   "jpg", "jpeg" -> "image/jpeg"
   "webp" -> "image/webp"
   "gif" -> "image/gif"
   "db", "sqlite", "sqlite3" -> "application/vnd.sqlite3"
   else -> "application/octet-stream"
  }
 }

 override fun onImportFailed(e: Exception, sessionId: String?) {
  if (intent.action == CrossProfileContract.ACTION_STORAGE_EXPORT) {
   runOnUiThread { Toast.makeText(this, "Storage export failed: ${e.message ?: "transfer failed"}", Toast.LENGTH_LONG).show() }
  }
 }

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

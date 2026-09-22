package com.nadeem.apkscope.domain

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.nadeem.apkscope.core.common.BoundedCopy
import com.nadeem.apkscope.core.common.MAX_APK_TRANSFER_BYTES
import com.nadeem.apkscope.core.risk.DefaultRiskEngine
import com.nadeem.apkscope.core.staticanalysis.ApkAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Home's "select APK → copy to app-private temp storage → create session → start real
 * ApkAnalyzer → run the real static risk engine → persist the completed analysis → navigate" flow
 * (item 6 of the UI checkpoint; risk-engine + persistence wiring added in checkpoint 3). Deliberately
 * does **not** install anything — that only ever happens later, through the already-verified
 * cross-profile handoff + `PackageInstaller` flow, with explicit Android confirmation (item 13).
 */
class ApkImportUseCase(private val context: Context) {
 // Shared with the cross-profile sandbox handoff's own limit ([MAX_APK_TRANSFER_BYTES]'s own doc) —
 // an APK this flow accepts for analysis must never be one the sandbox handoff then silently rejects.
 private val maxApkBytes = MAX_APK_TRANSFER_BYTES
 private val sessionRepository = SessionRepository(context)
 private val riskEngine = DefaultRiskEngine()

 data class Progress(val fraction: Float, val message: String)

 suspend fun importAndAnalyze(
  uri: Uri,
  onProgress: (Progress) -> Unit = {},
 ): String = withContext(Dispatchers.IO) {
  val sessionId = UUID.randomUUID().toString()
  val dest = File(context.filesDir, "analysis/$sessionId.apk")
  sessionRepository.putActive(AnalysisSession(id = sessionId, apkFilePath = dest.absolutePath, createdAtEpochMs = System.currentTimeMillis(), stage = SessionStage.Copying))

  var currentStage: ApkAnalyzer.Stage = ApkAnalyzer.Stage.READING_APK
  try {
   onProgress(Progress(0f, "Copying patched APK"))
   dest.parentFile?.mkdirs()
   context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { out -> BoundedCopy.copy(input, out, maxApkBytes) } }
    ?: error("Unable to open the selected file")
   onProgress(Progress(0.04f, "Reading patched APK identity"))

   // Lightweight identity read, before the full analyzer pass, so the UI can show
   // icon/name/package/version "as it becomes available" (item 7) rather than only at the end.
   val quickIdentity = runCatching { quickIdentity(dest) }.getOrNull()
   if (quickIdentity != null) sessionRepository.updateActive(sessionId) { it.copy(appIdentity = quickIdentity) }

   val result = ApkAnalyzer.analyze(
    context = context,
    apkFile = dest,
    onStage = { stage ->
     currentStage = stage
     sessionRepository.updateActive(sessionId) { it.copy(stage = SessionStage.Analyzing(stage)) }
    },
    onProgress = { stage, fraction -> onProgress(progressFor(stage, fraction)) },
   )
   onProgress(Progress(0.95f, "Finalizing analysis"))
   val riskAssessment = riskEngine.evaluate(result.toRiskInput())
   val appName = quickIdentity?.name ?: result.metadata.packageName
   val analyzedAt = System.currentTimeMillis()

   // Durable record first (item 1/3) — by the time this suspend function returns, the completed
   // analysis is already safely in Room, so navigating straight to Static Result (or surviving a
   // process death before the user even leaves this screen) can never lose it.
   sessionRepository.persistCompletedAnalysis(sessionId, appName, analyzedAt, result, riskAssessment)

   sessionRepository.updateActive(sessionId) {
    it.copy(stage = SessionStage.AnalysisComplete, analysis = result, appIdentity = AppIdentity(appName, result.metadata.packageName, result.metadata.versionName))
   }
   onProgress(Progress(1f, "Analysis complete"))
  } catch (e: Exception) {
   Log.e("ApkImportUseCase", "APK analysis failed at ${currentStage.name} for $dest", e)
   sessionRepository.updateActive(sessionId) { it.copy(stage = SessionStage.Failed(e.message ?: e.javaClass.simpleName, currentStage)) }
  }
  sessionId
 }

 private fun progressFor(stage: ApkAnalyzer.Stage, fraction: Float): Progress {
  val ranges = mapOf(
   ApkAnalyzer.Stage.READING_APK to (0.04f to 0.10f),
   ApkAnalyzer.Stage.PARSING_MANIFEST to (0.10f to 0.23f),
   ApkAnalyzer.Stage.CHECKING_SIGNATURE to (0.23f to 0.30f),
   ApkAnalyzer.Stage.ANALYZING_PERMISSIONS to (0.30f to 0.34f),
   // Component assembly also runs the URL, SDK, and DEX API scanners, so it owns most
   // of the analysis allocation instead of making every stage look equally expensive.
   ApkAnalyzer.Stage.INSPECTING_COMPONENTS to (0.34f to 0.92f),
   ApkAnalyzer.Stage.PREPARING_ASSESSMENT to (0.92f to 0.95f),
  )
  val (start, end) = ranges.getValue(stage)
  val bounded = fraction.coerceIn(0f, 1f)
  val message = when (stage) {
   ApkAnalyzer.Stage.READING_APK -> "Reading patched APK"
   ApkAnalyzer.Stage.PARSING_MANIFEST -> "Parsing manifest and package metadata"
   ApkAnalyzer.Stage.CHECKING_SIGNATURE -> "Checking APK signature"
   ApkAnalyzer.Stage.ANALYZING_PERMISSIONS -> "Analyzing permissions"
   ApkAnalyzer.Stage.INSPECTING_COMPONENTS -> "Scanning components and DEX contents"
   ApkAnalyzer.Stage.PREPARING_ASSESSMENT -> "Preparing analysis results"
  }
  return Progress(start + ((end - start) * bounded), message)
 }

 @Suppress("DEPRECATION")
 private fun quickIdentity(apkFile: File): AppIdentity? {
  val pm = context.packageManager
  val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
  info.applicationInfo?.sourceDir = apkFile.absolutePath
  info.applicationInfo?.publicSourceDir = apkFile.absolutePath
  val label = try { info.applicationInfo?.let { pm.getApplicationLabel(it) }?.toString() } catch (_: Exception) { null }
  return AppIdentity(name = label ?: info.packageName, packageName = info.packageName, versionName = info.versionName)
 }
}

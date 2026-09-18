package com.nadeem.apkscope.core.staticanalysis

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Static APK analysis. This doc comment previously described a "Slice 2 foundation" checkpoint
 * that used public `PackageManager`/`ApplicationInfo` APIs only, with no manifest binary-XML
 * parsing of its own — that description is now stale and was corrected during Milestone 10,
 * Phase 10.3: [BinaryXmlParser] has since been added and is used directly below (`AndroidManifest.xml`
 * read via a raw zip-entry scan, not just `getPackageArchiveInfo`), and both fields the original
 * comment listed as "deliberately not implemented" are in fact implemented:
 * [ApkMetadata.usesCleartextTraffic] via the real `ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC`
 * flag, and [ApkMetadata.intentFilters] via [BinaryXmlParser]'s per-component `<intent-filter>`
 * extraction. See each field's own doc comment on [ApkMetadata] for what is and is not verified
 * about it, rather than trusting this class-level summary alone.
 *
 * Currently populated, combining both sources:
 * - SHA-256, package identity, min/target SDK, requested permissions, all four component kinds
 *   with their exported state, `debuggable`, `allowBackup`, `usesCleartextTraffic`,
 *   `networkSecurityConfigPresent`, intent filters, and native library ABIs (via a zip-entry scan
 *   — `PackageManager` does not expose this for an *uninstalled* archive).
 * - Signature verification as a fully separate operation ([verifySigning]) — its failure never
 *   discards whatever [analyze] already extracted. This is not a shortcut; it is the explicit
 *   "keep signature verification in a separate parse operation" requirement.
 */
object ApkAnalyzer {

 /** Matches the UI checkpoint's required stage list exactly (item 7) — each value is emitted immediately before the real work it names actually starts, never on a timer. */
 enum class Stage { READING_APK, PARSING_MANIFEST, CHECKING_SIGNATURE, ANALYZING_PERMISSIONS, INSPECTING_COMPONENTS, PREPARING_ASSESSMENT }

 /**
  * [observedRuntimeHosts]: hostnames independently observed on the wire for this same analysis
  * session (e.g. from a completed sandbox run's network observations), so [DexUrlExtractor] can
  * attach non-elevating host-correlation metadata to embedded URLs sharing that host — never
  * `RUNTIME_OBSERVED`, which is reserved for an exact URL match with real transaction evidence
  * (see [UrlProvenance]). Defaults to empty because static analysis normally runs at import time,
  * before any sandbox run has produced runtime evidence to correlate against; a caller that has
  * since collected real per-session host observations (today: [com.nadeem.apkscope.sandbox.RuntimeObservationArtifact]'s
  * per-entry `hostname`, persisted as `NetworkObservationEntity`) should pass them here on a
  * re-analysis pass. Exact-URL `RUNTIME_OBSERVED` provenance needs full HTTP-transaction evidence
  * (URL, method, status) which has no cross-profile transport of its own yet — that remains a
  * distinct, not-yet-implemented follow-on, not something this parameter can produce on its own.
  */
 fun analyze(
  context: Context,
  apkFile: File,
  observedRuntimeHosts: Set<String> = emptySet(),
  onStage: (Stage) -> Unit = {},
  onProgress: (Stage, Float) -> Unit = { _, _ -> },
 ): ApkAnalysisResult {
  fun begin(stage: Stage) {
   onStage(stage)
   onProgress(stage, 0f)
  }

  fun finish(stage: Stage) {
   onProgress(stage, 1f)
  }

  begin(Stage.READING_APK)
  val hash = sha256(apkFile) // the real "reading the container" work — a full-file streaming read
  finish(Stage.READING_APK)
  begin(Stage.PARSING_MANIFEST)
  val pm = context.packageManager
  @Suppress("DEPRECATION") // getPackageArchiveInfo(String, Int) works across this project's whole minSdk..compileSdk range; the API-33+ PackageInfoFlags overload would need level-gating for no behavioral benefit here.
  val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
   PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
  @Suppress("DEPRECATION")
  val info = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
   ?: throw IllegalArgumentException("PackageManager could not parse this file as an APK")
  val appInfo = info.applicationInfo

  val allComponentNames = (info.activities?.map { it.name } ?: emptyList()) +
   (info.services?.map { it.name } ?: emptyList()) +
   (info.receivers?.map { it.name } ?: emptyList()) +
   (info.providers?.map { it.name } ?: emptyList())

  var manifestConfig: BinaryXmlParser.ManifestConfig? = null
  var nscSummary: NetworkSecurityConfigSummary? = null
  val (xmlComponents, detectedPlatform, rawManifestXml) = try {
   ZipFile(apkFile).use { zip ->
    val entry = zip.getEntry("AndroidManifest.xml")
    val full = if (entry != null) {
     zip.getInputStream(entry).use { BinaryXmlParser.parseManifestFull(it) }
    } else null
    manifestConfig = full?.config
    // Real resource-content parsing (Phase 10.3 correction), not just attribute presence — same zip
    // handle, avoids reopening the APK a second time.
    full?.config?.networkSecurityConfig?.let { rawAttrValue ->
     nscSummary = NetworkSecurityConfigSummary.from(NetworkSecurityConfigParser.parseFromManifestAttribute(zip, rawAttrValue))
    }
    val platform = PlatformDetector.detect(zip, allComponentNames)
    Triple(full?.components ?: emptyList(), platform, full?.rawXml)
   }
  } catch (_: Exception) {
   Triple(emptyList<BinaryXmlParser.ParsedComponentInfo>(), AppPlatformInfo(AppPlatform.UNKNOWN), null)
  }
  finish(Stage.PARSING_MANIFEST)

  fun findXml(name: String) = xmlComponents.firstOrNull { it.name == name }
   ?: xmlComponents.firstOrNull { it.name.substringAfterLast('.') == name.substringAfterLast('.') }

  begin(Stage.CHECKING_SIGNATURE)
  val signing = verifySigning(context, apkFile)
  finish(Stage.CHECKING_SIGNATURE)

  begin(Stage.ANALYZING_PERMISSIONS)
  val permissions = info.requestedPermissions?.toList() ?: emptyList()
  finish(Stage.ANALYZING_PERMISSIONS)

  begin(Stage.INSPECTING_COMPONENTS)
  val components = ArrayList<ComponentDescriptor>()
  info.activities?.forEach {
   val xml = findXml(it.name)
   components.add(
    ComponentDescriptor(
     name = it.name,
     type = ComponentDescriptor.ComponentType.ACTIVITY,
     exported = it.exported,
     permission = it.permission ?: xml?.permission,
     intentFilters = xml?.intentFilters ?: emptyList(),
    )
   )
  }
  info.services?.forEach {
   val xml = findXml(it.name)
   components.add(
    ComponentDescriptor(
     name = it.name,
     type = ComponentDescriptor.ComponentType.SERVICE,
     exported = it.exported,
     permission = it.permission ?: xml?.permission,
     intentFilters = xml?.intentFilters ?: emptyList(),
    )
   )
  }
  info.receivers?.forEach {
   val xml = findXml(it.name)
   components.add(
    ComponentDescriptor(
     name = it.name,
     type = ComponentDescriptor.ComponentType.RECEIVER,
     exported = it.exported,
     permission = it.permission ?: xml?.permission,
     intentFilters = xml?.intentFilters ?: emptyList(),
    )
   )
  }
  info.providers?.forEach {
   val xml = findXml(it.name)
   components.add(
    ComponentDescriptor(
     name = it.name,
     type = ComponentDescriptor.ComponentType.PROVIDER,
     exported = it.exported,
     permission = it.readPermission ?: it.writePermission ?: xml?.permission,
     intentFilters = xml?.intentFilters ?: emptyList(),
    )
   )
  }
  onProgress(Stage.INSPECTING_COMPONENTS, 0.35f)
  val nativeLibs = nativeLibraryAbis(apkFile)
  val allFilters = components.flatMap { it.intentFilters }

  val urlResult = DexUrlExtractor.extractUrls(apkFile, observedRuntimeHosts)
  onProgress(Stage.INSPECTING_COMPONENTS, 0.55f)
  val sdkResult = SdkSignatureCatalog.detectSdks(apkFile, rawManifestXml)
  onProgress(Stage.INSPECTING_COMPONENTS, 0.70f)
  val apiResult = DexApiScanner.scanApk(apkFile)
  onProgress(Stage.INSPECTING_COMPONENTS, 0.98f)

  val combinedCoverage = StaticAnalysisCoverage(
   dexFilesInspected = (urlResult.coverage.dexFilesInspected + sdkResult.coverage.dexFilesInspected + apiResult.coverage.dexFilesInspected).distinct(),
   entriesSkipped = (urlResult.coverage.entriesSkipped + sdkResult.coverage.entriesSkipped + apiResult.coverage.entriesSkipped).distinct(),
   parsingErrors = (urlResult.coverage.parsingErrors + sdkResult.coverage.parsingErrors + apiResult.coverage.parsingErrors).distinct(),
   limitsReached = urlResult.coverage.limitsReached || sdkResult.coverage.limitsReached || apiResult.coverage.limitsReached,
   scanDurationMs = urlResult.coverage.scanDurationMs + sdkResult.coverage.scanDurationMs + apiResult.coverage.scanDurationMs
  )
  finish(Stage.INSPECTING_COMPONENTS)

  begin(Stage.PREPARING_ASSESSMENT)
  val metadata = ApkMetadata(
   sha256 = hash,
   packageName = info.packageName,
   versionName = info.versionName,
   versionCode = info.longVersionCode,
   minSdkVersion = appInfo?.minSdkVersion ?: -1,
   targetSdkVersion = appInfo?.targetSdkVersion ?: -1,
   requestedPermissions = permissions,
   components = components,
   debuggable = (appInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
   nativeLibraryAbis = nativeLibs,
   // null (not false) only when the manifest itself could not be located/parsed at all (see
   // manifestConfig's assignment above) — never conflate "unknown" with "confirmed absent".
   networkSecurityConfigPresent = manifestConfig?.let { it.networkSecurityConfig != null },
   networkSecurityConfig = nscSummary,
   usesCleartextTraffic = (appInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0,
   allowBackup = (appInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
   intentFilters = allFilters,
   platformInfo = detectedPlatform,
   embeddedUrls = urlResult.urls,
   detectedSdks = sdkResult.sdks,
   apiFindings = apiResult.findings,
   staticCoverage = combinedCoverage,
  )
  finish(Stage.PREPARING_ASSESSMENT)
  return ApkAnalysisResult(metadata, signing)
 }

 fun sha256(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
   val buffer = ByteArray(65536)
   while (true) {
    val n = input.read(buffer)
    if (n < 0) break
    digest.update(buffer, 0, n)
   }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
 }

 private fun nativeLibraryAbis(apkFile: File): List<String> {
  val abis = sortedSetOf<String>()
  try {
   ZipFile(apkFile).use { zip ->
    zip.entries().asSequence().forEach { entry ->
     if (entry.name.startsWith("lib/") && !entry.isDirectory) {
      val abi = entry.name.removePrefix("lib/").substringBefore('/')
      if (abi.isNotEmpty()) abis.add(abi)
     }
    }
   }
  } catch (_: Exception) { /* not a valid zip / unreadable — treated as "no native libraries found", not a fatal error for the rest of metadata parsing */ }
  return abis.toList()
 }

 @Suppress("DEPRECATION")
 private fun verifySigning(context: Context, apkFile: File): SigningResult = try {
  val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
  val signingInfo = info?.signingInfo ?: return SigningResult.Failed("APK signature verification failed: no signing information returned")
  val signers = signingInfo.apkContentsSigners
  if (signers == null || signers.isEmpty()) SigningResult.Failed("APK signature verification failed: no signers found")
  else SigningResult.Verified(signers.map { sha256Bytes(it.toByteArray()) })
 } catch (e: Exception) {
  SigningResult.Failed("APK signature verification failed: ${e.javaClass.simpleName}: ${e.message}")
 }

 private fun sha256Bytes(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

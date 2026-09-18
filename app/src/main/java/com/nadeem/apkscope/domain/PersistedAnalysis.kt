package com.nadeem.apkscope.domain

import com.nadeem.apkscope.core.database.AnalysisComponentEntity
import com.nadeem.apkscope.core.database.AnalysisPermissionEntity
import com.nadeem.apkscope.core.database.AnalysisSessionEntity
import com.nadeem.apkscope.core.database.AnalysisSessionWithDetails
import com.nadeem.apkscope.core.database.RiskFindingEntity
import com.nadeem.apkscope.core.model.RiskEvidence
import com.nadeem.apkscope.core.model.RiskFinding
import com.nadeem.apkscope.core.model.RiskLevel
import com.nadeem.apkscope.core.model.RiskSeverity
import com.nadeem.apkscope.core.model.StaticRiskAssessment
import com.nadeem.apkscope.core.staticanalysis.ApkAnalysisResult
import com.nadeem.apkscope.core.staticanalysis.AppPlatform
import com.nadeem.apkscope.core.staticanalysis.AppPlatformInfo
import com.nadeem.apkscope.core.staticanalysis.ComponentDescriptor
import com.nadeem.apkscope.core.staticanalysis.IntentFilterDescriptor
import com.nadeem.apkscope.core.staticanalysis.SigningResult
import com.nadeem.apkscope.core.staticanalysis.DexUrlCandidate
import com.nadeem.apkscope.core.staticanalysis.SdkFinding
import com.nadeem.apkscope.core.staticanalysis.ApiFinding
import com.nadeem.apkscope.core.staticanalysis.NetworkSecurityConfigSummary
import com.nadeem.apkscope.core.staticanalysis.StaticAnalysisCoverage
import org.json.JSONArray
import org.json.JSONObject

/**
 * The durable, Room-backed read model for one **completed** analysis (checkpoint 3, item 2) — as
 * distinct from [AnalysisSession] (the in-memory, active/in-progress representation `AnalysisScreen`
 * alone still uses). Every consumer downstream of "analysis complete" (Static Result, Permissions/
 * Components detail, Configure Sandbox, Home's Recent Analysis) reads through this single type via
 * [SessionRepository], whether the session was just analyzed in this same process or reopened after
 * a process restart — there is deliberately only one code path for "read a completed analysis".
 */
data class PersistedComponent(
 val name: String,
 val type: ComponentDescriptor.ComponentType,
 val exported: Boolean,
 val permission: String? = null,
 val intentFilters: List<IntentFilterDescriptor> = emptyList(),
)

data class PersistedAnalysis(
 val sessionId: String,
 val appName: String?,
 val packageName: String,
 val versionName: String?,
 val versionCode: Long,
 val sha256: String,
 val analyzedAtEpochMs: Long,
 val minSdkVersion: Int,
 val targetSdkVersion: Int,
 val debuggable: Boolean,
 val usesCleartextTraffic: Boolean = false,
 val allowBackup: Boolean = true,
 /** See [AnalysisSessionEntity.staticSecurityFieldsKnown]'s doc — `false` means [usesCleartextTraffic]/[allowBackup] above are a pre-Security-Audit row's migration-backfilled defaults, not real analyzer-read values. */
 val staticSecurityFieldsKnown: Boolean = true,
 /** Milestone 10, Phase 10.3 — read from [StaticAnalysisResultStore]'s rich-findings cache, same mechanism as [embeddedUrls]/[detectedSdks]/[apiFindings] below; `null` for any analysis performed before this field existed, or whose cache entry could not be read back (see that store's own doc for exactly when). */
 val networkSecurityConfigPresent: Boolean? = null,
 /** See [com.nadeem.apkscope.core.staticanalysis.ApkMetadata.networkSecurityConfig]'s doc — non-null only when [networkSecurityConfigPresent] is `true`. */
 val networkSecurityConfig: NetworkSecurityConfigSummary? = null,
 val nativeLibraryAbis: List<String>,
 val permissions: List<String>,
 val components: List<PersistedComponent>,
 val signing: SigningResult,
 val riskAssessment: StaticRiskAssessment,
 val platformInfo: AppPlatformInfo = AppPlatformInfo(AppPlatform.UNKNOWN),
 val embeddedUrls: List<DexUrlCandidate> = emptyList(),
 val detectedSdks: List<SdkFinding> = emptyList(),
 val apiFindings: List<ApiFinding> = emptyList(),
 val staticCoverage: StaticAnalysisCoverage = StaticAnalysisCoverage(),
)

data class PersistedAnalysisSummary(
 val sessionId: String,
 val appName: String?,
 val packageName: String,
 val analyzedAtEpochMs: Long,
 val riskScore: Int,
 val riskLevel: RiskLevel,
 val platform: String? = null,
 val platformDetails: String? = null,
)

fun AnalysisSessionEntity.toSummary() = PersistedAnalysisSummary(
 sessionId = sessionId, appName = appName, packageName = packageName, analyzedAtEpochMs = analyzedAtEpochMs,
 riskScore = riskScore, riskLevel = RiskLevel.valueOf(riskLevel),
 platform = platform, platformDetails = platformDetails,
)

fun AnalysisSessionWithDetails.toDomain(context: android.content.Context): PersistedAnalysis {
 val signing = if (session.signatureVerified) {
  SigningResult.Verified(session.signatureDetail?.split(",")?.filter { it.isNotBlank() } ?: emptyList())
 } else {
  SigningResult.Failed(session.signatureDetail ?: "APK signature verification failed")
 }
 return PersistedAnalysis(
  sessionId = session.sessionId,
  appName = session.appName,
  packageName = session.packageName,
  versionName = session.versionName,
  versionCode = session.versionCode,
  sha256 = session.sha256,
  analyzedAtEpochMs = session.analyzedAtEpochMs,
  minSdkVersion = session.minSdkVersion,
  targetSdkVersion = session.targetSdkVersion,
  debuggable = session.debuggable,
  usesCleartextTraffic = session.usesCleartextTraffic,
  allowBackup = session.allowBackup,
  staticSecurityFieldsKnown = session.staticSecurityFieldsKnown,
  networkSecurityConfigPresent = StaticAnalysisResultStore.get(context, session.sessionId)?.networkSecurityConfigPresent,
  networkSecurityConfig = StaticAnalysisResultStore.get(context, session.sessionId)?.networkSecurityConfig,
  nativeLibraryAbis = session.nativeLibraryAbis,
  permissions = permissions.map { it.permission },
  components = components.map {
   PersistedComponent(
    name = it.name,
    type = ComponentDescriptor.ComponentType.valueOf(it.type),
    exported = it.exported,
    permission = it.permission,
    intentFilters = it.intentFiltersJson.toIntentFilterList(),
   )
  },
  signing = signing,
  riskAssessment = StaticRiskAssessment(
   score = session.riskScore,
   level = RiskLevel.valueOf(session.riskLevel),
   findings = findings.map { it.toDomain() },
   basedOnDeclaredCapabilities = permissions.map { com.nadeem.apkscope.core.model.DeclaredCapability(name = it.permission) },
   engineVersion = session.riskEngineVersion,
  ),
  platformInfo = if (session.platform != null) {
   AppPlatformInfo(AppPlatform.fromName(session.platform), session.platformDetails)
  } else {
   AppPlatformInfo(AppPlatform.UNKNOWN)
  },
  embeddedUrls = StaticAnalysisResultStore.get(context, session.sessionId)?.embeddedUrls ?: emptyList(),
  detectedSdks = StaticAnalysisResultStore.get(context, session.sessionId)?.detectedSdks ?: emptyList(),
  apiFindings = StaticAnalysisResultStore.get(context, session.sessionId)?.apiFindings ?: emptyList(),
  staticCoverage = StaticAnalysisResultStore.get(context, session.sessionId)?.staticCoverage ?: StaticAnalysisCoverage(),
 )
}

private fun RiskFindingEntity.toDomain() = RiskFinding(
 ruleId = ruleId, severity = RiskSeverity.valueOf(severity), title = title, explanation = explanation,
 scoreContribution = scoreContribution, evidence = evidence.map { RiskEvidence(it) },
)

/** The other direction — real [ApkAnalysisResult] + [StaticRiskAssessment] → the four Room rows one completed analysis is stored as (item 1). Building this here, next to [toDomain], keeps both directions of the mapping in one file. */
fun buildPersistedRows(sessionId: String, appName: String?, analyzedAtEpochMs: Long, result: ApkAnalysisResult, risk: StaticRiskAssessment): PersistedRows {
 val metadata = result.metadata
 val (verified, detail) = when (val signing = result.signing) {
  is SigningResult.Verified -> true to signing.signerCertificateSha256.joinToString(",")
  is SigningResult.Failed -> false to signing.reason
 }
 val session = AnalysisSessionEntity(
  sessionId = sessionId, packageName = metadata.packageName, appName = appName, versionName = metadata.versionName,
  versionCode = metadata.versionCode, sha256 = metadata.sha256, analyzedAtEpochMs = analyzedAtEpochMs,
  minSdkVersion = metadata.minSdkVersion, targetSdkVersion = metadata.targetSdkVersion, debuggable = metadata.debuggable,
  usesCleartextTraffic = metadata.usesCleartextTraffic, allowBackup = metadata.allowBackup,
  // A fresh analysis, built moments ago from a real APK read — always genuinely known, never a
  // migration-era default. Set explicitly rather than relying on the Kotlin default, matching this
  // project's established "always pass what was actually read" convention (see allowBackup's history).
  staticSecurityFieldsKnown = true,
  signatureVerified = verified, signatureDetail = detail, nativeLibraryAbis = metadata.nativeLibraryAbis,
  permissionCount = metadata.requestedPermissions.size, componentTotalCount = metadata.components.size,
  componentExportedCount = metadata.components.count { it.exported }, riskScore = risk.score, riskLevel = risk.level.name,
  riskEngineVersion = risk.engineVersion,
  platform = metadata.platformInfo.platform.name,
  platformDetails = metadata.platformInfo.details,
 )
 val permissions = metadata.requestedPermissions.map { AnalysisPermissionEntity(sessionId = sessionId, permission = it) }
 val components = metadata.components.map {
  AnalysisComponentEntity(
   sessionId = sessionId,
   name = it.name,
   type = it.type.name,
   exported = it.exported,
   permission = it.permission,
   intentFiltersJson = it.intentFilters.toJsonString(),
  )
 }
 val findings = risk.findings.map {
  RiskFindingEntity(
   sessionId = sessionId, ruleId = it.ruleId, severity = it.severity.name, title = it.title, explanation = it.explanation,
   scoreContribution = it.scoreContribution, evidence = it.evidence.map { e -> e.description },
  )
 }
 return PersistedRows(session, permissions, components, findings)
}

data class PersistedRows(
 val session: AnalysisSessionEntity,
 val permissions: List<AnalysisPermissionEntity>,
 val components: List<AnalysisComponentEntity>,
 val findings: List<RiskFindingEntity>,
)

private fun List<IntentFilterDescriptor>.toJsonString(): String {
 val array = JSONArray()
 for (f in this) {
  val obj = JSONObject()
  obj.put("componentName", f.componentName)
  obj.put("actions", JSONArray(f.actions))
  obj.put("categories", JSONArray(f.categories))
  obj.put("dataSchemes", JSONArray(f.dataSchemes))
  array.put(obj)
 }
 return array.toString()
}

private fun String?.toIntentFilterList(): List<IntentFilterDescriptor> {
 if (this.isNullOrBlank()) return emptyList()
 return try {
  val array = JSONArray(this)
  val result = mutableListOf<IntentFilterDescriptor>()
  for (i in 0 until array.length()) {
   val obj = array.getJSONObject(i)
   val componentName = obj.optString("componentName", "")
   val actions = mutableListOf<String>()
   val actArr = obj.optJSONArray("actions")
   if (actArr != null) {
    for (j in 0 until actArr.length()) actions.add(actArr.getString(j))
   }
   val categories = mutableListOf<String>()
   val catArr = obj.optJSONArray("categories")
   if (catArr != null) {
    for (j in 0 until catArr.length()) categories.add(catArr.getString(j))
   }
   val dataSchemes = mutableListOf<String>()
   val schemeArr = obj.optJSONArray("dataSchemes")
   if (schemeArr != null) {
    for (j in 0 until schemeArr.length()) dataSchemes.add(schemeArr.getString(j))
   }
   result.add(IntentFilterDescriptor(componentName, actions, categories, dataSchemes))
  }
  result
 } catch (_: Exception) {
  emptyList()
 }
}

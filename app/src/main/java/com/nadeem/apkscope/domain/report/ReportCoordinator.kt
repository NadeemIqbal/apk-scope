package com.nadeem.apkscope.domain.report

import android.content.Context
import com.nadeem.apkscope.core.database.AndroidConnectEvidenceEntity
import com.nadeem.apkscope.core.database.AndroidDnsEvidenceEntity
import com.nadeem.apkscope.core.database.FinalReportEntity
import com.nadeem.apkscope.core.database.FinalReportFindingEntity
import com.nadeem.apkscope.core.database.FinalReportWithFindings
import com.nadeem.apkscope.core.database.NetworkObservationEntity
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.core.model.AndroidConnectEvidence
import com.nadeem.apkscope.core.model.AndroidDnsEvidence
import com.nadeem.apkscope.core.model.AndroidEvidenceStatus
import com.nadeem.apkscope.core.model.AndroidEvidenceSummary
import com.nadeem.apkscope.core.model.ApkScopeReport
import com.nadeem.apkscope.core.model.AppIdentity
import com.nadeem.apkscope.core.model.AssessmentType
import com.nadeem.apkscope.core.model.CombinedRiskAssessment
import com.nadeem.apkscope.core.model.DeclaredCapability
import com.nadeem.apkscope.core.model.EvidenceCompleteness
import com.nadeem.apkscope.core.model.EvidenceReference
import com.nadeem.apkscope.core.model.EvidenceSource
import com.nadeem.apkscope.core.model.NetworkObservation
import com.nadeem.apkscope.core.model.ObservedBehaviorSummary
import com.nadeem.apkscope.core.model.RiskFinding
import com.nadeem.apkscope.core.model.RiskLevel
import com.nadeem.apkscope.core.model.RiskSeverity
import com.nadeem.apkscope.core.model.RuleVersions
import com.nadeem.apkscope.core.model.RuntimeRiskAssessment
import com.nadeem.apkscope.core.model.RuntimeRiskInput
import com.nadeem.apkscope.core.model.StaticRiskAssessment
import com.nadeem.apkscope.core.risk.COMBINED_ENGINE_VERSION
import com.nadeem.apkscope.core.risk.CombinedRiskEngine
import com.nadeem.apkscope.core.risk.DefaultCombinedRiskEngine
import com.nadeem.apkscope.core.risk.DefaultRuntimeRiskEngine
import com.nadeem.apkscope.core.risk.RISK_ENGINE_VERSION
import com.nadeem.apkscope.core.risk.RUNTIME_ENGINE_VERSION
import com.nadeem.apkscope.core.risk.RuntimeRiskEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Checkpoint 7: Central coordinator for generating, persisting, and querying final reports.
 *
 * Supports static-only report generation immediately after static analysis,
 * dynamic report generation upon sandbox session completion, and deterministic
 * recomputation when delayed DPM network logs arrive.
 */
class ReportCoordinator(
 context: Context,
 private val runtimeEngine: RuntimeRiskEngine = DefaultRuntimeRiskEngine(),
 private val combinedEngine: CombinedRiskEngine = DefaultCombinedRiskEngine(),
) {
 private val db = SandboxDatabaseProvider.get(context)
 private val finalReportDao = db.finalReportDao()
 private val analysisDao = db.analysisSessionDao()
 private val observationDao = db.observationDao()
 private val androidEvidenceDao = db.androidEvidenceDao()

 /**
  * Generates and persists a static-only report when static analysis completes.
  */
 suspend fun generateStaticOnlyReport(analysisId: String): ApkScopeReport? {
  val details = analysisDao.getDetails(analysisId) ?: return null
  val session = details.session

  val staticAssessment = StaticRiskAssessment(
   score = session.riskScore,
   level = RiskLevel.valueOf(session.riskLevel),
   findings = details.findings.map { f ->
    RiskFinding(
     ruleId = f.ruleId,
     severity = RiskSeverity.valueOf(f.severity),
     title = f.title,
     explanation = f.explanation,
     scoreContribution = f.scoreContribution,
     evidence = f.evidence.map { EvidenceReference(it) },
     assessmentType = AssessmentType.STATIC,
     ruleEngineVersion = session.riskEngineVersion,
     id = "${analysisId}_${f.ruleId}",
    )
   },
   basedOnDeclaredCapabilities = details.permissions.map { DeclaredCapability(it.permission) },
   engineVersion = session.riskEngineVersion,
  )

  val reportId = "report-static-$analysisId"
  val entity = FinalReportEntity(
   reportId = reportId,
   analysisId = analysisId,
   sessionId = null,
   appName = session.appName,
   packageName = session.packageName,
   versionName = session.versionName,
   versionCode = session.versionCode,
   sha256 = session.sha256,
   generatedAtEpochMs = System.currentTimeMillis(),
   overallScore = staticAssessment.score,
   overallLevel = staticAssessment.level.name,
   staticScore = staticAssessment.score,
   staticLevel = staticAssessment.level.name,
   runtimeScore = null,
   runtimeLevel = null,
   combinedScore = null,
   staticEngineVersion = staticAssessment.engineVersion,
   runtimeEngineVersion = null,
   combinedEngineVersion = null,
   staticComplete = true,
   runtimeComplete = false,
   androidEvidenceStatus = AndroidEvidenceStatus.NOT_AVAILABLE.name,
   platform = session.platform,
   platformDetails = session.platformDetails,
  )

  val findingsEntities = staticAssessment.findings.map { f ->
   FinalReportFindingEntity(
    reportId = reportId,
    ruleId = f.ruleId,
    severity = f.severity.name,
    title = f.title,
    explanation = f.explanation,
    scoreContribution = f.scoreContribution,
    assessmentType = AssessmentType.STATIC.name,
    ruleEngineVersion = f.ruleEngineVersion,
    evidenceJson = f.evidence.toJsonString(),
   )
  }

  finalReportDao.upsertReport(entity, findingsEntities)
  return getReport(reportId)
 }

 /**
  * Generates or updates a dynamic report for a sandbox session.
  * Deterministic and idempotent.
  */
 suspend fun generateOrUpdateSessionReport(
  sessionId: String,
  analysisId: String,
  overrideAndroidStatus: AndroidEvidenceStatus? = null,
 ): ApkScopeReport? {
  val details = analysisDao.getDetails(analysisId) ?: return null
  val session = details.session

  val staticAssessment = StaticRiskAssessment(
   score = session.riskScore,
   level = RiskLevel.valueOf(session.riskLevel),
   findings = details.findings.map { f ->
    RiskFinding(
     ruleId = f.ruleId,
     severity = RiskSeverity.valueOf(f.severity),
     title = f.title,
     explanation = f.explanation,
     scoreContribution = f.scoreContribution,
     evidence = f.evidence.map { EvidenceReference(it) },
     assessmentType = AssessmentType.STATIC,
     ruleEngineVersion = session.riskEngineVersion,
     id = "${analysisId}_${f.ruleId}",
    )
   },
   basedOnDeclaredCapabilities = details.permissions.map { DeclaredCapability(it.permission) },
   engineVersion = session.riskEngineVersion,
  )

  // Fetch runtime observations and summaries
  val observationRows = db.openHelper.readableDatabase // ensure db ready
  val summaryEntity = observationDao.getSummary(sessionId)
  val observations = mutableListOf<NetworkObservation>()
  // Query observations via cursor or DAO helper
  val obsEntities = queryObservationsForSession(sessionId)
  for (e in obsEntities) {
   toDomainObservation(e)?.let { observations.add(it) }
  }

  val dpmDnsEntities = androidEvidenceDao.getDnsEventsForSession(sessionId)
  val dpmConnectEntities = androidEvidenceDao.getConnectEventsForSession(sessionId)
  val dpmSummaryEntity = androidEvidenceDao.getSummary(sessionId)

  val androidStatus = overrideAndroidStatus ?: when {
   dpmSummaryEntity != null -> AndroidEvidenceStatus.valueOf(dpmSummaryEntity.status)
   dpmDnsEntities.isNotEmpty() || dpmConnectEntities.isNotEmpty() -> AndroidEvidenceStatus.READY
   else -> AndroidEvidenceStatus.PENDING
  }

  val runtimeInput = RuntimeRiskInput(
   sessionId = sessionId,
   packageName = session.packageName,
   observations = observations,
   androidDns = dpmDnsEntities.map { it.toDomain() },
   androidConnect = dpmConnectEntities.map { it.toDomain() },
   androidStatus = androidStatus,
   summary = summaryEntity?.let {
    ObservedBehaviorSummary(
     sessionId = sessionId,
     connectionCount = it.connectionCount,
     dnsQueryCount = it.dnsQueryCount,
     uniqueObservedDomains = it.uniqueObservedDomains,
     uploadedBytes = it.uploadedBytes,
     downloadedBytes = it.downloadedBytes,
     blockedConnectionCount = it.blockedConnectionCount,
     failedConnectionCount = it.failedConnectionCount,
    )
   },
  )

  val runtimeAssessment = runtimeEngine.evaluate(runtimeInput)
  val combinedAssessment = combinedEngine.evaluate(staticAssessment, runtimeAssessment, runtimeInput)

  val reportId = "report-$sessionId"
  val reportEntity = FinalReportEntity(
   reportId = reportId,
   analysisId = analysisId,
   sessionId = sessionId,
   appName = session.appName,
   packageName = session.packageName,
   versionName = session.versionName,
   versionCode = session.versionCode,
   sha256 = session.sha256,
   generatedAtEpochMs = System.currentTimeMillis(),
   overallScore = combinedAssessment.overallScore,
   overallLevel = combinedAssessment.overallLevel.name,
   staticScore = staticAssessment.score,
   staticLevel = staticAssessment.level.name,
   runtimeScore = runtimeAssessment.score,
   runtimeLevel = runtimeAssessment.level.name,
   combinedScore = combinedAssessment.combinedFindings.sumOf { it.scoreContribution },
   staticEngineVersion = staticAssessment.engineVersion,
   runtimeEngineVersion = RUNTIME_ENGINE_VERSION,
   combinedEngineVersion = COMBINED_ENGINE_VERSION,
   staticComplete = true,
   runtimeComplete = true,
   androidEvidenceStatus = androidStatus.name,
   platform = session.platform,
   platformDetails = session.platformDetails,
  )

  val allFindingsEntities = mutableListOf<FinalReportFindingEntity>()

  // 1. Static findings
  for (f in staticAssessment.findings) {
   allFindingsEntities.add(
    FinalReportFindingEntity(
     reportId = reportId,
     ruleId = f.ruleId,
     severity = f.severity.name,
     title = f.title,
     explanation = f.explanation,
     scoreContribution = f.scoreContribution,
     assessmentType = AssessmentType.STATIC.name,
     ruleEngineVersion = f.ruleEngineVersion,
     evidenceJson = f.evidence.toJsonString(),
    ),
   )
  }

  // 2. Runtime findings
  for (f in runtimeAssessment.findings) {
   allFindingsEntities.add(
    FinalReportFindingEntity(
     reportId = reportId,
     ruleId = f.ruleId,
     severity = f.severity.name,
     title = f.title,
     explanation = f.explanation,
     scoreContribution = f.scoreContribution,
     assessmentType = AssessmentType.RUNTIME.name,
     ruleEngineVersion = f.ruleEngineVersion,
     evidenceJson = f.evidence.toJsonString(),
    ),
   )
  }

  // 3. Combined findings
  for (f in combinedAssessment.combinedFindings) {
   allFindingsEntities.add(
    FinalReportFindingEntity(
     reportId = reportId,
     ruleId = f.ruleId,
     severity = f.severity.name,
     title = f.title,
     explanation = f.explanation,
     scoreContribution = f.scoreContribution,
     assessmentType = AssessmentType.COMBINED.name,
     ruleEngineVersion = f.ruleEngineVersion,
     evidenceJson = f.evidence.toJsonString(),
    ),
   )
  }

  finalReportDao.upsertReport(reportEntity, allFindingsEntities)
  return getReport(reportId)
 }

 suspend fun getReport(reportId: String): ApkScopeReport? {
  val reportWithFindings = finalReportDao.getReport(reportId) ?: return null
  return mapToDomainReport(reportWithFindings)
 }

 suspend fun getReportForSession(sessionId: String): ApkScopeReport? {
  val reportWithFindings = finalReportDao.getReportForSession(sessionId) ?: return null
  return mapToDomainReport(reportWithFindings)
 }

 fun observeReportForSession(sessionId: String): Flow<ApkScopeReport?> {
  return finalReportDao.observeReportForSession(sessionId).map { it?.let { mapToDomainReport(it) } }
 }

 suspend fun getLatestReportForAnalysis(analysisId: String): ApkScopeReport? {
  val reportWithFindings = finalReportDao.getLatestReportForAnalysis(analysisId) ?: return null
  return mapToDomainReport(reportWithFindings)
 }

 fun observeLatestReportForAnalysis(analysisId: String): Flow<ApkScopeReport?> {
  return finalReportDao.observeLatestReportForAnalysis(analysisId).map { it?.let { mapToDomainReport(it) } }
 }

 fun observeAllReports(): Flow<List<FinalReportEntity>> {
  return finalReportDao.observeAllReports()
 }

 private suspend fun mapToDomainReport(withFindings: FinalReportWithFindings): ApkScopeReport {
  val rep = withFindings.report
  val findings = withFindings.findings.map { f ->
   RiskFinding(
    ruleId = f.ruleId,
    severity = RiskSeverity.valueOf(f.severity),
    title = f.title,
    explanation = f.explanation,
    scoreContribution = f.scoreContribution,
    evidence = parseEvidenceJson(f.evidenceJson),
    assessmentType = AssessmentType.valueOf(f.assessmentType),
    ruleEngineVersion = f.ruleEngineVersion,
    id = "${rep.reportId}_${f.ruleId}_${f.id}",
   )
  }

  val staticFindings = findings.filter { it.assessmentType == AssessmentType.STATIC }
  val runtimeFindings = findings.filter { it.assessmentType == AssessmentType.RUNTIME }
  val combinedFindings = findings.filter { it.assessmentType == AssessmentType.COMBINED }

  val details = analysisDao.getDetails(rep.analysisId)
  val permissions = details?.permissions?.map { DeclaredCapability(it.permission) } ?: emptyList()

  val staticAssessment = StaticRiskAssessment(
   score = rep.staticScore,
   level = RiskLevel.valueOf(rep.staticLevel),
   findings = staticFindings,
   basedOnDeclaredCapabilities = permissions,
   engineVersion = rep.staticEngineVersion,
  )

  val rScore = rep.runtimeScore
  val runtimeAssessment = if (rScore != null) {
   RuntimeRiskAssessment(
    score = rScore,
    level = RiskLevel.valueOf(rep.runtimeLevel ?: "LOW"),
    findings = runtimeFindings,
    engineVersion = rep.runtimeEngineVersion ?: RUNTIME_ENGINE_VERSION,
   )
  } else null

  val combinedAssessment = if (runtimeAssessment != null) {
   CombinedRiskAssessment(
    static = staticAssessment,
    runtime = runtimeAssessment,
    overallScore = rep.overallScore,
    overallLevel = RiskLevel.valueOf(rep.overallLevel),
    combinedFindings = combinedFindings,
    engineVersion = rep.combinedEngineVersion ?: COMBINED_ENGINE_VERSION,
    isRuntimeComplete = true,
   )
  } else null

  val obsSummary = rep.sessionId?.let { sid ->
   observationDao.getSummary(sid)?.let {
    ObservedBehaviorSummary(
     sessionId = sid,
     connectionCount = it.connectionCount,
     dnsQueryCount = it.dnsQueryCount,
     uniqueObservedDomains = it.uniqueObservedDomains,
     uploadedBytes = it.uploadedBytes,
     downloadedBytes = it.downloadedBytes,
     blockedConnectionCount = it.blockedConnectionCount,
     failedConnectionCount = it.failedConnectionCount,
    )
   }
  }

  val dpmSummary = rep.sessionId?.let { sid ->
   androidEvidenceDao.getSummary(sid)?.let {
    AndroidEvidenceSummary(
     sessionId = sid,
     dnsCount = it.dnsCount,
     connectCount = it.connectCount,
     status = AndroidEvidenceStatus.valueOf(it.status),
     firstEventAt = it.firstEventTimestampEpochMs?.let { ms -> Instant.ofEpochMilli(ms) },
     lastEventAt = it.lastEventTimestampEpochMs?.let { ms -> Instant.ofEpochMilli(ms) },
     importedAt = Instant.ofEpochMilli(it.importedAtEpochMs),
    )
   }
  }

  return ApkScopeReport(
   reportId = rep.reportId,
   analysisId = rep.analysisId,
   sessionId = rep.sessionId,
   generatedAt = Instant.ofEpochMilli(rep.generatedAtEpochMs),
   appIdentity = AppIdentity(
    packageName = rep.packageName,
    appName = rep.appName,
    versionName = rep.versionName,
    versionCode = rep.versionCode,
    sha256 = rep.sha256,
   ),
   staticAssessment = staticAssessment,
   runtimeAssessment = runtimeAssessment,
   combinedAssessment = combinedAssessment,
   declaredCapabilities = permissions,
   observedBehaviorSummary = obsSummary,
   androidEvidenceSummary = dpmSummary,
   evidenceCompleteness = EvidenceCompleteness(
    staticComplete = rep.staticComplete,
    runtimeComplete = rep.runtimeComplete,
    androidEvidenceStatus = AndroidEvidenceStatus.valueOf(rep.androidEvidenceStatus),
   ),
   ruleVersions = RuleVersions(
    staticVersion = rep.staticEngineVersion,
    runtimeVersion = rep.runtimeEngineVersion,
    combinedVersion = rep.combinedEngineVersion,
   ),
  )
 }

 private suspend fun queryObservationsForSession(sessionId: String): List<NetworkObservationEntity> {
  // Query Room directly through sqlite cursor
  val list = mutableListOf<NetworkObservationEntity>()
  val cursor = db.openHelper.readableDatabase.query(
   "SELECT * FROM network_observations WHERE sessionId = ? ORDER BY sequence ASC",
   arrayOf(sessionId),
  )
  cursor.use {
   val colSessionId = it.getColumnIndex("sessionId")
   val colSequence = it.getColumnIndex("sequence")
   val colTimestamp = it.getColumnIndex("timestampEpochMs")
   val colType = it.getColumnIndex("type")
   val colProtocol = it.getColumnIndex("protocol")
   val colDestIp = it.getColumnIndex("destinationIp")
   val colDestPort = it.getColumnIndex("destinationPort")
   val colConnId = it.getColumnIndex("connectionId")
   val colStartTime = it.getColumnIndex("startTimeEpochMs")
   val colEndTime = it.getColumnIndex("endTimeEpochMs")
   val colUpload = it.getColumnIndex("uploadedBytes")
   val colDownload = it.getColumnIndex("downloadedBytes")
   val colFailReason = it.getColumnIndex("failureReason")
   val colFailDetail = it.getColumnIndex("failureDetail")
   val colHostname = it.getColumnIndex("hostname")
   val colResolved = it.getColumnIndex("resolvedAddressesCsv")
   val colTxId = it.getColumnIndex("transactionId")
   val colSourcePort = it.getColumnIndex("sourcePort")
   val colLimitName = it.getColumnIndex("limitName")
   val colCurrentVal = it.getColumnIndex("currentValue")
   val colLimitVal = it.getColumnIndex("limitValue")

   while (it.moveToNext()) {
    list.add(
     NetworkObservationEntity(
      sessionId = it.getString(colSessionId),
      sequence = it.getLong(colSequence),
      timestampEpochMs = it.getLong(colTimestamp),
      type = it.getString(colType),
      protocol = if (it.isNull(colProtocol)) null else it.getString(colProtocol),
      destinationIp = if (it.isNull(colDestIp)) null else it.getString(colDestIp),
      destinationPort = if (it.isNull(colDestPort)) null else it.getInt(colDestPort),
      connectionId = if (it.isNull(colConnId)) null else it.getLong(colConnId),
      startTimeEpochMs = if (it.isNull(colStartTime)) null else it.getLong(colStartTime),
      endTimeEpochMs = if (it.isNull(colEndTime)) null else it.getLong(colEndTime),
      uploadedBytes = if (it.isNull(colUpload)) null else it.getLong(colUpload),
      downloadedBytes = if (it.isNull(colDownload)) null else it.getLong(colDownload),
      failureReason = if (it.isNull(colFailReason)) null else it.getString(colFailReason),
      failureDetail = if (it.isNull(colFailDetail)) null else it.getString(colFailDetail),
      hostname = if (it.isNull(colHostname)) null else it.getString(colHostname),
      resolvedAddressesCsv = if (it.isNull(colResolved)) null else it.getString(colResolved),
      transactionId = if (it.isNull(colTxId)) null else it.getInt(colTxId),
      sourcePort = if (it.isNull(colSourcePort)) null else it.getInt(colSourcePort),
      limitName = if (it.isNull(colLimitName)) null else it.getString(colLimitName),
      currentValue = if (it.isNull(colCurrentVal)) null else it.getLong(colCurrentVal),
      limitValue = if (it.isNull(colLimitVal)) null else it.getLong(colLimitVal),
     ),
    )
   }
  }
  return list
 }

 private fun toDomainObservation(e: NetworkObservationEntity): NetworkObservation? {
  val ts = Instant.ofEpochMilli(e.timestampEpochMs)
  return when (e.type) {
   "ConnectionOpened" -> NetworkObservation.ConnectionOpened(
    timestamp = ts,
    connectionId = e.connectionId ?: 0L,
    protocol = NetworkObservation.Protocol.valueOf(e.protocol ?: "TCP"),
    destinationIp = e.destinationIp ?: "",
    destinationPort = e.destinationPort ?: 0,
   )
   "ConnectionClosed" -> NetworkObservation.ConnectionClosed(
    timestamp = ts,
    connectionId = e.connectionId ?: 0L,
    protocol = NetworkObservation.Protocol.valueOf(e.protocol ?: "TCP"),
    destinationIp = e.destinationIp ?: "",
    destinationPort = e.destinationPort ?: 0,
    startTime = e.startTimeEpochMs?.let { Instant.ofEpochMilli(it) } ?: ts,
    endTime = e.endTimeEpochMs?.let { Instant.ofEpochMilli(it) } ?: ts,
    uploadedBytes = e.uploadedBytes ?: 0L,
    downloadedBytes = e.downloadedBytes ?: 0L,
   )
   "ConnectionFailed" -> NetworkObservation.ConnectionFailed(
    timestamp = ts,
    protocol = NetworkObservation.Protocol.valueOf(e.protocol ?: "TCP"),
    destinationIp = e.destinationIp ?: "",
    destinationPort = e.destinationPort ?: 0,
    reason = NetworkObservation.FailureReason.valueOf(e.failureReason ?: "OTHER"),
    detail = e.failureDetail ?: "",
   )
   "DnsQuery" -> NetworkObservation.DnsQuery(
    timestamp = ts,
    transactionId = e.transactionId ?: 0,
    hostname = e.hostname ?: "",
    sourcePort = e.sourcePort ?: 0,
   )
   "DnsResponse" -> NetworkObservation.DnsResponse(
    timestamp = ts,
    transactionId = e.transactionId ?: 0,
    hostname = e.hostname,
    resolvedAddresses = e.resolvedAddressesCsv?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    sourcePort = e.sourcePort ?: 0,
   )
   "ResourceLimitExceeded" -> NetworkObservation.ResourceLimitExceeded(
    timestamp = ts,
    limitName = e.limitName ?: "",
    currentValue = e.currentValue ?: 0L,
    limitValue = e.limitValue ?: 0L,
   )
   else -> null
  }
 }

 private fun AndroidDnsEvidenceEntity.toDomain() = AndroidDnsEvidence(
  eventId = eventId,
  batchToken = batchToken,
  packageName = packageName,
  timestamp = Instant.ofEpochMilli(timestampEpochMs),
  receivedAt = Instant.ofEpochMilli(receivedAtEpochMs),
  hostname = hostname,
  resolvedAddresses = resolvedAddressesCsv.split(",").filter { it.isNotBlank() },
  totalResolvedAddressCount = totalResolvedAddressCount,
 )

 private fun AndroidConnectEvidenceEntity.toDomain() = AndroidConnectEvidence(
  eventId = eventId,
  batchToken = batchToken,
  packageName = packageName,
  timestamp = Instant.ofEpochMilli(timestampEpochMs),
  receivedAt = Instant.ofEpochMilli(receivedAtEpochMs),
  destinationAddress = destinationAddress,
  destinationPort = destinationPort,
 )
}

private fun List<EvidenceReference>.toJsonString(): String {
 val arr = JSONArray()
 for (ref in this) {
  arr.put(
   JSONObject()
    .put("source", ref.source.name)
    .put("evidenceId", ref.evidenceId)
    .put("description", ref.description),
  )
 }
 return arr.toString()
}

private fun parseEvidenceJson(json: String): List<EvidenceReference> {
 if (json.isBlank()) return emptyList()
 val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
 val list = mutableListOf<EvidenceReference>()
 for (i in 0 until arr.length()) {
  val obj = arr.optJSONObject(i) ?: continue
  list.add(
   EvidenceReference(
    source = EvidenceSource.valueOf(obj.optString("source", EvidenceSource.DECLARED_CAPABILITY.name)),
    evidenceId = obj.optString("evidenceId", ""),
    description = obj.optString("description", ""),
   ),
  )
 }
 return list
}

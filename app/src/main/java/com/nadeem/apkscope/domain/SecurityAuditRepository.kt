package com.nadeem.apkscope.domain

import android.content.Context
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.core.database.SecurityAuditEntity
import com.nadeem.apkscope.core.database.SecurityAuditFindingEntity
import com.nadeem.apkscope.core.database.SecurityAuditWithFindings
import com.nadeem.apkscope.core.risk.audit.AuditFinding
import com.nadeem.apkscope.core.risk.audit.AuditOutcome
import com.nadeem.apkscope.core.risk.audit.Confidence
import com.nadeem.apkscope.core.risk.audit.DefaultSecurityAuditEngine
import com.nadeem.apkscope.core.risk.audit.SecurityAuditEngine
import com.nadeem.apkscope.core.risk.audit.SecurityAuditReport
import com.nadeem.apkscope.core.risk.audit.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Milestone 10 (Security Audit), Phase 10.2 — the one repository every Security Audit ViewModel
 * goes through, mirroring [SessionRepository]'s split between "run and persist" and "observe
 * persisted" that the rest of this codebase already uses for the analogous final-report flow. Never
 * touches [SessionRepository]'s own tables or [com.nadeem.apkscope.core.risk.DefaultRiskEngine]'s output —
 * MS10-FOUND04's explicit requirement that Security Audit never automatically changes the existing
 * deterministic risk score.
 */
/**
 * `open`, not `sealed`/`final`: [SecurityAuditViewModel]'s test suite subclasses this to inject a
 * controllable delay ahead of the real work, making the cancellation race deterministic rather than
 * racing real disk/computation timing — the same problem a manual on-device tap-through cannot
 * reliably hit either, per this milestone's own STATE.md checkpoint. No DI framework is introduced;
 * this is the minimal seam needed for that one test to be deterministic.
 */
open class SecurityAuditRepository(private val context: Context) {
 private val dao = SandboxDatabaseProvider.get(context).securityAuditDao()
 private val sessionRepository = SessionRepository(context)
 private val engine: SecurityAuditEngine = DefaultSecurityAuditEngine()

 /**
  * Loads the persisted analysis for [sessionId], runs the full v1 static catalog against it, and
  * atomically persists the result — replacing any prior audit for the same analysis (one current
  * audit per analysis, per [SecurityAuditEntity]'s unique index on `analysisId`). Returns the report
  * on success. Throws [AnalysisNotFoundException] if no persisted analysis exists for [sessionId] —
  * the caller (ViewModel) turns that into an explicit error state, never a silently empty report.
  */
 open suspend fun runAndPersistAudit(sessionId: String): SecurityAuditReport {
  val analysis = sessionRepository.getPersisted(sessionId) ?: throw AnalysisNotFoundException(sessionId)
  val input = analysis.toRiskInput()
  val report = engine.audit(input)
  val auditId = UUID.randomUUID().toString()
  val auditEntity = SecurityAuditEntity(
   auditId = auditId,
   analysisId = sessionId,
   engineVersion = report.engineVersion,
   createdAtEpochMs = System.currentTimeMillis(),
   status = AuditStatus.COMPLETE.name,
  )
  val findingEntities = report.findings.map { it.toEntity(auditId) }
  dao.upsertAudit(auditEntity, findingEntities)
  return report
 }

 /** Reads whatever audit currently exists for [sessionId], or `null` if none has ever run — distinct from a completed audit with zero findings, which would be a non-null report with an empty findings list (not expected with the current 10-rule catalog, which always produces one finding per rule, but the type distinction matters once later phases add rules that can be `NOT_APPLICABLE`-skipped). */
 open fun observeAudit(sessionId: String): Flow<SecurityAuditReport?> =
  dao.observeAuditForAnalysis(sessionId).map { it?.toDomain() }

 open suspend fun getAudit(sessionId: String): SecurityAuditReport? =
  dao.getAuditForAnalysis(sessionId)?.toDomain()
}

class AnalysisNotFoundException(sessionId: String) : Exception("No persisted analysis found for sessionId=$sessionId")

/** RUNNING is intentionally unused by [SecurityAuditRepository] itself — a static audit is fast, synchronous, in-memory computation once the persisted analysis is loaded, so there is no real intermediate state to persist; a killed process mid-run simply leaves no row (correctly indistinguishable from "never run") since the Room write is one atomic transaction. Reserved for Phase 10.5+ guided sessions, which have genuine long-running, interruptible work. */
enum class AuditStatus { RUNNING, COMPLETE, CANCELLED, FAILED }

private fun AuditFinding.toEntity(auditId: String) = SecurityAuditFindingEntity(
 auditId = auditId,
 ruleId = ruleId,
 outcome = outcome.name,
 severity = severity.name,
 confidence = confidence.name,
 title = title,
 detail = detail,
 remediation = remediation,
)

private fun SecurityAuditFindingEntity.toDomain() = AuditFinding(
 ruleId = ruleId,
 outcome = AuditOutcome.valueOf(outcome),
 severity = Severity.valueOf(severity),
 confidence = Confidence.valueOf(confidence),
 title = title,
 detail = detail,
 remediation = remediation,
)

private fun SecurityAuditWithFindings.toDomain() = SecurityAuditReport(
 engineVersion = audit.engineVersion,
 findings = findings.map { it.toDomain() },
)

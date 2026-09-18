package com.nadeem.apkscope.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Milestone 10 (Security Audit) durable storage — mirrors [FinalReportEntity]/[FinalReportFindingEntity]'s
 * shape (one parent row, N child finding rows, cascade delete) since a Security Audit is exactly the
 * same relationship: one analysis (referenced by [analysisId], `AnalysisSessionEntity.sessionId`) has
 * zero-or-one current audit, which has N findings. Deliberately a **separate** table from
 * `final_reports` — [MS10-FOUND04 in `.planning/REQUIREMENTS.md`] requires Security Audit findings to
 * never automatically alter the existing risk score/final report; a shared table would make that
 * separation easy to violate by accident.
 *
 * [status] models MS10-UI05's explicit non-clean states (loading/cancellation/error), not just success
 * — an audit that was cancelled or failed leaves a row here so a reopen shows *why* nothing rendered,
 * rather than an empty screen indistinguishable from "audit succeeded, empty catalog".
 */
@Entity(
 tableName = "security_audits",
 indices = [Index("analysisId", unique = true)],
)
data class SecurityAuditEntity(
 @PrimaryKey val auditId: String,
 val analysisId: String, // AnalysisSessionEntity.sessionId — one current audit per analysis (MS10-FOUND01)
 val engineVersion: String,
 val createdAtEpochMs: Long,
 /** RUNNING / COMPLETE / CANCELLED / FAILED — see MS10-UI05. A row in RUNNING state left behind by a killed process is treated as stale on next read (the repository layer's job, not this entity's), never presented as a valid in-progress state indefinitely. */
 val status: String,
 /** Non-null only when [status] == FAILED — the reason, for display, never silently swallowed. */
 val failureReason: String? = null,
)

@Entity(
 tableName = "security_audit_findings",
 foreignKeys = [
  ForeignKey(
   entity = SecurityAuditEntity::class,
   parentColumns = ["auditId"],
   childColumns = ["auditId"],
   onDelete = ForeignKey.CASCADE,
  ),
 ],
 indices = [Index("auditId"), Index("ruleId")],
)
data class SecurityAuditFindingEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val auditId: String,
 val ruleId: String,
 val outcome: String,
 val severity: String,
 val confidence: String,
 val title: String,
 val detail: String,
 val remediation: String,
)

data class SecurityAuditWithFindings(
 @Embedded val audit: SecurityAuditEntity,
 @Relation(parentColumn = "auditId", entityColumn = "auditId")
 val findings: List<SecurityAuditFindingEntity>,
)

@Dao
interface SecurityAuditDao {
 @Insert(onConflict = OnConflictStrategy.REPLACE)
 suspend fun insertAudit(audit: SecurityAuditEntity)

 @Insert(onConflict = OnConflictStrategy.REPLACE)
 suspend fun insertFindings(findings: List<SecurityAuditFindingEntity>)

 @Query("DELETE FROM security_audit_findings WHERE auditId = :auditId")
 suspend fun deleteFindings(auditId: String)

 @Query("DELETE FROM security_audits WHERE auditId = :auditId")
 suspend fun deleteAudit(auditId: String)

 @Query("DELETE FROM security_audits WHERE analysisId = :analysisId")
 suspend fun deleteAuditsForAnalysis(analysisId: String)

 /** Atomically replaces this analysis's current audit — re-running an audit for the same [analysisId] fully replaces the prior one (index is `unique` on [SecurityAuditEntity.analysisId]) rather than accumulating history; MS10-RPT05's build-comparison needs are a distinct, later requirement this table does not yet serve. */
 @Transaction
 suspend fun upsertAudit(audit: SecurityAuditEntity, findings: List<SecurityAuditFindingEntity>) {
  deleteAuditsForAnalysis(audit.analysisId)
  insertAudit(audit)
  insertFindings(findings)
 }

 @Transaction
 @Query("SELECT * FROM security_audits WHERE analysisId = :analysisId")
 suspend fun getAuditForAnalysis(analysisId: String): SecurityAuditWithFindings?

 @Transaction
 @Query("SELECT * FROM security_audits WHERE analysisId = :analysisId")
 fun observeAuditForAnalysis(analysisId: String): Flow<SecurityAuditWithFindings?>
}

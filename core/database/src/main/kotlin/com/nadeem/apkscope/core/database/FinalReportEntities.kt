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
 * Checkpoint 7: Personal-side durable storage for the final security report.
 *
 * Survives process death, app restart, and device restart.
 * Relationship: Analysis -> 0..N Sandbox Sessions -> final report.
 * Supports static-only report (sessionId is null) and multiple dynamic sessions.
 */
@Entity(
 tableName = "final_reports",
 indices = [
  Index("analysisId"),
  Index("sessionId"),
  Index("generatedAtEpochMs"),
 ],
)
data class FinalReportEntity(
 @PrimaryKey val reportId: String,
 val analysisId: String,
 val sessionId: String?, // null for static-only report
 val appName: String?,
 val packageName: String,
 val versionName: String?,
 val versionCode: Long,
 val sha256: String,
 val generatedAtEpochMs: Long,
 val overallScore: Int,
 val overallLevel: String,
 val staticScore: Int,
 val staticLevel: String,
 val runtimeScore: Int?,
 val runtimeLevel: String?,
 val combinedScore: Int?,
 val staticEngineVersion: String,
 val runtimeEngineVersion: String?,
 val combinedEngineVersion: String?,
 val staticComplete: Boolean,
 val runtimeComplete: Boolean,
 val androidEvidenceStatus: String,
 val platform: String? = null,
 val platformDetails: String? = null,
)

@Entity(
 tableName = "final_report_findings",
 foreignKeys = [
  ForeignKey(
   entity = FinalReportEntity::class,
   parentColumns = ["reportId"],
   childColumns = ["reportId"],
   onDelete = ForeignKey.CASCADE,
  ),
 ],
 indices = [
  Index("reportId"),
  Index("ruleId"),
 ],
)
data class FinalReportFindingEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val reportId: String,
 val ruleId: String,
 val severity: String,
 val title: String,
 val explanation: String,
 val scoreContribution: Int,
 val assessmentType: String, // STATIC, RUNTIME, COMBINED
 val ruleEngineVersion: String,
 /** Serialized evidence references in JSON */
 val evidenceJson: String,
)

data class FinalReportWithFindings(
 @Embedded val report: FinalReportEntity,
 @Relation(parentColumn = "reportId", entityColumn = "reportId")
 val findings: List<FinalReportFindingEntity>,
)

@Dao
interface FinalReportDao {
 @Insert(onConflict = OnConflictStrategy.REPLACE)
 suspend fun insertReport(report: FinalReportEntity)

 @Insert(onConflict = OnConflictStrategy.REPLACE)
 suspend fun insertFindings(findings: List<FinalReportFindingEntity>)

 @Query("DELETE FROM final_report_findings WHERE reportId = :reportId")
 suspend fun deleteFindings(reportId: String)

 @Query("DELETE FROM final_reports WHERE reportId = :reportId")
 suspend fun deleteReport(reportId: String)

 @Transaction
 suspend fun upsertReport(report: FinalReportEntity, findings: List<FinalReportFindingEntity>) {
  insertReport(report)
  deleteFindings(report.reportId)
  insertFindings(findings)
 }

 @Transaction
 @Query("SELECT * FROM final_reports WHERE reportId = :reportId")
 suspend fun getReport(reportId: String): FinalReportWithFindings?

 @Transaction
 @Query("SELECT * FROM final_reports WHERE reportId = :reportId")
 fun observeReport(reportId: String): Flow<FinalReportWithFindings?>

 @Transaction
 @Query("SELECT * FROM final_reports WHERE sessionId = :sessionId")
 suspend fun getReportForSession(sessionId: String): FinalReportWithFindings?

 @Transaction
 @Query("SELECT * FROM final_reports WHERE sessionId = :sessionId")
 fun observeReportForSession(sessionId: String): Flow<FinalReportWithFindings?>

 @Transaction
 @Query("SELECT * FROM final_reports WHERE analysisId = :analysisId ORDER BY generatedAtEpochMs DESC LIMIT 1")
 suspend fun getLatestReportForAnalysis(analysisId: String): FinalReportWithFindings?

 @Transaction
 @Query("SELECT * FROM final_reports WHERE analysisId = :analysisId ORDER BY generatedAtEpochMs DESC LIMIT 1")
 fun observeLatestReportForAnalysis(analysisId: String): Flow<FinalReportWithFindings?>

 @Query("SELECT * FROM final_reports ORDER BY generatedAtEpochMs DESC")
 fun observeAllReports(): Flow<List<FinalReportEntity>>

 @Query("SELECT * FROM final_reports ORDER BY generatedAtEpochMs DESC")
 suspend fun getAllReports(): List<FinalReportEntity>

 @Query("DELETE FROM final_reports WHERE sessionId = :sessionId")
 suspend fun deleteReportsForSession(sessionId: String): Int
}

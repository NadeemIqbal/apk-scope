package com.nadeem.apkscope.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverter
import kotlinx.coroutines.flow.Flow

/**
 * Checkpoint 3, item 1: durable storage for a *completed* static analysis — deliberately separate
 * from `SandboxSessionEntity` above, which tracks a sandbox run's lifecycle, not an analysis
 * result. An in-progress analysis (copying, mid-stage) is never written here; only a finished
 * [com.nadeem.apkscope.core.model.StaticRiskAssessment] and the metadata it was computed from ever are
 * (see `app`'s `SessionRepository` for where that boundary is enforced).
 *
 * Schema choice (item 1's "document the choice"): permissions and components are **normalized**
 * into their own related tables ([AnalysisPermissionEntity]/[AnalysisComponentEntity]) rather than
 * one opaque blob — both are unbounded-length flat lists of independent facts a future checkpoint
 * plausibly wants to query directly (e.g. "every session that declared CAMERA"), and the existing
 * Permissions/Components detail screens already need the *complete* list, not just a count, to keep
 * working identically after a process-death reopen. Risk-finding *evidence* text
 * ([RiskFindingEntity.evidence]), by contrast, is small, always flat, never independently queried,
 * and has no natural row-per-item value — it is stored via a documented [TypeConverter]
 * (plain values joined on a control character, not JSON — no JSON library dependency needed for a
 * flat list of plain-text strings) rather than a fifth table.
 */
@Entity(tableName = "analysis_sessions")
data class AnalysisSessionEntity(
 @PrimaryKey val sessionId: String,
 val packageName: String,
 val appName: String?,
 val versionName: String?,
 val versionCode: Long,
 val sha256: String,
 val analyzedAtEpochMs: Long,
 val minSdkVersion: Int,
 val targetSdkVersion: Int,
 val debuggable: Boolean,
 /**
  * Added for Security Audit (Milestone 10) — see [com.nadeem.apkscope.core.staticanalysis.ApkMetadata.usesCleartextTraffic]'s
  * own doc for the underlying flag's device-verification caveat. This doc previously claimed
  * `fallbackToDestructiveMigration` would wipe any pre-existing row rather than let a stale default
  * reach a real row — that stopped being true once `MIGRATION_8_9` replaced the destructive fallback
  * for exactly this transition with a real, data-preserving one (Phase 10.2 correction pass). A row
  * migrated through 8→9 gets this column's bare SQL default (`false`), not a real analyzer-read
  * value — see [staticSecurityFieldsKnown] below, added specifically so that default is never
  * mistaken for a confirmed "no cleartext traffic" result by [com.nadeem.apkscope.core.risk.audit.StaticAuditRules].
  */
 val usesCleartextTraffic: Boolean = false,
 /** Added for Security Audit (Milestone 10) — see [com.nadeem.apkscope.core.staticanalysis.ApkMetadata.allowBackup]'s own doc. Same [staticSecurityFieldsKnown] caveat as [usesCleartextTraffic] above applies. */
 val allowBackup: Boolean = true,
 /**
  * Milestone 10 (Security Audit), Phase 10.3 correction: `true` only when [usesCleartextTraffic] and
  * [allowBackup] were actually read from a real APK analysis (every insert this app's own code
  * performs sets this explicitly `true` — see `SessionRepository`) — `false` for any row that
  * predates these two columns and was backfilled with their bare SQL defaults by `MIGRATION_9_10`,
  * which cannot know their real values. Exists so `StaticAuditRules`' `CLEARTEXT_TRAFFIC_ENABLED`/
  * `BACKUP_ENABLED` rules report [com.nadeem.apkscope.core.risk.audit.AuditOutcome.NOT_TESTED] instead of
  * a false `CHECK_PASSED`/`NEEDS_REVIEW` for pre-migration rows — a defaulted "false" must never read
  * as a confirmed "no cleartext traffic", the exact false-pass this field prevents.
  */
 val staticSecurityFieldsKnown: Boolean = true,
 val signatureVerified: Boolean,
 /** Signer certificate SHA-256(es), joined, when [signatureVerified]; the verification-failure reason when not. Either way, plain text meant for direct display, never parsed back apart from the join/split. */
 val signatureDetail: String?,
 val nativeLibraryAbis: List<String>,
 val permissionCount: Int,
 val componentTotalCount: Int,
 val componentExportedCount: Int,
 val riskScore: Int,
 val riskLevel: String,
 /** Item 19: which `core:risk` rule set produced [riskScore]/[riskLevel]/the linked [RiskFindingEntity] rows — never silently reinterpreted if the engine's rule set changes later. */
 val riskEngineVersion: String,
 val platform: String? = null,
 val platformDetails: String? = null,
)

@Entity(
 tableName = "analysis_permissions",
 foreignKeys = [ForeignKey(entity = AnalysisSessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
 indices = [Index("sessionId")],
)
data class AnalysisPermissionEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val sessionId: String,
 val permission: String,
)

@Entity(
 tableName = "analysis_components",
 foreignKeys = [ForeignKey(entity = AnalysisSessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
 indices = [Index("sessionId")],
)
data class AnalysisComponentEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val sessionId: String,
 val name: String,
 val type: String,
 val exported: Boolean,
 val permission: String? = null,
 val intentFiltersJson: String? = null,
)

@Entity(
 tableName = "analysis_risk_findings",
 foreignKeys = [ForeignKey(entity = AnalysisSessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
 indices = [Index("sessionId")],
)
data class RiskFindingEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val sessionId: String,
 val ruleId: String,
 val severity: String,
 val title: String,
 val explanation: String,
 val scoreContribution: Int,
 val evidence: List<String>,
)

/** Room `@Relation`-backed read model for one session's full detail — the shape `getDetails`/`observeDetails` return, mapped to the app-layer `PersistedAnalysis` domain type by `SessionRepository`. */
data class AnalysisSessionWithDetails(
 @androidx.room.Embedded val session: AnalysisSessionEntity,
 @androidx.room.Relation(parentColumn = "sessionId", entityColumn = "sessionId") val permissions: List<AnalysisPermissionEntity>,
 @androidx.room.Relation(parentColumn = "sessionId", entityColumn = "sessionId") val components: List<AnalysisComponentEntity>,
 @androidx.room.Relation(parentColumn = "sessionId", entityColumn = "sessionId") val findings: List<RiskFindingEntity>,
)

/** A single control character as the list separator — chosen specifically because it cannot appear in ordinary manifest/permission-name or human-readable evidence text, so no escaping logic is needed. */
private const val LIST_SEPARATOR = ""

class StringListConverters {
 @TypeConverter fun fromList(value: List<String>): String = value.joinToString(LIST_SEPARATOR)
 @TypeConverter fun toList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(LIST_SEPARATOR)
}

@Dao
interface AnalysisSessionDao {
 @Insert(onConflict = OnConflictStrategy.REPLACE)
 suspend fun insertSession(session: AnalysisSessionEntity)

 @Insert suspend fun insertPermissions(items: List<AnalysisPermissionEntity>)
 @Insert suspend fun insertComponents(items: List<AnalysisComponentEntity>)
 @Insert suspend fun insertFindings(items: List<RiskFindingEntity>)

 @Query("DELETE FROM analysis_permissions WHERE sessionId = :sessionId") suspend fun deletePermissions(sessionId: String)
 @Query("DELETE FROM analysis_components WHERE sessionId = :sessionId") suspend fun deleteComponents(sessionId: String)
 @Query("DELETE FROM analysis_risk_findings WHERE sessionId = :sessionId") suspend fun deleteFindings(sessionId: String)
 @Query("DELETE FROM analysis_sessions WHERE sessionId = :sessionId") suspend fun deleteSession(sessionId: String)

 /**
  * Atomically replaces one session's complete record (item 19's "a future explicit re-analysis
  * may produce a different score" — re-running this for the same [session.sessionId] fully
  * replaces the prior permissions/components/findings rather than appending to them).
  */
 @Transaction
 suspend fun insertCompleteAnalysis(session: AnalysisSessionEntity, permissions: List<AnalysisPermissionEntity>, components: List<AnalysisComponentEntity>, findings: List<RiskFindingEntity>) {
  insertSession(session)
  deletePermissions(session.sessionId)
  insertPermissions(permissions)
  deleteComponents(session.sessionId)
  insertComponents(components)
  deleteFindings(session.sessionId)
  insertFindings(findings)
 }

 @Query("SELECT * FROM analysis_sessions ORDER BY analyzedAtEpochMs DESC")
 fun observeAllSummaries(): Flow<List<AnalysisSessionEntity>>

 @Transaction
 @Query("SELECT * FROM analysis_sessions WHERE sessionId = :sessionId")
 fun observeDetails(sessionId: String): Flow<AnalysisSessionWithDetails?>

 @Transaction
 @Query("SELECT * FROM analysis_sessions WHERE sessionId = :sessionId")
 suspend fun getDetails(sessionId: String): AnalysisSessionWithDetails?

 /** Test-only visibility into the child table directly (not through [getDetails], which already returns null once the parent row is gone) — lets a cascade-delete test prove the child rows themselves were removed, not merely unreachable. */
 @Query("SELECT COUNT(*) FROM analysis_permissions WHERE sessionId = :sessionId")
 suspend fun countPermissionsForSession(sessionId: String): Int
}

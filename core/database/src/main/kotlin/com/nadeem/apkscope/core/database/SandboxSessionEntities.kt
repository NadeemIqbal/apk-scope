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
 * Checkpoint 4, item 2: the real production sandbox-session lifecycle table — replaces
 * checkpoint-1's minimal `SandboxSessionEntity` skeleton (`sessionId`/`packageName`/`state`/
 * `createdAtEpochMs` only, never populated by any real code) with the full shape
 * `com.nadeem.apkscope.core.model.SandboxSession` needs. Kept as the same table name (`sandbox_sessions`)
 * and the same `sessionId` primary-key column specifically so `NetworkObservationEntity`'s existing
 * foreign key (unused, reserved for the next checkpoint's live-monitoring wiring per item 12 — left
 * completely untouched here) keeps pointing at a real table.
 *
 * [analysisId] is a **plain reference, not a foreign key** — item 22's explicit "do not merge
 * sandbox-session persistence with static-analysis history in a way that breaks independent static
 * scans": one [AnalysisSessionEntity] may have zero or many [SandboxSessionEntity] rows over time,
 * and deleting either table's row must never cascade into the other.
 *
 * The requested policy and cleanup summary are both small, fixed-shape records — flattened into
 * columns here rather than child tables (unlike [SandboxPolicyEnforcementEntity] below, which is a
 * real variable-length list of richer records and does get its own table).
 */
@Entity(tableName = "sandbox_sessions")
data class SandboxSessionEntity(
 @PrimaryKey val sessionId: String,
 val analysisId: String,
 val packageName: String,
 val state: String,
 val policyDenyCamera: Boolean,
 val policyDenyMicrophone: Boolean,
 val policyDenyLocation: Boolean,
 val policyAlwaysOnVpnLockdown: Boolean,
 val policyDisposableSession: Boolean,
 val createdAtEpochMs: Long,
 val startedAtEpochMs: Long?,
 val endedAtEpochMs: Long?,
 val errorCode: String?,
 val errorUserMessage: String?,
 val errorTechnicalDetail: String?,
 val errorRecoverability: String?,
 val personalApkPath: String?,
 val installSessionId: Int?,
 val installedVersionCode: Long?,
 val dataClearRequestedAtEpochMs: Long?,
 val dataClearCompletedAtEpochMs: Long?,
 val dataClearResult: Boolean?,
 val cleanupAppDataCleared: Boolean?,
 val cleanupApkRemoved: Boolean?,
 val cleanupWorkTempApkDeleted: Boolean?,
 val cleanupPersonalTempApkDeleted: Boolean?,
 val cleanupUriGrantReleased: Boolean?,
 val cleanupNetworkSessionClosed: Boolean?,
)

/** One [com.nadeem.apkscope.core.model.PolicyEnforcementResult] — real enough structure (policy/status/mechanism/message) that a child table is a better fit than a delimited string (item 1/5's "store the actual `PolicyEnforcementResult`s in the session"). */
@Entity(
 tableName = "sandbox_policy_enforcements",
 foreignKeys = [ForeignKey(entity = SandboxSessionEntity::class, parentColumns = ["sessionId"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
 indices = [Index("sessionId")],
)
data class SandboxPolicyEnforcementEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val sessionId: String,
 val policy: String,
 val status: String,
 val mechanism: String?,
 val message: String?,
)

data class SandboxSessionWithEnforcements(
 @Embedded val session: SandboxSessionEntity,
 @Relation(parentColumn = "sessionId", entityColumn = "sessionId") val enforcements: List<SandboxPolicyEnforcementEntity>,
)

@Dao
interface SandboxSessionDao {
 @Insert(onConflict = OnConflictStrategy.REPLACE)
 suspend fun insertSession(session: SandboxSessionEntity)

 @Insert suspend fun insertEnforcements(items: List<SandboxPolicyEnforcementEntity>)

 @Query("DELETE FROM sandbox_policy_enforcements WHERE sessionId = :sessionId")
 suspend fun deleteEnforcements(sessionId: String)

 /** Real deletion (not a state transition) — used once a session's [com.nadeem.apkscope.core.model.SandboxSessionState.COMPLETED]/terminal record is no longer needed, and by tests proving the FK `CASCADE` actually removes child rows rather than merely orphaning them. */
 @Query("DELETE FROM sandbox_sessions WHERE sessionId = :sessionId")
 suspend fun deleteSession(sessionId: String)

 /** Atomically replaces the session row and its enforcement-result rows — every coordinator state transition goes through this one method, so the persisted session is never left half-updated (item 2's "recoverable after ... app process death"). */
 @Transaction
 suspend fun upsertSession(session: SandboxSessionEntity, enforcements: List<SandboxPolicyEnforcementEntity>) {
  insertSession(session)
  deleteEnforcements(session.sessionId)
  insertEnforcements(enforcements)
 }

 @Query("SELECT * FROM sandbox_sessions WHERE sessionId = :sessionId")
 suspend fun getSession(sessionId: String): SandboxSessionEntity?

 @Transaction
 @Query("SELECT * FROM sandbox_sessions WHERE sessionId = :sessionId")
 fun observeSessionWithEnforcements(sessionId: String): Flow<SandboxSessionWithEnforcements?>

 @Transaction
 @Query("SELECT * FROM sandbox_sessions WHERE sessionId = :sessionId")
 suspend fun getSessionWithEnforcements(sessionId: String): SandboxSessionWithEnforcements?

 @Query("SELECT * FROM sandbox_sessions WHERE analysisId = :analysisId ORDER BY createdAtEpochMs DESC")
 fun observeSessionsForAnalysis(analysisId: String): Flow<List<SandboxSessionEntity>>

 @Query("SELECT * FROM sandbox_sessions ORDER BY createdAtEpochMs DESC")
 fun observeAllSessions(): Flow<List<SandboxSessionEntity>>

 /** Test-only visibility into the child table directly — lets a cascade-delete test prove the child rows themselves were removed, not merely unreachable through [getSessionWithEnforcements] (which already returns null once the parent is gone regardless). */
 @Query("SELECT COUNT(*) FROM sandbox_policy_enforcements WHERE sessionId = :sessionId")
 suspend fun countEnforcementsForSession(sessionId: String): Int
}

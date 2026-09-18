package com.nadeem.apkscope.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Checkpoint 5, item 7: Work-local, typed, high-frequency network-observation storage — a second
 * table in [WorkEvidenceDatabase] (not [WorkEvidenceDatabase]'s existing cumulative-JSON-blob
 * table, per this checkpoint's explicit "do not store the entire session as an ever-growing JSON
 * blob" instruction). One row per real [com.nadeem.apkscope.core.model.NetworkObservation], with typed
 * columns rather than an opaque JSON string — [NetworkObservation]'s own sealed-interface shape is
 * flattened here since Room has no first-class sum-type column support; [type] plus whichever
 * subset of the nullable columns that variant actually populates is the encoding (mirrored exactly
 * by `app`'s `WorkNetworkObservationSink.toEntity`/`fromEntity`).
 *
 * [sequence] is a strictly-increasing per-session counter assigned by the sink at record time (item
 * 4/23) — the stable identity `(sessionId, sequence)` this checkpoint's idempotent-import
 * requirement is built on, independent of [timestampEpochMs] (wall-clock time is not a safe
 * uniqueness key on its own: two observations can share a millisecond).
 */
@Entity(
 tableName = "work_network_observations",
 indices = [
  Index("sessionId"),
  Index(value = ["sessionId", "sequence"], unique = true),
  Index(value = ["sessionId", "type"]),
 ],
)
data class WorkNetworkObservationEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val sessionId: String,
 val sequence: Long,
 val timestampEpochMs: Long,
 /** One of NetworkObservation's sealed-subtype simple names: ConnectionOpened/ConnectionClosed/ConnectionFailed/DnsQuery/DnsResponse/ResourceLimitExceeded. */
 val type: String,
 val protocol: String?,
 val destinationIp: String?,
 val destinationPort: Int?,
 val connectionId: Long?,
 val startTimeEpochMs: Long?,
 val endTimeEpochMs: Long?,
 val uploadedBytes: Long?,
 val downloadedBytes: Long?,
 /** ConnectionFailed only. [NetworkObservation.FailureReason.POLICY_DENIED] is this checkpoint's chosen "blocked" signal — item 31's decision, no separate ConnectionBlocked type. */
 val failureReason: String?,
 val failureDetail: String?,
 /** DnsQuery/DnsResponse only — DnsResponse's own may be null (unparseable question section, item 6's "never fabricated" rule). */
 val hostname: String?,
 /** DnsResponse only — every A/AAAA record found, comma-joined (never more than a handful per response, so no separate child table). */
 val resolvedAddressesCsv: String?,
 val transactionId: Int?,
 val sourcePort: Int?,
 /** ResourceLimitExceeded only. */
 val limitName: String?,
 val currentValue: Long?,
 val limitValue: Long?,
)

/**
 * Checkpoint 5, item 18: one row per sandbox session — the durable [com.nadeem.apkscope.core.model.RuntimeObservationSummary],
 * computed and written once forwarding has genuinely stopped and the observation pipeline has been
 * flushed (item 17's exact ordering), read back by [com.nadeem.apkscope.sandbox.SandboxWorkQueryActivity]'s
 * runtime-artifact export.
 */
@Entity(tableName = "work_runtime_summary")
data class WorkRuntimeSummaryEntity(
 @PrimaryKey val sessionId: String,
 val startedAtEpochMs: Long,
 val endedAtEpochMs: Long,
 val connectionCount: Int,
 val dnsQueryCount: Int,
 val uniqueObservedDomains: Int,
 val uploadedBytes: Long,
 val downloadedBytes: Long,
 val blockedConnectionCount: Int,
 val failedConnectionCount: Int,
 val droppedObservationCount: Long,
 /** Set once Personal has acknowledged successfully persisting the runtime artifact (item 24) — the durable evidence row this checkpoint's ack/delete/retention design (items 24/25) hinges on, independent of whether the exported artifact *file* has already been deleted. */
 val acknowledgedByPersonal: Boolean = false,
)

@Dao
interface WorkNetworkObservationDao {
 @Insert(onConflict = OnConflictStrategy.IGNORE)
 suspend fun insertAll(rows: List<WorkNetworkObservationEntity>)

 @Query("SELECT * FROM work_network_observations WHERE sessionId = :sessionId ORDER BY sequence ASC")
 fun observeForSession(sessionId: String): Flow<List<WorkNetworkObservationEntity>>

 /** Item 16: the Work-side Live Monitor never loads a whole 10k+-row session into memory at once — it observes only the most recent [limit] rows, newest first. */
 @Query("SELECT * FROM work_network_observations WHERE sessionId = :sessionId ORDER BY sequence DESC LIMIT :limit")
 fun observeLatestForSession(sessionId: String, limit: Int): Flow<List<WorkNetworkObservationEntity>>

 @Query("SELECT * FROM work_network_observations WHERE sessionId = :sessionId ORDER BY sequence ASC LIMIT :limit OFFSET :offset")
 suspend fun page(sessionId: String, limit: Int, offset: Int): List<WorkNetworkObservationEntity>

 @Query("SELECT COUNT(*) FROM work_network_observations WHERE sessionId = :sessionId")
 suspend fun count(sessionId: String): Int

 /**
  * Item 15/16: the Live Monitor's metric grid is computed by the database itself, from the whole
  * session, rather than by loading every row into Kotlin memory to sum — a real, `SUM`/`COUNT`-
  * backed total regardless of session size, that recomputes only on an actual table write (this
  * table's own batched-insert cadence, item 8/9, is what naturally throttles how often these Flows
  * re-emit — no separate polling/throttle logic needed here).
  */
 @Query("SELECT COUNT(*) FROM work_network_observations WHERE sessionId = :sessionId AND type = 'ConnectionOpened'")
 fun observeConnectionCount(sessionId: String): Flow<Int>

 @Query("SELECT COUNT(DISTINCT hostname) FROM work_network_observations WHERE sessionId = :sessionId AND type = 'DnsQuery' AND hostname IS NOT NULL")
 fun observeDomainCount(sessionId: String): Flow<Int>

 @Query("SELECT COALESCE(SUM(uploadedBytes), 0) FROM work_network_observations WHERE sessionId = :sessionId AND type = 'ConnectionClosed'")
 fun observeUploadedBytes(sessionId: String): Flow<Long>

 @Query("SELECT COALESCE(SUM(downloadedBytes), 0) FROM work_network_observations WHERE sessionId = :sessionId AND type = 'ConnectionClosed'")
 fun observeDownloadedBytes(sessionId: String): Flow<Long>

 @Query("SELECT COUNT(*) FROM work_network_observations WHERE sessionId = :sessionId AND type = 'ConnectionFailed' AND failureReason = 'POLICY_DENIED'")
 fun observeBlockedCount(sessionId: String): Flow<Int>

 @Query("DELETE FROM work_network_observations WHERE sessionId = :sessionId")
 suspend fun deleteForSession(sessionId: String)

 @Insert(onConflict = OnConflictStrategy.REPLACE)
 suspend fun upsertSummary(summary: WorkRuntimeSummaryEntity)

 @Query("SELECT * FROM work_runtime_summary WHERE sessionId = :sessionId")
 suspend fun getSummary(sessionId: String): WorkRuntimeSummaryEntity?

 @Query("UPDATE work_runtime_summary SET acknowledgedByPersonal = 1 WHERE sessionId = :sessionId")
 suspend fun markSummaryAcknowledged(sessionId: String)

 @Query("DELETE FROM work_runtime_summary WHERE sessionId = :sessionId")
 suspend fun deleteSummary(sessionId: String)
}

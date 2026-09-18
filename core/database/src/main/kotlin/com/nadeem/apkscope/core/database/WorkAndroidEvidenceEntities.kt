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
 * Checkpoint 6: Work-local storage for independent Android-recorded network evidence via DPM
 * (NetworkEvent: DnsEvent).
 *
 * One row per real [android.app.admin.DnsEvent], strictly attributed to [sessionId].
 * [eventId] is the monotonic 64-bit event ID assigned by the Android platform kernel.
 * [timestampEpochMs] is the timestamp assigned by Android when the event occurred.
 * [receivedAtEpochMs] is when the sandbox admin receiver received the batch callback.
 *
 * Unique index on (sessionId, eventId) ensures idempotent batch processing.
 */
@Entity(
 tableName = "work_android_dns_evidence",
 indices = [
  Index("sessionId"),
  Index(value = ["sessionId", "eventId"], unique = true),
  Index(value = ["sessionId", "packageName"]),
 ],
)
data class WorkAndroidDnsEvidenceEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val sessionId: String,
 val eventId: Long,
 val batchToken: Long,
 val packageName: String,
 val timestampEpochMs: Long,
 val receivedAtEpochMs: Long,
 val hostname: String,
 val resolvedAddressesCsv: String,
 val totalResolvedAddressCount: Int,
)

/**
 * Checkpoint 6: Work-local storage for independent Android-recorded network evidence via DPM
 * (NetworkEvent: ConnectEvent).
 *
 * One row per real [android.app.admin.ConnectEvent], strictly attributed to [sessionId].
 * [destinationAddress] is the string IP address of the target socket.
 * [destinationPort] is the target TCP/UDP port number.
 *
 * Unique index on (sessionId, eventId) ensures idempotent batch processing.
 */
@Entity(
 tableName = "work_android_connect_evidence",
 indices = [
  Index("sessionId"),
  Index(value = ["sessionId", "eventId"], unique = true),
  Index(value = ["sessionId", "packageName"]),
 ],
)
data class WorkAndroidConnectEvidenceEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val sessionId: String,
 val eventId: Long,
 val batchToken: Long,
 val packageName: String,
 val timestampEpochMs: Long,
 val receivedAtEpochMs: Long,
 val destinationAddress: String,
 val destinationPort: Int,
)

/**
 * Checkpoint 6: Work-local tracking of Android evidence readiness and export status per session.
 */
@Entity(tableName = "work_android_evidence_summaries")
data class WorkAndroidEvidenceSummaryEntity(
 @PrimaryKey val sessionId: String,
 val dnsCount: Int,
 val connectCount: Int,
 val status: String,
 val firstEventTimestampEpochMs: Long?,
 val lastEventTimestampEpochMs: Long?,
 val acknowledgedByPersonal: Boolean,
 val updatedAtEpochMs: Long,
)

@Dao
interface WorkAndroidEvidenceDao {
 @Insert(onConflict = OnConflictStrategy.IGNORE)
 suspend fun insertDnsEvents(rows: List<WorkAndroidDnsEvidenceEntity>)

 @Insert(onConflict = OnConflictStrategy.IGNORE)
 suspend fun insertConnectEvents(rows: List<WorkAndroidConnectEvidenceEntity>)

 @Insert(onConflict = OnConflictStrategy.REPLACE)
 suspend fun upsertSummary(summary: WorkAndroidEvidenceSummaryEntity)

 @Query("SELECT * FROM work_android_dns_evidence WHERE sessionId = :sessionId ORDER BY timestampEpochMs ASC")
 suspend fun getDnsEventsForSession(sessionId: String): List<WorkAndroidDnsEvidenceEntity>

 @Query("SELECT * FROM work_android_connect_evidence WHERE sessionId = :sessionId ORDER BY timestampEpochMs ASC")
 suspend fun getConnectEventsForSession(sessionId: String): List<WorkAndroidConnectEvidenceEntity>

 @Query("SELECT * FROM work_android_dns_evidence WHERE sessionId = :sessionId ORDER BY timestampEpochMs ASC")
 fun observeDnsEventsForSession(sessionId: String): Flow<List<WorkAndroidDnsEvidenceEntity>>

 @Query("SELECT * FROM work_android_connect_evidence WHERE sessionId = :sessionId ORDER BY timestampEpochMs ASC")
 fun observeConnectEventsForSession(sessionId: String): Flow<List<WorkAndroidConnectEvidenceEntity>>

 @Query("SELECT * FROM work_android_evidence_summaries WHERE sessionId = :sessionId")
 suspend fun getSummary(sessionId: String): WorkAndroidEvidenceSummaryEntity?

 @Query("SELECT * FROM work_android_evidence_summaries WHERE sessionId = :sessionId")
 fun observeSummary(sessionId: String): Flow<WorkAndroidEvidenceSummaryEntity?>

 @Query("UPDATE work_android_evidence_summaries SET acknowledgedByPersonal = 1, updatedAtEpochMs = :nowEpochMs WHERE sessionId = :sessionId")
 suspend fun markAcknowledged(sessionId: String, nowEpochMs: Long)

 @Query("DELETE FROM work_android_dns_evidence WHERE sessionId = :sessionId")
 suspend fun deleteDnsForSession(sessionId: String): Int

 @Query("DELETE FROM work_android_connect_evidence WHERE sessionId = :sessionId")
 suspend fun deleteConnectForSession(sessionId: String): Int

 @Query("DELETE FROM work_android_evidence_summaries WHERE sessionId = :sessionId")
 suspend fun deleteSummaryForSession(sessionId: String): Int
}

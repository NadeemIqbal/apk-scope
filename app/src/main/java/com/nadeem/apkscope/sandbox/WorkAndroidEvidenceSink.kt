package com.nadeem.apkscope.sandbox

import android.app.admin.ConnectEvent
import android.app.admin.DnsEvent
import android.app.admin.NetworkEvent
import android.content.Context
import com.nadeem.apkscope.core.database.WorkAndroidConnectEvidenceEntity
import com.nadeem.apkscope.core.database.WorkAndroidDnsEvidenceEntity
import com.nadeem.apkscope.core.database.WorkAndroidEvidenceDao
import com.nadeem.apkscope.core.database.WorkAndroidEvidenceSummaryEntity
import com.nadeem.apkscope.core.database.WorkEvidenceDatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Checkpoint 6: Ingestion sink for DevicePolicyManager network logs on the Work profile side.
 * Converts raw [NetworkEvent] objects from [android.app.admin.DevicePolicyManager.retrieveNetworkLogs]
 * into typed [WorkAndroidDnsEvidenceEntity] and [WorkAndroidConnectEvidenceEntity] rows, preserving
 * Android event facts without mutation or mixing with VPN observations.
 */
object WorkAndroidEvidenceSink {
 @Volatile var currentSessionId: String? = null
 @Volatile var currentTargetPackage: String? = null

 fun recordEvents(
  context: Context,
  batchToken: Long,
  events: List<NetworkEvent>?,
  coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
 ): kotlinx.coroutines.Job? {
  if (events == null || events.isEmpty()) return null

  val sessionId = currentSessionId ?: return null
  val targetPackage = currentTargetPackage
  val receivedAt = System.currentTimeMillis()

  return coroutineScope.launch {
   val db = WorkEvidenceDatabaseProvider.get(context)
   val dao = db.workAndroidEvidenceDao()

   val dnsRows = mutableListOf<WorkAndroidDnsEvidenceEntity>()
   val connectRows = mutableListOf<WorkAndroidConnectEvidenceEntity>()

   for (event in events) {
    // Only record events attributed to the sandboxed target package (or all if targetPackage is null)
    if (targetPackage != null && event.packageName != targetPackage) {
     continue
    }

    when (event) {
     is DnsEvent -> {
      val addressesCsv = event.inetAddresses?.joinToString(",") { it.hostAddress ?: "" } ?: ""
      dnsRows += WorkAndroidDnsEvidenceEntity(
       sessionId = sessionId,
       eventId = event.id,
       batchToken = batchToken,
       packageName = event.packageName,
       timestampEpochMs = event.timestamp,
       receivedAtEpochMs = receivedAt,
       hostname = event.hostname,
       resolvedAddressesCsv = addressesCsv,
       totalResolvedAddressCount = event.totalResolvedAddressCount,
      )
     }
     is ConnectEvent -> {
      connectRows += WorkAndroidConnectEvidenceEntity(
       sessionId = sessionId,
       eventId = event.id,
       batchToken = batchToken,
       packageName = event.packageName,
       timestampEpochMs = event.timestamp,
       receivedAtEpochMs = receivedAt,
       destinationAddress = event.inetAddress?.hostAddress ?: "",
       destinationPort = event.port,
      )
     }
    }
   }

   if (dnsRows.isNotEmpty()) {
    dao.insertDnsEvents(dnsRows)
   }
   if (connectRows.isNotEmpty()) {
    dao.insertConnectEvents(connectRows)
   }

   // Update summary
   val allDns = dao.getDnsEventsForSession(sessionId)
   val allConnect = dao.getConnectEventsForSession(sessionId)
   val allTimestamps = (allDns.map { it.timestampEpochMs } + allConnect.map { it.timestampEpochMs }).sorted()

   dao.upsertSummary(
    WorkAndroidEvidenceSummaryEntity(
     sessionId = sessionId,
     dnsCount = allDns.size,
     connectCount = allConnect.size,
     status = "READY",
     firstEventTimestampEpochMs = allTimestamps.firstOrNull(),
     lastEventTimestampEpochMs = allTimestamps.lastOrNull(),
     acknowledgedByPersonal = false,
     updatedAtEpochMs = receivedAt,
    )
   )
  }
 }
}

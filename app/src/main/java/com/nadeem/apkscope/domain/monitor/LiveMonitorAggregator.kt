package com.nadeem.apkscope.domain.monitor

import com.nadeem.apkscope.core.database.WorkNetworkObservationEntity

/**
 * Checkpoint 5, item 13/14/31/32: pure, Android/Compose-free mapping from raw
 * [WorkNetworkObservationEntity] rows to the Live Monitor's two UI-facing shapes — a chronological
 * event feed ([FeedEntry]) and connection-level aggregation ([ConnectionRow]). Kept entirely free of
 * Compose/Room/coroutines so it is directly unit-testable (item 36) independent of any screen or
 * database wiring.
 *
 * Never destroys evidence to simplify presentation (item 14) — this only *derives* a presentation
 * from the rows; the rows themselves, and every field on them, remain exactly as persisted. Never
 * fabricates a hostname (item 6): [ConnectionRow.associatedHostname]/[FeedEntry.subtitle] only ever
 * come from a real [WorkNetworkObservationEntity] of type `DnsResponse`, joined to a TCP connection
 * purely as a best-effort UI convenience — never presented as anything the connection itself proved.
 */
object LiveMonitorAggregator {

 enum class FeedCategory { DNS, TCP, BLOCKED, WARNING }

 data class FeedEntry(
  val sequence: Long,
  val timestampEpochMs: Long,
  val category: FeedCategory,
  val title: String,
  val subtitle: String?,
  val uploadedBytes: Long? = null,
  val downloadedBytes: Long? = null,
  /** Item 6/31: a raw-IP TCP connection with no preceding DNS evidence, or a non-policy connection failure — flagged for the "Warnings" filter without being called blocked or malicious. */
  val suspicious: Boolean = false,
 )

 data class ConnectionRow(
  val connectionId: Long,
  val protocol: String,
  val destinationIp: String,
  val destinationPort: Int,
  val startTimeEpochMs: Long,
  /** Null while the connection has not yet closed. */
  val durationMs: Long?,
  val uploadedBytes: Long,
  val downloadedBytes: Long,
  val open: Boolean,
  /** Item 6: only ever set from a real preceding [WorkNetworkObservationEntity] of type `DnsResponse` whose resolved addresses include [destinationIp] — never derived from the IP alone. */
  val associatedHostname: String?,
 )

 /**
  * One entry per real DNS/TCP/blocked/resource-limit event — never one entry per low-level state
  * transition the way [connectionRows] aggregates (item 14 keeps these two views deliberately
  * separate: the feed is the raw chronological trace, [connectionRows] is the summarized view).
  * [rows] is expected newest-first (matching `observeLatestForSession`'s own ordering) and the
  * result preserves that order.
  */
 fun feed(rows: List<WorkNetworkObservationEntity>): List<FeedEntry> {
  val respondedTransactionIds = rows.asSequence().filter { it.type == "DnsResponse" }.mapNotNull { it.transactionId }.toHashSet()
  val dnsResponses = rows.filter { it.type == "DnsResponse" }.sortedBy { it.timestampEpochMs }
  val entries = ArrayList<FeedEntry>(rows.size)
  for (row in rows) {
   when (row.type) {
    "DnsResponse" -> entries += FeedEntry(
     sequence = row.sequence, timestampEpochMs = row.timestampEpochMs, category = FeedCategory.DNS,
     title = row.hostname ?: "(unparseable DNS response)",
     subtitle = row.resolvedAddressesCsv?.substringBefore(',')?.let { "resolved to $it" } ?: "no answer recorded",
    )
    "DnsQuery" -> if (row.transactionId !in respondedTransactionIds) entries += FeedEntry(
     sequence = row.sequence, timestampEpochMs = row.timestampEpochMs, category = FeedCategory.DNS,
     title = row.hostname ?: "(unknown host)", subtitle = "query sent, awaiting response",
    )
    "ConnectionClosed" -> {
     val hostname = associatedHostname(dnsResponses, row.destinationIp.orEmpty(), row.timestampEpochMs)
     entries += FeedEntry(
      sequence = row.sequence, timestampEpochMs = row.timestampEpochMs, category = FeedCategory.TCP,
      title = "${row.destinationIp}:${row.destinationPort}",
      subtitle = hostname?.let { "Associated via DNS: $it" },
      uploadedBytes = row.uploadedBytes, downloadedBytes = row.downloadedBytes,
      suspicious = hostname == null,
     )
    }
    "ConnectionFailed" -> {
     val blocked = row.failureReason == "POLICY_DENIED"
     entries += FeedEntry(
      sequence = row.sequence, timestampEpochMs = row.timestampEpochMs,
      category = if (blocked) FeedCategory.BLOCKED else FeedCategory.TCP,
      title = "${row.destinationIp}:${row.destinationPort}",
      // Item 31: a policy-denied destination is described only as "blocked", never "malicious".
      subtitle = row.failureDetail ?: row.failureReason,
      suspicious = !blocked,
     )
    }
    "ResourceLimitExceeded" -> entries += FeedEntry(
     sequence = row.sequence, timestampEpochMs = row.timestampEpochMs, category = FeedCategory.WARNING,
     title = row.limitName ?: "resource limit",
     // Item 32: factual only — "N limit reached", never a severity/risk framing.
     subtitle = "${row.currentValue ?: "?"} / ${row.limitValue ?: "?"} reached",
     suspicious = true,
    )
    // ConnectionOpened carries no standalone feed value beyond what ConnectionClosed's own start
    // time already reports — surfacing it too would double-count the same real connection in the
    // event count without adding a fact the closed event doesn't already carry.
   }
  }
  return entries
 }

 /** Item 6: best-effort join — the closest [WorkNetworkObservationEntity] of type `DnsResponse` at or before [atEpochMs] whose resolved addresses include [destinationIp]. Never a fabricated guess: absence of a match means no hostname is shown, full stop. */
 private fun associatedHostname(dnsResponses: List<WorkNetworkObservationEntity>, destinationIp: String, atEpochMs: Long): String? =
  dnsResponses.lastOrNull { it.timestampEpochMs <= atEpochMs && it.hostname != null && it.resolvedAddressesCsv?.split(',')?.contains(destinationIp) == true }?.hostname

 /**
  * Item 14: connection-level rows — one per real connection (`connectionId`), built from its
  * `ConnectionOpened` (start time) and, once it exists, its matching `ConnectionClosed` (duration +
  * bytes). A connection with no `ConnectionClosed` yet is reported [ConnectionRow.open] with a null
  * duration and zero bytes so far — never a fabricated "still transferring" estimate.
  */
 fun connectionRows(rows: List<WorkNetworkObservationEntity>): List<ConnectionRow> {
  val dnsResponses = rows.filter { it.type == "DnsResponse" }.sortedBy { it.timestampEpochMs }
  val opened = rows.filter { it.type == "ConnectionOpened" && it.connectionId != null }.associateBy { it.connectionId }
  val closed = rows.filter { it.type == "ConnectionClosed" && it.connectionId != null }.associateBy { it.connectionId }
  return opened.values.map { open ->
   val close = closed[open.connectionId]
   ConnectionRow(
    connectionId = requireNotNull(open.connectionId),
    protocol = open.protocol ?: close?.protocol ?: "?",
    destinationIp = open.destinationIp ?: close?.destinationIp ?: "?",
    destinationPort = open.destinationPort ?: close?.destinationPort ?: 0,
    startTimeEpochMs = open.timestampEpochMs,
    durationMs = close?.let { (it.endTimeEpochMs ?: it.timestampEpochMs) - (it.startTimeEpochMs ?: open.timestampEpochMs) },
    uploadedBytes = close?.uploadedBytes ?: 0L,
    downloadedBytes = close?.downloadedBytes ?: 0L,
    open = close == null,
    associatedHostname = associatedHostname(dnsResponses, open.destinationIp ?: "", open.timestampEpochMs),
   )
  }.sortedByDescending { it.startTimeEpochMs }
 }
}

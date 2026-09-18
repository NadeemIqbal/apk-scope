package com.nadeem.apkscope.ui.screens.monitor

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nadeem.apkscope.core.database.NetworkObservationEntity
import com.nadeem.apkscope.core.database.WorkNetworkObservationEntity
import com.nadeem.apkscope.domain.monitor.LiveMonitorAggregator
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.MetricCard
import com.nadeem.apkscope.ui.components.NetworkEventRow
import com.nadeem.apkscope.ui.theme.Spacing

/**
 * Checkpoint 5, item 35: "View Runtime Activity" — Personal's durable, read-only record of a
 * completed session's real network observations, imported via the runtime-observation artifact
 * (item 19-24). Reuses [LiveMonitorAggregator] (via [toWorkShape]) for the feed rendering — the
 * two Room entities are deliberately structurally parallel for exactly this reason (see
 * `RuntimeObservationEntities.kt`'s doc comment) — rather than duplicating the mapping logic.
 */
@Composable
fun RuntimeActivityScreen(sessionId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
 val context = LocalContext.current
 val viewModel = sessionViewModel { RuntimeActivityViewModel(context.applicationContext as Application, sessionId) }
 val state by viewModel.uiState.collectAsState()
 val feed = LiveMonitorAggregator.feed(state.rows.map { it.toWorkShape() }).sortedByDescending { it.sequence }

 Scaffold(modifier = modifier, topBar = { AppTopBar(title = "Runtime Activity", onBack = onBack) }) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
   state.summary?.let { summary ->
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
     MetricCard(modifier = Modifier.weight(1f), label = "Connections", value = summary.connectionCount.toString())
     MetricCard(modifier = Modifier.weight(1f), label = "DNS domains", value = summary.uniqueObservedDomains.toString())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
     MetricCard(modifier = Modifier.weight(1f), label = "Uploaded", value = formatBytes(summary.uploadedBytes))
     MetricCard(modifier = Modifier.weight(1f), label = "Downloaded", value = formatBytes(summary.downloadedBytes))
    }
    if (summary.blockedConnectionCount > 0) {
     Text("${summary.blockedConnectionCount} blocked destination(s) during this session.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (summary.truncated) {
     Text(
      "This session's full activity exceeded the export size limit — showing ${summary.exportedObservationCount} of ${summary.totalObservationCount} total observations. The summary above still reflects the whole session.",
      style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
     )
    }
   } ?: Text("No runtime activity was recorded for this session.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

   LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    items(feed, key = { it.sequence }) { entry ->
     NetworkEventRow(
      protocol = when (entry.category) {
       LiveMonitorAggregator.FeedCategory.DNS -> "DNS"; LiveMonitorAggregator.FeedCategory.TCP -> "TCP"
       LiveMonitorAggregator.FeedCategory.BLOCKED -> "BLOCKED"; LiveMonitorAggregator.FeedCategory.WARNING -> "WARN"
      },
      detail = entry.title, subDetail = entry.subtitle, ageLabel = "",
      blocked = entry.category == LiveMonitorAggregator.FeedCategory.BLOCKED, suspicious = entry.suspicious,
     )
    }
   }
  }
 }
}

/** Adapts the Personal-side [NetworkObservationEntity] into [WorkNetworkObservationEntity]'s shape so [LiveMonitorAggregator] — written once against the Work-local entity — works unchanged against Personal's durable imported copy too (the two schemas are kept field-for-field parallel exactly for this reuse). */
private fun NetworkObservationEntity.toWorkShape(): WorkNetworkObservationEntity = WorkNetworkObservationEntity(
 id = id, sessionId = sessionId, sequence = sequence, timestampEpochMs = timestampEpochMs, type = type,
 protocol = protocol, destinationIp = destinationIp, destinationPort = destinationPort, connectionId = connectionId,
 startTimeEpochMs = startTimeEpochMs, endTimeEpochMs = endTimeEpochMs, uploadedBytes = uploadedBytes, downloadedBytes = downloadedBytes,
 failureReason = failureReason, failureDetail = failureDetail, hostname = hostname, resolvedAddressesCsv = resolvedAddressesCsv,
 transactionId = transactionId, sourcePort = sourcePort, limitName = limitName, currentValue = currentValue, limitValue = limitValue,
)

private fun formatBytes(bytes: Long): String = when {
 bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
 bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
 else -> "$bytes B"
}

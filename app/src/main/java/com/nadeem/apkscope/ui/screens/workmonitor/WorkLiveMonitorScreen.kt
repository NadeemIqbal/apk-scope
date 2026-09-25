package com.nadeem.apkscope.ui.screens.workmonitor

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadeem.apkscope.domain.monitor.LiveMonitorAggregator
import com.nadeem.apkscope.poc.apkrepack.FridaTrafficMonitor
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.InfoCard
import com.nadeem.apkscope.ui.components.NetworkEventRow
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.components.openSandboxAppLabel
import com.nadeem.apkscope.ui.theme.Spacing
import java.time.Duration
import java.time.Instant

/**
 * Checkpoint 5, item 11/12/33: the real Work-profile-side Live Monitor — "Work Profile owns the
 * live monitor" (item 2). Reachable when the app is opened *inside the Work Profile* (see
 * `MainActivity`'s profile branch) and from the persistent monitoring notification
 * ([com.nadeem.apkscope.sandbox.SandboxVpnService]'s own foreground-service notification, item 11).
 * The APK Scope visual system is this screen's source of truth (header/identity/metrics grid/filter
 * chips/event feed) — every value below is real,
 * database-backed data (see [WorkLiveMonitorViewModel]), not the mock numbers that design shows.
 *
 * No "End Dynamic Session" control here: item 34's End Session sequence is Personal-initiated (the
 * session's lifecycle state lives only in Personal's own Room store) — this screen shows a plain
 * note pointing back to the Personal-side control instead of fabricating a second, Work-side one.
 */
@Composable
fun WorkLiveMonitorScreen(
 sessionId: String,
 packageName: String,
 onBack: () -> Unit,
 onOpenHttpsInspection: () -> Unit = {},
 onOpenSandboxApp: () -> Unit = {},
 onOpenFridaConsole: () -> Unit = {},
 onOpenStorageInspector: () -> Unit = {},
 modifier: Modifier = Modifier
) {
 val context = LocalContext.current
 val viewModel = sessionViewModel { WorkLiveMonitorViewModel(context.applicationContext as Application, sessionId, packageName) }
 val state by viewModel.uiState.collectAsState()
 val fridaStatus by FridaTrafficMonitor.shared.status.collectAsState()
 val filtered = viewModel.filteredFeed(state)
 var selectedSource by remember { mutableStateOf(TrafficSource.FRIDA) }
 val targetAppName = remember(packageName) {
  runCatching {
   context.packageManager.getApplicationInfo(packageName, 0)
    .let(context.packageManager::getApplicationLabel)
    .toString()
  }.getOrNull()
 }

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "Live Monitor", eyebrow = "SANDBOX", onBack = onBack) },
  bottomBar = {
   if (selectedSource != TrafficSource.VPN) {
   StickyActionBar {
     PrimaryActionButton(text = "Open Traffic Inspector", icon = Icons.Filled.Radar, onClick = onOpenHttpsInspection)
    }
   }
  },
 ) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.base, vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
   SessionStatusBanner(state.durationLabel)
   AppIdentityCard(state.packageName)
   SecondaryActionButton(
    text = openSandboxAppLabel(targetAppName, packageName),
    icon = Icons.Filled.RocketLaunch,
    onClick = onOpenSandboxApp,
   )
   TrafficSourceSelector(selectedSource, onSelect = { selectedSource = it })
   when (selectedSource) {
    TrafficSource.FRIDA -> {
     FridaReceiverStatusCard(fridaStatus)
     SecondaryActionButton(
      text = "Open Frida Command Console",
      onClick = onOpenFridaConsole,
      // Let the user open the editor while the receiver is still connecting so the quick
      // commands, formatter, and validation feedback are discoverable. The Run button inside
      // the console remains disabled until both verification and target authentication succeed.
     enabled = fridaStatus.isListening,
     )
     SecondaryActionButton(
      text = "Open Storage Inspector",
      icon = Icons.Filled.Info,
      onClick = onOpenStorageInspector,
      enabled = fridaStatus.commandReady,
     )
     InfoCard(
      title = "Frida traffic",
      text = "Open the patched sandboxed app and use the network feature you want to inspect. Captured SSL_read/SSL_write chunks appear in Traffic Inspector when the injected script connects. The command console runs JavaScript only in this active target process.",
     )
    }
    TrafficSource.HTTP -> {
     InfoCard(
      title = "HTTP traffic",
      text = "Traffic Inspector shows decoded HTTP, HTTPS, WebSocket, gRPC, and SSE records. CA mode needs the CA installed; Frida mode keeps CA off and uses patched-app hooks.",
     )
    }
    TrafficSource.VPN -> {
     MetricsRow(state)
     FilterChipsRow(state.selectedFilter, state.feed, onSelect = viewModel::selectFilter)
     LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
      items(filtered, key = { it.sequence }) { entry -> FeedRow(entry) }
      item {
       InfoCard(
        title = "Ending this session",
       text = "End this sandbox session from the sandboxed app's card in your Personal Profile — Work Profile only monitors network activity, it does not control the session's lifecycle.",
       modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm),
       )
      }
     }
    }
   }
  }
 }
}

private enum class TrafficSource { FRIDA, HTTP, VPN }

@Composable
private fun TrafficSourceSelector(selected: TrafficSource, onSelect: (TrafficSource) -> Unit) {
 Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
  SourceButton("Frida", TrafficSource.FRIDA, selected, onSelect)
  SourceButton("HTTP", TrafficSource.HTTP, selected, onSelect)
  SourceButton("VPN", TrafficSource.VPN, selected, onSelect)
 }
}

@Composable
private fun SourceButton(label: String, source: TrafficSource, selected: TrafficSource, onSelect: (TrafficSource) -> Unit, modifier: Modifier = Modifier) {
 Surface(
  modifier = modifier.clickable { onSelect(source) },
  shape = RoundedCornerShape(com.nadeem.apkscope.ui.theme.Radii.pill),
  color = if (source == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
  tonalElevation = 0.dp,
 ) {
  Text(
   label,
   modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
   style = MaterialTheme.typography.labelMedium,
   color = if (source == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
  )
 }
}

@Composable
private fun FridaReceiverStatusCard(status: FridaTrafficMonitor.Status) {
 BaseCard {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Column {
    Text("Frida Receiver", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Text(
     if (status.isListening) "Listening on 127.0.0.1:${status.port}" else "Not listening",
     style = MaterialTheme.typography.labelSmall,
     color = if (status.isListening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
    )
   }
   Text("${status.capturedCount} chunks", style = com.nadeem.apkscope.ui.theme.MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.xs))
  Text("Connected package: ${status.connectedPackage ?: "none"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text("Target package: ${status.targetPackage ?: "not bound"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text("Target PID: ${status.connectedPid ?: "none"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text(
   if (status.commandReady) "Dynamic commands: ready" else "Dynamic commands: waiting for the target-bound channel",
   style = MaterialTheme.typography.labelSmall,
   color = if (status.commandReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
  )
  status.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
 }
}

@Composable
private fun SessionStatusBanner(durationLabel: String) {
 BaseCard {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
    androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
    Text("Sandbox Session Active", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
   }
   Text(durationLabel, style = com.nadeem.apkscope.ui.theme.MonoCodeStyle, color = MaterialTheme.colorScheme.primary)
  }
 }
}

@Composable
private fun AppIdentityCard(packageName: String) {
 BaseCard {
  Row(verticalAlignment = Alignment.CenterVertically) {
   Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
    Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
   }
   androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
   Column {
    Text(packageName.ifEmpty { "Sandboxed app" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    Text(packageName, style = com.nadeem.apkscope.ui.theme.MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.xs))
  Row(
   Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(com.nadeem.apkscope.ui.theme.Radii.md)).padding(Spacing.xs),
   verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
  ) {
   Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Filled.Radar, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
    androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.xs))
    // Item 33: factual, never implies HTTPS content/passwords/messages are read.
    Text("Monitoring network activity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
   }
   Icon(Icons.Filled.Info, contentDescription = "Encrypted HTTPS content is never decrypted or read", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
  }
 }
}

@Composable
private fun MetricsRow(state: WorkLiveMonitorUiState) {
 Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
  CompactMetricItem(modifier = Modifier.weight(1f), label = "Connections", value = state.connectionCount.toString())
  CompactMetricItem(modifier = Modifier.weight(1f), label = "Domains", value = state.domainCount.toString())
  CompactMetricItem(modifier = Modifier.weight(1f), label = "Uploaded", value = formatBytes(state.uploadedBytes))
  CompactMetricItem(modifier = Modifier.weight(1f), label = "Downloaded", value = formatBytes(state.downloadedBytes))
 }
}

@Composable
private fun CompactMetricItem(label: String, value: String, modifier: Modifier = Modifier) {
 Column(
  modifier = modifier
   .background(
    MaterialTheme.colorScheme.surfaceContainer,
    RoundedCornerShape(com.nadeem.apkscope.ui.theme.Radii.md),
   )
   .padding(horizontal = 4.dp, vertical = 6.dp),
  horizontalAlignment = Alignment.CenterHorizontally,
  verticalArrangement = Arrangement.Center,
 ) {
  Text(
   text = label.uppercase(),
   style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
   color = MaterialTheme.colorScheme.onSurfaceVariant,
   maxLines = 1,
   overflow = TextOverflow.Ellipsis,
  )
  androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
  Text(
   text = value,
   style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
   color = MaterialTheme.colorScheme.onSurface,
   maxLines = 1,
   overflow = TextOverflow.Ellipsis,
  )
 }
}

@Composable
private fun FilterChipsRow(selected: MonitorFilter, feed: List<LiveMonitorAggregator.FeedEntry>, onSelect: (MonitorFilter) -> Unit) {
 val counts = mapOf(
  MonitorFilter.ALL to feed.size,
  MonitorFilter.DNS to feed.count { it.category == LiveMonitorAggregator.FeedCategory.DNS },
  MonitorFilter.TCP to feed.count { it.category == LiveMonitorAggregator.FeedCategory.TCP },
  MonitorFilter.BLOCKED to feed.count { it.category == LiveMonitorAggregator.FeedCategory.BLOCKED },
  MonitorFilter.WARNINGS to feed.count { it.category == LiveMonitorAggregator.FeedCategory.WARNING || it.suspicious },
 )
 Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
  MonitorFilter.entries.forEach { filter ->
   val active = filter == selected
   com.nadeem.apkscope.ui.components.Badge(
    text = "${filter.label()} ${counts[filter]}",
    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    filled = active,
    modifier = Modifier.clickable { onSelect(filter) },
   )
  }
 }
}

private fun MonitorFilter.label() = when (this) {
 MonitorFilter.ALL -> "All"; MonitorFilter.DNS -> "DNS"; MonitorFilter.TCP -> "TCP"
 MonitorFilter.BLOCKED -> "Blocked"; MonitorFilter.WARNINGS -> "Warnings"
}

@Composable
private fun FeedRow(entry: LiveMonitorAggregator.FeedEntry) {
 val ageLabel = ageLabel(entry.timestampEpochMs)
 val protocol = when (entry.category) {
  LiveMonitorAggregator.FeedCategory.DNS -> "DNS"
  LiveMonitorAggregator.FeedCategory.TCP -> "TCP"
  LiveMonitorAggregator.FeedCategory.BLOCKED -> "BLOCKED"
  LiveMonitorAggregator.FeedCategory.WARNING -> "WARN"
 }
 val subDetail = buildString {
  if (entry.subtitle != null) append(entry.subtitle)
  if (entry.uploadedBytes != null || entry.downloadedBytes != null) {
   if (isNotEmpty()) append(" · ")
   append("↑${formatBytes(entry.uploadedBytes ?: 0L)} ↓${formatBytes(entry.downloadedBytes ?: 0L)}")
  }
 }.ifEmpty { null }
 NetworkEventRow(
  protocol = protocol, detail = entry.title, subDetail = subDetail, ageLabel = ageLabel,
  blocked = entry.category == LiveMonitorAggregator.FeedCategory.BLOCKED, suspicious = entry.suspicious,
 )
}

private fun ageLabel(timestampEpochMs: Long): String {
 val elapsed = Duration.between(Instant.ofEpochMilli(timestampEpochMs), Instant.now()).coerceAtLeast(Duration.ZERO)
 return when {
  elapsed.seconds < 60 -> "${elapsed.seconds}s ago"
  elapsed.toMinutes() < 60 -> "${elapsed.toMinutes()}m ago"
  else -> "${elapsed.toHours()}h ago"
 }
}

private fun formatBytes(bytes: Long): String = when {
 bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
 bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
 else -> "$bytes B"
}

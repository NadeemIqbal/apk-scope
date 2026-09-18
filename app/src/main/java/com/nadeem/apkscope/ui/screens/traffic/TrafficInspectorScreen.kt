package com.nadeem.apkscope.ui.screens.traffic

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadeem.apkscope.core.database.WorkEvidenceDatabaseProvider
import com.nadeem.apkscope.core.database.WorkNetworkObservationEntity
import com.nadeem.apkscope.core.network.https.HttpsInspectionConfig
import com.nadeem.apkscope.core.network.traffic.Direction
import com.nadeem.apkscope.core.network.traffic.MessageType
import com.nadeem.apkscope.core.network.traffic.TrafficCaptureState
import com.nadeem.apkscope.core.network.traffic.TrafficFilterQuery
import com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore
import com.nadeem.apkscope.core.network.traffic.TrafficProtocol
import com.nadeem.apkscope.core.network.traffic.TrafficRecord
import com.nadeem.apkscope.core.network.traffic.WebSocketMessage
import com.nadeem.apkscope.core.network.traffic.WebSocketSessionData
import com.nadeem.apkscope.poc.apkrepack.FridaTrafficMonitor
import com.nadeem.apkscope.domain.monitor.LiveMonitorAggregator
import com.nadeem.apkscope.sandbox.CaInstaller
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing
import kotlinx.coroutines.flow.flowOf
import java.time.Duration
import java.time.Instant

@Composable
fun TrafficInspectorScreen(
    onBack: () -> Unit,
    workSessionId: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allRecords by TrafficInspectionStore.recordsFlow.collectAsState()
    val workObservationRows by remember(workSessionId) {
        if (workSessionId == null) {
            flowOf<List<WorkNetworkObservationEntity>>(emptyList())
        } else {
            WorkEvidenceDatabaseProvider.get(context.applicationContext).workNetworkObservationDao()
                .observeLatestForSession(workSessionId, 500)
        }
    }.collectAsState(initial = emptyList())
    val workFeed = remember(workObservationRows) { LiveMonitorAggregator.feed(workObservationRows) }

    var selectedRecord by remember { mutableStateOf<TrafficRecord?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    // Collapsed by default so the traffic list gets the vertical space the five filter rows used to
    // always occupy; expand on demand via the accordion toggle beneath the search bar.
    var showFilters by remember { mutableStateOf(false) }

    // Filter state
    var searchText by remember { mutableStateOf("") }
    var selectedProtocol by remember { mutableStateOf<TrafficProtocol?>(null) }
    var selectedState by remember { mutableStateOf<TrafficCaptureState?>(null) }
    var selectedMethod by remember { mutableStateOf<String?>(null) }
    var selectedStatus by remember { mutableStateOf<Int?>(null) }
    var selectedTimeFilter by remember { mutableStateOf<String>("ALL") }
    var selectedWsDirection by remember { mutableStateOf<Direction?>(null) }
    var selectedWsMessageType by remember { mutableStateOf<MessageType?>(null) }

    // Settings state
    var isInspectionEnabled by remember { mutableStateOf(HttpsInspectionConfig.isEnabled) }
    var isCaInstalled by remember { mutableStateOf(false) }

    fun refreshCaStatus() {
        isCaInstalled = CaInstaller.isCaInstalled(context)
    }

    LaunchedEffect(Unit) {
        refreshCaStatus()
        // The personal-side inspector is also a valid Frida receiver surface. Starting the
        // receiver here lets a locally installed instrumented APK stream its real TLS bytes into
        // the same inspector even when no Work Profile session is active.
        if (workSessionId == null) {
            FridaTrafficMonitor.shared.start()
        }
    }

    // Detail view routing
    if (selectedRecord != null) {
        BackHandler { selectedRecord = null }
        val record = selectedRecord!!
        if (record.webSocketSession != null) {
            WebSocketSessionDetailScreen(
                record = record,
                onBack = { selectedRecord = null }
            )
        } else if (record.grpcSession != null) {
            GrpcSessionDetailScreen(
                record = record,
                onBack = { selectedRecord = null }
            )
        } else if (record.sseSession != null) {
            SseSessionDetailScreen(
                record = record,
                onBack = { selectedRecord = null }
            )
        } else {
            TrafficTransactionDetailScreen(
                record = record,
                onBack = { selectedRecord = null }
            )
        }
        return
    }

    BackHandler(onBack = onBack)

    val timeRangeStart = when (selectedTimeFilter) {
        "1M" -> Instant.now().minus(Duration.ofMinutes(1))
        "5M" -> Instant.now().minus(Duration.ofMinutes(5))
        else -> null
    }

    val query = TrafficFilterQuery(
        protocol = selectedProtocol,
        state = selectedState,
        method = selectedMethod,
        statusCode = selectedStatus,
        wsDirection = selectedWsDirection,
        wsMessageType = selectedWsMessageType,
        timeRangeStart = timeRangeStart,
        searchText = searchText.ifBlank { null }
    )
    val filteredRecords = remember(allRecords, query) {
        TrafficInspectionStore.filter(query, allRecords)
    }
    val filteredWorkFeed = remember(workFeed, query, selectedProtocol, selectedState, selectedMethod, selectedStatus, selectedWsDirection, selectedWsMessageType) {
        filterWorkFeedForInspector(workFeed, query)
    }
    val combinedItems = remember(filteredRecords, filteredWorkFeed) {
        buildList {
            filteredRecords.forEach { add(InspectorTimelineItem.Transaction(it)) }
            filteredWorkFeed.forEach { add(InspectorTimelineItem.WorkObservation(it)) }
        }.sortedByDescending { it.timestampEpochMs }
    }
    // Chip-based filters only (the search bar is always visible on its own, so it's not counted here)
    // — lets the collapsed accordion still say how many are active without expanding it.
    val activeFilterCount = listOfNotNull(
        selectedProtocol, selectedState, selectedMethod, selectedStatus, selectedWsDirection, selectedWsMessageType
    ).size + (if (selectedTimeFilter != "ALL") 1 else 0)

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "Traffic Inspector",
                eyebrow = "SANDBOX",
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.base, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Search bar with the settings/clear actions on the same row (icon-only, so the row
            // stays compact and no longer needs its own separate toolbar row above the search bar).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = {
                        Text("Search host, path, headers, body...", style = MaterialTheme.typography.bodyMedium)
                    },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) {
                            IconButton(onClick = { searchText = "" }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(Radii.md)
                )
                IconButton(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (showSettings) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(Radii.md)
                        )
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = if (showSettings) "Hide inspection settings" else "Inspection settings",
                        tint = if (showSettings) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { TrafficInspectionStore.clear() },
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(Radii.md))
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Clear captured traffic",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Optional Settings Card
            AnimatedVisibility(visible = showSettings) {
                TrafficSettingsCard(
                    isEnabled = isInspectionEnabled,
                    onToggle = { enabled ->
                        HttpsInspectionConfig.isEnabled = enabled
                        isInspectionEnabled = enabled
                    },
                    isCaInstalled = isCaInstalled,
                    onInstallCa = {
                        val ok = CaInstaller.installCa(context)
                        refreshCaStatus()
                        Toast.makeText(context, if (ok) "CA installed" else "Failed to install CA", Toast.LENGTH_SHORT).show()
                    },
                    onUninstallCa = {
                        CaInstaller.uninstallCa(context)
                        refreshCaStatus()
                        Toast.makeText(context, "CA uninstalled", Toast.LENGTH_SHORT).show()
                    },
                    onReset = {
                        CaInstaller.resetPoc(context)
                        isInspectionEnabled = false
                        refreshCaStatus()
                        Toast.makeText(context, "Inspection reset", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Filters accordion toggle — collapsed by default so the five chip rows below don't
            // permanently eat into the list's vertical space; expand on demand.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.md))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { showFilters = !showFilters }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = if (activeFilterCount > 0) "Filters ($activeFilterCount active)" else "Filters",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (activeFilterCount > 0) FontWeight.Bold else FontWeight.Normal
                    )
                }
                Icon(
                    if (showFilters) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (showFilters) "Collapse filters" else "Expand filters"
                )
            }

            AnimatedVisibility(visible = showFilters) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    // Protocol chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        FilterChip(
                            selected = selectedProtocol == null,
                            onClick = { selectedProtocol = null },
                            label = { Text("All Protocols") }
                        )
                        TrafficProtocol.values().forEach { proto ->
                            FilterChip(
                                selected = selectedProtocol == proto,
                                onClick = { selectedProtocol = if (selectedProtocol == proto) null else proto },
                                label = { Text(proto.name) }
                            )
                        }
                    }

                    // Method chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        FilterChip(
                            selected = selectedMethod == null,
                            onClick = { selectedMethod = null },
                            label = { Text("All Methods") }
                        )
                        listOf("GET", "POST", "PUT", "DELETE", "UPGRADE").forEach { m ->
                            FilterChip(
                                selected = selectedMethod == m,
                                onClick = { selectedMethod = if (selectedMethod == m) null else m },
                                label = { Text(m) }
                            )
                        }
                    }

                    // State chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        FilterChip(
                            selected = selectedState == null,
                            onClick = { selectedState = null },
                            label = { Text("All States") }
                        )
                        listOf(
                            TrafficCaptureState.DECODED,
                            TrafficCaptureState.ENCRYPTED,
                            TrafficCaptureState.TLS_HANDSHAKE_FAILED,
                            TrafficCaptureState.TRUNCATED
                        ).forEach { state ->
                            FilterChip(
                                selected = selectedState == state,
                                onClick = { selectedState = if (selectedState == state) null else state },
                                label = { Text(state.name) }
                            )
                        }
                    }

                    // Status Code chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        FilterChip(
                            selected = selectedStatus == null,
                            onClick = { selectedStatus = null },
                            label = { Text("All Status") }
                        )
                        listOf(200 to "200 OK", 101 to "101 Upgrade", 204 to "204 No Content", 404 to "404 Not Found", 500 to "500 Error").forEach { (code, label) ->
                            FilterChip(
                                selected = selectedStatus == code,
                                onClick = { selectedStatus = if (selectedStatus == code) null else code },
                                label = { Text(label) }
                            )
                        }
                    }

                    // Time Range & WebSocket Direction / Message Type chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        // Time Range
                        FilterChip(
                            selected = selectedTimeFilter == "ALL",
                            onClick = { selectedTimeFilter = "ALL" },
                            label = { Text("All Time") }
                        )
                        FilterChip(
                            selected = selectedTimeFilter == "1M",
                            onClick = { selectedTimeFilter = if (selectedTimeFilter == "1M") "ALL" else "1M" },
                            label = { Text("Last 1m") }
                        )
                        FilterChip(
                            selected = selectedTimeFilter == "5M",
                            onClick = { selectedTimeFilter = if (selectedTimeFilter == "5M") "ALL" else "5M" },
                            label = { Text("Last 5m") }
                        )

                        // WebSocket Direction
                        FilterChip(
                            selected = selectedWsDirection == Direction.OUTBOUND,
                            onClick = { selectedWsDirection = if (selectedWsDirection == Direction.OUTBOUND) null else Direction.OUTBOUND },
                            label = { Text("WS Outbound ↑") }
                        )
                        FilterChip(
                            selected = selectedWsDirection == Direction.INBOUND,
                            onClick = { selectedWsDirection = if (selectedWsDirection == Direction.INBOUND) null else Direction.INBOUND },
                            label = { Text("WS Inbound ↓") }
                        )

                        // WebSocket Message Type
                        FilterChip(
                            selected = selectedWsMessageType == MessageType.TEXT,
                            onClick = { selectedWsMessageType = if (selectedWsMessageType == MessageType.TEXT) null else MessageType.TEXT },
                            label = { Text("WS Text") }
                        )
                        FilterChip(
                            selected = selectedWsMessageType == MessageType.BINARY,
                            onClick = { selectedWsMessageType = if (selectedWsMessageType == MessageType.BINARY) null else MessageType.BINARY },
                            label = { Text("WS Binary") }
                        )
                    }
                }
            }

            // Results summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Showing ${combinedItems.size} of ${allRecords.size + workFeed.size} items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!query.isEmpty()) {
                    TextButton(onClick = {
                        searchText = ""
                        selectedProtocol = null
                        selectedState = null
                        selectedMethod = null
                        selectedStatus = null
                        selectedTimeFilter = "ALL"
                        selectedWsDirection = null
                        selectedWsMessageType = null
                    }) {
                        Text("Reset Filters", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Traffic List
            if (combinedItems.isEmpty()) {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.base),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(
                            text = if (allRecords.isEmpty() && workFeed.isEmpty()) "No network observations yet" else "No traffic matching filters",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (allRecords.isEmpty() && workFeed.isEmpty()) "Decoded HTTP, HTTPS, WebSocket, HTTP/2, gRPC, SSE, and QUIC observations appear here when readable. Raw Work VPN DNS, TCP, blocked destinations, and resource warnings appear here for the active Work Profile session." else "Try adjusting or clearing your search filters.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(combinedItems, key = { it.key }) { item ->
                        when (item) {
                            is InspectorTimelineItem.Transaction -> TrafficItemRow(
                                record = item.record,
                                onClick = { selectedRecord = item.record }
                            )
                            is InspectorTimelineItem.WorkObservation -> WorkObservationItemRow(item.entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrafficItemRow(
    record: TrafficRecord,
    onClick: () -> Unit
) {
    val methodColor = when (record.method.uppercase()) {
        "GET" -> MaterialTheme.colorScheme.primary
        "POST" -> MaterialTheme.colorScheme.tertiary
        "UPGRADE" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.secondary
    }

    val stateColor = when (record.state) {
        TrafficCaptureState.DECODED -> MaterialTheme.colorScheme.tertiary
        TrafficCaptureState.ENCRYPTED -> MaterialTheme.colorScheme.onSurfaceVariant
        TrafficCaptureState.TLS_HANDSHAKE_FAILED, TrafficCaptureState.ERROR -> MaterialTheme.colorScheme.error
        TrafficCaptureState.TRUNCATED -> MaterialTheme.colorScheme.secondary
        TrafficCaptureState.UNSUPPORTED_PROTOCOL -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(Radii.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(text = record.method, color = methodColor)
                    Spacer(Modifier.width(Spacing.xs))
                    Badge(text = record.protocol.name, color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = record.host,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = record.url,
                    style = MonoCodeStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        text = "${record.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val ws = record.webSocketSession
                    val grpc = record.grpcSession
                    val sse = record.sseSession
                    val countText = when {
                        ws != null -> "${ws.messages.size} msgs"
                        grpc != null -> "${grpc.messages.size} gRPC msgs"
                        sse != null -> "${sse.events.size} SSE events"
                        else -> "${record.responseBodyBytes} B"
                    }
                    Text(
                        text = countText,
                        style = MonoCodeStyle.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                if (record.statusCode != null) {
                    Text(
                        text = "${record.statusCode}",
                        style = MonoCodeStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = when (record.statusCode) {
                            in 200..299 -> MaterialTheme.colorScheme.tertiary
                            101 -> MaterialTheme.colorScheme.secondary
                            in 400..599 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Spacer(Modifier.height(2.dp))
                Badge(text = record.state.name, color = stateColor)
            }
        }
    }
}

private sealed class InspectorTimelineItem(val timestampEpochMs: Long, val key: String) {
    class Transaction(val record: TrafficRecord) : InspectorTimelineItem(record.timestamp.toEpochMilli(), "traffic:${record.id}")
    class WorkObservation(val entry: LiveMonitorAggregator.FeedEntry) : InspectorTimelineItem(entry.timestampEpochMs, "work:${entry.sequence}")
}

private fun filterWorkFeedForInspector(
    feed: List<LiveMonitorAggregator.FeedEntry>,
    query: TrafficFilterQuery
): List<LiveMonitorAggregator.FeedEntry> {
    val transactionOnlyFilterActive = query.protocol != null ||
            query.method != null ||
            query.statusCode != null ||
            query.state != null ||
            query.wsDirection != null ||
            query.wsMessageType != null
    if (transactionOnlyFilterActive) return emptyList()

    val searchLower = query.searchText?.trim()?.lowercase()?.ifBlank { null }
    return feed.filter { entry ->
        val timestamp = Instant.ofEpochMilli(entry.timestampEpochMs)
        if (query.timeRangeStart != null && timestamp.isBefore(query.timeRangeStart)) return@filter false
        if (query.timeRangeEnd != null && timestamp.isAfter(query.timeRangeEnd)) return@filter false
        if (searchLower != null) {
            val haystack = buildString {
                append(entry.category.name).append(' ')
                append(entry.title).append(' ')
                append(entry.subtitle.orEmpty())
            }.lowercase()
            if (!haystack.contains(searchLower)) return@filter false
        }
        true
    }
}

@Composable
private fun WorkObservationItemRow(entry: LiveMonitorAggregator.FeedEntry) {
    val protocolLabel = when (entry.category) {
        LiveMonitorAggregator.FeedCategory.DNS -> "DNS"
        LiveMonitorAggregator.FeedCategory.TCP -> "TCP"
        LiveMonitorAggregator.FeedCategory.BLOCKED -> "BLOCKED"
        LiveMonitorAggregator.FeedCategory.WARNING -> "LIMIT"
    }
    val chipColor = when (entry.category) {
        LiveMonitorAggregator.FeedCategory.DNS -> MaterialTheme.colorScheme.secondary
        LiveMonitorAggregator.FeedCategory.TCP -> MaterialTheme.colorScheme.primary
        LiveMonitorAggregator.FeedCategory.BLOCKED -> MaterialTheme.colorScheme.error
        LiveMonitorAggregator.FeedCategory.WARNING -> MaterialTheme.colorScheme.tertiary
    }
    val age = remember(entry.timestampEpochMs) {
        val seconds = Duration.between(Instant.ofEpochMilli(entry.timestampEpochMs), Instant.now()).seconds.coerceAtLeast(0)
        when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            else -> "${seconds / 3600}h ago"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(text = protocolLabel, color = chipColor)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = entry.subtitle ?: "Work VPN observation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val byteSummary = when {
                    entry.uploadedBytes != null || entry.downloadedBytes != null ->
                        "up ${entry.uploadedBytes ?: 0} B · down ${entry.downloadedBytes ?: 0} B"
                    else -> "metadata observation"
                }
                Text(
                    text = byteSummary,
                    style = MonoCodeStyle.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(age, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.suspicious) Badge(text = "REVIEW", color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun TrafficSettingsCard(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    isCaInstalled: Boolean,
    onInstallCa: () -> Unit,
    onUninstallCa: () -> Unit,
    onReset: () -> Unit
) {
    BaseCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ObservationModeGuide()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("HTTPS & WSS Decryption", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    Text(
                        text = if (isEnabled) "Active: Intercepting port 443" else "Disabled: Port 443 passed through raw",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = isEnabled, onCheckedChange = onToggle)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CA Status: ${if (isCaInstalled) "Installed in Work Profile" else "Not installed"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCaInstalled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                )
                if (!isCaInstalled) {
                    Button(onClick = onInstallCa) { Text("Install CA", style = MaterialTheme.typography.labelSmall) }
                } else {
                    OutlinedButton(onClick = onUninstallCa) { Text("Uninstall CA", style = MaterialTheme.typography.labelSmall) }
                }
            }

            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset Inspection & Purge CA Keys", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ObservationModeGuide() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            "Choose an observation mode",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "1. CA mode: install the Work Profile CA, enable decryption, then observe HTTPS/WSS. Some apps may reject interception with TLS or certificate-pinning failures.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "2. Frida mode: patch/instrument the APK, keep CA decryption disabled, then observe traffic from the instrumented app. Raw TCP, DNS, blocked endpoints, and non-decoded streams remain in Live Monitor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun seedDemoTraffic() {
    TrafficInspectionStore.clear()
    val now = Instant.now()

    // 1. HTTP GET (plaintext)
    TrafficInspectionStore.record(
        TrafficRecord(
            id = "rec-http-get",
            protocol = TrafficProtocol.HTTP,
            method = "GET",
            host = "httpbin.org",
            port = 80,
            url = "http://httpbin.org/get",
            requestHeaders = mapOf("Host" to "httpbin.org", "User-Agent" to "Android-Sandbox/1.0"),
            responseHeaders = mapOf("Content-Type" to "application/json", "Server" to "gunicorn/19.9.0"),
            statusCode = 200,
            statusMessage = "OK",
            contentType = "application/json",
            requestBody = null,
            responseBody = "{\n  \"args\": {},\n  \"headers\": {\n    \"Host\": \"httpbin.org\"\n  },\n  \"origin\": \"127.0.0.1\",\n  \"url\": \"http://httpbin.org/get\"\n}",
            state = TrafficCaptureState.DECODED,
            timestamp = now.minusSeconds(120),
            durationMs = 85
        )
    )

    // 2. HTTP POST (plaintext)
    TrafficInspectionStore.record(
        TrafficRecord(
            id = "rec-http-post",
            protocol = TrafficProtocol.HTTP,
            method = "POST",
            host = "httpbin.org",
            port = 80,
            url = "http://httpbin.org/post",
            requestHeaders = mapOf("Host" to "httpbin.org", "Content-Type" to "application/json"),
            responseHeaders = mapOf("Content-Type" to "application/json"),
            statusCode = 200,
            statusMessage = "OK",
            contentType = "application/json",
            requestBody = "{\"action\": \"ping\", \"client\": \"sandbox-fixture\"}",
            responseBody = "{\n  \"data\": \"{\\\"action\\\": \\\"ping\\\", \\\"client\\\": \\\"sandbox-fixture\\\"}\",\n  \"json\": {\"action\": \"ping\", \"client\": \"sandbox-fixture\"}\n}",
            state = TrafficCaptureState.DECODED,
            timestamp = now.minusSeconds(90),
            durationMs = 112
        )
    )

    // 3. HTTPS GET (intercepted & decoded)
    TrafficInspectionStore.record(
        TrafficRecord(
            id = "rec-https-get",
            protocol = TrafficProtocol.HTTPS,
            method = "GET",
            host = "api.github.com",
            port = 443,
            url = "https://api.github.com/zen",
            requestHeaders = mapOf("Host" to "api.github.com", "User-Agent" to "Android-Sandbox/1.0"),
            responseHeaders = mapOf("Content-Type" to "text/plain;charset=utf-8"),
            statusCode = 200,
            statusMessage = "OK",
            contentType = "text/plain",
            requestBody = null,
            responseBody = "Practicality beats purity.",
            state = TrafficCaptureState.DECODED,
            timestamp = now.minusSeconds(60),
            durationMs = 145
        )
    )

    // 4. HTTPS POST (intercepted & decoded)
    TrafficInspectionStore.record(
        TrafficRecord(
            id = "rec-https-post",
            protocol = TrafficProtocol.HTTPS,
            method = "POST",
            host = "httpbin.org",
            port = 443,
            url = "https://httpbin.org/post",
            requestHeaders = mapOf("Host" to "httpbin.org", "Content-Type" to "application/json"),
            responseHeaders = mapOf("Content-Type" to "application/json"),
            statusCode = 200,
            statusMessage = "OK",
            contentType = "application/json",
            requestBody = "{\"token\": \"sec_test_token_42\", \"status\": \"verified\"}",
            responseBody = "{\n  \"data\": \"{\\\"token\\\": \\\"sec_test_token_42\\\"}\",\n  \"url\": \"https://httpbin.org/post\"\n}",
            state = TrafficCaptureState.DECODED,
            timestamp = now.minusSeconds(40),
            durationMs = 160
        )
    )

    // 5. WS Echo
    val wsSession = WebSocketSessionData(
        openedAt = now.minusSeconds(30),
        closedAt = now.minusSeconds(20),
        closeCode = 1000,
        closeReason = "Normal closure",
        messages = listOf(
            WebSocketMessage(sequence = 1, direction = Direction.OUTBOUND, type = MessageType.TEXT, timestamp = now.minusSeconds(28), payloadLength = 14, payloadPreview = "hello ws echo!", isMasked = true),
            WebSocketMessage(sequence = 2, direction = Direction.INBOUND, type = MessageType.TEXT, timestamp = now.minusSeconds(27), payloadLength = 14, payloadPreview = "hello ws echo!", isMasked = false),
            WebSocketMessage(sequence = 3, direction = Direction.OUTBOUND, type = MessageType.CLOSE, timestamp = now.minusSeconds(20), payloadLength = 2, payloadPreview = "1000 Normal closure", isMasked = true, closeCode = 1000, closeReason = "Normal closure")
        ),
        bytesIn = 14,
        bytesOut = 16
    )
    TrafficInspectionStore.record(
        TrafficRecord(
            id = "rec-ws-echo",
            protocol = TrafficProtocol.WS,
            method = "GET",
            host = "echo.websocket.org",
            port = 80,
            url = "ws://echo.websocket.org/.ws",
            requestHeaders = mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"),
            responseHeaders = mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"),
            statusCode = 101,
            statusMessage = "Switching Protocols",
            state = TrafficCaptureState.DECODED,
            timestamp = now.minusSeconds(30),
            durationMs = 10000,
            webSocketSession = wsSession
        )
    )

    // 6. WSS Reconstructed Echo (fragmented, defragmented logical messages, interleaved control frames, binary)
    val wssSession = WebSocketSessionData(
        openedAt = now.minusSeconds(15),
        closedAt = now.minusSeconds(2),
        closeCode = 1000,
        closeReason = "Session finished",
        messages = listOf(
            WebSocketMessage(sequence = 1, direction = Direction.OUTBOUND, type = MessageType.TEXT, timestamp = now.minusSeconds(14), payloadLength = 48, payloadPreview = "Fragmented message part 1 + Fragmented message part 2", isMasked = true, isFragmented = true, isReconstructed = true, fragmentCount = 2),
            WebSocketMessage(sequence = 2, direction = Direction.INBOUND, type = MessageType.PING, timestamp = now.minusSeconds(12), payloadLength = 4, payloadPreview = "ping", isMasked = false),
            WebSocketMessage(sequence = 3, direction = Direction.OUTBOUND, type = MessageType.PONG, timestamp = now.minusSeconds(11), payloadLength = 4, payloadPreview = "ping", isMasked = true),
            WebSocketMessage(sequence = 4, direction = Direction.INBOUND, type = MessageType.TEXT, timestamp = now.minusSeconds(10), payloadLength = 48, payloadPreview = "Fragmented message part 1 + Fragmented message part 2", isMasked = false, isFragmented = true, isReconstructed = true, fragmentCount = 2),
            WebSocketMessage(sequence = 5, direction = Direction.OUTBOUND, type = MessageType.BINARY, timestamp = now.minusSeconds(8), payloadLength = 4, payloadPreview = "[Binary 4 bytes: 0xDEADBEEF]", isMasked = true, isFragmented = false, isReconstructed = false),
            WebSocketMessage(sequence = 6, direction = Direction.INBOUND, type = MessageType.BINARY, timestamp = now.minusSeconds(7), payloadLength = 4, payloadPreview = "[Binary 4 bytes: 0xDEADBEEF]", isMasked = false, isFragmented = false, isReconstructed = false),
            WebSocketMessage(sequence = 7, direction = Direction.OUTBOUND, type = MessageType.CLOSE, timestamp = now.minusSeconds(2), payloadLength = 2, payloadPreview = "1000 Session finished", isMasked = true, closeCode = 1000, closeReason = "Session finished")
        ),
        bytesIn = 56,
        bytesOut = 58
    )
    TrafficInspectionStore.record(
        TrafficRecord(
            id = "rec-wss-echo",
            protocol = TrafficProtocol.WSS,
            method = "GET",
            host = "echo.websocket.org",
            port = 443,
            url = "wss://echo.websocket.org/.ws",
            requestHeaders = mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"),
            responseHeaders = mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"),
            statusCode = 101,
            statusMessage = "Switching Protocols",
            state = TrafficCaptureState.DECODED,
            timestamp = now.minusSeconds(15),
            durationMs = 13000,
            webSocketSession = wssSession
        )
    )

    // 7. HTTP/2 Multiplexed GET (TLS Decoded)
    TrafficInspectionStore.record(
        TrafficRecord(
            id = "rec-h2-get",
            protocol = TrafficProtocol.HTTPS_2,
            method = "GET",
            host = "http2.golang.org",
            port = 443,
            url = "https://http2.golang.org/reqinfo",
            streamId = 1,
            requestHeaders = mapOf(
                ":method" to "GET",
                ":path" to "/reqinfo",
                ":scheme" to "https",
                ":authority" to "http2.golang.org",
                "user-agent" to "APK-Scope/1.0"
            ),
            responseHeaders = mapOf(
                ":status" to "200",
                "content-type" to "application/json"
            ),
            statusCode = 200,
            statusMessage = "OK",
            contentType = "application/json",
            requestBody = null,
            responseBody = "{\n  \"protocol\": \"HTTP/2.0\",\n  \"multiplexed\": true,\n  \"stream_id\": 1\n}",
            responseBodyBytes = 68,
            state = TrafficCaptureState.DECODED,
            timestamp = now.minusSeconds(10),
            durationMs = 95
        )
    )

    // 8. gRPC Unary Exchange (HTTP/2 Stream #3)
    val grpcMessages = listOf(
        com.nadeem.apkscope.core.network.traffic.GrpcMessage(
            sequence = 1,
            direction = Direction.OUTBOUND,
            timestamp = now.minusSeconds(7),
            isCompressed = false,
            length = 18,
            payloadPreview = "[Protobuf Binary: 18 bytes] 0A 0C 41 6E 64 72 6F 69 64 2D 41 70 6B 10 01",
            isBinary = true
        ),
        com.nadeem.apkscope.core.network.traffic.GrpcMessage(
            sequence = 2,
            direction = Direction.INBOUND,
            timestamp = now.minusSeconds(6),
            isCompressed = false,
            length = 24,
            payloadPreview = "[Protobuf Binary: 24 bytes] 0A 12 48 65 6C 6C 6F 20 41 6E 64 72 6F 69 64 21",
            isBinary = true
        )
    )
    val grpcSession = com.nadeem.apkscope.core.network.traffic.GrpcSessionData(
        serviceName = "helloworld.Greeter",
        methodName = "SayHello",
        messages = grpcMessages,
        grpcStatus = 0,
        grpcStatusName = "OK",
        grpcMessage = null,
        trailers = mapOf("grpc-status" to "0", "grpc-message" to "OK")
    )
    TrafficInspectionStore.record(
        TrafficRecord(
            id = "rec-grpc-sayhello",
            protocol = TrafficProtocol.GRPC,
            method = "POST",
            host = "grpcb.in",
            port = 443,
            url = "https://grpcb.in/helloworld.Greeter/SayHello",
            streamId = 3,
            requestHeaders = mapOf(
                ":method" to "POST",
                ":path" to "/helloworld.Greeter/SayHello",
                ":scheme" to "https",
                ":authority" to "grpcb.in",
                "content-type" to "application/grpc"
            ),
            responseHeaders = mapOf(
                ":status" to "200",
                "content-type" to "application/grpc"
            ),
            statusCode = 200,
            statusMessage = "OK",
            contentType = "application/grpc",
            state = TrafficCaptureState.DECODED,
            timestamp = now.minusSeconds(7),
            durationMs = 120,
            grpcSession = grpcSession
        )
    )

    // 9. Server-Sent Events (SSE) Live Stream
    val sseEvents = listOf(
        com.nadeem.apkscope.core.network.traffic.SseEvent(
            id = "evt-101",
            eventType = "status",
            data = "{\"service\": \"sandbox-bridge\", \"status\": \"connected\"}",
            retryMs = 3000,
            timestamp = now.minusSeconds(5)
        ),
        com.nadeem.apkscope.core.network.traffic.SseEvent(
            id = "evt-102",
            eventType = "comment",
            data = "keep-alive heartbeat",
            isComment = true,
            timestamp = now.minusSeconds(3)
        ),
        com.nadeem.apkscope.core.network.traffic.SseEvent(
            id = "evt-103",
            eventType = "message",
            data = "{\"event\": \"security_alert\", \"severity\": \"low\", \"rule\": \"URL_REFERENCED\"}",
            timestamp = now.minusSeconds(1)
        )
    )
    val sseSession = com.nadeem.apkscope.core.network.traffic.SseSessionData(
        events = sseEvents,
        isLive = true,
        lastEventId = "evt-103"
    )
    TrafficInspectionStore.record(
        TrafficRecord(
            id = "rec-sse-live",
            protocol = TrafficProtocol.SSE,
            method = "GET",
            host = "events.apksandbox.io",
            port = 443,
            url = "https://events.apksandbox.io/stream",
            streamId = 5,
            requestHeaders = mapOf(
                ":method" to "GET",
                ":path" to "/stream",
                "accept" to "text/event-stream"
            ),
            responseHeaders = mapOf(
                ":status" to "200",
                "content-type" to "text/event-stream",
                "cache-control" to "no-cache"
            ),
            statusCode = 200,
            statusMessage = "OK",
            contentType = "text/event-stream",
            state = TrafficCaptureState.DECODED,
            timestamp = now.minusSeconds(5),
            durationMs = 4500,
            sseSession = sseSession
        )
    )

    // 10. QUIC Observational Telemetry
    val quicObservation = com.nadeem.apkscope.core.network.traffic.QuicObservationData(
        version = "QUIC v1 (RFC 9000)",
        packetType = "INITIAL",
        destinationConnectionIdHex = "8343ac8f12674e2d",
        sourceConnectionIdHex = "5236b281fa01",
        isConfirmedHttp3 = false
    )
    TrafficInspectionStore.record(
        TrafficRecord(
            id = "rec-quic-obs",
            protocol = TrafficProtocol.QUIC_OBSERVED,
            method = "UDP",
            host = "cloudflare-quic.com",
            port = 443,
            url = "quic://cloudflare-quic.com:443",
            requestHeaders = emptyMap(),
            responseHeaders = emptyMap(),
            statusCode = null,
            state = TrafficCaptureState.DECODED,
            timestamp = now.minusSeconds(1),
            durationMs = 12,
            quicDetails = quicObservation
        )
    )
}

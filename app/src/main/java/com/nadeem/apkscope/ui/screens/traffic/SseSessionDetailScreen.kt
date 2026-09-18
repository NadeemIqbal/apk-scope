package com.nadeem.apkscope.ui.screens.traffic

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadeem.apkscope.core.network.traffic.SseEvent
import com.nadeem.apkscope.core.network.traffic.TrafficRecord
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing

@Composable
fun SseSessionDetailScreen(
    record: TrafficRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sse = record.sseSession
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "SSE Stream Inspection",
                eyebrow = record.protocol.name,
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.base, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Overview Card
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Badge(text = "Server-Sent Events", color = MaterialTheme.colorScheme.primary)
                            val isLive = sse?.isLive ?: false
                            val liveColor = if (isLive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                            Badge(text = if (isLive) "LIVE STREAMING" else "STREAM FINISHED", color = liveColor)
                        }

                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = record.url,
                            style = MonoCodeStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Content-Type: ${record.contentType ?: "text/event-stream"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (sse?.lastEventId != null) {
                            Text(
                                text = "Last Dispatched ID: ${sse.lastEventId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = Spacing.xs),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Total Events: ${sse?.events?.size ?: 0}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Bytes: ${record.responseBodyBytes} B",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Events List Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Received Events",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${sse?.events?.size ?: 0} events",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (sse == null || sse.events.isEmpty()) {
                item {
                    BaseCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(Spacing.base),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No events received on this stream yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(sse.events) { event ->
                    SseEventCard(
                        event = event,
                        onCopy = { clipboardManager.setText(AnnotatedString(event.data)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SseEventCard(
    event: SseEvent,
    onCopy: () -> Unit
) {
    BaseCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.isComment) {
                        Badge(text = "COMMENT / HEARTBEAT", color = MaterialTheme.colorScheme.outline)
                    } else {
                        Badge(text = event.eventType, color = MaterialTheme.colorScheme.secondary)
                    }

                    if (event.id != null) {
                        Spacer(Modifier.width(Spacing.xs))
                        Badge(text = "id: ${event.id}", color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    if (event.retryMs != null) {
                        Spacer(Modifier.width(Spacing.xs))
                        Badge(text = "retry: ${event.retryMs}ms", color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy event data",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(Radii.sm)
                    )
                    .padding(Spacing.sm)
            ) {
                Text(
                    text = event.data.ifBlank { if (event.isComment) "[Keep-Alive Heartbeat]" else "[Empty Event Data]" },
                    style = MonoCodeStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

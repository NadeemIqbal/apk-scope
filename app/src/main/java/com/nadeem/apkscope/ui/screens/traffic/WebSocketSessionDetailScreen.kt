package com.nadeem.apkscope.ui.screens.traffic

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadeem.apkscope.core.network.traffic.Direction
import com.nadeem.apkscope.core.network.traffic.MessageType
import com.nadeem.apkscope.core.network.traffic.TrafficRecord
import com.nadeem.apkscope.core.network.traffic.WebSocketMessage
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing

@Composable
fun WebSocketSessionDetailScreen(
    record: TrafficRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val session = record.webSocketSession

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "WebSocket Session",
                eyebrow = "SANDBOX TRAFFIC",
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
            // Header card
            item {
                BaseCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            com.nadeem.apkscope.ui.components.Badge(
                                text = record.protocol.name,
                                color = if (record.protocol.name == "WSS") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "${session?.messages?.size ?: 0} messages",
                                style = MonoCodeStyle,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = record.url,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Host: ${record.host}:${record.port}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (session?.closedAt != null) {
                                Text(
                                    text = "Closed (Code: ${session.closeCode ?: 1000})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                Text(
                                    text = "Active / Open",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }

            // Handshake card
            item {
                BaseCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(
                            text = "Handshake HTTP/1.1 101 Switching Protocols",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        record.requestHeaders.filterKeys { it.startsWith("sec-websocket", ignoreCase = true) || it.equals("upgrade", ignoreCase = true) }.forEach { (k, v) ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(k, style = MonoCodeStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(v, style = MonoCodeStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }

            // Message Timeline
            item {
                Text(
                    text = "Recorded Messages (${session?.messages?.size ?: 0})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = Spacing.xs)
                )
            }

            if (session == null || session.messages.isEmpty()) {
                item {
                    BaseCard {
                        Text(
                            text = "No messages recorded in this WebSocket session yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(session.messages, key = { "${it.sequence}_${it.direction}" }) { msg ->
                    WebSocketMessageRow(msg)
                }
            }
        }
    }
}

@Composable
fun WebSocketMessageRow(msg: WebSocketMessage) {
    val isOutbound = msg.direction == Direction.OUTBOUND
    val dirColor = if (isOutbound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val dirIcon = if (isOutbound) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward
    val dirLabel = if (isOutbound) "CLIENT → SERVER" else "SERVER → CLIENT"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(dirColor.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(dirIcon, contentDescription = null, tint = dirColor, modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = dirLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = dirColor
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (msg.isReconstructed) {
                        com.nadeem.apkscope.ui.components.Badge(
                            text = "${msg.fragmentCount} FRAGMENTS",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    com.nadeem.apkscope.ui.components.Badge(
                        text = msg.type.name,
                        color = when (msg.type) {
                            MessageType.TEXT -> MaterialTheme.colorScheme.secondary
                            MessageType.BINARY -> MaterialTheme.colorScheme.primary
                            MessageType.CLOSE -> MaterialTheme.colorScheme.error
                            MessageType.PING, MessageType.PONG -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = "${msg.payloadLength} B",
                        style = MonoCodeStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val preview = msg.payloadPreview
            if (!preview.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Radii.sm))
                        .padding(Spacing.xs)
                ) {
                    Text(
                        text = preview,
                        style = MonoCodeStyle.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

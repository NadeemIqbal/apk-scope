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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.nadeem.apkscope.core.network.traffic.Direction
import com.nadeem.apkscope.core.network.traffic.GrpcMessage
import com.nadeem.apkscope.core.network.traffic.TrafficRecord
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing

@Composable
fun GrpcSessionDetailScreen(
    record: TrafficRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val grpc = record.grpcSession
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "gRPC Inspection",
                eyebrow = "HTTP/2 STREAM #${record.streamId ?: 1}",
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
            // Header Overview Card
            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Badge(text = "gRPC", color = MaterialTheme.colorScheme.primary)
                            val statusVal = grpc?.grpcStatus ?: 0
                            val statusName = grpc?.grpcStatusName ?: "OK"
                            val statusColor = if (statusVal == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                            Badge(text = "$statusVal $statusName", color = statusColor)
                        }

                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Service: ${grpc?.serviceName.orEmpty().ifBlank { record.url.substringBeforeLast('/').substringAfterLast('/') }}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Method: ${grpc?.methodName.orEmpty().ifBlank { record.url.substringAfterLast('/') }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (grpc?.grpcMessage != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Message: ${grpc.grpcMessage}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = Spacing.xs),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        Text(
                            text = "Endpoint: ${record.url}",
                            style = MonoCodeStyle.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Duration: ${record.durationMs}ms • Messages: ${grpc?.messages?.size ?: 0} (${if (grpc?.isStreaming == true) "Streaming" else "Unary"})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Section: Decoded gRPC Messages
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Message Timeline",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${grpc?.messages?.size ?: 0} message(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (grpc == null || grpc.messages.isEmpty()) {
                item {
                    BaseCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(Spacing.base),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No messages recorded in this gRPC stream",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(grpc.messages) { msg ->
                    GrpcMessageCard(msg = msg, onCopy = { clipboardManager.setText(AnnotatedString(msg.payloadPreview)) })
                }
            }

            // Section: Metadata / Headers / Trailers
            item {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "Stream Metadata & Trailers",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            item {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text("Request Headers", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        record.requestHeaders.forEach { (k, v) ->
                            Text("$k: $v", style = MonoCodeStyle.copy(fontSize = 11.sp))
                        }

                        if (record.responseHeaders.isNotEmpty()) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text("Response Headers", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                            record.responseHeaders.forEach { (k, v) ->
                                Text("$k: $v", style = MonoCodeStyle.copy(fontSize = 11.sp))
                            }
                        }

                        if (grpc?.trailers?.isNotEmpty() == true) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text("Response Trailers", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                            grpc.trailers.forEach { (k, v) ->
                                Text("$k: $v", style = MonoCodeStyle.copy(fontSize = 11.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GrpcMessageCard(
    msg: GrpcMessage,
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
                    val isOutbound = msg.direction == Direction.OUTBOUND
                    val dirColor = if (isOutbound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    val dirText = if (isOutbound) "OUTBOUND ↑" else "INBOUND ↓"
                    Badge(text = dirText, color = dirColor)
                    Spacer(Modifier.width(Spacing.xs))
                    Badge(text = "#${msg.sequence}", color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(Spacing.xs))
                    Badge(text = "${msg.length} B", color = MaterialTheme.colorScheme.outlineVariant)
                    if (msg.isCompressed) {
                        Spacer(Modifier.width(Spacing.xs))
                        Badge(text = "GZIP", color = MaterialTheme.colorScheme.tertiary)
                    }
                }

                IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy message",
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
                    text = msg.payloadPreview.ifBlank { "[Empty Payload]" },
                    style = MonoCodeStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

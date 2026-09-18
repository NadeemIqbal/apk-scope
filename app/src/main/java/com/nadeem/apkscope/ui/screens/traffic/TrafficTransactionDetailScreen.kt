package com.nadeem.apkscope.ui.screens.traffic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadeem.apkscope.core.network.traffic.TrafficCaptureState
import com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore
import com.nadeem.apkscope.core.network.traffic.TrafficRecord
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing

@Composable
fun TrafficTransactionDetailScreen(
    record: TrafficRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var redactSecrets by rememberSaveable { mutableStateOf(false) }
    val displayedRecord = if (redactSecrets) TrafficInspectionStore.redacted(record) else record

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun shareTransaction() {
        val text = buildShareText(displayedRecord)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text),
                "Share API transaction"
            )
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "Transaction Detail",
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Badge(
                                    text = record.method,
                                    color = when (record.method.uppercase()) {
                                        "GET" -> MaterialTheme.colorScheme.primary
                                        "POST" -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.secondary
                                    }
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Badge(
                                    text = record.protocol.name,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            val statusColor = when (record.statusCode ?: 0) {
                                in 200..299 -> MaterialTheme.colorScheme.tertiary
                                in 300..399 -> MaterialTheme.colorScheme.secondary
                                in 400..499 -> MaterialTheme.colorScheme.error
                                in 500..599 -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${record.statusCode ?: "-"} ${record.statusMessage ?: ""}",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = statusColor
                                )
                                IconButton(
                                    onClick = {
                                        copyToClipboard("API transaction", buildShareText(displayedRecord))
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "Copy cURL and response",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = ::shareTransaction, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Filled.Share,
                                        contentDescription = "Share cURL and response",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = displayedRecord.url,
                            style = MonoCodeStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Duration: ${displayedRecord.durationMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Req: ${displayedRecord.requestBodyBytes} B | Resp: ${displayedRecord.responseBodyBytes} B",
                                style = MonoCodeStyle.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                BaseCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Redact secrets", style = MaterialTheme.typography.labelLarge)
                            Text(
                                if (redactSecrets) "Authorization, cookies, URL and body secrets hidden" else "Showing captured headers, cookies, and authorization values",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = redactSecrets, onCheckedChange = { redactSecrets = it })
                    }
                }
            }

            // Failure / State alert card if not simply DECODED
            if (record.state != TrafficCaptureState.DECODED) {
                item {
                    val alertColor = when (record.state) {
                        TrafficCaptureState.TLS_HANDSHAKE_FAILED, TrafficCaptureState.ERROR -> MaterialTheme.colorScheme.error
                        TrafficCaptureState.TRUNCATED -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radii.md),
                        colors = CardDefaults.cardColors(containerColor = alertColor.copy(alpha = 0.15f))
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = alertColor)
                            Spacer(Modifier.width(Spacing.sm))
                            Column {
                                Text(
                                    text = "Capture State: ${record.state.name}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = alertColor
                                )
                                val details = record.failureDetails
                                if (!details.isNullOrBlank()) {
                                    Text(
                                        text = details,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Request Section
            item {
                DetailSectionCard(
                    title = "Request Headers & Body",
                    headers = displayedRecord.requestHeaders,
                    body = displayedRecord.requestBody,
                    onCopyBody = { copyToClipboard("Request Body", displayedRecord.requestBody ?: "") }
                )
            }

            // Response Section
            item {
                DetailSectionCard(
                    title = "Response Headers & Body",
                    headers = displayedRecord.responseHeaders,
                    body = displayedRecord.responseBody,
                    onCopyBody = { copyToClipboard("Response Body", displayedRecord.responseBody ?: "") }
                )
            }
        }
    }
}

private fun buildShareText(record: TrafficRecord): String = buildString {
    appendLine("# cURL")
    append("curl -i -X ").append(record.method.ifBlank { "GET" }).append(" ")
    record.requestHeaders.forEach { (name, value) ->
        append("-H ").append(shellQuote("$name: $value")).append(" ")
    }
    record.requestBody?.takeIf { it.isNotBlank() }?.let { body ->
        append("--data-raw ").append(shellQuote(body)).append(" ")
    }
    append(shellQuote(record.url)).appendLine()
    appendLine()
    appendLine("# Response")
    appendLine("HTTP ${record.statusCode ?: "-"} ${record.statusMessage.orEmpty()}".trim())
    record.responseHeaders.forEach { (name, value) -> appendLine("$name: $value") }
    record.responseBody?.takeIf { it.isNotBlank() }?.let {
        appendLine()
        appendLine(it)
    }
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\\"'\\\"'")}'"

@Composable
private fun DetailSectionCard(
    title: String,
    headers: Map<String, String>,
    body: String?,
    onCopyBody: () -> Unit
) {
    BaseCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (headers.isEmpty()) {
                Text("No headers recorded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    headers.forEach { (name, value) ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = name,
                                style = MonoCodeStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.weight(0.4f)
                            )
                            Text(
                                text = value,
                                style = MonoCodeStyle.copy(fontSize = 11.sp),
                                color = if (value == "[REDACTED]") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(0.6f)
                            )
                        }
                    }
                }
            }

            if (!body.isNullOrBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Body Preview",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onCopyBody, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy body", modifier = Modifier.size(16.dp))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Radii.sm))
                        .padding(Spacing.xs)
                ) {
                    Text(
                        text = body,
                        style = MonoCodeStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

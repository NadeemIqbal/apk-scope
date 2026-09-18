package com.nadeem.apkscope.ui.components
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors

/** One row in Live Monitor's "Inspection Timeline" — protocol chip(s), age, and detail line(s). [hostname] must come only from real DNS evidence (a `NetworkObservation.DnsResponse`); a raw IP connection with no such evidence must render with no hostname, never a guessed one (item 15). */
@Composable
fun NetworkEventRow(
 protocol: String,
 detail: String,
 ageLabel: String,
 modifier: Modifier = Modifier,
 subDetail: String? = null,
 blocked: Boolean = false,
 suspicious: Boolean = false,
) {
 val accent = when {
  blocked -> MaterialTheme.colorScheme.error
  suspicious -> MaterialTheme.extendedColors.warning
  else -> MaterialTheme.colorScheme.onSurfaceVariant
 }
 BaseCard(containerColor = if (blocked) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Row(verticalAlignment = Alignment.CenterVertically) {
    Badge(text = protocol, color = accent)
    if (blocked) { Spacer(Modifier.width(Spacing.xs)); Badge(text = "BLOCKED", color = MaterialTheme.colorScheme.error) }
    if (suspicious) { Spacer(Modifier.width(Spacing.xs)); Badge(text = "Suspicious", color = MaterialTheme.extendedColors.warning) }
   }
   Text(ageLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Spacer(Modifier.height(Spacing.xs))
  Text(detail, style = MonoCodeStyle, color = MaterialTheme.colorScheme.onSurface)
  if (subDetail != null) Text(subDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
 }
}

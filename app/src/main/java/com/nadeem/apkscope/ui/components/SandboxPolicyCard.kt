package com.nadeem.apkscope.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors

/**
 * Configure Sandbox / Ready-to-Run's per-restriction row, driven directly by `core:model`'s
 * [EnforcementStatus] — the whole point of item 11: never use an active-looking toggle for a
 * restriction Android won't actually enforce. [ENFORCED] alone gets a green "Blocked"/"Active"
 * label; [NOT_SUPPORTED] gets a neutral "Unavailable on this device" (matches the physical Pixel 8
 * finding on sensor permissions verbatim); [FAILED] gets an error label with [message] surfaced.
 */
@Composable
fun SandboxPolicyCard(
 icon: ImageVector,
 title: String,
 status: EnforcementStatus,
 modifier: Modifier = Modifier,
 activeLabel: String = "Blocked",
 message: String? = null,
) {
 BaseCard(modifier) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Radii.md)), contentAlignment = Alignment.Center) {
     androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(Spacing.sm))
    Column {
     Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
     Text(statusSubtitle(status, activeLabel, message), style = MaterialTheme.typography.labelSmall, color = statusColorFor(status))
    }
   }
   EnforcementStatusPill(status, activeLabel)
  }
 }
}

@Composable
private fun statusColorFor(status: EnforcementStatus) = when (status) {
 EnforcementStatus.ENFORCED -> MaterialTheme.colorScheme.tertiary
 EnforcementStatus.NOT_SUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
 EnforcementStatus.FAILED -> MaterialTheme.colorScheme.error
}

private fun statusSubtitle(status: EnforcementStatus, activeLabel: String, message: String?): String = when (status) {
 EnforcementStatus.ENFORCED -> activeLabel
 EnforcementStatus.NOT_SUPPORTED -> "Unavailable on this device"
 EnforcementStatus.FAILED -> message ?: "Could not be applied"
}

@Composable
private fun EnforcementStatusPill(status: EnforcementStatus, activeLabel: String) {
 val (text, color) = when (status) {
  EnforcementStatus.ENFORCED -> activeLabel to MaterialTheme.colorScheme.tertiary
  EnforcementStatus.NOT_SUPPORTED -> "Unavailable" to MaterialTheme.colorScheme.onSurfaceVariant
  EnforcementStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
 }
 Badge(text = text, color = color)
}

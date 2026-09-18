package com.nadeem.apkscope.ui.components
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.ui.common.UiStatus
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors

/** Telemetry console panel: white surface, hairline border, compact 8dp radius. */
@Composable
fun BaseCard(modifier: Modifier = Modifier, containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainer, content: @Composable ColumnScope.() -> Unit) {
 Card(
  modifier = modifier.fillMaxWidth(),
  colors = CardDefaults.cardColors(containerColor = containerColor),
  shape = RoundedCornerShape(Radii.xl),
  border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
 ) {
  Column(Modifier.padding(Spacing.md), content = content)
 }
}

/** One row inside Home's "Sandbox Environment" card, Ready-to-Run's "Isolation Parameters", or Configure Sandbox's network-state list — icon + label/sublabel + right-aligned [UiStatus]. */
@Composable
fun PolicyStatusRow(icon: ImageVector, label: String, status: UiStatus, modifier: Modifier = Modifier, sublabel: String? = null) {
 // The leading (icon+label) side gets weight(1f) so it shrinks/wraps instead of squeezing the
 // trailing StatusLabel — without this, a long status word like "Not configured" (real state,
 // see item 11) wraps into three cramped lines because the label column claims all the width.
 Row(modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
  Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
   Icon(icon, contentDescription = null, tint = statusColor(status), modifier = Modifier.size(18.dp))
   androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
   Column(Modifier.weight(1f)) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    if (sublabel != null) Text(sublabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
  StatusLabel(status)
 }
}

@Composable
fun statusColor(status: UiStatus) = when (status) {
 UiStatus.READY -> MaterialTheme.colorScheme.tertiary
 UiStatus.NOT_CONFIGURED -> MaterialTheme.colorScheme.onSurfaceVariant
 UiStatus.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
 UiStatus.CHECKING -> MaterialTheme.colorScheme.secondary
 UiStatus.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
fun StatusLabel(status: UiStatus, modifier: Modifier = Modifier) {
 val text = when (status) {
  UiStatus.READY -> "Ready"; UiStatus.NOT_CONFIGURED -> "Not configured"; UiStatus.UNAVAILABLE -> "Unavailable"
  UiStatus.CHECKING -> "Checking…"; UiStatus.ERROR -> "Error"
 }
 Row(modifier, verticalAlignment = Alignment.CenterVertically) {
  Text(text, style = MaterialTheme.typography.labelSmall, color = statusColor(status), maxLines = 1, softWrap = false)
  if (status == UiStatus.READY) {
   androidx.compose.foundation.layout.Spacer(Modifier.width(2.dp))
   Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = statusColor(status), modifier = Modifier.size(14.dp))
  }
 }
}

/** Home's "Sandbox Environment" / Ready-to-Run's "Isolation Parameters" outer container — a header row plus a list of [PolicyStatusRow]s built by the caller. */
@Composable
fun EnvironmentStatusCard(title: String, subtitle: String, allReady: Boolean, modifier: Modifier = Modifier, rows: @Composable () -> Unit) {
 BaseCard(modifier) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Column {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
   Icon(Icons.Filled.CheckCircle, contentDescription = if (allReady) "All ready" else null, tint = if (allReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
  }
  androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.sm))
  rows()
 }
}

/** The 2×2 metric grid on Static Analysis Result ("PERMISSIONS · 18 declared", ...). [accent] highlights a sub-detail, e.g. sensitive-permission count in warning color. */
@Composable
fun MetricCard(
 label: String,
 value: String,
 modifier: Modifier = Modifier,
 subtext: String? = null,
 subtextColor: androidx.compose.ui.graphics.Color? = null,
 onClick: (() -> Unit)? = null,
) {
 val cardModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
 BaseCard(cardModifier, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
  Column(
   modifier = Modifier.fillMaxWidth(),
   verticalArrangement = Arrangement.SpaceBetween,
  ) {
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (onClick != null) {
     Icon(
      Icons.AutoMirrored.Filled.ArrowForward,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(14.dp),
     )
    }
   }
   androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.xs))
   Text(
    text = value,
    style = MaterialTheme.typography.headlineMedium,
    color = MaterialTheme.colorScheme.onSurface,
    maxLines = 1,
    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
   )
   androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.xxs))
   Text(
    text = subtext ?: " ",
    style = MaterialTheme.typography.labelSmall,
    color = if (subtext != null) (subtextColor ?: MaterialTheme.colorScheme.onSurfaceVariant) else androidx.compose.ui.graphics.Color.Transparent,
    maxLines = 1,
    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
   )
  }
 }
}

/** Rich interactive row for navigating to detailed permissions and components inspection views. */
@Composable
fun InspectionActionRow(
 icon: ImageVector,
 title: String,
 countText: String,
 subtext: String,
 onClick: () -> Unit,
 modifier: Modifier = Modifier,
 iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
 Row(
  modifier = modifier
   .fillMaxWidth()
   .clickable(onClick = onClick)
   .padding(vertical = Spacing.xs),
  verticalAlignment = Alignment.CenterVertically,
  horizontalArrangement = Arrangement.SpaceBetween,
 ) {
  Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
   Box(
    modifier = Modifier
     .size(40.dp)
     .background(iconTint.copy(alpha = 0.12f), RoundedCornerShape(Radii.md)),
    contentAlignment = Alignment.Center,
   ) {
    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
   }
   androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.md))
   Column(Modifier.weight(1f)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
     Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
     androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.xs))
     Badge(text = countText, color = iconTint)
    }
    androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
    Text(subtext, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
  Icon(
   Icons.AutoMirrored.Filled.ArrowForward,
   contentDescription = null,
   tint = MaterialTheme.colorScheme.onSurfaceVariant,
   modifier = Modifier.size(18.dp),
  )
 }
}

/** A single declared-capability call-out on Static Analysis Result — icon, title, [EvidenceSourceBadge], description, and the exact manifest constant as monospace reference text. Deliberately carries no risk score/severity in production (only [Severity] previews may add one) — see item 9's "declared capability, not observed behavior" boundary. */
@Composable
fun SecurityFindingCard(
 icon: ImageVector,
 title: String,
 description: String,
 modifier: Modifier = Modifier,
 source: EvidenceSource? = null,
 reference: String? = null,
 severity: Severity? = null,
) {
 BaseCard(modifier) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
   Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
    Column(Modifier.weight(1f)) {
     Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
     androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
     Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     if (reference != null) {
      androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.xs))
      Text(reference, style = com.nadeem.apkscope.ui.theme.MonoCodeStyle, color = MaterialTheme.colorScheme.secondary)
     }
    }
   }
   Column(horizontalAlignment = Alignment.End) {
    if (severity != null) SeverityBadge(severity)
    if (source != null && source != EvidenceSource.DECLARED) {
     androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.xs))
     EvidenceSourceBadge(source)
    }
   }
  }
 }
}

/** DESIGN.md's blue-bordered informational callout ("Your APK is analyzed locally...", "Android confirmation required"). */
@Composable
fun InfoCard(text: String, modifier: Modifier = Modifier, title: String? = null) {
 Callout(text = text, title = title, modifier = modifier, icon = Icons.Filled.Info, color = MaterialTheme.colorScheme.secondary)
}

/** The amber equivalent for non-critical warnings ("1 restriction unavailable on this device"). */
@Composable
fun WarningCard(text: String, modifier: Modifier = Modifier, title: String? = null) {
 Callout(text = text, title = title, modifier = modifier, icon = Icons.Filled.Warning, color = MaterialTheme.extendedColors.warning)
}

@Composable
private fun Callout(text: String, title: String?, modifier: Modifier, icon: ImageVector, color: androidx.compose.ui.graphics.Color) {
 Row(
  modifier
   .fillMaxWidth()
   .background(color.copy(alpha = 0.08f), RoundedCornerShape(Radii.md))
   .padding(Spacing.base),
  verticalAlignment = Alignment.Top,
 ) {
  Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
  androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
  Column {
   if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
   Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

package com.nadeem.apkscope.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.core.staticanalysis.AppPlatform
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors

/** Three-tier severity used on declared-capability call-outs and (in previews only) risk findings — DESIGN.md's Clean/Suspicious/Malicious status-badge triad, renamed to match this product's own vocabulary. */
enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }

/** A coarse risk tier for a completed analysis (Home's "Recent Analysis" list, Reports' filter chips) — visual only; the numeric score behind it is preview/mock data until the real risk engine exists (item 8). */
enum class RiskTier { LOW, MODERATE, HIGH, CRITICAL }

@Composable
private fun pillColors(color: Color): Pair<Color, Color> = color.copy(alpha = 0.14f) to color

@Composable
fun SeverityBadge(severity: Severity, modifier: Modifier = Modifier) {
 val color = when (severity) {
  Severity.LOW -> MaterialTheme.colorScheme.tertiary
  Severity.MEDIUM -> MaterialTheme.extendedColors.warning
  Severity.HIGH, Severity.CRITICAL -> MaterialTheme.colorScheme.error
 }
 Badge(text = severity.name, color = color, modifier = modifier)
}

@Composable
fun RiskBadge(tier: RiskTier, score: Int, modifier: Modifier = Modifier) {
 val color = when (tier) {
  RiskTier.LOW -> MaterialTheme.colorScheme.tertiary
  RiskTier.MODERATE -> MaterialTheme.extendedColors.warning
  RiskTier.HIGH, RiskTier.CRITICAL -> MaterialTheme.colorScheme.error
 }
 val label = when (tier) { RiskTier.LOW -> "Low Risk"; RiskTier.MODERATE -> "Moderate"; RiskTier.HIGH -> "High Risk"; RiskTier.CRITICAL -> "Critical" }
 Badge(text = "$label · $score", color = color, modifier = modifier, showDot = true)
}

@Composable
fun RiskLevelBadge(level: com.nadeem.apkscope.core.model.RiskLevel, modifier: Modifier = Modifier) {
 val color = when (level) {
  com.nadeem.apkscope.core.model.RiskLevel.LOW -> MaterialTheme.colorScheme.tertiary
  com.nadeem.apkscope.core.model.RiskLevel.MODERATE -> MaterialTheme.extendedColors.warning
  com.nadeem.apkscope.core.model.RiskLevel.HIGH, com.nadeem.apkscope.core.model.RiskLevel.CRITICAL -> MaterialTheme.colorScheme.error
 }
 Badge(text = level.name, color = color, modifier = modifier, showDot = true)
}

@Composable
fun RiskSeverityBadge(severity: com.nadeem.apkscope.core.model.RiskSeverity, modifier: Modifier = Modifier) {
 val color = when (severity) {
  com.nadeem.apkscope.core.model.RiskSeverity.INFO -> MaterialTheme.colorScheme.outline
  com.nadeem.apkscope.core.model.RiskSeverity.LOW -> MaterialTheme.colorScheme.tertiary
  com.nadeem.apkscope.core.model.RiskSeverity.MEDIUM -> MaterialTheme.extendedColors.warning
  com.nadeem.apkscope.core.model.RiskSeverity.HIGH -> MaterialTheme.colorScheme.error
 }
 Badge(text = severity.name, color = color, modifier = modifier)
}

/** Evidence-source provenance — never merge these concepts (item 6/16). DECLARED = from the manifest; OBSERVED = this app's own runtime instrumentation; ANDROID = an independent OS-level callback; INFERRED = a downstream best-effort join the system is not certain of. */
enum class EvidenceSource { DECLARED, OBSERVED, ANDROID, INFERRED }

@Composable
fun EvidenceSourceBadge(source: EvidenceSource, modifier: Modifier = Modifier) {
 val color = when (source) {
  EvidenceSource.DECLARED -> MaterialTheme.colorScheme.onSurfaceVariant
  EvidenceSource.OBSERVED -> MaterialTheme.colorScheme.secondary
  EvidenceSource.ANDROID -> MaterialTheme.colorScheme.tertiary
  EvidenceSource.INFERRED -> MaterialTheme.extendedColors.warning
 }
 Badge(text = source.name, color = color, modifier = modifier, filled = false)
}

/** Base pill primitive every badge above renders through — keeps padding/radius/typography consistent (DESIGN.md "Status Badges": pill, 24dp tall, 10dp horizontal padding). */
@Composable
fun Badge(
 text: String,
 color: Color,
 modifier: Modifier = Modifier,
 showDot: Boolean = false,
 filled: Boolean = true,
 leadingContent: (@Composable () -> Unit)? = null,
) {
 val (bg, fg) = if (filled) pillColors(color) else Color.Transparent to color
 Row(
  modifier = modifier
   .background(bg, RoundedCornerShape(Radii.sm))
   .border(BorderStroke(1.dp, color.copy(alpha = 0.4f)), RoundedCornerShape(Radii.sm))
   .padding(horizontal = Spacing.sm, vertical = 3.dp),
  verticalAlignment = Alignment.CenterVertically,
 ) {
  if (leadingContent != null) {
   leadingContent()
   Spacer(Modifier.width(Spacing.xs))
  } else if (showDot) {
   Box(Modifier.size(6.dp).background(color, CircleShape))
   Spacer(Modifier.width(Spacing.xs))
  }
  Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
 }
}

/**
 * Visual pill badge displaying the detected build platform (Flutter, React Native, Native Kotlin, Unity, etc.)
 */
@Composable
fun PlatformBadge(
	platformName: String?,
	details: String? = null,
	modifier: Modifier = Modifier,
) {
	if (platformName.isNullOrBlank() || platformName == "UNKNOWN") return
	val platform = AppPlatform.fromName(platformName)
	val color = when (platform) {
		AppPlatform.FLUTTER -> Color(0xFF29B6F6) // Flutter cyan/blue
		AppPlatform.REACT_NATIVE -> Color(0xFF00A8CC) // React Native cyan
		AppPlatform.NATIVE_KOTLIN -> Color(0xFF3DDC84) // Android green
		AppPlatform.NATIVE_JAVA -> Color(0xFF3DDC84) // Android green
		AppPlatform.UNITY -> Color(0xFF5B6472) // Unity slate
		AppPlatform.CORDOVA -> Color(0xFFFFB300) // Cordova gold
		AppPlatform.CAPACITOR_IONIC -> Color(0xFF4FC3F7) // Ionic light blue
		AppPlatform.XAMARIN_MAUI -> Color(0xFFBA68C8) // .NET purple
		AppPlatform.GODOT -> Color(0xFF4DD0E1) // Godot cyan
		AppPlatform.UNREAL_ENGINE -> Color(0xFF90A4AE) // Unreal slate
		AppPlatform.UNKNOWN -> MaterialTheme.colorScheme.outline
	}

	val label = if (!details.isNullOrBlank() && details != "Dart AOT" && details != "Kotlin Android" && details != "Java Android") {
		"${platform.displayName} · $details"
	} else {
		platform.displayName
	}

	val hasDedicatedIcon = platform in setOf(
		AppPlatform.FLUTTER,
		AppPlatform.REACT_NATIVE,
		AppPlatform.NATIVE_KOTLIN,
		AppPlatform.NATIVE_JAVA,
		AppPlatform.UNITY,
		AppPlatform.UNKNOWN,
	)
	Badge(
		text = label,
		color = color,
		modifier = modifier,
		showDot = !hasDedicatedIcon,
		leadingContent = if (hasDedicatedIcon) {
			{ PlatformIcon(platform = platform, color = color, modifier = Modifier.size(14.dp)) }
		} else {
			null
		},
	)
}

/** Compact logo treatment used by platform tags throughout the app. */
@Composable
fun PlatformIcon(platform: AppPlatform, color: Color, modifier: Modifier = Modifier) {
	when (platform) {
		AppPlatform.REACT_NATIVE -> ReactNativePlatformIcon(color, modifier)
		AppPlatform.FLUTTER -> FlutterPlatformIcon(color, modifier)
		AppPlatform.UNITY -> UnityPlatformIcon(color, modifier)
		AppPlatform.NATIVE_KOTLIN,
		AppPlatform.NATIVE_JAVA,
		AppPlatform.UNKNOWN -> Icon(Icons.Filled.Android, contentDescription = null, tint = color, modifier = modifier)
		else -> Icon(Icons.Filled.Android, contentDescription = null, tint = color, modifier = modifier)
	}
}

@Composable
private fun ReactNativePlatformIcon(color: Color, modifier: Modifier = Modifier) {
	Canvas(modifier) {
		val center = Offset(size.width / 2f, size.height / 2f)
		val radius = size.minDimension * 0.09f
		val orbitWidth = size.width * 0.78f
		val orbitHeight = size.height * 0.32f
		repeat(3) { index ->
			rotate(degrees = index * 60f, pivot = center) {
				drawOval(
					color = color,
					topLeft = Offset(center.x - orbitWidth / 2f, center.y - orbitHeight / 2f),
					size = androidx.compose.ui.geometry.Size(orbitWidth, orbitHeight),
					style = Stroke(width = size.minDimension * 0.08f),
				)
			}
		}
		drawCircle(color = color, radius = radius, center = center)
	}
}

@Composable
private fun FlutterPlatformIcon(color: Color, modifier: Modifier = Modifier) {
	Canvas(modifier) {
		val w = size.width
		val h = size.height
		val upper = Path().apply {
			moveTo(w * 0.70f, h * 0.08f)
			lineTo(w * 0.20f, h * 0.58f)
			lineTo(w * 0.38f, h * 0.76f)
			lineTo(w * 0.88f, h * 0.26f)
			close()
		}
		val lower = Path().apply {
			moveTo(w * 0.39f, h * 0.78f)
			lineTo(w * 0.58f, h * 0.96f)
			lineTo(w * 0.88f, h * 0.66f)
			lineTo(w * 0.69f, h * 0.48f)
			close()
		}
		drawPath(upper, color)
		drawPath(lower, color.copy(alpha = 0.72f))
	}
}

@Composable
private fun UnityPlatformIcon(color: Color, modifier: Modifier = Modifier) {
	Canvas(modifier) {
		val stroke = size.minDimension * 0.16f
		val center = Offset(size.width / 2f, size.height / 2f)
		val top = Offset(size.width * 0.5f, size.height * 0.14f)
		val left = Offset(size.width * 0.18f, size.height * 0.68f)
		val right = Offset(size.width * 0.82f, size.height * 0.68f)
		drawLine(color, top, center, strokeWidth = stroke)
		drawLine(color, center, left, strokeWidth = stroke)
		drawLine(color, center, right, strokeWidth = stroke)
		drawLine(color, Offset(size.width * 0.29f, size.height * 0.25f), top, strokeWidth = stroke)
		drawLine(color, Offset(size.width * 0.71f, size.height * 0.25f), top, strokeWidth = stroke)
	}
}

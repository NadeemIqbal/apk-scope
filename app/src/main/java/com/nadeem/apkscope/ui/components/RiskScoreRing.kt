package com.nadeem.apkscope.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.ui.theme.extendedColors

/**
 * Custom circular telemetry gauge (DESIGN.md "Risk Gauges"), now wired to the real static risk
 * engine (checkpoint 3, item 15) — [score] must come from a real `StaticRiskAssessment.score`.
 * Color bands match the checkpoint's official [com.nadeem.apkscope.core.model.RiskLevel] boundaries
 * (0-24 LOW/green, 25-49 MODERATE/amber, 50-100 HIGH-or-CRITICAL/red) rather than this component's
 * original ad hoc 20/60 split, so the ring's color always agrees with the level label shown next
 * to it. The `72`-style mock score from Stitch remains Preview-only unless a real APK evaluates to
 * that value (item 15).
 */
@Composable
fun RiskScoreRing(score: Int, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 120.dp) {
 val color = when {
  score <= 24 -> MaterialTheme.colorScheme.tertiary
  score <= 49 -> MaterialTheme.extendedColors.warning
  else -> MaterialTheme.colorScheme.error
 }
 val track = MaterialTheme.colorScheme.surfaceContainerHigh
 Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
  Canvas(modifier = Modifier.size(size)) {
   val strokeWidth = 10.dp.toPx()
   drawArc(color = track, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = Stroke(strokeWidth), size = Size(this.size.width - strokeWidth, this.size.height - strokeWidth), topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2, strokeWidth / 2))
   drawArc(color = color, startAngle = -90f, sweepAngle = 360f * (score.coerceIn(0, 100) / 100f), useCenter = false, style = Stroke(strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round), size = Size(this.size.width - strokeWidth, this.size.height - strokeWidth), topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2, strokeWidth / 2))
  }
  androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
   Text("$score", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
   Text("/ 100", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

/** The "Analysis complete" ring shown once static analysis finishes without a risk score — a green check, not a fabricated number (item 8). */
@Composable
fun CompleteRing(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 120.dp) {
 val color = MaterialTheme.colorScheme.tertiary
 Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
  Canvas(modifier = Modifier.size(size)) {
   val strokeWidth = 10.dp.toPx()
   drawArc(color = color, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = Stroke(strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round), size = Size(this.size.width - strokeWidth, this.size.height - strokeWidth), topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2, strokeWidth / 2))
  }
  Icon(Icons.Filled.Check, contentDescription = null, tint = color, modifier = Modifier.size(size / 3))
 }
}

@Composable
fun FailedRing(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 120.dp) {
 val color = MaterialTheme.colorScheme.error
 Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
  Canvas(modifier = Modifier.size(size)) {
   val strokeWidth = 10.dp.toPx()
   drawArc(
    color = color,
    startAngle = -90f,
    sweepAngle = 360f,
    useCenter = false,
    style = Stroke(strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
    size = Size(this.size.width - strokeWidth, this.size.height - strokeWidth),
    topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2, strokeWidth / 2)
   )
   val crossRadius = (size / 6).toPx()
   val crossStroke = 5.dp.toPx()
   val c = androidx.compose.ui.geometry.Offset(this.size.width / 2, this.size.height / 2)
   drawLine(
    color = color,
    start = androidx.compose.ui.geometry.Offset(c.x - crossRadius, c.y - crossRadius),
    end = androidx.compose.ui.geometry.Offset(c.x + crossRadius, c.y + crossRadius),
    strokeWidth = crossStroke,
    cap = androidx.compose.ui.graphics.StrokeCap.Round
   )
   drawLine(
    color = color,
    start = androidx.compose.ui.geometry.Offset(c.x + crossRadius, c.y - crossRadius),
    end = androidx.compose.ui.geometry.Offset(c.x - crossRadius, c.y + crossRadius),
    strokeWidth = crossStroke,
    cap = androidx.compose.ui.graphics.StrokeCap.Round
   )
  }
 }
}

@Composable
fun AnalyzingRing(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 120.dp) {
 val color = MaterialTheme.colorScheme.primary
 Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
  androidx.compose.material3.CircularProgressIndicator(
   modifier = Modifier.size(size),
   color = color,
   strokeWidth = 8.dp,
   trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
  )
  Canvas(modifier = Modifier.size(size / 3)) {
   val glassColor = color
   val stroke = 3.dp.toPx()
   val headRadius = this.size.width * 0.32f
   val centerOffset = androidx.compose.ui.geometry.Offset(this.size.width * 0.42f, this.size.height * 0.42f)
   drawCircle(
    color = glassColor,
    radius = headRadius,
    center = centerOffset,
    style = Stroke(stroke)
   )
   drawLine(
    color = glassColor,
    start = androidx.compose.ui.geometry.Offset(centerOffset.x + headRadius * 0.707f, centerOffset.y + headRadius * 0.707f),
    end = androidx.compose.ui.geometry.Offset(this.size.width * 0.88f, this.size.height * 0.88f),
    strokeWidth = stroke,
    cap = androidx.compose.ui.graphics.StrokeCap.Round
   )
  }
 }
}


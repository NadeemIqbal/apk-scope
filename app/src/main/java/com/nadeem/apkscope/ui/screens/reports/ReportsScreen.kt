package com.nadeem.apkscope.ui.screens.reports

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.core.database.FinalReportEntity
import com.nadeem.apkscope.core.model.RiskLevel
import com.nadeem.apkscope.domain.report.ReportCoordinator
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.BottomNavTab
import com.nadeem.apkscope.ui.components.BottomNavigationBar
import com.nadeem.apkscope.ui.components.EmptyState
import com.nadeem.apkscope.ui.components.PlatformBadge
import com.nadeem.apkscope.ui.components.RiskLevelBadge
import com.nadeem.apkscope.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReportsViewModel(application: Application) : AndroidViewModel(application) {
 private val coordinator = ReportCoordinator(application)
 val reports: StateFlow<List<FinalReportEntity>> = coordinator.observeAllReports()
  .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/**
 * Checkpoint 7: Functional Reports screen.
 *
 * Lists all generated security reports ordered newest first.
 * Allows opening static-only or dynamic reports with full score breakdown and evidence.
 */
@Composable
fun ReportsScreen(
 onTabSelected: (BottomNavTab) -> Unit,
 onOpenReport: (String) -> Unit = {},
 onOpenAnalysis: (String) -> Unit = {},
 modifier: Modifier = Modifier,
) {
 val context = LocalContext.current
 val viewModel = sessionViewModel { ReportsViewModel(context.applicationContext as Application) }
 val reports by viewModel.reports.collectAsState()

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "Reports", eyebrow = "PERSONAL") },
  bottomBar = { BottomNavigationBar(selected = BottomNavTab.REPORTS, onSelect = onTabSelected) },
 ) { padding ->
  if (reports.isEmpty()) {
   EmptyState(
    title = "No reports yet",
    description = "Run a static analysis or launch an app in the sandbox to generate a report.",
    icon = Icons.Filled.BarChart,
    modifier = Modifier.fillMaxSize().padding(padding),
   )
  } else {
   LazyColumn(
    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.base),
    verticalArrangement = Arrangement.spacedBy(Spacing.base),
   ) {
    item { ReportsHistoryHeader(total = reports.size) }
    items(reports, key = { it.reportId }) { report ->
     ReportItemCard(
      report = report,
      onOpenAnalysis = { onOpenAnalysis(report.analysisId) },
      onOpenReport = { onOpenReport(report.sessionId ?: report.analysisId) },
     )
    }
    item {
     Spacer(Modifier.height(Spacing.base))
    }
   }
  }
 }
}

@Composable
private fun ReportsHistoryHeader(total: Int) {
 Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
  Spacer(Modifier.height(Spacing.sm))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Column {
    Text("Inspection History", style = MaterialTheme.typography.headlineLarge)
    Text("Personal workspace reports and sandbox captures", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
   androidx.compose.material3.Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
  }
  BaseCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
   Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
    Column {
     Text("$total Sessions Archived", style = MaterialTheme.typography.headlineSmall)
     Text("Static and dynamic report history", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Badge("PERSONAL WORKSPACE", MaterialTheme.colorScheme.primary)
   }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
   Badge("ALL", MaterialTheme.colorScheme.primary)
   Badge("RUNTIME", MaterialTheme.colorScheme.secondary, filled = false)
   Badge("STATIC", MaterialTheme.colorScheme.onSurfaceVariant, filled = false)
  }
 }
}

@Composable
private fun ReportItemCard(
 report: FinalReportEntity,
 onOpenAnalysis: () -> Unit,
 onOpenReport: () -> Unit,
) {
 val level = try { RiskLevel.valueOf(report.overallLevel) } catch (_: Exception) { RiskLevel.LOW }
 val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy · HH:mm:ss").withZone(ZoneId.systemDefault())
 val dateStr = try { formatter.format(Instant.ofEpochMilli(report.generatedAtEpochMs)) } catch (_: Exception) { "" }

 // The primary history action is the complete static-analysis surface. The evidence report
 // remains available as a secondary action below for users who want the interpreted report.
 BaseCard(modifier = Modifier.clickable { onOpenAnalysis() }) {
  Row(
   Modifier.fillMaxWidth(),
   horizontalArrangement = Arrangement.SpaceBetween,
   verticalAlignment = Alignment.CenterVertically,
  ) {
   Column(modifier = Modifier.weight(1f)) {
    Row(
     verticalAlignment = Alignment.CenterVertically,
     horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
     Text(
     report.appName ?: report.packageName,
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
     )
     if (!report.platform.isNullOrBlank()) {
      PlatformIdentityBadge(platformName = report.platform, details = report.platformDetails)
     }
    }
    Text(
     report.packageName,
     style = MaterialTheme.typography.bodySmall,
     color = MaterialTheme.colorScheme.onSurfaceVariant,
     fontFamily = FontFamily.Monospace,
    )
   }
   Spacer(Modifier.width(Spacing.sm))
   Row(verticalAlignment = Alignment.CenterVertically) {
    Column(horizontalAlignment = Alignment.End) {
     Text(
     "${report.overallScore}",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
     )
     RiskLevelBadge(level = level)
    }
   }
  }

  Row(
   Modifier.fillMaxWidth(),
   horizontalArrangement = Arrangement.SpaceBetween,
   verticalAlignment = Alignment.CenterVertically,
  ) {
   Text(
    if (report.runtimeComplete) "Static + runtime" else "Static analysis",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.primary,
   )
   Text(
    dateStr,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
   )
  }

  Row(
   Modifier.fillMaxWidth(),
   horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
   ReportScoreLabel(modifier = Modifier.weight(1f), label = "Static", score = report.staticScore)
   ReportScoreLabel(modifier = Modifier.weight(1f), label = "Runtime", score = report.runtimeScore)
  }

  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
   CompletenessChip(
    label = "Static analysis",
    active = report.staticComplete,
   )
   CompletenessChip(
    label = "Runtime",
    active = report.runtimeComplete,
   )
   CompletenessChip(
    label = "Android OS: ${androidEvidenceLabel(report.androidEvidenceStatus)}",
    active = report.androidEvidenceStatus == "READY",
   )
  }

  Row(
   modifier = Modifier.fillMaxWidth(),
   horizontalArrangement = Arrangement.End,
  ) {
   TextButton(onClick = onOpenAnalysis) {
    Text("Static analysis")
   }
   TextButton(onClick = onOpenReport) {
    Text("Evidence report")
   }
  }
 }
}

@Composable
private fun ReportScoreLabel(modifier: Modifier, label: String, score: Int?) {
 Row(
  modifier = modifier,
  horizontalArrangement = Arrangement.SpaceBetween,
  verticalAlignment = Alignment.CenterVertically,
 ) {
  Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text(score?.let { "$it / 100" } ?: "Not run", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
 }
}

@Composable
private fun PlatformIdentityBadge(platformName: String?, details: String?) {
	PlatformBadge(platformName = platformName, details = details)
}

private fun androidEvidenceLabel(status: String?): String = when (status) {
 "READY" -> "Collected"
 "PENDING" -> "Pending"
 "TIMEOUT" -> "Timed out"
 "NOT_AVAILABLE", null -> "Not collected"
 else -> status.lowercase().replace('_', ' ')
}

@Composable
private fun CompletenessChip(label: String, active: Boolean) {
 Text(
  label,
  style = MaterialTheme.typography.labelSmall,
  color = if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
  modifier = Modifier.padding(end = 4.dp),
 )
}

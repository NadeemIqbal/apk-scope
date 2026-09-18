package com.nadeem.apkscope.ui.screens.securityaudit

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.core.risk.audit.AuditFinding
import com.nadeem.apkscope.core.risk.audit.AuditOutcome
import com.nadeem.apkscope.core.risk.audit.SecurityAuditReport
import com.nadeem.apkscope.core.risk.audit.Severity
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.EmptyState
import com.nadeem.apkscope.ui.components.ErrorState
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors

/**
 * Milestone 10 (Security Audit), Phase 10.2 — MS10-UI01/02/05. Explicit loading/empty/running/error/
 * cancelled states (never a silent blank screen), findings grouped by outcome with severity badges,
 * and a coverage summary. Reuses [AppTopBar]/[BaseCard]/[EmptyState]/[ErrorState], the same
 * conventions every other detail screen in this app already uses.
 */
@Composable
fun SecurityAuditScreen(
 sessionId: String,
 onBack: () -> Unit,
 onOpenFinding: (String, String) -> Unit,
 modifier: Modifier = Modifier,
) {
 val context = LocalContext.current
 val viewModel = sessionViewModel { SecurityAuditViewModel(context.applicationContext as Application, sessionId) }
 val state by viewModel.uiState.collectAsState()

 Scaffold(
  modifier = modifier,
  topBar = {
   AppTopBar(
    title = "Security Audit" + (state.report?.let { " (${it.findings.size})" } ?: ""),
    onBack = onBack,
    onOverflow = {},
   )
  },
 ) { padding ->
  Column(Modifier.fillMaxSize().padding(padding)) {
   when (state.phase) {
    SecurityAuditPhase.LOADING_PERSISTED -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
     CircularProgressIndicator()
    }
    SecurityAuditPhase.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
     Column(horizontalAlignment = Alignment.CenterHorizontally) {
      EmptyState(
       title = "No Security Audit yet",
       description = "Run the static audit rule catalog against this analysis. No installation or Work Profile is required for static checks.",
      )
      Spacer(Modifier.height(Spacing.base))
      Button(onClick = { viewModel.runAudit() }) {
       Icon(Icons.Filled.PlayArrow, contentDescription = null)
       Spacer(Modifier.width(Spacing.xs))
       Text("Run Security Audit")
      }
     }
    }
    SecurityAuditPhase.RUNNING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
     Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator()
      Spacer(Modifier.height(Spacing.base))
      Text("Running Security Audit…", style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(Spacing.sm))
      TextButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
     }
    }
    SecurityAuditPhase.CANCELLED -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
     Column(horizontalAlignment = Alignment.CenterHorizontally) {
      EmptyState(title = "Audit cancelled", description = "No result was saved. Run the audit again when ready.")
      Spacer(Modifier.height(Spacing.base))
      Button(onClick = { viewModel.runAudit() }) { Text("Run Security Audit") }
     }
    }
    SecurityAuditPhase.ERROR -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
     ErrorState(message = state.errorMessage ?: "Security Audit failed.", onRetry = { viewModel.runAudit() })
    }
    SecurityAuditPhase.LOADED -> {
     val report = state.report
     if (report == null) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
       ErrorState(message = "Audit completed but produced no result.", onRetry = { viewModel.runAudit() })
      }
     } else {
      SecurityAuditReportBody(report = report, onOpenFinding = { ruleId -> onOpenFinding(sessionId, ruleId) }, onRerun = { viewModel.runAudit() })
     }
    }
   }
  }
 }
}

@Composable
private fun SecurityAuditReportBody(report: SecurityAuditReport, onOpenFinding: (String) -> Unit, onRerun: () -> Unit) {
 LazyColumn(
  modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.base),
  verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  contentPadding = PaddingValues(vertical = Spacing.base),
 ) {
  item { CoverageSummaryCard(report, onRerun) }
  item { Text("Findings", style = MaterialTheme.typography.headlineSmall) }
  if (report.findings.isEmpty()) {
   item { EmptyState(title = "No findings", description = "The current catalog produced no findings for this analysis.") }
  } else {
   items(report.findings, key = { it.ruleId }) { finding ->
    AuditFindingRow(finding = finding, onClick = { onOpenFinding(finding.ruleId) })
   }
  }
 }
}

@Composable
private fun CoverageSummaryCard(report: SecurityAuditReport, onRerun: () -> Unit) {
 BaseCard {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
   Column {
    Text("Coverage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text("Catalog: ${report.engineVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
   TextButton(onClick = onRerun) {
    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.height(16.dp))
    Spacer(Modifier.width(Spacing.xs))
    Text("Re-run")
   }
  }
  Spacer(Modifier.height(Spacing.sm))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
   CoverageCount("Findings", report.findingCount, MaterialTheme.colorScheme.error, Modifier.weight(1f))
   CoverageCount("Review", report.needsReviewCount, MaterialTheme.extendedColors.warning, Modifier.weight(1f))
   CoverageCount("Passed", report.passCount, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
   CoverageCount("Untested", report.notTestedCount, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
  }
 }
}

@Composable
private fun CoverageCount(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
 Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
  Text("$count", style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
  Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
 }
}

@Composable
private fun AuditFindingRow(finding: AuditFinding, onClick: () -> Unit) {
 BaseCard(modifier = Modifier.fillMaxWidth()) {
  Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
   Column(Modifier.weight(1f)) {
    Text(finding.title, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(2.dp))
    Text(finding.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(Spacing.xs))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
     Badge(text = finding.outcome.name.replace('_', ' '), color = outcomeColor(finding.outcome))
     if (finding.outcome == AuditOutcome.FINDING_DETECTED || finding.outcome == AuditOutcome.NEEDS_REVIEW) {
      Badge(text = finding.severity.name, color = severityColor(finding.severity))
     }
    }
   }
  }
 }
}

@Composable
internal fun outcomeColor(outcome: AuditOutcome): Color = when (outcome) {
 AuditOutcome.FINDING_DETECTED -> MaterialTheme.colorScheme.error
 AuditOutcome.NEEDS_REVIEW -> MaterialTheme.extendedColors.warning
 AuditOutcome.CHECK_PASSED -> MaterialTheme.colorScheme.primary
 AuditOutcome.NOT_TESTED, AuditOutcome.NOT_APPLICABLE -> MaterialTheme.colorScheme.onSurfaceVariant
 AuditOutcome.COLLECTION_FAILED -> MaterialTheme.colorScheme.error
}

@Composable
internal fun severityColor(severity: Severity): Color = when (severity) {
 Severity.CRITICAL, Severity.HIGH -> MaterialTheme.colorScheme.error
 Severity.MEDIUM -> MaterialTheme.extendedColors.warning
 Severity.LOW -> MaterialTheme.colorScheme.tertiary
 Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}

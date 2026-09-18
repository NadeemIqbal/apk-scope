package com.nadeem.apkscope.ui.screens.securityaudit

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.core.risk.audit.AuditFinding
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.ErrorState
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Spacing

/**
 * MS10-UI03 — a finding's full evidence and remediation, opened from [SecurityAuditScreen]. Reuses
 * the same [SecurityAuditViewModel] the list screen uses (both are keyed by [sessionId], per this
 * app's established `sessionViewModel`-per-screen convention) rather than passing the finding object
 * through navigation — Navigation Compose routes carry only primitive ids (see `Routes.kt`'s own
 * documented rule), and re-deriving the finding from the persisted report also means this screen
 * shows the same reopened-after-restart data the list screen does, never a stale in-memory copy.
 */
@Composable
fun SecurityAuditFindingDetailScreen(
 sessionId: String,
 ruleId: String,
 onBack: () -> Unit,
 modifier: Modifier = Modifier,
) {
 val context = LocalContext.current
 val viewModel = sessionViewModel { SecurityAuditViewModel(context.applicationContext as Application, sessionId) }
 val state by viewModel.uiState.collectAsState()
 val finding: AuditFinding? = state.report?.findings?.firstOrNull { it.ruleId == ruleId }

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = finding?.title ?: "Finding", onBack = onBack, onOverflow = {}) },
 ) { padding ->
  if (state.phase == SecurityAuditPhase.LOADING_PERSISTED) {
   Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
   return@Scaffold
  }
  if (finding == null) {
   Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
    ErrorState(message = "This finding is no longer available — the audit may have been re-run since you opened it.")
   }
   return@Scaffold
  }
  LazyColumn(
   modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.base),
   verticalArrangement = Arrangement.spacedBy(Spacing.base),
  ) {
   item {
    BaseCard {
     Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      Badge(text = finding.outcome.name.replace('_', ' '), color = outcomeColor(finding.outcome))
      Badge(text = "Severity: ${finding.severity.name}", color = severityColor(finding.severity))
      Badge(text = "Confidence: ${finding.confidence.name}", color = MaterialTheme.colorScheme.secondary)
     }
    }
   }
   item { DetailSection(title = "Evidence", body = finding.detail) }
   item { DetailSection(title = "Remediation", body = finding.remediation) }
   item {
    BaseCard {
     Text("Rule ID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     Spacer(Modifier.height(2.dp))
     Text(finding.ruleId, style = MonoCodeStyle, color = MaterialTheme.colorScheme.secondary)
     Spacer(Modifier.height(Spacing.sm))
     Text("Static or runtime origin", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     Spacer(Modifier.height(2.dp))
     Text("Static (declared manifest/platform facts) — Phase 10.1 catalog. No guided runtime session has contributed to this analysis yet.", style = MaterialTheme.typography.bodySmall)
    }
   }
  }
 }
}

@Composable
private fun DetailSection(title: String, body: String) {
 BaseCard {
  Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
  Spacer(Modifier.height(Spacing.xs))
  Text(body, style = MaterialTheme.typography.bodyMedium)
 }
}

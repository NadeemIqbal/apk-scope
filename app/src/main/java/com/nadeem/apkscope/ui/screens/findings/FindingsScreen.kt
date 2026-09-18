package com.nadeem.apkscope.ui.screens.findings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.core.model.RiskFinding
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.EmptyState
import com.nadeem.apkscope.ui.components.RiskScoreRing
import com.nadeem.apkscope.ui.components.riskFindingIcon
import com.nadeem.apkscope.ui.screens.staticresult.StaticResultViewModel
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Spacing

/**
 * "Why this score?" / "View all findings" (checkpoint 3, items 16 & 18) — one screen serves both
 * entry points from Static Result. Every row here is a real [RiskFinding] from `core:risk`, and
 * the running total at the bottom is the literal sum of every [RiskFinding.scoreContribution]
 * shown, clamped to 100 — never a separately-fabricated number (item 7's explainability
 * requirement: a user must be able to answer "why is this score N" by reading this list).
 */
@Composable
fun FindingsScreen(sessionId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
 val context = LocalContext.current
 val viewModel = sessionViewModel { StaticResultViewModel(context.applicationContext as Application, sessionId) }
 val state by viewModel.uiState.collectAsState()

 Scaffold(modifier = modifier, topBar = { AppTopBar(title = "Score Breakdown", onBack = onBack, onOverflow = {}) }) { padding ->
  if (state.isLoading || state.analysis == null) {
   Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    CircularProgressIndicator()
   }
   return@Scaffold
  }
  val risk = state.analysis!!.riskAssessment
  val sortedFindings = risk.findings.sortedByDescending { it.scoreContribution }

  Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
   BaseCard {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RiskScoreRing(score = risk.score) }
    Spacer(Modifier.height(Spacing.sm))
    Text(
     "Every point below comes from a real, explainable finding — summing every contribution and clamping to 100 reproduces this score exactly. Engine rule set: ${risk.engineVersion}.",
     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
   }

   if (sortedFindings.isEmpty()) {
    EmptyState(title = "No findings", description = "This analysis found no capabilities or structural facts flagged by the current rule set — the score is 0.")
   } else {
    sortedFindings.forEach { finding -> FindingRow(finding) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
     Text("Risk score", style = MaterialTheme.typography.titleMedium)
     Text("${risk.score}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    if (sortedFindings.sumOf { it.scoreContribution } > risk.score) {
     Text("Raw total ${sortedFindings.sumOf { it.scoreContribution }} clamped to 100.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
   }
  }
 }
}

@Composable
private fun FindingRow(finding: RiskFinding) {
 BaseCard {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
   Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
    androidx.compose.material3.Icon(riskFindingIcon(finding.ruleId), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.width(Spacing.sm))
    Column(Modifier.weight(1f)) {
     Text(finding.title, style = MaterialTheme.typography.bodyLarge)
     Text(finding.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     Spacer(Modifier.height(Spacing.xs))
     Text(finding.ruleId, style = MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
     if (finding.evidence.isNotEmpty()) {
      Spacer(Modifier.height(Spacing.xs))
      finding.evidence.forEach { Text("• ${it.description}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
     }
    }
   }
   Text("+${finding.scoreContribution}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
  }
 }
}

@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun FindingRowPreview() {
 ApkScopeTheme {
  Column(Modifier.padding(Spacing.base)) {
   FindingRow(
    RiskFinding(
     ruleId = "STATIC_ACCESSIBILITY_OVERLAY_COMBINATION",
     severity = com.nadeem.apkscope.core.model.RiskSeverity.HIGH,
     title = "Accessibility and overlay capabilities declared together",
     explanation = "This app declares both an accessibility-service capability and an overlay capability.",
     scoreContribution = 20,
     evidence = listOf(com.nadeem.apkscope.core.model.RiskEvidence("Declares android.permission.BIND_ACCESSIBILITY_SERVICE"), com.nadeem.apkscope.core.model.RiskEvidence("Declares android.permission.SYSTEM_ALERT_WINDOW")),
    ),
   )
  }
 }
}

package com.nadeem.apkscope.ui.screens.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.theme.ApkScopeColors
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing

private const val TUTORIAL_PREFERENCES = "apk_scope_tutorial"
private const val TUTORIAL_COMPLETED = "completed"

object TutorialPreferences {
 fun isCompleted(context: android.content.Context): Boolean =
  context.getSharedPreferences(TUTORIAL_PREFERENCES, android.content.Context.MODE_PRIVATE)
   .getBoolean(TUTORIAL_COMPLETED, false)

 fun markCompleted(context: android.content.Context) {
  context.getSharedPreferences(TUTORIAL_PREFERENCES, android.content.Context.MODE_PRIVATE)
   .edit()
   .putBoolean(TUTORIAL_COMPLETED, true)
   .apply()
 }
}

private data class TutorialStep(
 val eyebrow: String,
 val title: String,
 val description: String,
 val icon: ImageVector,
 val accent: Color,
 val bullets: List<String>,
)

private val tutorialSteps = listOf(
 TutorialStep(
  eyebrow = "01 · START WITH AN APK",
  title = "Choose what to inspect",
  description = "Select an APK from your Personal Profile. APK Scope records its package identity and fingerprint before analysis begins.",
  icon = Icons.Filled.FolderOpen,
  accent = ApkScopeColors.Primary,
  bullets = listOf("Static review does not execute the app", "Reports stay on this device"),
 ),
 TutorialStep(
  eyebrow = "02 · PICK A TRAFFIC PATH",
  title = "Use the right inspection mode",
  description = "CA Mode is for apps that trust the Work Profile CA. Frida Mode is for supported anti-pinning cases and produces a modified APK.",
  icon = Icons.Filled.Key,
  accent = ApkScopeColors.Secondary,
  bullets = listOf("CA Mode keeps the original package", "Frida Mode changes the package and behavior may differ"),
 ),
 TutorialStep(
  eyebrow = "03 · RUN IN SANDBOX",
  title = "Keep execution isolated",
  description = "The Work Profile is a separate Android container. You confirm installation and launch there before live observations begin.",
  icon = Icons.Filled.Shield,
  accent = ApkScopeColors.Success,
  bullets = listOf("Personal files remain outside the sandbox", "Runtime observations are kept separate from declarations"),
 ),
 TutorialStep(
  eyebrow = "04 · READ THE EVIDENCE",
  title = "Follow the evidence trail",
  description = "Reports keep declared capabilities, observed behavior, and Android evidence distinct so every conclusion has clear provenance.",
  icon = Icons.Filled.FactCheck,
  accent = ApkScopeColors.Warning,
  bullets = listOf("Open Reports to revisit completed sessions", "Unsupported protocols are disclosed instead of guessed"),
 ),
)

@Composable
fun TutorialDialog(
 onFinished: () -> Unit,
) {
 var stepIndex by rememberSaveable { mutableIntStateOf(0) }
 val step = tutorialSteps[stepIndex]

 Dialog(
  onDismissRequest = onFinished,
  properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
 ) {
  Surface(
   modifier = Modifier.fillMaxSize(),
   color = MaterialTheme.colorScheme.background,
  ) {
   Scaffold(
    topBar = {
     AppTopBar(
      title = "Getting started",
      eyebrow = "PERSONAL WORKSPACE",
      onOverflow = onFinished,
     )
    },
    bottomBar = {
     StickyActionBar {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
       if (stepIndex > 0) {
        SecondaryActionButton(
         text = "Back",
         onClick = { stepIndex -= 1 },
         modifier = Modifier.weight(1f),
        )
       }
       PrimaryActionButton(
        text = if (stepIndex == tutorialSteps.lastIndex) "Done" else "Next",
        onClick = {
         if (stepIndex == tutorialSteps.lastIndex) onFinished() else stepIndex += 1
        },
        modifier = Modifier.weight(1f),
       )
      }
     }
    },
   ) { padding ->
    Column(
     modifier = Modifier
      .fillMaxSize()
      .padding(padding)
      .padding(horizontal = Spacing.base)
      .verticalScroll(rememberScrollState()),
     verticalArrangement = Arrangement.spacedBy(Spacing.base),
    ) {
     Spacer(Modifier.height(Spacing.lg))
     Text("HOW APK SCOPE WORKS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
     Text("A clear path from APK to evidence.", style = MaterialTheme.typography.headlineLarge)
     Text(
      "Use this quick guide whenever you need a reminder of what each part of the workspace does.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
     )
     Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
      tutorialSteps.forEachIndexed { index, _ ->
       Spacer(
        Modifier
         .weight(1f)
         .height(4.dp)
         .background(
          if (index <= stepIndex) step.accent else MaterialTheme.colorScheme.outlineVariant,
          RoundedCornerShape(Radii.pill),
         ),
       )
      }
     }
     TutorialStepCard(step)
     BaseCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
       Icon(Icons.Filled.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
       Text(
        "APK Scope is local-first: evidence is collected and stored on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
       )
      }
     }
     Spacer(Modifier.height(Spacing.xl))
    }
   }
  }
 }
}

@Composable
private fun TutorialStepCard(step: TutorialStep) {
 BaseCard(containerColor = MaterialTheme.colorScheme.surface) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
   Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    Text(step.eyebrow, style = MaterialTheme.typography.labelSmall, color = step.accent)
    Text(step.title, style = MaterialTheme.typography.headlineMedium)
   }
   Surface(
    color = step.accent.copy(alpha = 0.12f),
    shape = RoundedCornerShape(Radii.md),
   ) {
    Icon(step.icon, contentDescription = null, tint = step.accent, modifier = Modifier.padding(Spacing.md).size(28.dp))
   }
  }
  Spacer(Modifier.height(Spacing.md))
  Text(step.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(Spacing.md))
  step.bullets.forEach { bullet ->
   Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    Text("•", style = MaterialTheme.typography.bodyLarge, color = step.accent)
    Text(bullet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
   }
  }
 }
}

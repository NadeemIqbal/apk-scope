package com.nadeem.apkscope.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.ui.common.StepState
import com.nadeem.apkscope.ui.theme.Spacing

/**
 * One row in a vertical stepper (Analysis's "Execution Pipeline", Preparing Sandbox's
 * "Verification Lifecycle", Report Building's pipeline). [state] must reflect real
 * work — never a timed fake animation (item 7/12). [connectToNext] draws the connecting line
 * DESIGN.md's stepper visuals use, omitted for the last step.
 */
@Composable
fun AnalysisProgressStep(
 title: String,
 state: StepState,
 modifier: Modifier = Modifier,
 detail: String? = null,
 trailingLabel: String? = null,
 connectToNext: Boolean = true,
) {
 Row(modifier.fillMaxWidth()) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
   StepIndicator(state)
   if (connectToNext) {
    Box(
     Modifier
      .width(2.dp)
      .height(if (detail != null) 32.dp else 16.dp)
      .background(if (state == StepState.COMPLETE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant),
    )
   }
  }
  Spacer(Modifier.width(Spacing.sm))
  Column(Modifier.padding(bottom = Spacing.base)) {
   Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
    Text(title, style = MaterialTheme.typography.bodyLarge, color = if (state == StepState.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    if (trailingLabel != null) {
     Text(trailingLabel, style = MaterialTheme.typography.labelMedium, color = stepColor(state))
    }
   }
   if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

@Composable
private fun stepColor(state: StepState) = when (state) {
 StepState.COMPLETE -> MaterialTheme.colorScheme.tertiary
 StepState.ACTIVE -> MaterialTheme.colorScheme.secondary
 StepState.FAILED -> MaterialTheme.colorScheme.error
 StepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun StepIndicator(state: StepState) {
 val color = stepColor(state)
 Box(Modifier.size(24.dp).background(color.copy(alpha = if (state == StepState.PENDING) 0.15f else 0.2f), CircleShape), contentAlignment = Alignment.Center) {
  when (state) {
   StepState.COMPLETE -> Icon(Icons.Filled.Check, contentDescription = "Complete", tint = color, modifier = Modifier.size(14.dp))
   StepState.FAILED -> Icon(Icons.Filled.Close, contentDescription = "Failed", tint = color, modifier = Modifier.size(14.dp))
   StepState.ACTIVE -> CircularProgressIndicator(modifier = Modifier.size(12.dp), color = color, strokeWidth = 2.dp)
   StepState.PENDING -> Box(Modifier.size(8.dp).background(color, CircleShape))
  }
 }
}

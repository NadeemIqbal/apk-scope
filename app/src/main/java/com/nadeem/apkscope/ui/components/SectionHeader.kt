package com.nadeem.apkscope.ui.components
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** "Recent Analysis · 3 · View all" style header used above nearly every list section. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, count: Int? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
 Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
  Row(verticalAlignment = Alignment.CenterVertically) {
   Text(title, style = MaterialTheme.typography.headlineSmall)
   if (count != null) {
    androidx.compose.foundation.layout.Spacer(Modifier.width(com.nadeem.apkscope.ui.theme.Spacing.xs))
    Badge(text = "$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  if (actionLabel != null && onAction != null) TextLinkButton(actionLabel, onAction)
 }
}

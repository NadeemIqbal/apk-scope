package com.nadeem.apkscope.ui.components
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.ui.theme.Spacing

/** Full-bleed loading state — used while a screen's very first real data (e.g. `Recent Analysis` from the repository) is still being fetched. Never used to fake analyzer/report progress; those have their own real stepper state (item 7/12). */
@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = "Loading…") {
 Column(modifier.fillMaxSize().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
  CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
  androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.base))
  Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
 }
}

/** "No records exist yet" — Home's Recent Analysis with zero history, Reports with zero saved reports. Never populated with fabricated sample entries in production (item 6/20). */
@Composable
fun EmptyState(title: String, modifier: Modifier = Modifier, description: String? = null, icon: ImageVector = Icons.Filled.Inbox) {
 Column(modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
  Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
  androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.base))
  Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
  if (description != null) {
   androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.xs))
   Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
 Column(modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
  Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
  androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.base))
  Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
  if (onRetry != null) {
   androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.base))
   TextLinkButton("Retry", onRetry)
  }
 }
}

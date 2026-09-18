package com.nadeem.apkscope.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing

/** icon + name + package + version — shown "as it becomes available" during analysis (item 7) and atop every screen in the flow thereafter. Any field still unknown renders as an em dash, never a placeholder guess. */
@Composable
fun AppIdentityHeader(
 appName: String?,
 packageName: String?,
 versionName: String?,
 platform: String? = null,
 platformDetails: String? = null,
 modifier: Modifier = Modifier,
) {
 Row(modifier, verticalAlignment = Alignment.CenterVertically) {
  Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Radii.md)), contentAlignment = Alignment.Center) {
   Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Spacer(Modifier.width(Spacing.sm))
  Column {
   Row(verticalAlignment = Alignment.CenterVertically) {
    Text(appName ?: "—", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    if (versionName != null) {
     Spacer(Modifier.width(Spacing.xs))
     Badge(text = "v$versionName", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!platform.isNullOrBlank()) {
     Spacer(Modifier.width(Spacing.xs))
     PlatformBadge(platformName = platform, details = platformDetails)
    }
   }
   Text(packageName ?: "—", style = com.nadeem.apkscope.ui.theme.MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

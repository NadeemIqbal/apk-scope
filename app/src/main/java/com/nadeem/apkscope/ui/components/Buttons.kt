package com.nadeem.apkscope.ui.components
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.ui.theme.MinTouchTarget
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing

/** Page-level actions remain visible while the content above them scrolls. */
@Composable
fun StickyActionBar(
 content: @Composable ColumnScope.() -> Unit,
) {
 Surface(
  modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
  color = MaterialTheme.colorScheme.surface,
  tonalElevation = 2.dp,
 ) {
  Column(
   modifier = androidx.compose.ui.Modifier
    .fillMaxWidth()
    .navigationBarsPadding()
    .padding(horizontal = Spacing.base, vertical = Spacing.sm),
   verticalArrangement = Arrangement.spacedBy(Spacing.xs),
   content = content,
  )
 }
}

/** Filled execution action from the telemetry console design. */
@Composable
fun PrimaryActionButton(
 text: String,
 onClick: () -> Unit,
 modifier: Modifier = Modifier,
 icon: ImageVector? = null,
 enabled: Boolean = true,
 loading: Boolean = false,
) {
 Button(
  onClick = onClick,
  modifier = modifier.fillMaxWidth().height(MinTouchTarget),
  enabled = enabled && !loading,
  shape = RoundedCornerShape(Radii.md),
  colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
 ) {
  if (loading) {
   CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
   androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
  } else if (icon != null) {
   Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
   androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
  }
  Text(text, style = MaterialTheme.typography.labelLarge)
 }
}

/** Outline utility action from the telemetry console design. */
@Composable
fun SecondaryActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
 OutlinedButton(
  onClick = onClick,
  modifier = modifier.fillMaxWidth().height(MinTouchTarget),
  enabled = enabled,
  shape = RoundedCornerShape(Radii.md),
  colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface),
  border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
 ) {
  if (icon != null) {
   Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
   androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
  }
  Text(text, style = MaterialTheme.typography.labelLarge)
 }
}

/** DESIGN.md "Destructive" — terminating tasks, purging containers ("End Dynamic Session", "Rebuild Sandbox Container"). */
@Composable
fun DestructiveActionButton(
 text: String,
 onClick: () -> Unit,
 modifier: Modifier = Modifier,
 icon: ImageVector? = null,
 enabled: Boolean = true,
 loading: Boolean = false,
) {
 Button(
  onClick = onClick,
  modifier = modifier.fillMaxWidth().height(MinTouchTarget),
  enabled = enabled && !loading,
  shape = RoundedCornerShape(Radii.md),
  colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
 ) {
  if (loading) {
   CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.error, strokeWidth = 2.dp)
   androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
  } else if (icon != null) {
   Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
   androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
  }
  Text(text, style = MaterialTheme.typography.labelLarge)
 }
}

@Composable
fun TextLinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, trailingIcon: ImageVector? = null) {
 androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier) {
  Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
  if (trailingIcon != null) {
   androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.xs))
   Icon(trailingIcon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
  }
 }
}

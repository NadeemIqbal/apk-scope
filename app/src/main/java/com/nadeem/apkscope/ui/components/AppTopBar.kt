package com.nadeem.apkscope.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.ui.theme.LocalProfileContext
import com.nadeem.apkscope.ui.theme.ProfileContext
import com.nadeem.apkscope.ui.theme.Radii

/**
 * Compact profile context indicator chip:
 * - Personal Profile: cyan/blue accent with Person icon and "PERSONAL" label.
 * - Sandbox Profile: green/teal accent with Shield icon and "SANDBOX" label.
 * Semantic content description included for screen readers so color is never the sole indicator.
 */
@Composable
fun ProfileIndicatorChip(
 modifier: Modifier = Modifier,
 profileContext: ProfileContext = LocalProfileContext.current,
) {
 val (label, icon, desc) = when (profileContext) {
  ProfileContext.PERSONAL -> Triple("PERSONAL", Icons.Filled.Person, "Personal profile")
  ProfileContext.SANDBOX -> Triple("SANDBOX", Icons.Filled.Shield, "Sandbox profile")
 }
 Surface(
  shape = RoundedCornerShape(Radii.sm),
  color = MaterialTheme.colorScheme.primaryContainer,
  border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
  modifier = modifier.semantics { contentDescription = desc },
 ) {
  Row(
   modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
   verticalAlignment = Alignment.CenterVertically,
   horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
   Icon(
    icon,
    contentDescription = null,
    tint = MaterialTheme.colorScheme.primary,
    modifier = Modifier.size(11.dp),
   )
   Text(
    text = label,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.Bold,
   )
  }
 }
}

/**
 * Profile-aware top app bar:
 * Shows the navigation icon, title, eyebrow, persistent profile indicator chip, and actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
 title: String,
 modifier: Modifier = Modifier,
 eyebrow: String? = null,
 onBack: (() -> Unit)? = null,
 onOverflow: (() -> Unit)? = null,
 actions: @Composable RowScope.() -> Unit = {},
) {
 val profile = LocalProfileContext.current
 TopAppBar(
  modifier = modifier,
  navigationIcon = {
   if (onBack != null) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
   } else if (eyebrow != null) {
    Box(
     Modifier.padding(start = 12.dp).size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Radii.md)),
     contentAlignment = Alignment.Center,
    ) {
     Icon(
      if (profile == ProfileContext.SANDBOX) Icons.Filled.Shield else Icons.Filled.Person,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
     )
    }
   }
  },
  title = {
   if (eyebrow != null) {
    Column {
     ProfileIndicatorChip(profileContext = profile)
     Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
   } else {
    Row(
     verticalAlignment = Alignment.CenterVertically,
     horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
     Text(
      title,
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier.weight(1f, fill = false),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
     )
     ProfileIndicatorChip(profileContext = profile)
    }
   }
  },
  actions = {
   if (onOverflow != null) IconButton(onClick = onOverflow) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
   actions()
  },
  colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
 )
}

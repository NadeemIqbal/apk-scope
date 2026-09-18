package com.nadeem.apkscope.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

enum class BottomNavTab(val label: String) { HOME("Home"), REPORTS("Reports"), SETTINGS("More") }

/** Primary product navigation: the APK entry point, report history, and trust controls. */
@Composable
fun BottomNavigationBar(selected: BottomNavTab, onSelect: (BottomNavTab) -> Unit) {
 NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
  NavigationBarItem(
   selected = selected == BottomNavTab.HOME, onClick = { onSelect(BottomNavTab.HOME) },
   icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text(BottomNavTab.HOME.label) },
   colors = navColors(),
  )
  NavigationBarItem(
   selected = selected == BottomNavTab.REPORTS, onClick = { onSelect(BottomNavTab.REPORTS) },
   icon = { Icon(Icons.Filled.History, contentDescription = null) }, label = { Text(BottomNavTab.REPORTS.label) },
   colors = navColors(),
  )
  NavigationBarItem(
   selected = selected == BottomNavTab.SETTINGS, onClick = { onSelect(BottomNavTab.SETTINGS) },
   icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = null) }, label = { Text(BottomNavTab.SETTINGS.label) },
   colors = navColors(),
  )
 }
}

@Composable
private fun navColors() = NavigationBarItemDefaults.colors(
 selectedIconColor = MaterialTheme.colorScheme.primary,
 selectedTextColor = MaterialTheme.colorScheme.primary,
 indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
 unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
 unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

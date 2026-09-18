package com.nadeem.apkscope.ui.components

/** Keeps target-app launch actions consistent across Personal and Work Profile screens. */
fun openSandboxAppLabel(appName: String?, packageName: String?): String {
 val displayName = appName?.trim().takeUnless { it.isNullOrBlank() }
  ?: packageName?.trim().takeUnless { it.isNullOrBlank() }
  ?: "Sandboxed App"
 val suffix = if (displayName.contains("app", ignoreCase = true)) "" else " App"
 return "Open $displayName$suffix"
}

package com.nadeem.apkscope.ui.theme

import androidx.compose.ui.graphics.Color

/** Light telemetry-console tokens for the APK Scope product palette. */
object ApkScopeColors {
 // APK Scope hi-fi product palette. Personal surfaces are intentionally quiet and
 // low-contrast so evidence and status colours carry the meaning.
 val Canvas = Color(0xFFEDF1ED)
 val Surface = Color(0xFFFFFFFF)
 val SurfaceSoft = Color(0xFFF5F8F4)
 val SurfaceDim = Color(0xFFD9E1DA)
 val SurfaceBright = Color(0xFFFFFFFF)
 val SurfaceContainerLowest = Color(0xFFFFFFFF)
 val SurfaceContainerLow = SurfaceSoft
 val SurfaceContainer = Color(0xFFFFFFFF)
 val SurfaceContainerHigh = Color(0xFFF0F5F0)
 val SurfaceContainerHighest = Color(0xFFE8EFEA)
 val OnSurface = Color(0xFF15211D)
 val OnSurfaceVariant = Color(0xFF6B7771)
 val Outline = Color(0xFFD5DFD8)
 val OutlineVariant = Outline

 val Primary = Color(0xFF176B50)
 val OnPrimary = Color(0xFFFFFFFF)
 val PrimaryContainer = Color(0xFFDCEFE6)
 val OnPrimaryContainer = Color(0xFF176B50)
 val Secondary = Color(0xFF2B6793)
 val OnSecondary = Color(0xFFFFFFFF)
 val SecondaryContainer = Color(0xFFE3EFF7)

 val Lime = Color(0xFFC8ED73)
 val OnLime = Color(0xFF1C2B1E)

 /** M3 "tertiary" role, used throughout Stitch as the success/verified/green signal. */
 val Success = Color(0xFF176B50)
 val OnSuccess = Color(0xFFFFFFFF)
 val SuccessContainer = Color(0xFFF0FDF4)
 val OnSuccessContainer = Color(0xFF15803D)

 val Error = Color(0xFFB91C1C)
 val OnError = Color(0xFFFFFFFF)
 val ErrorContainer = Color(0xFFFEF2F2)
 val OnErrorContainer = Color(0xFFB91C1C)

 val Warning = Color(0xFFB66A1E)
 val OnWarning = Color(0xFFFFFFFF)
 val WarningContainer = Color(0xFFFFF0D8)
 val OnWarningContainer = Color(0xFFB45309)

 val Background = Canvas
 val OnBackground = OnSurface

 // Sandbox / Work Profile theme palette (Green / Teal accent)
 val SandboxPrimary = Color(0xFF0D9488)
 val SandboxOnPrimary = Color(0xFFFFFFFF)
 val SandboxPrimaryContainer = Color(0xFFF0FDFA)
 val SandboxOnPrimaryContainer = Color(0xFF0F766E)
 val SandboxSecondary = Color(0xFF14B8A6)
 val SandboxOnSecondary = Color(0xFFFFFFFF)
 val SandboxSecondaryContainer = Color(0xFFCCFBF1)
}

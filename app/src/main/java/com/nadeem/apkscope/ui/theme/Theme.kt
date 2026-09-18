package com.nadeem.apkscope.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** `LocalExtendedColors` carries telemetry tokens M3 has no direct slot for. */
data class ExtendedColors(
 val warning: androidx.compose.ui.graphics.Color,
 val onWarning: androidx.compose.ui.graphics.Color,
 val warningContainer: androidx.compose.ui.graphics.Color,
 val onWarningContainer: androidx.compose.ui.graphics.Color,
 val surfaceContainerLowest: androidx.compose.ui.graphics.Color,
 val surfaceContainerLow: androidx.compose.ui.graphics.Color,
 val surfaceContainerHigh: androidx.compose.ui.graphics.Color,
 val surfaceContainerHighest: androidx.compose.ui.graphics.Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
 ExtendedColors(
  warning = ApkScopeColors.Warning, onWarning = ApkScopeColors.OnWarning,
  warningContainer = ApkScopeColors.WarningContainer, onWarningContainer = ApkScopeColors.OnWarningContainer,
  surfaceContainerLowest = ApkScopeColors.SurfaceContainerLowest, surfaceContainerLow = ApkScopeColors.SurfaceContainerLow,
  surfaceContainerHigh = ApkScopeColors.SurfaceContainerHigh, surfaceContainerHighest = ApkScopeColors.SurfaceContainerHighest,
 )
}

enum class ProfileContext {
 PERSONAL,
 SANDBOX,
}

val LocalProfileContext = staticCompositionLocalOf { ProfileContext.PERSONAL }

private val PersonalColors = lightColorScheme(
 primary = ApkScopeColors.Primary, onPrimary = ApkScopeColors.OnPrimary,
 primaryContainer = ApkScopeColors.PrimaryContainer, onPrimaryContainer = ApkScopeColors.OnPrimaryContainer,
 secondary = ApkScopeColors.Secondary, onSecondary = ApkScopeColors.OnSecondary,
 secondaryContainer = ApkScopeColors.SecondaryContainer,
 tertiary = ApkScopeColors.Success, onTertiary = ApkScopeColors.OnSuccess,
 tertiaryContainer = ApkScopeColors.SuccessContainer, onTertiaryContainer = ApkScopeColors.OnSuccessContainer,
 error = ApkScopeColors.Error, onError = ApkScopeColors.OnError,
 errorContainer = ApkScopeColors.ErrorContainer, onErrorContainer = ApkScopeColors.OnErrorContainer,
 background = ApkScopeColors.Background, onBackground = ApkScopeColors.OnBackground,
 surface = ApkScopeColors.Surface, onSurface = ApkScopeColors.OnSurface,
 surfaceVariant = ApkScopeColors.SurfaceContainerHighest, onSurfaceVariant = ApkScopeColors.OnSurfaceVariant,
 outline = ApkScopeColors.Outline, outlineVariant = ApkScopeColors.OutlineVariant,
 surfaceContainer = ApkScopeColors.SurfaceContainer,
 surfaceContainerLow = ApkScopeColors.SurfaceContainerLow,
 surfaceContainerLowest = ApkScopeColors.SurfaceContainerLowest,
 surfaceContainerHigh = ApkScopeColors.SurfaceContainerHigh,
 surfaceContainerHighest = ApkScopeColors.SurfaceContainerHighest,
)

private val SandboxColors = lightColorScheme(
 primary = ApkScopeColors.SandboxPrimary, onPrimary = ApkScopeColors.SandboxOnPrimary,
 primaryContainer = ApkScopeColors.SandboxPrimaryContainer, onPrimaryContainer = ApkScopeColors.SandboxOnPrimaryContainer,
 secondary = ApkScopeColors.SandboxSecondary, onSecondary = ApkScopeColors.SandboxOnSecondary,
 secondaryContainer = ApkScopeColors.SandboxSecondaryContainer,
 tertiary = ApkScopeColors.Success, onTertiary = ApkScopeColors.OnSuccess,
 tertiaryContainer = ApkScopeColors.SuccessContainer, onTertiaryContainer = ApkScopeColors.OnSuccessContainer,
 error = ApkScopeColors.Error, onError = ApkScopeColors.OnError,
 errorContainer = ApkScopeColors.ErrorContainer, onErrorContainer = ApkScopeColors.OnErrorContainer,
 background = ApkScopeColors.Background, onBackground = ApkScopeColors.OnBackground,
 surface = ApkScopeColors.Surface, onSurface = ApkScopeColors.OnSurface,
 surfaceVariant = ApkScopeColors.SurfaceContainerHighest, onSurfaceVariant = ApkScopeColors.OnSurfaceVariant,
 outline = ApkScopeColors.Outline, outlineVariant = ApkScopeColors.OutlineVariant,
 surfaceContainer = ApkScopeColors.SurfaceContainer,
 surfaceContainerLow = ApkScopeColors.SurfaceContainerLow,
 surfaceContainerLowest = ApkScopeColors.SurfaceContainerLowest,
 surfaceContainerHigh = ApkScopeColors.SurfaceContainerHigh,
 surfaceContainerHighest = ApkScopeColors.SurfaceContainerHighest,
)

private val AppShapes = Shapes(
 extraSmall = RoundedCornerShape(Radii.none),
 small = RoundedCornerShape(Radii.sm),
 medium = RoundedCornerShape(Radii.md),
 large = RoundedCornerShape(Radii.md),
 extraLarge = RoundedCornerShape(Radii.md),
)

@Composable
fun ApkScopeTheme(
 profileContext: ProfileContext = ProfileContext.PERSONAL,
 content: @Composable () -> Unit,
) {
 val extended = ExtendedColors(
  warning = ApkScopeColors.Warning, onWarning = ApkScopeColors.OnWarning,
  warningContainer = ApkScopeColors.WarningContainer, onWarningContainer = ApkScopeColors.OnWarningContainer,
  surfaceContainerLowest = ApkScopeColors.SurfaceContainerLowest, surfaceContainerLow = ApkScopeColors.SurfaceContainerLow,
  surfaceContainerHigh = ApkScopeColors.SurfaceContainerHigh, surfaceContainerHighest = ApkScopeColors.SurfaceContainerHighest,
 )
 val view = LocalView.current
 if (!view.isInEditMode) {
  val activity = view.context as? android.app.Activity
  activity?.window?.let { window ->
   WindowCompat.setDecorFitsSystemWindows(window, false)
   window.statusBarColor = android.graphics.Color.TRANSPARENT
   window.navigationBarColor = android.graphics.Color.TRANSPARENT
   WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
  }
 }
 val colorScheme = when (profileContext) {
  ProfileContext.PERSONAL -> PersonalColors
  ProfileContext.SANDBOX -> SandboxColors
 }
 androidx.compose.runtime.CompositionLocalProvider(
  LocalExtendedColors provides extended,
  LocalProfileContext provides profileContext,
 ) {
  MaterialTheme(colorScheme = colorScheme, typography = ApkScopeTypography, shapes = AppShapes, content = content)
 }
}

/** Convenience accessor, e.g. `MaterialTheme.extendedColors.warning`. */
val MaterialTheme.extendedColors: ExtendedColors
 @Composable get() = LocalExtendedColors.current

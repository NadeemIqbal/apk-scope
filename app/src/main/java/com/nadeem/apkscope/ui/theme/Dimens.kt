package com.nadeem.apkscope.ui.theme

import androidx.compose.ui.unit.dp

/** Centralized spacing/radius/elevation — DESIGN.md's `spacing`/`rounded` tokens plus its prose "Elevation & Depth"/"Shapes" sections. Never hardcode a `.dp` literal in screen code; reference these instead. */
object Spacing {
 val xxs = 2.dp
 val xs = 4.dp
 val sm = 8.dp
 val md = 12.dp
 val base = 16.dp
 val lg = 24.dp
 val xl = 32.dp
 val xxl = 48.dp
}

object Radii {
 val none = 0.dp
 val sm = 4.dp
 val md = 8.dp
 val lg = 12.dp
 val xl = 12.dp
 val pill = 99.dp
}

object Elevation {
 /** Level 1 in DESIGN.md's elevation model is a color-step + border, not a shadow — the border width components should draw. */
 val cardBorder = 1.dp
}

/** Minimum touch target per Android accessibility guidance (item 22) — every tappable component in this design system respects this. */
val MinTouchTarget = 48.dp

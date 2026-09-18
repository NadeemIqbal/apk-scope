package com.nadeem.apkscope.ui.common

/** Shared by every stepper-style screen (Analysis pipeline, Preparing Sandbox, Report Building) — real stage state, never a timed fake animation (item 7/12). */
enum class StepState { PENDING, ACTIVE, COMPLETE, FAILED }

/** The four states a Stitch status row/pill can show — matches Home's environment card and Ready-to-Run's isolation parameters, and doubles as the mapping target for `core:model`'s `EnforcementStatus` (item 11). */
enum class UiStatus { READY, NOT_CONFIGURED, UNAVAILABLE, CHECKING, ERROR }

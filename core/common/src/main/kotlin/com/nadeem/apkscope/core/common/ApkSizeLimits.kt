package com.nadeem.apkscope.core.common

/**
 * The one size ceiling every "accept an APK" path in this app enforces — shared so the front door
 * (importing an APK for analysis) and the cross-profile sandbox handoff never disagree about what
 * counts as too large.
 *
 * **A real, reproduced bug this constant fixes, not a hypothetical one**: the cross-profile handoff
 * ([com.nadeem.apkscope.core.crossprofile.Handoff.copy]'s caller) previously used a hardcoded 128 MiB
 * limit while [com.nadeem.apkscope.domain.ApkImportUseCase] already accepted APKs up to 300 MiB for
 * analysis. An APK between those two sizes (confirmed on-device: a real 131 MiB APK) analyzed
 * successfully, then silently failed the moment it was handed off to the sandbox — the work-profile
 * copy threw `IllegalArgumentException: Transfer too large`, the failure was swallowed by a no-op
 * `onImportFailed` override, and the personal-side "Preparing" screen span forever with no error and
 * no way out (see `SandboxWorkerActivity.onImportFailed`'s companion fix, which now reports this class
 * of failure back to the personal profile instead of relying on this limit never being hit).
 *
 * 300 MiB is not an arbitrary round number picked for this fix — it is raised to match the limit this
 * app's own APK-selection flow already accepted, so "the app let me analyze it" and "the app can
 * sandbox it" are never two different answers for the same file.
 */
const val MAX_APK_TRANSFER_BYTES = 300L * 1024 * 1024

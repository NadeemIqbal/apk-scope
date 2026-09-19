package com.nadeem.apkscope.core.model

/** Platform status integers enter here; no Android framework is simulated. */
object InstallLifecycle {
 enum class Outcome { PENDING_CONFIRMATION, VERIFY_PACKAGE, CANCELLED, FAILED }
 fun outcome(status: Int): Outcome = when (status) {
  -1 -> Outcome.PENDING_CONFIRMATION
  0 -> Outcome.VERIFY_PACKAGE
  3 -> Outcome.CANCELLED
  else -> Outcome.FAILED
 }
 fun interrupted(state: SandboxSessionState, packagePresent: Boolean, installerSessionPresent: Boolean): Boolean =
  state in setOf(SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION, SandboxSessionState.INSTALLING) &&
   !packagePresent && !installerSessionPresent
 fun confirmationAvailable(permissionGranted: Boolean, notificationsEnabled: Boolean, channelEnabled: Boolean): Boolean =
  permissionGranted && notificationsEnabled && channelEnabled
}

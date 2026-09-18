package com.nadeem.apkscope.core.model

import org.junit.Assert.*
import org.junit.Test

class InstallLifecycleTest {
 @Test fun pendingRequiresConfirmation() { assertEquals(InstallLifecycle.Outcome.PENDING_CONFIRMATION, InstallLifecycle.outcome(-1)) }
 @Test fun successRequiresPackageVerification() { assertEquals(InstallLifecycle.Outcome.VERIFY_PACKAGE, InstallLifecycle.outcome(0)) }
 @Test fun abortedIsCancellation() { assertEquals(InstallLifecycle.Outcome.CANCELLED, InstallLifecycle.outcome(3)) }
 @Test fun failuresNeverBecomeSuccess() {
  // FAILURE, BLOCKED, INVALID, CONFLICT, STORAGE, INCOMPATIBLE, unknown.
  for (status in listOf(1, 2, 4, 5, 6, 7, Int.MIN_VALUE)) assertEquals(InstallLifecycle.Outcome.FAILED, InstallLifecycle.outcome(status))
 }
 @Test fun absentInstallerAndPackageInterruptInstallingOnly() {
  for (state in SandboxSessionState.entries) for (installed in listOf(false, true)) for (active in listOf(false, true)) {
   assertEquals(state == SandboxSessionState.INSTALLING && !installed && !active, InstallLifecycle.interrupted(state, installed, active))
  }
 }
 @Test fun allNotificationGatesMustPass() {
  for (permission in listOf(false, true)) for (enabled in listOf(false, true)) for (channel in listOf(false, true)) {
   assertEquals(permission && enabled && channel, InstallLifecycle.confirmationAvailable(permission, enabled, channel))
  }
 }
}

package com.nadeem.apkscope.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 6/21: `launchAllowed == installationConfirmed && environmentValid && requiredPoliciesSatisfied && networkIsolationActive` — every one of the four inputs is independently required. */
class LaunchReadinessTest {
 private val allTrue = LaunchReadiness(installationConfirmed = true, environmentValid = true, requiredPoliciesSatisfied = true, networkIsolationActive = true)

 @Test fun allowedWhenEverythingTrue() {
  assertTrue(allTrue.launchAllowed)
 }

 @Test fun deniedWithoutInstallationConfirmed() {
  assertFalse(allTrue.copy(installationConfirmed = false).launchAllowed)
 }

 @Test fun deniedWithoutEnvironmentValid() {
  assertFalse(allTrue.copy(environmentValid = false).launchAllowed)
 }

 @Test fun deniedWithoutRequiredPoliciesSatisfied() {
  assertFalse(allTrue.copy(requiredPoliciesSatisfied = false).launchAllowed)
 }

 @Test fun deniedWithoutNetworkIsolationActive_failClosed() {
  // This is the checkpoint's explicit security invariant (item 6): if monitored networking cannot
  // be established, the suspicious APK must never launch with unrestricted network access.
  assertFalse(allTrue.copy(networkIsolationActive = false).launchAllowed)
 }

 @Test fun deniedWhenEverythingFalse() {
  assertFalse(LaunchReadiness(false, false, false, false).launchAllowed)
 }
}

package com.nadeem.apkscope.core.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Positive/negative coverage for every single-declared-capability rule (item 20). Each rule needs exactly its own permission to fire and nothing else. */
class CapabilityRulesTest {

 @Test fun accessibilityService_firesWhenDeclared() {
  val finding = AccessibilityServiceRule.evaluate(testInput(permissions = listOf(Permissions.BIND_ACCESSIBILITY_SERVICE)))
  assertEquals(RuleIds.ACCESSIBILITY_SERVICE, finding?.ruleId)
  assertEquals(20, finding?.scoreContribution)
 }
 @Test fun accessibilityService_doesNotFireWhenAbsent() {
  assertNull(AccessibilityServiceRule.evaluate(testInput(permissions = emptyList())))
 }

 @Test fun overlayPermission_firesWhenDeclared() {
  val finding = OverlayPermissionRule.evaluate(testInput(permissions = listOf(Permissions.SYSTEM_ALERT_WINDOW)))
  assertEquals(RuleIds.OVERLAY_PERMISSION, finding?.ruleId)
  assertEquals(10, finding?.scoreContribution)
 }
 @Test fun overlayPermission_doesNotFireWhenAbsent() {
  assertNull(OverlayPermissionRule.evaluate(testInput()))
 }

 @Test fun readSms_firesWhenDeclared() {
  val finding = ReadSmsRule.evaluate(testInput(permissions = listOf(Permissions.READ_SMS)))
  assertEquals(RuleIds.READ_SMS, finding?.ruleId)
  assertEquals(15, finding?.scoreContribution)
 }
 @Test fun readSms_doesNotFireWhenAbsent() {
  assertNull(ReadSmsRule.evaluate(testInput()))
 }

 @Test fun receiveSms_firesWhenDeclared() {
  val finding = ReceiveSmsRule.evaluate(testInput(permissions = listOf(Permissions.RECEIVE_SMS)))
  assertEquals(RuleIds.RECEIVE_SMS, finding?.ruleId)
  assertEquals(10, finding?.scoreContribution)
 }
 @Test fun receiveSms_doesNotFireWhenAbsent() {
  assertNull(ReceiveSmsRule.evaluate(testInput()))
 }

 @Test fun sendSms_firesWhenDeclared() {
  val finding = SendSmsRule.evaluate(testInput(permissions = listOf(Permissions.SEND_SMS)))
  assertEquals(RuleIds.SEND_SMS, finding?.ruleId)
  assertEquals(15, finding?.scoreContribution)
 }
 @Test fun sendSms_doesNotFireWhenAbsent() {
  assertNull(SendSmsRule.evaluate(testInput()))
 }

 @Test fun requestInstallPackages_firesWhenDeclared() {
  val finding = RequestInstallPackagesRule.evaluate(testInput(permissions = listOf(Permissions.REQUEST_INSTALL_PACKAGES)))
  assertEquals(RuleIds.REQUEST_INSTALL_PACKAGES, finding?.ruleId)
  assertEquals(15, finding?.scoreContribution)
 }
 @Test fun requestInstallPackages_doesNotFireWhenAbsent() {
  assertNull(RequestInstallPackagesRule.evaluate(testInput()))
 }

 @Test fun bootReceiver_firesWhenDeclared() {
  val finding = BootReceiverRule.evaluate(testInput(permissions = listOf(Permissions.RECEIVE_BOOT_COMPLETED)))
  assertEquals(RuleIds.BOOT_RECEIVER, finding?.ruleId)
  assertEquals(5, finding?.scoreContribution)
 }
 @Test fun bootReceiver_doesNotFireWhenAbsent() {
  assertNull(BootReceiverRule.evaluate(testInput()))
 }

 @Test fun backgroundLocation_firesWhenDeclared() {
  val finding = BackgroundLocationRule.evaluate(testInput(permissions = listOf(Permissions.ACCESS_BACKGROUND_LOCATION)))
  assertEquals(RuleIds.BACKGROUND_LOCATION, finding?.ruleId)
  assertEquals(15, finding?.scoreContribution)
 }
 @Test fun backgroundLocation_doesNotFireWhenAbsent() {
  assertNull(BackgroundLocationRule.evaluate(testInput()))
 }

 @Test fun queryAllPackages_firesWhenDeclared() {
  val finding = QueryAllPackagesRule.evaluate(testInput(permissions = listOf(Permissions.QUERY_ALL_PACKAGES)))
  assertEquals(RuleIds.QUERY_ALL_PACKAGES, finding?.ruleId)
  assertEquals(10, finding?.scoreContribution)
 }
 @Test fun queryAllPackages_doesNotFireWhenAbsent() {
  assertNull(QueryAllPackagesRule.evaluate(testInput()))
 }
}

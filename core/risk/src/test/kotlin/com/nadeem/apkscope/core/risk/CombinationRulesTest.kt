package com.nadeem.apkscope.core.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every combination rule (item 10/20) must fire only when *both* sides are present, and must NOT fire when only one side is — the specific case item 20 calls out explicitly. */
class CombinationRulesTest {

 @Test fun accessibilityOverlay_firesWhenBothDeclared() {
  val finding = AccessibilityOverlayCombinationRule.evaluate(testInput(permissions = listOf(Permissions.BIND_ACCESSIBILITY_SERVICE, Permissions.SYSTEM_ALERT_WINDOW)))
  assertEquals(RuleIds.ACCESSIBILITY_OVERLAY_COMBINATION, finding?.ruleId)
  assertEquals(20, finding?.scoreContribution)
 }
 @Test fun accessibilityOverlay_doesNotFireWithOnlyAccessibility() {
  assertNull(AccessibilityOverlayCombinationRule.evaluate(testInput(permissions = listOf(Permissions.BIND_ACCESSIBILITY_SERVICE))))
 }
 @Test fun accessibilityOverlay_doesNotFireWithOnlyOverlay() {
  assertNull(AccessibilityOverlayCombinationRule.evaluate(testInput(permissions = listOf(Permissions.SYSTEM_ALERT_WINDOW))))
 }

 @Test fun accessibilityBoot_firesWhenBothDeclared() {
  val finding = AccessibilityBootCombinationRule.evaluate(testInput(permissions = listOf(Permissions.BIND_ACCESSIBILITY_SERVICE, Permissions.RECEIVE_BOOT_COMPLETED)))
  assertEquals(RuleIds.ACCESSIBILITY_BOOT_COMBINATION, finding?.ruleId)
  assertEquals(15, finding?.scoreContribution)
 }
 @Test fun accessibilityBoot_doesNotFireWithOnlyAccessibility() {
  assertNull(AccessibilityBootCombinationRule.evaluate(testInput(permissions = listOf(Permissions.BIND_ACCESSIBILITY_SERVICE))))
 }
 @Test fun accessibilityBoot_doesNotFireWithOnlyBoot() {
  assertNull(AccessibilityBootCombinationRule.evaluate(testInput(permissions = listOf(Permissions.RECEIVE_BOOT_COMPLETED))))
 }

 @Test fun smsInternet_firesWhenBothDeclared() {
  val finding = SmsInternetCombinationRule.evaluate(testInput(permissions = listOf(Permissions.READ_SMS, Permissions.INTERNET)))
  assertEquals(RuleIds.SMS_INTERNET_COMBINATION, finding?.ruleId)
  assertEquals(15, finding?.scoreContribution)
 }
 @Test fun smsInternet_firesWithAnySmsPermission() {
  assertEquals(RuleIds.SMS_INTERNET_COMBINATION, SmsInternetCombinationRule.evaluate(testInput(permissions = listOf(Permissions.SEND_SMS, Permissions.INTERNET)))?.ruleId)
  assertEquals(RuleIds.SMS_INTERNET_COMBINATION, SmsInternetCombinationRule.evaluate(testInput(permissions = listOf(Permissions.RECEIVE_SMS, Permissions.INTERNET)))?.ruleId)
 }
 @Test fun smsInternet_doesNotFireWithOnlySms() {
  assertNull(SmsInternetCombinationRule.evaluate(testInput(permissions = listOf(Permissions.READ_SMS))))
 }
 @Test fun smsInternet_doesNotFireWithOnlyInternet() {
  assertNull(SmsInternetCombinationRule.evaluate(testInput(permissions = listOf(Permissions.INTERNET))))
 }

 @Test fun contactsInternet_firesWhenBothDeclared() {
  val finding = ContactsInternetCombinationRule.evaluate(testInput(permissions = listOf(Permissions.READ_CONTACTS, Permissions.INTERNET)))
  assertEquals(RuleIds.CONTACTS_INTERNET_COMBINATION, finding?.ruleId)
  assertEquals(10, finding?.scoreContribution)
 }
 @Test fun contactsInternet_doesNotFireWithOnlyContacts() {
  assertNull(ContactsInternetCombinationRule.evaluate(testInput(permissions = listOf(Permissions.READ_CONTACTS))))
 }
 @Test fun contactsInternet_doesNotFireWithOnlyInternet() {
  assertNull(ContactsInternetCombinationRule.evaluate(testInput(permissions = listOf(Permissions.INTERNET))))
 }

 @Test fun backgroundLocationInternet_firesWhenBothDeclared() {
  val finding = BackgroundLocationInternetCombinationRule.evaluate(testInput(permissions = listOf(Permissions.ACCESS_BACKGROUND_LOCATION, Permissions.INTERNET)))
  assertEquals(RuleIds.BACKGROUND_LOCATION_INTERNET_COMBINATION, finding?.ruleId)
  assertEquals(15, finding?.scoreContribution)
 }
 @Test fun backgroundLocationInternet_doesNotFireWithOnlyLocation() {
  assertNull(BackgroundLocationInternetCombinationRule.evaluate(testInput(permissions = listOf(Permissions.ACCESS_BACKGROUND_LOCATION))))
 }
 @Test fun backgroundLocationInternet_doesNotFireWithOnlyInternet() {
  assertNull(BackgroundLocationInternetCombinationRule.evaluate(testInput(permissions = listOf(Permissions.INTERNET))))
 }
}

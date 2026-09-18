package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 21: engine-level scoring behavior, independent of any single rule. */
class DefaultRiskEngineTest {
 private val engine = DefaultRiskEngine()

 @Test fun zeroFindings_scoresZero() {
  val result = engine.evaluate(testInput())
  assertEquals(0, result.score)
  assertEquals(RiskLevel.LOW, result.level)
  assertTrue(result.findings.isEmpty())
 }

 @Test fun scoreEqualsSumOfFindingContributions_whenUnclamped() {
  // Accessibility(20) + Overlay(10) + their combination(20) = 50, exactly the HIGH lower bound.
  val result = engine.evaluate(testInput(permissions = listOf(Permissions.BIND_ACCESSIBILITY_SERVICE, Permissions.SYSTEM_ALERT_WINDOW)))
  val expectedSum = result.findings.sumOf { it.scoreContribution }
  assertEquals(expectedSum, result.score)
  assertEquals(50, result.score)
  assertEquals(RiskLevel.HIGH, result.level)
 }

 @Test fun scoreClampsTo100() {
  // Accessibility+Overlay+combo (50) + SMS trio + Internet + SMS/Internet combo (40+15=55) = 105 raw.
  val input = testInput(permissions = listOf(
   Permissions.BIND_ACCESSIBILITY_SERVICE, Permissions.SYSTEM_ALERT_WINDOW,
   Permissions.READ_SMS, Permissions.RECEIVE_SMS, Permissions.SEND_SMS, Permissions.INTERNET,
  ))
  val result = engine.evaluate(input)
  val rawSum = result.findings.sumOf { it.scoreContribution }
  assertTrue("test fixture should actually exceed 100 raw before asserting the clamp", rawSum > 100)
  assertEquals(100, result.score)
  assertEquals(RiskLevel.CRITICAL, result.level)
 }

 @Test fun evaluationIsDeterministic() {
  val input = testInput(permissions = listOf(Permissions.BIND_ACCESSIBILITY_SERVICE, Permissions.RECEIVE_BOOT_COMPLETED), debuggable = true, targetSdkVersion = 24)
  val first = engine.evaluate(input)
  val second = engine.evaluate(input)
  assertEquals(first, second)
 }

 @Test fun findingsCarryTheEngineVersion() {
  val result = engine.evaluate(testInput(permissions = listOf(Permissions.RECEIVE_BOOT_COMPLETED)))
  assertEquals(RISK_ENGINE_VERSION, result.engineVersion)
 }

 @Test fun basedOnDeclaredCapabilitiesReflectsRequestedPermissions() {
  val result = engine.evaluate(testInput(permissions = listOf(Permissions.CAMERA, Permissions.INTERNET)))
  assertEquals(listOf(Permissions.CAMERA, Permissions.INTERNET), result.basedOnDeclaredCapabilities.map { it.name })
 }
}

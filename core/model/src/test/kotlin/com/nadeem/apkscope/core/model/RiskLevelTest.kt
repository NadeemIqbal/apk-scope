package com.nadeem.apkscope.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The four score bands are fixed by the checkpoint spec (item 6): 0-24 LOW, 25-49 MODERATE, 50-74 HIGH, 75-100 CRITICAL. Every boundary tested on both sides. */
class RiskLevelTest {
 @Test fun lowBand() {
  assertEquals(RiskLevel.LOW, riskLevelFor(0))
  assertEquals(RiskLevel.LOW, riskLevelFor(24))
 }
 @Test fun moderateBand() {
  assertEquals(RiskLevel.MODERATE, riskLevelFor(25))
  assertEquals(RiskLevel.MODERATE, riskLevelFor(49))
 }
 @Test fun highBand() {
  assertEquals(RiskLevel.HIGH, riskLevelFor(50))
  assertEquals(RiskLevel.HIGH, riskLevelFor(74))
 }
 @Test fun criticalBand() {
  assertEquals(RiskLevel.CRITICAL, riskLevelFor(75))
  assertEquals(RiskLevel.CRITICAL, riskLevelFor(100))
 }
}

package com.nadeem.apkscope.core.risk.audit

import com.nadeem.apkscope.core.risk.testInput
import org.junit.Assert.assertEquals
import org.junit.Test

/** [DefaultSecurityAuditEngine] must evaluate the entire catalog, in order, every run — no rule is ever skipped based on another rule's result. */
class SecurityAuditEngineTest {

 @Test fun audit_evaluatesEveryRuleInCatalog() {
  val report = DefaultSecurityAuditEngine().audit(testInput())
  assertEquals(StaticAuditRules.all.size, report.findings.size)
  assertEquals(SECURITY_AUDIT_ENGINE_VERSION, report.engineVersion)
 }

 @Test fun audit_findingOrderMatchesCatalogOrder() {
  val report = DefaultSecurityAuditEngine().audit(testInput())
  assertEquals(StaticAuditRules.all.map { it.id }, report.findings.map { it.ruleId })
 }

 @Test fun audit_outcomeCountsSumToTotal() {
  val report = DefaultSecurityAuditEngine().audit(
   testInput(debuggable = true, signatureVerified = false, targetSdkVersion = 28, nativeLibraryAbis = listOf("arm64-v8a"))
  )
  val total = report.findingCount + report.passCount + report.needsReviewCount +
   report.notTestedCount + report.notApplicableCount + report.collectionFailedCount
  assertEquals(report.findings.size, total)
  assertEquals(2, report.findingCount) // debuggable build + unverified signature
  assertEquals(2, report.needsReviewCount) // outdated target sdk + backup enabled (testInput()'s default allowBackup=true)
  assertEquals(1, report.notTestedCount) // native code present
  assertEquals(6, report.passCount) // cleartext disabled + no network security config declared + 4 others
  assertEquals(0, report.notApplicableCount)
  assertEquals(0, report.collectionFailedCount)
 }

 @Test fun audit_cleanInputProducesAllChecksPassed() {
  val report = DefaultSecurityAuditEngine().audit(
   testInput(signatureVerified = true, targetSdkVersion = 34, allowBackup = false, usesCleartextTraffic = false)
  )
  assertEquals(StaticAuditRules.all.size, report.passCount)
  assertEquals(0, report.findingCount)
  assertEquals(0, report.needsReviewCount)
  assertEquals(0, report.notTestedCount)
 }
}

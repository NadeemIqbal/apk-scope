package com.nadeem.apkscope.core.network.https

import com.nadeem.apkscope.core.network.traffic.OwnershipVerificationStatus
import com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore
import com.nadeem.apkscope.core.network.traffic.TrafficProtocol
import com.nadeem.apkscope.core.network.traffic.TrafficRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpsInspectionStoreTest {

 @Before
 fun setup() {
  HttpsInspectionStore.clear()
 }

 /**
  * Milestone 9 attribution fix (2026-09-12) regression test — unit-level, faster and more precise
  * than the on-device `testTrafficRecordCarriesSessionAttributionOnDevice` for this specific
  * defect: `HttpsInspectionStore.record()`'s "mirror to unified TrafficInspectionStore" block used
  * to reconstruct a fresh, unattributed `TrafficRecord` under the same id the real
  * `TrafficInspectionEngine` code path had *already* written with real attribution moments earlier —
  * silently clobbering it, every time, since the mirror always runs immediately after. Reproduces
  * that exact call order (direct `TrafficRecord` write, then `HttpsInspectionStore.record()` for the
  * same id — precisely what `HttpsInspectionEngine.relayHttp11` does) and asserts the attribution
  * survives the mirror instead of being nulled out.
  */
 @Test
 fun testMirrorPreservesSessionAttributionAcrossOverwrite() {
  TrafficInspectionStore.clear()
  val txId = "attr-overwrite-test-id"
  val sessionId = "session-under-test"
  val targetPackage = "com.apksandbox.fixture"

  // Step 1: the real TrafficRecord write HttpsInspectionEngine always performs first, with real
  // attribution — matches the exact shape/order of the production defect. ownershipStatus=MATCHED
  // here mirrors a genuine, verified-owner connection (forSession requires this in addition to
  // sessionId/targetPackage — see that method's own doc comment); this test is about the
  // sessionId/targetPackage overwrite defect specifically, not ownership verification itself.
  TrafficInspectionStore.record(
   TrafficRecord(
    id = txId,
    sessionId = sessionId,
    targetPackage = targetPackage,
    ownershipStatus = OwnershipVerificationStatus.MATCHED,
    protocol = TrafficProtocol.HTTPS,
    host = "httpbin.org",
    method = "GET",
    statusCode = 200,
   )
  )
  assertEquals(
   "Precondition: the direct write must be attributed before the mirror runs",
   1,
   TrafficInspectionStore.forSession(sessionId, targetPackage).size,
  )

  // Step 2: the legacy "backward compatibility" mirror write for the *same* transaction id,
  // exactly as HttpsInspectionEngine.relayHttp11 performs it — now passing attribution *and*
  // ownership explicitly (the canonical fix), not relying on a lookup-and-merge. Production always
  // passes the same real `ownership` value to both the direct write and this mirror call — this
  // test does the same, rather than letting the mirror silently default to UNVERIFIED.
  HttpsInspectionStore.record(
   HttpsTransaction(id = txId, method = "GET", url = "https://httpbin.org/get", host = "httpbin.org", statusCode = 200),
   sessionId = sessionId,
   targetPackage = targetPackage,
   ownership = com.nadeem.apkscope.core.network.traffic.OwnershipVerification(1310289, OwnershipVerificationStatus.MATCHED, null),
  )

  val afterMirror = TrafficInspectionStore.forSession(sessionId, targetPackage)
  assertEquals(
   "The mirror write must not clobber the attribution the direct write already set",
   1,
   afterMirror.size,
  )
  assertEquals(sessionId, afterMirror[0].sessionId)
  assertEquals(targetPackage, afterMirror[0].targetPackage)
  assertEquals(OwnershipVerificationStatus.MATCHED, afterMirror[0].ownershipStatus)
 }

 /**
  * The canonical-conversion-path requirement's converse: a mirror call with *no* attribution
  * (matching HttpsInspectionEngine's policy-deny/handshake-failure paths, which — unlike the
  * success path above — have never had a matching direct TrafficInspectionStore write under this
  * id) must record null attribution honestly, not fabricate or inherit attribution from an
  * unrelated prior record. Guards against reintroducing the earlier "look up whatever the store
  * already holds under this id" merge, which had no way to distinguish "this id genuinely belongs
  * to no session" from "this id collides with something else's attribution."
  */
 /**
  * Milestone 9 (userspace traffic ownership verification) regression test — the exact same
  * "mirror silently clobbers what the direct write already set" defect class as
  * [testMirrorPreservesSessionAttributionAcrossOverwrite], but for the newer ownership-verification
  * fields specifically: an early version of the ownership feature threaded `sessionId`/
  * `targetPackage` through the mirror's canonical parameters but forgot the mirror also needed
  * `ownership` as an explicit parameter, so every mirrored record's `ownershipStatus` silently reset
  * to `UNKNOWN` regardless of what the direct write had computed. Reproduces that call order and
  * asserts `MISMATCHED` (a definitive, non-trivial result) survives the mirror.
  */
 @Test
 fun testMirrorPreservesOwnershipStatusAcrossOverwrite() {
  TrafficInspectionStore.clear()
  val txId = "ownership-overwrite-test-id"
  val sessionId = "session-under-test"
  val targetPackage = "com.apksandbox.fixture"
  val mismatchedOwnership = com.nadeem.apkscope.core.network.traffic.OwnershipVerification(
   99999, OwnershipVerificationStatus.MISMATCHED, null,
  )

  TrafficInspectionStore.record(
   TrafficRecord(
    id = txId, sessionId = sessionId, targetPackage = targetPackage,
    observedOwnerUid = mismatchedOwnership.observedOwnerUid, ownershipStatus = mismatchedOwnership.status,
    protocol = TrafficProtocol.HTTPS, host = "httpbin.org", method = "GET", statusCode = 200,
   )
  )
  HttpsInspectionStore.record(
   HttpsTransaction(id = txId, method = "GET", url = "https://httpbin.org/get", host = "httpbin.org", statusCode = 200),
   sessionId = sessionId,
   targetPackage = targetPackage,
   ownership = mismatchedOwnership,
  )

  val stored = TrafficInspectionStore.get(txId)
  assertEquals(
   "The mirror must not reset a definitive MISMATCHED result back to UNKNOWN",
   OwnershipVerificationStatus.MISMATCHED,
   stored?.ownershipStatus,
  )
  assertEquals(99999, stored?.observedOwnerUid)
  // And this MISMATCHED record must never surface as confirmed target evidence.
  assertTrue(TrafficInspectionStore.forSession(sessionId, targetPackage).isEmpty())
 }

 @Test
 fun testMirrorWithoutAttributionRecordsNullNotInherited() {
  TrafficInspectionStore.clear()
  val txId = "attr-no-session-test-id"

  HttpsInspectionStore.record(
   HttpsTransaction(id = txId, method = "CONNECT", url = "https://example.com:443", host = "example.com", failureDetails = "denied"),
  )

  val stored = TrafficInspectionStore.get(txId)
  assertNull("A mirror call with no attribution must record null, not inherit from elsewhere", stored?.sessionId)
  assertNull(stored?.targetPackage)
 }

 /**
  * Milestone 9 (userspace traffic ownership verification) — `forSession` (the sole production
  * evidence-export read path) must never treat `UNKNOWN` ownership as confirmed target evidence,
  * even though `sessionId`/`targetPackage` match — an unverified record is not the same as a
  * verified one, and must not silently pass as such.
  */
 @Test
 fun testForSessionExcludesUnknownOwnership() {
  TrafficInspectionStore.clear()
  TrafficInspectionStore.record(
   TrafficRecord(
    id = "unknown-ownership-test-id", sessionId = "s1", targetPackage = "com.apksandbox.fixture",
    ownershipStatus = OwnershipVerificationStatus.UNKNOWN,
    protocol = TrafficProtocol.HTTPS, host = "httpbin.org", method = "GET", statusCode = 200,
   )
  )
  assertTrue(
   "An UNKNOWN-ownership record must not appear in forSession's evidence-export view",
   TrafficInspectionStore.forSession("s1", "com.apksandbox.fixture").isEmpty(),
  )
  // But it must still be visible via the unfiltered general read path (Live Monitor), just not
  // silently hidden from the app entirely.
  assertEquals(1, TrafficInspectionStore.all().size)
 }

 @Test
 fun testHeaderRedaction() {
  val headers = mapOf(
   "Host" to "httpbin.org",
   "Authorization" to "Bearer secret-token-12345",
   "Cookie" to "session_id=abcdef98765",
   "X-Api-Key" to "super-secret-key",
   "Content-Type" to "application/json"
  )

  val sanitized = HttpsInspectionStore.sanitizeHeaders(headers)
  assertEquals("httpbin.org", sanitized["Host"])
  assertEquals("application/json", sanitized["Content-Type"])
  assertEquals("[REDACTED]", sanitized["Authorization"])
  assertEquals("[REDACTED]", sanitized["Cookie"])
  assertEquals("[REDACTED]", sanitized["X-Api-Key"])
 }

 @Test
 fun testJsonSecretRedaction() {
  val json = """{"username":"testuser","password":"mypassword123","token":"jwt-secret-xyz","data":"public-info"}"""
  val redacted = HttpsInspectionStore.redactSecrets(json)

  assertTrue(redacted.contains("\"password\":\"[REDACTED]\""))
  assertTrue(redacted.contains("\"token\":\"[REDACTED]\""))
  assertTrue(redacted.contains("\"username\":\"testuser\""))
  assertTrue(redacted.contains("\"data\":\"public-info\""))
 }

 @Test
 fun testBodyTruncationAt64KiB() {
  val smallBody = "A".repeat(1000)
  val (smallResult, smallTruncated) = HttpsInspectionStore.sanitizeAndTruncateBody(smallBody)
  assertFalse("Small body should not be truncated", smallTruncated)
  assertEquals(1000, smallResult!!.length)

  val largeBody = "B".repeat(70 * 1024) // 70 KiB
  val (largeResult, largeTruncated) = HttpsInspectionStore.sanitizeAndTruncateBody(largeBody)
  assertTrue("Large body (>64 KiB) must be truncated", largeTruncated)
  assertTrue("Must contain truncation notice", largeResult!!.contains("[TRUNCATED: Exceeded 64 KiB POC limit]"))
 }

 @Test
 fun testTransactionLimitAndOrdering() {
  for (i in 1..120) {
   HttpsInspectionStore.record(
    HttpsTransaction(
     method = "GET",
     url = "https://httpbin.org/get?id=$i",
     host = "httpbin.org"
    )
   )
  }

  val list = HttpsInspectionStore.all()
  assertEquals("Must retain at most 100 transactions", 100, list.size)
  // First item must be the most recent (id=120)
  assertTrue(list[0].url.contains("id=120"))
 }

 @Test
 fun testUrlSecretRedaction() {
  val urlWithQuery = "https://httpbin.org/get?user=test&token=secret-token-value&api_key=mykey123#frag"
  val sanitized = HttpsInspectionStore.sanitizeUrl(urlWithQuery)
  assertTrue("token parameter must be redacted", sanitized.contains("token=[REDACTED]"))
  assertTrue("api_key parameter must be redacted", sanitized.contains("api_key=[REDACTED]"))
  assertTrue("user parameter must be preserved", sanitized.contains("user=test"))
 }
}

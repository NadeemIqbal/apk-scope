package com.nadeem.apkscope.core.network.https

import com.nadeem.apkscope.core.network.DestinationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsInspectionPolicyTest {

 @Test
 fun testDestinationPolicyPreservation() {
  val rfc1918Ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)
  val decision = DestinationPolicy.evaluate(DestinationPolicy.Destination.V4(rfc1918Ip), emptyList())
  assertEquals("RFC1918 destination must be DENIED by DestinationPolicy", DestinationPolicy.Verdict.DENY, decision.verdict)

  val publicIp = byteArrayOf(93, 184.toByte(), 216.toByte(), 34) // example.com
  val publicDecision = DestinationPolicy.evaluate(DestinationPolicy.Destination.V4(publicIp), emptyList())
  assertEquals("Public destination must be ALLOWED", DestinationPolicy.Verdict.ALLOW, publicDecision.verdict)
 }

 @Test
 fun testInspectionTargetFiltering() {
  HttpsInspectionConfig.inspectAllHosts = false
  assertTrue("httpbin.org must be a target", HttpsInspectionConfig.isTargetDestination("httpbin.org"))
  assertTrue("Subdomain of target must match", HttpsInspectionConfig.isTargetDestination("api.httpbin.org"))
  assertTrue("jsonplaceholder.typicode.com must be a target", HttpsInspectionConfig.isTargetDestination("jsonplaceholder.typicode.com"))
  assertFalse("Arbitrary untargeted site must not match", HttpsInspectionConfig.isTargetDestination("bank.example.org"))
  HttpsInspectionConfig.inspectAllHosts = true
  assertTrue("With inspectAllHosts, all sites match", HttpsInspectionConfig.isTargetDestination("bank.example.org"))
 }

 @Test
 fun testDisabledByDefault() {
  HttpsInspectionConfig.reset()
  assertFalse("Inspection must be disabled by default", HttpsInspectionConfig.isEnabled)
  assertTrue("inspectAllHosts must be enabled by default", HttpsInspectionConfig.inspectAllHosts)
 }
}

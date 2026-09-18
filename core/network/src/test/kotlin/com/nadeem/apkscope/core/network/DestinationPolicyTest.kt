package com.nadeem.apkscope.core.network

import org.junit.Assert.*
import org.junit.Test

class DestinationPolicyTest {
 private fun v4(a: Int, b: Int, c: Int, d: Int) = DestinationPolicy.Destination.V4(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

 @Test fun deniesLoopback() = assertDenied(v4(127, 0, 0, 1))
 @Test fun deniesThisNetwork() = assertDenied(v4(0, 1, 2, 3))
 @Test fun deniesLinkLocalIncludingCloudMetadata() = assertDenied(v4(169, 254, 169, 254))
 @Test fun deniesMulticastLowAndHighEnd() { assertDenied(v4(224, 0, 0, 1)); assertDenied(v4(239, 255, 255, 255)) }
 @Test fun deniesLimitedBroadcast() = assertDenied(v4(255, 255, 255, 255))
 @Test fun deniesRfc1918Ten() = assertDenied(v4(10, 1, 2, 3))
 @Test fun deniesRfc1918OneSevenTwo() { assertDenied(v4(172, 16, 0, 1)); assertDenied(v4(172, 31, 255, 255)); assertAllowed(v4(172, 32, 0, 1)) }
 @Test fun deniesRfc1918OneNineTwo() = assertDenied(v4(192, 168, 1, 1))
 @Test fun allowsOrdinaryPublicAddress() = assertAllowed(v4(93, 184, 216, 34))
 @Test fun allowsTestNetReservedRange() = assertAllowed(v4(192, 0, 2, 1)) // sanity check the RFC5737 range test-fixture destinations rely on

 @Test fun deniesDevicesCurrentLocalSubnetWhenConfigured() {
  val subnet = DestinationPolicy.LocalSubnet(byteArrayOf(192.toByte(), 168.toByte(), 50, 1), 24)
  val decision = DestinationPolicy.evaluate(v4(192, 168, 50, 200), listOf(subnet))
  assertEquals(DestinationPolicy.Verdict.DENY, decision.verdict)
 }

 @Test fun localSubnetDenyDoesNotLeakToUnrelatedAddresses() {
  // Uses a non-RFC1918 subnet (TEST-NET-3) so this isolates the local-subnet rule specifically,
  // rather than incidentally passing because 192.168.0.0/16 is already denied unconditionally.
  val subnet = DestinationPolicy.LocalSubnet(byteArrayOf(203.toByte(), 0, 113, 1), 24)
  val decision = DestinationPolicy.evaluate(v4(203, 0, 114, 1), listOf(subnet)) // different /24
  assertEquals(DestinationPolicy.Verdict.ALLOW, decision.verdict)
 }

 @Test fun everyIpv6DestinationIsDenied() {
  // Defense in depth only — ForwardingEngine never routes IPv6 into the TUN at all (see its class doc).
  assertEquals(DestinationPolicy.Verdict.DENY, DestinationPolicy.evaluate(DestinationPolicy.Destination.V6(ByteArray(16))).verdict) // ::
  val loopback = ByteArray(16).also { it[15] = 1 } // ::1
  assertEquals(DestinationPolicy.Verdict.DENY, DestinationPolicy.evaluate(DestinationPolicy.Destination.V6(loopback)).verdict)
  val linkLocal = ByteArray(16).also { it[0] = 0xFE.toByte(); it[1] = 0x80.toByte() } // fe80::
  assertEquals(DestinationPolicy.Verdict.DENY, DestinationPolicy.evaluate(DestinationPolicy.Destination.V6(linkLocal)).verdict)
  val uniqueLocal = ByteArray(16).also { it[0] = 0xFD.toByte() } // fd00::
  assertEquals(DestinationPolicy.Verdict.DENY, DestinationPolicy.evaluate(DestinationPolicy.Destination.V6(uniqueLocal)).verdict)
  val globalUnicast = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01 } // 2001:: (a real global range)
  assertEquals(DestinationPolicy.Verdict.DENY, DestinationPolicy.evaluate(DestinationPolicy.Destination.V6(globalUnicast)).verdict)
 }

 private fun assertDenied(d: DestinationPolicy.Destination) = assertEquals(DestinationPolicy.Verdict.DENY, DestinationPolicy.evaluate(d).verdict)
 private fun assertAllowed(d: DestinationPolicy.Destination) = assertEquals(DestinationPolicy.Verdict.ALLOW, DestinationPolicy.evaluate(d).verdict)
}

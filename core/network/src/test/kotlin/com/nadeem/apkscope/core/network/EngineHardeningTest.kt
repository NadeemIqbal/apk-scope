package com.nadeem.apkscope.core.network

import com.nadeem.apkscope.core.model.NetworkObservation

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class EngineHardeningTest {
 private val sinks = mutableListOf<FakeTunSink>()
 private fun sink(limits: EngineLimits = EngineLimits.DEFAULT): FakeTunSink = FakeTunSink(limits).also { sinks.add(it) }

 @After fun tearDown() { sinks.forEach { it.closeSelector() } }

 // ---- Destination policy is consulted before any real socket is opened ----

 @Test fun tcpSynToLoopbackIsDeniedAndNeverOpensAConnection() {
  val sink = sink()
  val proxy = TcpProxy(sink)
  val packet = TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 40000, TestPackets.LOOPBACK, 22)
  proxy.onOutbound(packet, 0, 20, packet.size)
  assertEquals(0, proxy.activeCount())
  assertTrue(sink.observations.any { it is NetworkObservation.ConnectionFailed && it.reason == NetworkObservation.FailureReason.POLICY_DENIED })
 }

 @Test fun tcpSynToCloudMetadataAddressIsDenied() {
  val sink = sink()
  val proxy = TcpProxy(sink)
  val packet = TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 40000, TestPackets.LINK_LOCAL_METADATA, 80)
  proxy.onOutbound(packet, 0, 20, packet.size)
  assertEquals(0, proxy.activeCount())
 }

 @Test fun tcpSynToTestNetPublicRangeIsAllowedAndOpensAConnection() {
  val sink = sink()
  val proxy = TcpProxy(sink)
  val packet = TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 40000, TestPackets.TEST_NET, 443)
  proxy.onOutbound(packet, 0, 20, packet.size)
  assertEquals(1, proxy.activeCount())
  assertTrue(sink.observations.any { it is NetworkObservation.ConnectionOpened })
  proxy.closeAll()
 }

 @Test fun udpToPrivateRfc1918AddressIsDenied() {
  val sink = sink()
  val nat = UdpNat(sink)
  val packet = TestPackets.udp(TestPackets.CLIENT_TUN_ADDR, 41000, TestPackets.PRIVATE_192, 53, byteArrayOf(1, 2, 3))
  nat.onOutbound(packet, 0, 20, packet.size)
  assertEquals(0, nat.activeCount())
  assertTrue(sink.observations.any { it is NetworkObservation.ConnectionFailed && it.reason == NetworkObservation.FailureReason.POLICY_DENIED })
 }

 @Test fun udpToDevicesOwnCurrentLocalSubnetIsDenied() {
  val subnet = DestinationPolicy.LocalSubnet(byteArrayOf(203.toByte(), 0, 113, 1), 24) // pretend the phone is on 203.0.113.0/24 (TEST-NET-3)
  val sink = FakeTunSink(EngineLimits.DEFAULT, listOf(subnet)).also { sinks.add(it) }
  val nat = UdpNat(sink)
  val packet = TestPackets.udp(TestPackets.CLIENT_TUN_ADDR, 41000, byteArrayOf(203.toByte(), 0, 113, 55), 53, byteArrayOf(1))
  nat.onOutbound(packet, 0, 20, packet.size)
  assertEquals(0, nat.activeCount())
 }

 // ---- Resource limits ----

 @Test fun tcpConnectionsBeyondTheLimitAreRejectedWithoutOpeningASocket() {
  val sink = sink(EngineLimits(maxConcurrentTcpConnections = 3))
  val proxy = TcpProxy(sink)
  repeat(5) { i ->
   val packet = TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 40000 + i, TestPackets.TEST_NET, 443)
   proxy.onOutbound(packet, 0, 20, packet.size)
  }
  assertEquals(3, proxy.activeCount())
  assertTrue(sink.observations.any { it is NetworkObservation.ResourceLimitExceeded && it.limitName == "maxConcurrentTcpConnections" })
  proxy.closeAll()
 }

 @Test fun udpSessionsBeyondTheLimitAreRejected() {
  val sink = sink(EngineLimits(maxConcurrentUdpSessions = 2))
  val nat = UdpNat(sink)
  repeat(5) { i ->
   val packet = TestPackets.udp(TestPackets.CLIENT_TUN_ADDR, 41000 + i, TestPackets.TEST_NET, 53, byteArrayOf(1))
   nat.onOutbound(packet, 0, 20, packet.size)
  }
  assertEquals(2, nat.activeCount())
  assertTrue(sink.observations.any { it is NetworkObservation.ResourceLimitExceeded && it.limitName == "maxConcurrentUdpSessions" })
  nat.closeAll()
 }

 @Test fun engineWideBufferBudgetIsEnforceable() {
  val sink = sink(EngineLimits(maxTotalBufferedBytes = 100))
  assertTrue(sink.tryReserveGlobalBuffer(60))
  assertTrue(sink.tryReserveGlobalBuffer(30))
  assertFalse(sink.tryReserveGlobalBuffer(20)) // 60+30+20=110 > 100
  sink.releaseGlobalBuffer(30)
  assertTrue(sink.tryReserveGlobalBuffer(20)) // now 60+20=80 <= 100
 }

 // ---- Hostile/malformed input is dropped, not crashed on ----

 @Test fun tcpSegmentWithInvalidDataOffsetIsDropped() {
  val sink = sink()
  val proxy = TcpProxy(sink)
  val packet = TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 40000, TestPackets.TEST_NET, 443)
  packet[20 + 12] = 0x30 // data offset = 3 words = 12 bytes, below the 20-byte minimum
  proxy.onOutbound(packet, 0, 20, packet.size)
  assertEquals(0, proxy.activeCount())
 }

 @Test fun tcpSegmentWithCorruptedChecksumIsDropped() {
  val sink = sink()
  val proxy = TcpProxy(sink)
  val packet = TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 40000, TestPackets.TEST_NET, 443)
  packet[20 + 16] = (packet[20 + 16] + 1).toByte() // flip a bit in the TCP checksum field
  proxy.onOutbound(packet, 0, 20, packet.size)
  assertEquals(0, proxy.activeCount())
 }

 @Test fun udpDatagramWithCorruptedChecksumIsDropped() {
  val sink = sink()
  val nat = UdpNat(sink)
  val packet = TestPackets.udp(TestPackets.CLIENT_TUN_ADDR, 41000, TestPackets.TEST_NET, 53, byteArrayOf(1, 2, 3))
  packet[20 + 6] = (packet[20 + 6] + 1).toByte() // flip a bit in the UDP checksum field
  nat.onOutbound(packet, 0, 20, packet.size)
  assertEquals(0, nat.activeCount())
 }

 @Test fun udpZeroLengthChecksumOfZeroMeansNoChecksumAndIsStillForwarded() {
  // RFC 768: an all-zero UDP checksum field means "no checksum transmitted" — must not be treated as corrupt.
  val sink = sink()
  val nat = UdpNat(sink)
  val packet = TestPackets.udp(TestPackets.CLIENT_TUN_ADDR, 41000, TestPackets.TEST_NET, 53, byteArrayOf(1, 2, 3))
  packet[20 + 6] = 0; packet[20 + 7] = 0 // zero out the checksum field entirely
  nat.onOutbound(packet, 0, 20, packet.size)
  assertEquals(1, nat.activeCount())
  nat.closeAll()
 }

 @Test fun truncatedUdpHeaderIsDroppedNotCrashed() {
  val sink = sink()
  val nat = UdpNat(sink)
  val fullPacket = TestPackets.udp(TestPackets.CLIENT_TUN_ADDR, 41000, TestPackets.TEST_NET, 53, byteArrayOf(1, 2, 3, 4, 5))
  val truncated = fullPacket.copyOf(22) // IP header (20) + 2 bytes of an 8-byte UDP header
  nat.onOutbound(truncated, 0, 20, truncated.size)
  assertEquals(0, nat.activeCount())
 }

 @Test fun ipTotalLengthLyingAboutPacketSizeIsRejectedByClassify() {
  val packet = TestPackets.udp(TestPackets.CLIENT_TUN_ADDR, 41000, TestPackets.TEST_NET, 53, byteArrayOf(1, 2, 3))
  // Claim a total length far larger than what was actually read.
  packet[2] = 0x7F; packet[3] = 0xFF.toByte()
  val result = PacketValidation.classify(packet, packet.size)
  assertEquals(PacketValidation.Result.Malformed, result)
 }

 @Test fun zeroLengthPacketAndGarbageAreClassifiedWithoutThrowing() {
  assertEquals(PacketValidation.Result.Malformed, PacketValidation.classify(ByteArray(0), 0))
  assertEquals(PacketValidation.Result.Malformed, PacketValidation.classify(ByteArray(19), 19))
 }

 // ---- Cleanup: after closeAll(), nothing is left open ----

 @Test fun closeAllLeavesZeroActiveTcpConnections() {
  val sink = sink()
  val proxy = TcpProxy(sink)
  repeat(3) { i -> proxy.onOutbound(TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 42000 + i, TestPackets.TEST_NET, 443), 0, 20, 40) }
  assertEquals(3, proxy.activeCount())
  proxy.closeAll()
  assertEquals(0, proxy.activeCount())
 }

 @Test fun closeAllLeavesZeroActiveUdpSessions() {
  val sink = sink()
  val nat = UdpNat(sink)
  repeat(3) { i ->
   val packet = TestPackets.udp(TestPackets.CLIENT_TUN_ADDR, 43000 + i, TestPackets.TEST_NET, 53, byteArrayOf(1))
   nat.onOutbound(packet, 0, 20, packet.size)
  }
  assertEquals(3, nat.activeCount())
  nat.closeAll()
  assertEquals(0, nat.activeCount())
 }
}

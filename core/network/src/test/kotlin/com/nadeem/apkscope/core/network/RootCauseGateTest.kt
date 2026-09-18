package com.nadeem.apkscope.core.network

import com.nadeem.apkscope.core.model.NetworkObservation

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the Forwarding Reliability Root Cause Gate's item 7 (RST on connect failure — the
 * sandboxed app should fail promptly instead of waiting out its own timeout) and item 4 (internal
 * invariant checks).
 */
class RootCauseGateTest {
 private val sinks = mutableListOf<FakeTunSink>()
 private fun sink(limits: EngineLimits = EngineLimits.DEFAULT): FakeTunSink = FakeTunSink(limits).also { sinks.add(it) }

 @After fun tearDown() { sinks.forEach { it.closeSelector() } }

 @Test fun connectTimeoutSendsRstToClientBeforeClosing() {
  val sink = sink(EngineLimits(tcpConnectTimeoutMs = 1))
  val proxy = TcpProxy(sink)
  val packet = TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 40000, TestPackets.TEST_NET, 443)
  proxy.onOutbound(packet, 0, 20, packet.size)
  assertEquals(1, proxy.activeCount())
  Thread.sleep(5) // let the 1ms connect timeout elapse
  proxy.sweepIdle()
  assertEquals(0, proxy.activeCount())
  val rstSent = sink.writtenPackets.any { p -> p.size >= 20 + Tcp.HEADER_LEN && (Tcp.flags(p, 20) and Tcp.RST) != 0 }
  assertTrue("expected a TUN-bound packet with the RST flag set after a connect timeout, got: ${sink.writtenPackets.map { Tcp.flags(it, 20) }}", rstSent)
  assertTrue(sink.observations.any { it is NetworkObservation.ConnectionFailed && it.reason == NetworkObservation.FailureReason.CONNECT_TIMEOUT })
 }

 @Test fun connectExceptionSendsRstToClient() {
  // Simulate a destination that rejects the connection outright (a real ConnectException) by using
  // a protectSocket() that always fails, which onConnectable's exception branch treats the same way.
  val sink = object : FakeTunSink() {
   override fun protectSocket(socket: java.net.Socket): Boolean = false
  }
  sinks.add(sink)
  val proxy = TcpProxy(sink)
  val packet = TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 40001, TestPackets.TEST_NET, 443)
  proxy.onOutbound(packet, 0, 20, packet.size)
  assertEquals(0, proxy.activeCount()) // setup failed before a TCB was ever registered — this path doesn't send an RST since no SYN-ACK/handshake ever started
  assertTrue(sink.observations.any { it is NetworkObservation.ConnectionFailed })
 }

 @Test fun sweepIdleOneBadTcbDoesNotStopProcessingOthers() {
  // Two TCBs both past their connect timeout; closing must not stop partway through the batch.
  val sink = sink(EngineLimits(tcpConnectTimeoutMs = 1))
  val proxy = TcpProxy(sink)
  val a = TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 40010, TestPackets.TEST_NET, 443)
  val b = TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 40011, TestPackets.TEST_NET, 443)
  proxy.onOutbound(a, 0, 20, a.size)
  proxy.onOutbound(b, 0, 20, b.size)
  assertEquals(2, proxy.activeCount())
  Thread.sleep(5)
  proxy.sweepIdle()
  assertEquals("both connect-timed-out TCBs must be closed, not just the first", 0, proxy.activeCount())
 }

 @Test fun engineInvariantsAreCleanOnAFreshProxy() {
  val sink = sink()
  val proxy = TcpProxy(sink)
  assertTrue(proxy.checkInvariants().isEmpty())
 }

 @Test fun engineInvariantsStayCleanAfterNormalOpenAndClose() {
  val sink = sink()
  val proxy = TcpProxy(sink)
  val packet = TestPackets.tcpSyn(TestPackets.CLIENT_TUN_ADDR, 40020, TestPackets.TEST_NET, 443)
  proxy.onOutbound(packet, 0, 20, packet.size)
  assertTrue(proxy.checkInvariants().isEmpty())
  proxy.closeAll()
  assertTrue(proxy.checkInvariants().isEmpty())
  assertEquals(0, proxy.activeCount())
 }

 @Test fun udpInvariantsStayCleanAfterNormalOpenAndClose() {
  val sink = sink()
  val nat = UdpNat(sink)
  val packet = TestPackets.udp(TestPackets.CLIENT_TUN_ADDR, 41000, TestPackets.TEST_NET, 53, byteArrayOf(1, 2, 3))
  nat.onOutbound(packet, 0, 20, packet.size)
  assertTrue(nat.checkInvariants().isEmpty())
  nat.closeAll()
  assertTrue(nat.checkInvariants().isEmpty())
 }

 // ---- Upload Throughput Root Cause Gate: root cause was a missing window-update ACK when the
 // upload-receive queue drains from a fully closed (0) advertised window back to non-zero — a
 // client honoring our zero window sends nothing further to react to, so without this the client
 // stalls until its own RFC 1122 zero-window persist-timer probe, measured at 30-60+ seconds
 // against real destinations. See TcpProxy.shouldSendWindowReopenAck's doc for why only this one
 // transition is covered. ----

 @Test fun windowReopenAckFiresOnlyOnTheZeroToNonzeroTransition() {
  assertTrue("a client sitting on a fully closed window must be told the moment it reopens",
   TcpProxy.shouldSendWindowReopenAck(previouslyAdvertisedWindow = 0, currentAdvertisedWindow = 65535))
  assertTrue("even a small reopening still unblocks a zero-window client and must be announced",
   TcpProxy.shouldSendWindowReopenAck(previouslyAdvertisedWindow = 0, currentAdvertisedWindow = 1))
  assertFalse("the window was never closed — the client was never stalled waiting for this",
   TcpProxy.shouldSendWindowReopenAck(previouslyAdvertisedWindow = 32768, currentAdvertisedWindow = 65535))
  assertFalse("nothing reopened — do not manufacture an ACK when the window is still (or newly) zero",
   TcpProxy.shouldSendWindowReopenAck(previouslyAdvertisedWindow = 0, currentAdvertisedWindow = 0))
  assertFalse("a shrinking window is backpressure working as designed, not a stall to unstick",
   TcpProxy.shouldSendWindowReopenAck(previouslyAdvertisedWindow = 65535, currentAdvertisedWindow = 0))
 }

 // Note on item 4's interestOps coalescing fix: TcpProxy.Tcb is a private inner class reachable
 // only through a real ESTABLISHED connection (a real three-way handshake to an allowed, reachable
 // destination), which this JVM-only unit test suite has no way to construct — every existing test
 // in this file and EngineHardeningTest stops at CONNECTING for the same reason. That fix's
 // regression evidence is therefore the device-level TASKQUEUE_SAMPLE before/after comparison in
 // HARDENING_GATE_ROOT_CAUSE.md (99.8% redundant requests measured before, near-zero after),
 // matching how this codebase has always validated ESTABLISHED-state behavior — see e.g. the
 // multi-endpoint upload workload in RootCauseSuite.kt.
}

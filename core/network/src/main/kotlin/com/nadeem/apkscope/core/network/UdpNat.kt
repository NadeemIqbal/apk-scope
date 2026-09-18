package com.nadeem.apkscope.core.network

import com.nadeem.apkscope.core.model.NetworkObservation

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Userspace NAT for UDP over the forwarding TUN.
 *
 * The TUN's own configured address is the *only* local address any sandboxed app can ever use as
 * a source (see [ForwardingEngine]), so a flow is fully identified by (localPort, remoteIp,
 * remotePort) — no destination-address dimension is needed on our side of the table.
 *
 * One real, protected [DatagramChannel] per flow relays datagrams to the actual remote host;
 * replies are written back into the TUN with source/destination swapped so the sandboxed app's
 * own kernel stack delivers them to the socket that is expecting them. Every new flow is checked
 * against [DestinationPolicy] and [EngineLimits.maxConcurrentUdpSessions] before a real socket is
 * ever opened. Port-53 traffic is additionally parsed (best-effort, never affecting forwarding) to
 * emit [NetworkObservation.DnsQuery]/[NetworkObservation.DnsResponse].
 */
class UdpNat(private val sink: TunSink) {
 private data class Key(val remoteIp: Int, val remotePort: Int, val localPort: Int)

 private inner class Session(val key: Key, val channel: DatagramChannel, val remoteIpBytes: ByteArray) {
  @Volatile var lastActivity = System.currentTimeMillis()
  val startTime: Instant = Instant.now()
  var uploadedBytes = 0L
  var downloadedBytes = 0L
  private val readBuf = ByteBuffer.allocate(MAX_DATAGRAM)

  val handler = object : SelectHandler {
   override fun onReadable() {
    readBuf.clear()
    val n = try { channel.read(readBuf) } catch (e: Exception) { close("read failed: $e"); return }
    if (n < 0) { close("remote closed"); return }
    lastActivity = System.currentTimeMillis()
    downloadedBytes += n
    if (key.remotePort == 53) DnsMessage.parseResponse(readBuf.array(), 0, n)?.let { r ->
     sink.observe(NetworkObservation.DnsResponse(Instant.now(), r.transactionId, r.hostname, r.addresses, key.localPort))
    }
    val packet = ByteArray(20 + Udp.HEADER_LEN + n)
    Ipv4.writeHeader(packet, 0, Ipv4.PROTO_UDP, remoteIpBytes, sink.tunAddress, Udp.HEADER_LEN + n, idSeq.getAndIncrement() and 0xFFFF)
    Udp.writeDatagram(packet, 20, key.remotePort, key.localPort, remoteIpBytes, sink.tunAddress, readBuf.array(), 0, n)
    sink.writeToTun(packet, packet.size)
   }
  }

  fun close(reason: String) {
   ConnDiag.event(connectionIdOf(key), "UDP_SESSION_CLOSED", "reason=$reason")
   if (sessions.remove(key, this)) {
    activeSessions.decrementAndGet()
    sink.observe(
     NetworkObservation.ConnectionClosed(
      Instant.now(), connectionIdOf(key), NetworkObservation.Protocol.UDP, describeV4(remoteIpBytes), key.remotePort,
      startTime, Instant.now(), uploadedBytes, downloadedBytes,
     )
    )
   }
   try { channel.close() } catch (_: Exception) {}
  }
 }

 private val sessions = ConcurrentHashMap<Key, Session>()
 private val activeSessions = AtomicLong(0)
 private val idSeq = java.util.concurrent.atomic.AtomicInteger(0)

 /**
  * [ipOff] points at the start of the IPv4 header; [udpOff] at the start of the UDP header within
  * [buf]; [length] is the number of bytes actually read from the TUN starting at [ipOff] — the
  * bound to validate against, since [buf] itself may be a larger, reused read buffer.
  *
  * This does not assume [ForwardingEngine.dispatch] already checked that a full UDP header fits —
  * it is re-checked here too, so this method is safe to call directly (as the test suite does).
  */
 fun onOutbound(buf: ByteArray, ipOff: Int, udpOff: Int, length: Int) {
  if (ipOff < 0 || udpOff < ipOff + 20 || udpOff + Udp.HEADER_LEN > ipOff + length || udpOff + Udp.HEADER_LEN > buf.size) return // not even a full IP+UDP header present
  val remoteIpBytes = Ipv4.dstIp(buf, ipOff)
  val remoteIp = ipToInt(remoteIpBytes)
  val remotePort = Udp.dstPort(buf, udpOff)
  val localPort = Udp.srcPort(buf, udpOff)
  val udpLen = Udp.length(buf, udpOff)
  val payloadLen = udpLen - Udp.HEADER_LEN
  if (udpLen < Udp.HEADER_LEN || payloadLen < 0 || udpOff + udpLen > ipOff + length) return // truncated/malformed, drop
  if (!Udp.checksumValid(buf, udpOff, udpLen, Ipv4.srcIp(buf, ipOff), remoteIpBytes)) {
   sink.onEvidence("UdpNat.checksum", "FAIL", "port=$localPort -> $remotePort"); return
  }
  val key = Key(remoteIp, remotePort, localPort)
  var session = sessions[key]
  if (session == null) {
   val decision = DestinationPolicy.evaluate(DestinationPolicy.Destination.V4(remoteIpBytes), sink.localSubnets)
   if (decision.verdict == DestinationPolicy.Verdict.DENY) {
    sink.observe(NetworkObservation.ConnectionFailed(Instant.now(), NetworkObservation.Protocol.UDP, describeV4(remoteIpBytes), remotePort, NetworkObservation.FailureReason.POLICY_DENIED, decision.reason))
    return
   }
   if (activeSessions.get() >= sink.limits.maxConcurrentUdpSessions) {
    sink.observe(NetworkObservation.ResourceLimitExceeded(Instant.now(), "maxConcurrentUdpSessions", activeSessions.get(), sink.limits.maxConcurrentUdpSessions.toLong()))
    return
   }
   session = try {
    val channel = DatagramChannel.open()
    channel.configureBlocking(false)
    if (!sink.protectDatagram(channel.socket())) {
     sink.observe(NetworkObservation.ConnectionFailed(Instant.now(), NetworkObservation.Protocol.UDP, describeV4(remoteIpBytes), remotePort, NetworkObservation.FailureReason.OTHER, "VpnService.protect refused"))
     return
    }
    channel.connect(InetSocketAddress(InetAddress.getByAddress(remoteIpBytes), remotePort))
    val s = Session(key, channel, remoteIpBytes)
    // See TunSink.runOnSelectorThread's doc: registering directly from this (reader) thread,
    // even followed by wakeup(), measurably raced the selector's own blocking select() under
    // concurrent load — this is the same root cause found and fixed for TcpProxy.
    sink.runOnSelectorThread { channel.register(sink.selector, SelectionKey.OP_READ, s.handler) }
    sessions[key] = s
    activeSessions.incrementAndGet()
    ConnDiag.event(connectionIdOf(key), "UDP_SESSION_OPENED", "dst=${describeV4(remoteIpBytes)}:$remotePort")
    sink.observe(NetworkObservation.ConnectionOpened(Instant.now(), connectionIdOf(key), NetworkObservation.Protocol.UDP, describeV4(remoteIpBytes), remotePort))
    s
   } catch (e: Exception) {
    sink.observe(NetworkObservation.ConnectionFailed(Instant.now(), NetworkObservation.Protocol.UDP, describeV4(remoteIpBytes), remotePort, NetworkObservation.FailureReason.OTHER, "${e.javaClass.simpleName}: ${e.message}"))
    return
   }
  }
  session.lastActivity = System.currentTimeMillis()
  if (remotePort == 53) DnsMessage.parseQuery(buf, udpOff + Udp.HEADER_LEN, payloadLen)?.let { q ->
   sink.observe(NetworkObservation.DnsQuery(Instant.now(), q.transactionId, q.hostname, localPort))
  }
  try {
   session.uploadedBytes += payloadLen
   session.channel.write(ByteBuffer.wrap(buf, udpOff + Udp.HEADER_LEN, payloadLen))
  } catch (e: Exception) {
   session.close("write failed: $e")
  }
 }

 /** Drops flows idle longer than [EngineLimits.udpIdleTimeoutMs]; call periodically from the engine's housekeeping tick. Each close is independently guarded — see [TcpProxy.sweepIdle]'s doc for why. */
 fun sweepIdle() {
  val cutoff = System.currentTimeMillis() - sink.limits.udpIdleTimeoutMs
  sessions.values.filter { it.lastActivity < cutoff }.forEach { session ->
   try { session.close("idle") } catch (e: Exception) { sink.onEvidence("UdpNat.sweepIdle", "FAIL", "${e.javaClass.simpleName}: ${e.message}") }
  }
 }

 fun activeCount(): Int = sessions.size

 /** Closes every open flow's real socket — call when the engine is shutting down, or these leak until GC. Each close is independently guarded, same reasoning as [sweepIdle]. */
 fun closeAll() {
  sessions.values.toList().forEach { session ->
   try { session.close("engine stopping") } catch (e: Exception) { sink.onEvidence("UdpNat.closeAll", "FAIL", "${e.javaClass.simpleName}: ${e.message}") }
  }
 }

 /** Item 4 of the root-cause gate: no session exists without its channel, no stale entry for something already gone. */
 fun checkInvariants(): List<String> {
  val violations = ArrayList<String>()
  if (sessions.size.toLong() != activeSessions.get()) violations += "activeSessions counter (${activeSessions.get()}) != sessions.size (${sessions.size})"
  return violations
 }

 companion object {
  private const val MAX_DATAGRAM = 65535
  private fun ipToInt(ip: ByteArray): Int =
   ((ip[0].toInt() and 0xFF) shl 24) or ((ip[1].toInt() and 0xFF) shl 16) or ((ip[2].toInt() and 0xFF) shl 8) or (ip[3].toInt() and 0xFF)
  private fun describeV4(ip: ByteArray) = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
  private fun connectionIdOf(key: Key): Long = (key.remoteIp.toLong() shl 32) or ((key.remotePort.toLong() shl 16) or key.localPort.toLong())
 }
}

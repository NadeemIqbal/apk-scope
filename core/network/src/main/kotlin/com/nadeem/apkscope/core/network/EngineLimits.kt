package com.nadeem.apkscope.core.network

/**
 * Every bound the forwarding engine enforces against a sandboxed app trying to exhaust it —
 * unlimited connections, unlimited buffering, or a stuck handshake that never gets cleaned up. No
 * map, queue, buffer, thread, or socket in [UdpNat]/[TcpProxy]/[ForwardingEngine] is meant to be
 * unbounded; if one turns out to be, that is a bug against this contract.
 *
 * Defaults below are conservative starting points for a single sandboxed app under interactive use
 * (a browser-like app can easily hold 20-30 TCP connections at once for one page load), not a
 * measured production tuning — revisit once real app traffic profiles are observed.
 */
data class EngineLimits(
 /** Hard cap on simultaneously open real TCP connections. Exceeding it refuses the new SYN (silently, matching an unresponsive host — no RST, so no probing signal is handed to the sandboxed app). */
 val maxConcurrentTcpConnections: Int = 200,
 /** Hard cap on simultaneously open real UDP flows. */
 val maxConcurrentUdpSessions: Int = 200,
 /** How long a real TCP `connect()` may stay pending before the half-open attempt is aborted. */
 val tcpConnectTimeoutMs: Long = 15_000L,
 /** How long an established TCP connection may sit with no traffic before it is force-closed. */
 val tcpIdleTimeoutMs: Long = 5 * 60_000L,
 /** How long a UDP flow may sit with no traffic before its real socket is closed. */
 val udpIdleTimeoutMs: Long = 60_000L,
 /** Per-connection cap on bytes buffered in either direction (window-limited outbound data, or backpressured inbound data) before that connection is throttled. */
 val maxQueuedBytesPerConnection: Int = 512 * 1024,
 /** Cap on bytes buffered across *all* connections combined — the actual exhaustion backstop; a sandboxed app cannot get past this by opening many connections that each stay under the per-connection cap. */
 val maxTotalBufferedBytes: Long = 32L * 1024 * 1024,
 /** Any single packet read from the TUN larger than this is dropped unparsed. Comfortably above the 1500-byte MTU the TUN is configured with, to allow margin without accepting arbitrarily large reads. */
 val maxAcceptedPacketBytes: Int = 4096,
) {
 companion object {
  val DEFAULT = EngineLimits()
 }
}

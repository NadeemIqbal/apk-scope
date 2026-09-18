package com.nadeem.apkscope.core.network

import com.nadeem.apkscope.core.model.NetworkObservation

import java.net.DatagramSocket
import java.net.Socket
import java.nio.channels.Selector

/**
 * Milestone 9 (userspace traffic ownership verification) — the outcome of a single
 * [TunSink.resolveConnectionOwnerUid] attempt. A sealed result rather than a bare `Int?` so the
 * "why" of a failed/ambiguous lookup ([Unknown.reason]) is a first-class, distinct concept from
 * the resolved value itself — never conflated, and never silently coerced into "no owner" or "a
 * match".
 */
sealed class OwnerUidLookup {
 data class Resolved(val uid: Int) : OwnerUidLookup()
 data class Unknown(val reason: String) : OwnerUidLookup()
}

/** What [UdpNat] and [TcpProxy] need from the owning [ForwardingEngine]/VpnService, without depending on Android's Service class directly. */
interface TunSink {
 val selector: Selector
 /** The forwarding TUN's own configured address — the sole "local" IP for every session (see [UdpNat]/[TcpProxy] docs). */
 val tunAddress: ByteArray
 val limits: EngineLimits
 /** The device's own current local subnet(s) to deny under [DestinationPolicy], when known — see [ForwardingEngine]'s construction of this list. */
 val localSubnets: List<DestinationPolicy.LocalSubnet>
 /** Port of the local HTTPS inspection engine, or null if inspection is inactive. */
 val httpsInspectionPort: Int? get() = null
 /** In-memory authentication token for the local HTTPS inspection engine. */
 val httpsInspectionAuthToken: ByteArray? get() = null
 /** Writes one complete IP packet back into the TUN so the sandboxed app's kernel stack receives it. Thread-safe. */
 fun writeToTun(packet: ByteArray, length: Int)
 /** VpnService.protect() — keeps the real forwarding socket off the VPN's own route so it doesn't loop back into itself. */
 fun protectSocket(socket: Socket): Boolean
 fun protectDatagram(socket: DatagramSocket): Boolean
 fun onEvidence(test: String, result: String, detail: String = "")
 /** Publishes a sandbox-observable network event — see [NetworkObservation]. */
 fun observe(observation: NetworkObservation)
 /**
  * Claims [bytes] against the engine-wide buffered-data budget ([EngineLimits.maxTotalBufferedBytes]);
  * returns false (claiming nothing) if that would exceed it. Every successful claim must be matched
  * by exactly one [releaseGlobalBuffer] call for the same byte count once the data is no longer held.
  */
 fun tryReserveGlobalBuffer(bytes: Int): Boolean
 fun releaseGlobalBuffer(bytes: Int)
 /** Current engine-wide buffered-byte total — diagnostic read only (Upload Throughput Root Cause Gate's per-connection sampling), never used for admission decisions (that's [tryReserveGlobalBuffer]). */
 fun currentGlobalBufferedBytes(): Long

 /**
  * Milestone 9 (userspace traffic ownership verification) — resolves the *real* owning UID of a
  * TCP flow via `ConnectivityManager.getConnectionOwnerUid`, called by [TcpProxy] **exactly once
  * per connection**, at the point its preamble is sent — never per packet, never retried beyond
  * this method's own single bounded internal retry (see [ForwardingEngine]'s implementation).
  *
  * [localIp]/[localPort]/[remoteIp]/[remotePort] must be the connection's *original* 4-tuple exactly
  * as observed on the inbound TUN packet — i.e. the values [TcpProxy] itself already tracks for the
  * real flow (its `Tcb.srcIpBytes`/`key`) — never the loopback address/port of the local
  * HTTPS-inspection server [TcpProxy] separately connects to; querying that loopback socket would
  * resolve the *inspector's own* UID, not the target app's, and must not be mistaken for it.
  *
  * [localIp] is expected to equal this tunnel's own assigned address ([tunAddress]): Android routes
  * an app's traffic into a `VpnService`'s TUN by having outbound packets on that route carry the
  * tunnel's own assigned address as their IP source (standard route-selected source-address
  * assignment, not a NAT rewrite the app is unaware of), so the OS's connection-owner table is keyed
  * on that pair, not the app's real network-facing IP. This is passed explicitly (read from the
  * actual captured packet) rather than assumed/substituted by the implementation, so a violation of
  * that expectation — e.g. a future dual-stack/multi-address TUN configuration — is *observable*
  * (see [ForwardingEngine]'s implementation, which logs a mismatch) instead of silently producing a
  * wrong lookup.
  */
 fun resolveConnectionOwnerUid(localIp: ByteArray, localPort: Int, remoteIp: ByteArray, remotePort: Int): OwnerUidLookup =
  OwnerUidLookup.Unknown("resolveConnectionOwnerUid not implemented by this TunSink")

 /**
  * Milestone 9 (userspace traffic ownership verification) — resolves (and caches, on success only)
  * the UID [targetPackage] currently maps to in this profile. Retried on every call until it
  * succeeds rather than resolved once at construction time: the target may not be installed yet
  * when the VPN is first established (Phase 9.1's Prepare-Sequence-install-ordering finding — the
  * tunnel is established before the target package exists in this profile), so a one-time
  * resolution attempt would permanently miss it. Once resolved, the same profile-scoped package's
  * UID cannot legitimately change for the rest of a session, so a successful result is cached
  * rather than re-queried — this is a `PackageManager` call, not a per-packet cost, and must never
  * become one.
  */
 fun resolveTargetUid(targetPackage: String): Int? = null

 /**
  * Root-caused during the Forwarding Reliability Root Cause Gate: calling `channel.register()`
  * directly from the TUN reader thread while the selector thread is blocked in `select()` — even
  * immediately followed by `selector.wakeup()` — measurably does not always cause that registration
  * to take effect before the selector's *own* next natural timeout, under concurrent/rapid bursts
  * of new connections specifically (confirmed via [ConnDiag] event timestamps: the entire delay
  * sat between `connect()` being called and `OP_CONNECT_REGISTERED`, in exact multiples of the
  * selector's 1000ms poll timeout — 1x, 2x, 3x — while a control test of the same 20 concurrent
  * raw connections bypassing this engine entirely completed in 150-300ms with no such pattern).
  *
  * The fix is the standard one for multi-threaded NIO: every `Selector.register()` call now goes
  * through this method instead of being called directly from whatever thread has the new channel —
  * [task] runs on the selector thread itself (immediately, if already called from that thread;
  * otherwise queued and the selector woken), so a registration is never racing a concurrently
  * blocked `select()` from a different thread. [task] should perform the registration itself
  * (`channel.register(selector, ops, attachment)`) and store the resulting key wherever it's
  * needed — there is no return value because the caller does not block waiting for one.
  */
 fun runOnSelectorThread(task: () -> Unit)
}

package com.nadeem.apkscope.core.model

import java.time.Instant

/**
 * Transport-independent record of what the sandboxed app's network activity looked like — the
 * sandbox's actual audit feed, distinct from the harness's own test-outcome log. Emitted by the
 * forwarding engine (`core:network`) as things happen; a [NetworkObservationSink] decides what to
 * do with them (log, persist, surface to UI). Promoted unchanged from the spike's
 * `com.nadeem.apkscope.spike.net.NetworkObservation` as part of the v0.1 production promotion — see
 * `UPLOAD_THROUGHPUT_ROOT_CAUSE.md` and `HARDENING_GATE_ROOT_CAUSE.md` for the gates that
 * validated the engine producing these.
 *
 * Hostname association is deliberately never inferred from a destination IP alone: multiple
 * domains can resolve to the same address, and a connection can be opened to an IP the sandbox
 * never saw a DNS answer for (hardcoded IP, cached OS-level resolution, DNS-over-HTTPS bypassing
 * this UDP-53 observation entirely). [DnsQuery]/[DnsResponse] are reported as their own explicit,
 * separately-timestamped events; correlating a [ConnectionOpened] to a hostname, if desired, is a
 * downstream best-effort join on timestamp + destination address, not something this contract
 * asserts as certain. This is also this project's `ObservedBehavior`/`AndroidEvidence` boundary in
 * practice — see [com.nadeem.apkscope.core.model.evidence] — a [NetworkObservation] is what the engine
 * itself directly saw on the wire, not what Android's own DevicePolicyManager separately logged.
 */
sealed interface NetworkObservation {
 val timestamp: Instant

 enum class Protocol { TCP, UDP }

 enum class FailureReason {
  CONNECT_TIMEOUT, CONNECT_REFUSED, CONNECT_UNREACHABLE, POLICY_DENIED,
  RESOURCE_LIMIT, MALFORMED_PACKET, RESET, OTHER,
 }

 data class ConnectionOpened(
  override val timestamp: Instant,
  val connectionId: Long,
  val protocol: Protocol,
  val destinationIp: String,
  val destinationPort: Int,
 ) : NetworkObservation

 data class ConnectionClosed(
  override val timestamp: Instant,
  val connectionId: Long,
  val protocol: Protocol,
  val destinationIp: String,
  val destinationPort: Int,
  val startTime: Instant,
  val endTime: Instant,
  val uploadedBytes: Long,
  val downloadedBytes: Long,
 ) : NetworkObservation {
  val duration: java.time.Duration get() = java.time.Duration.between(startTime, endTime)
 }

 data class ConnectionFailed(
  override val timestamp: Instant,
  val protocol: Protocol,
  val destinationIp: String,
  val destinationPort: Int,
  val reason: FailureReason,
  val detail: String,
 ) : NetworkObservation

 /** A DNS question sent by the sandboxed app over UDP/53. Best-effort: unparseable queries are silently skipped, never fabricated. */
 data class DnsQuery(
  override val timestamp: Instant,
  val transactionId: Int,
  val hostname: String,
  val sourcePort: Int,
 ) : NetworkObservation

 /**
  * A DNS answer read back over UDP/53. [hostname] comes from the response's own echoed question
  * section — never guessed from [resolvedAddresses] — and is null when the response's question
  * section could not be parsed. [resolvedAddresses] holds every A/AAAA record found, in order.
  * This is the *only* legitimate source of a hostname-to-IP association in this system: never
  * derive one from [ConnectionOpened.destinationIp] and present it as observed DNS evidence.
  */
 data class DnsResponse(
  override val timestamp: Instant,
  val transactionId: Int,
  val hostname: String?,
  val resolvedAddresses: List<String>,
  val sourcePort: Int,
 ) : NetworkObservation

 /** A packet, connection attempt, or session was refused purely to stay within a configured resource-limit bound. */
 data class ResourceLimitExceeded(
  override val timestamp: Instant,
  val limitName: String,
  val currentValue: Long,
  val limitValue: Long,
 ) : NetworkObservation
}

fun interface NetworkObservationSink {
 fun onObservation(observation: NetworkObservation)
}

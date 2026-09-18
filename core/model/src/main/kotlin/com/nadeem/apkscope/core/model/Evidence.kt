package com.nadeem.apkscope.core.model

import java.time.Instant

/**
 * The three kinds of claim this system is ever allowed to make about a sandboxed app, kept
 * strictly separate per the v0.1 architecture review: never collapse them into one statement.
 *
 * - [DeclaredCapability]: what the APK's own manifest *says* it can do (a requested permission, an
 *   exported component, an intent filter). Static, no runtime behavior implied.
 * - [ObservedBehavior]: what this sandbox's own instrumentation *directly saw happen* at runtime —
 *   a [NetworkObservation] the forwarding engine produced, a file access, a launched activity.
 *   This is the sandbox's own eyewitness account.
 * - [AndroidEvidence]: something the OS itself independently recorded and attributed to the
 *   sandboxed package — values here must only ever pass through unchanged from a real
 *   `DevicePolicyManager` callback (`onNetworkLogsAvailable`, a `DnsEvent`/`ConnectEvent`), never
 *   synthesized. It usually arrives later than the observation it corroborates or repeats — see
 *   [ReportState].
 *
 * Example, from the physical Pixel 8 validation gate: "CAMERA declared" is a [DeclaredCapability]
 * (read from the manifest); "TCP connection to 1.2.3.4:443" is an [ObservedBehavior] (the
 * forwarding engine saw it on the wire); "DPM NetworkEvent attributed to this package" is
 * [AndroidEvidence] (Android's own DevicePolicyManager corroborating it independently). A report
 * or risk assessment may cite all three side by side, but must never present one as if it were
 * another — e.g. never claim a [DeclaredCapability] ("requests CAMERA") as proof of behavior
 * ("accessed the camera").
 */
data class DeclaredCapability(val name: String, val source: String = "manifest")

data class ObservedBehavior(val description: String, val observedAt: Instant, val observation: NetworkObservation? = null)

/** [occurredAt] is Android's own timestamp for the underlying event; [receivedAt] is when this process actually got the callback — deliberately kept apart since DPM network-log delivery is batched and can lag well behind [occurredAt]. */
data class AndroidEvidence(val description: String, val occurredAt: Instant, val receivedAt: Instant)

data class AndroidDnsEvidence(
 val eventId: Long,
 val batchToken: Long,
 val packageName: String,
 val timestamp: Instant,
 val receivedAt: Instant,
 val hostname: String,
 val resolvedAddresses: List<String>,
 val totalResolvedAddressCount: Int,
)

data class AndroidConnectEvidence(
 val eventId: Long,
 val batchToken: Long,
 val packageName: String,
 val timestamp: Instant,
 val receivedAt: Instant,
 val destinationAddress: String,
 val destinationPort: Int,
)

enum class AndroidEvidenceStatus {
 PENDING,
 READY,
 TIMEOUT,
 NOT_AVAILABLE,
}

data class AndroidEvidenceSummary(
 val sessionId: String,
 val dnsCount: Int,
 val connectCount: Int,
 val status: AndroidEvidenceStatus,
 val firstEventAt: Instant? = null,
 val lastEventAt: Instant? = null,
 val importedAt: Instant = Instant.now(),
)

/**
 * A session's evidence lifecycle. Sandbox-side work finishing (the app closed, the VPN torn down)
 * is deliberately not the same thing as having *complete* Android-side evidence for that session —
 * DevicePolicyManager network-log batches can arrive well after the session itself has ended. A
 * report must not claim [COMPLETE] until that evidence has actually been collected or the wait has
 * been explicitly abandoned.
 */
enum class ReportState {
 LIVE,
 SESSION_COMPLETE,
 WAITING_FOR_ANDROID_EVIDENCE,
 ANDROID_EVIDENCE_READY,
 TIMEOUT,
 COMPLETE,
}


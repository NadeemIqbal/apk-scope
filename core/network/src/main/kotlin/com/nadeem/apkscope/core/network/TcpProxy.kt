package com.nadeem.apkscope.core.network

import com.nadeem.apkscope.core.model.NetworkObservation

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Terminates each sandboxed TCP connection at the tunnel and re-originates it as a real, protected
 * [SocketChannel] to the actual destination — the same "split TCP" technique used by non-root
 * Android firewalls/VPN traffic monitors to proxy connections without a kernel module.
 *
 * This is deliberately not a general TCP/IP stack. The link between us and the sandboxed app's own
 * kernel is a TUN character device: writes we make are delivered to that kernel's network stack
 * directly, in order, without loss — so unlike a real network path, our side of each connection
 * never needs retransmission or reorder buffering. We still track sequence/ack numbers and the
 * client's advertised window (required for its stack to accept our segments and pace its own
 * sending), but the "hard part" of TCP — recovering from a lossy, reordering wire — does not apply
 * to this link and is intentionally not implemented. The only unreliable segment of the whole path
 * is real-socket <-> real destination, which the OS's own TCP stack already handles.
 *
 * Every inbound TUN packet is treated as hostile input (see [onOutbound]'s validation before any
 * field is trusted), every new connection is checked against [DestinationPolicy] before a real
 * socket is opened, and every resource this class holds (connection count, per-connection buffers,
 * the engine-wide buffer budget) is bounded by [EngineLimits] — see [sink].
 *
 * [ConnDiag] tags every lifecycle event with this connection's id (see the Forwarding Reliability
 * Root Cause Gate doc) — temporary, verbose, and deliberately never doing file I/O itself.
 *
 * Known limitations of this spike: IPv4 only; no window scaling (both sides are capped at a 65535
 * byte unscaled window because our SYN-ACK never advertises the option, which per RFC 1323 disables
 * scaling for the whole connection); no SACK; no path MTU discovery (segments are capped at
 * [OUR_MSS]); no IP-layer fragmentation/reassembly (a client segment split by IP fragmentation is
 * dropped, not reassembled).
 */
class TcpProxy(private val sink: TunSink) {
 private data class Key(val remoteIp: Int, val remotePort: Int, val localPort: Int)
 private enum class State { CONNECTING, ESTABLISHED, CLOSED }

 private inner class Tcb(val key: Key, val remoteIpBytes: ByteArray, val srcIpBytes: ByteArray, val clientIsn: Long, val outgoingMss: Int) {
  val connId = connectionIdOf(key)
  val createdAtNanos = System.nanoTime()
  val createdAt = System.currentTimeMillis()
  val startTime: Instant = Instant.now()
  var uploadedBytes = 0L   // client -> real destination
  var downloadedBytes = 0L // real destination -> client
  var state = State.CONNECTING
  lateinit var channel: SocketChannel
  var selKey: SelectionKey? = null
  val ourIsn = (secureRandom.nextLong() and 0xFFFFFFFFL)
  var ourNextSeq = (ourIsn + 1) and 0xFFFFFFFFL
  var clientNextSeq = (clientIsn + 1) and 0xFFFFFFFFL
  var lastAckFromClient = ourNextSeq
  var clientWindow = 65535
  var lastActivity = System.currentTimeMillis()
  var clientFinSeen = false
  var clientDone = false      // client sent FIN and we've been told to half-close the real socket
  var remoteShutdown = false  // we've called shutdownOutput() on the real socket
  var weSentFin = false
  var finSeq: Long? = null
  var weFinAcked = false
  var pausedReadingRemote = false
  var loggedFirstClientPayload = false
  var loggedFirstUpstreamWrite = false
  var loggedFirstUpstreamRead = false
  var loggedFirstDownstreamSent = false
  var isInspected = false
  var preambleSent = false
  private val initialPayloadAccumulator = java.io.ByteArrayOutputStream()

  fun trySendPreamble(newPayload: ByteArray? = null) {
   if (!isInspected || preambleSent) return
   if (newPayload != null) {
    initialPayloadAccumulator.write(newPayload)
   }
   val accumulated = initialPayloadAccumulator.toByteArray()
   val sni = com.nadeem.apkscope.core.network.https.TlsClientHelloParser.extractSni(accumulated)
   val complete = com.nadeem.apkscope.core.network.https.TlsClientHelloParser.isRecordComplete(accumulated)
   val willSend = sni != null || complete || accumulated.size >= 16384 || (newPayload == null && accumulated.isNotEmpty())
   ConnDiag.event(connId, "TRY_SEND_PREAMBLE", "newPayloadBytes=${newPayload?.size ?: -1} accumulatedBytes=${accumulated.size} sniFound=${sni != null} recordComplete=$complete willSend=$willSend")
   if (willSend) {
    sendPreamble(sni)
   }
  }

  fun sendPreamble(sni: String? = null) {
   if (!isInspected || preambleSent) return
   preambleSent = true
   // Milestone 9 (userspace traffic ownership verification): resolved exactly once per connection,
   // right here — never per packet. Uses this flow's *original* 4-tuple exactly as captured off the
   // inbound SYN packet's own IP/TCP headers (srcIpBytes/key.localPort for the local side, the real
   // remote address/port for the other) — never the loopback address/port of the local inspection
   // server `channel` is connected to; that would resolve the inspector's own UID, not the target
   // app's, and must not be mistaken for it (see TunSink.resolveConnectionOwnerUid's own doc
   // comment). srcIpBytes is passed through rather than letting the sink substitute its own
   // assumed tunAddress, so the lookup uses the actual observed tuple for every connection.
   val ownerLookup = sink.resolveConnectionOwnerUid(srcIpBytes, key.localPort, remoteIpBytes, key.remotePort)
   val ownerUidForWire = when (ownerLookup) {
    is com.nadeem.apkscope.core.network.OwnerUidLookup.Resolved -> ownerLookup.uid
    is com.nadeem.apkscope.core.network.OwnerUidLookup.Unknown -> -1
   }
   ConnDiag.event(connId, "PREAMBLE_SENT", "sni=${sni ?: "(none)"} accumulatedBytes=${initialPayloadAccumulator.size()} inspectionPort=${sink.httpsInspectionPort} ownerUid=$ownerUidForWire" +
    if (ownerLookup is com.nadeem.apkscope.core.network.OwnerUidLookup.Unknown) " ownerUidUnknownReason=${ownerLookup.reason}" else "")
   val token = sink.httpsInspectionAuthToken ?: ByteArray(16)
   val sniBytes = sni?.toByteArray(Charsets.UTF_8) ?: EMPTY
   // Milestone 9: preamble format is now [16 token][4 ip][2 port][4 ownerUid][2 sniLen][sni] — a
   // fixed-size field inserted *before* sniLen, not appended at the end. An earlier version of this
   // fix tried appending the field at the end and reading it "defensively" (tolerating its absence)
   // on the receiving side; that is unsound for a stream protocol — there is no way to distinguish
   // "no trailing ownerUid field" from "the next 4 bytes are actually the start of the real payload
   // that immediately follows the preamble" without an explicit length/version marker, and the
   // attempt would have silently corrupted the payload framing for any sender using the old format.
   // Given that, every caller that hand-crafts this preamble (this codebase's own test suite) must
   // be updated to the new fixed format instead — see HttpsInspectionEngine.handleClient's own note.
   val buf = ByteBuffer.allocate(16 + 4 + 2 + 4 + 2 + sniBytes.size)
   buf.put(token)
   buf.put(remoteIpBytes)
   buf.putShort(key.remotePort.toShort())
   buf.putInt(ownerUidForWire)
   buf.putShort(sniBytes.size.toShort())
   if (sniBytes.isNotEmpty()) buf.put(sniBytes)
   buf.flip()
   try { channel.write(buf) } catch (_: Exception) {}

   // Flush any accumulated initial payload immediately following preamble
   val buffered = initialPayloadAccumulator.toByteArray()
   if (buffered.isNotEmpty()) {
    try { channel.write(ByteBuffer.wrap(buffered)) } catch (_: Exception) {}
    initialPayloadAccumulator.reset()
   }
  }

  // --- Upload Throughput Root Cause Gate diagnostics: cumulative counters, sampled periodically
  // (see maybeSampleUpload/maybeSampleDownload below) rather than logged per-byte or per-packet.
  // All guarded by this Tcb's handler monitor, same as everything else it touches. ---
  var diagBytesReceivedFromTun = 0L
  var diagBytesQueuedForUpstream = 0L
  var diagBytesWrittenToSocket = 0L
  var diagTunPacketsReceived = 0L
  var diagSocketWriteCalls = 0L
  var diagPartialWrites = 0L
  var diagZeroByteWrites = 0L
  var diagBytesReadFromSocket = 0L
  var diagBytesWrittenToTun = 0L
  /** The last ops value this Tcb actually asked [TunSink.runOnSelectorThread] to apply — lets [updateInterest] coalesce a request that would be a no-op, and lets the item-3 invariant check know whether an already-in-flight task covers the current desired state. */
  var lastRequestedOps = -1
  var lastUploadSampleNanos = 0L
  var lastDownloadSampleNanos = 0L
  /** Upload Throughput Root Cause Gate root cause: the window value actually put on the wire in our most recently sent packet — see [flushInbound]'s use of this. */
  var lastAdvertisedWindowSent = ADVERTISED_WINDOW

  val pendingOutbound = ArrayDeque<ByteArray>() // read from the real socket, held back by the client's TCP window
  var pendingOutboundOffset = 0
  var pendingOutboundBytes = 0
  val pendingInbound = ArrayDeque<ByteArray>()  // from the client, held back until the real socket is writable
  var pendingInboundOffset = 0
  var pendingInboundBytes = 0

  fun outstanding(): Long = (ourNextSeq - lastAckFromClient) and 0xFFFFFFFFL
  fun advertisedWindow(): Int = (ADVERTISED_WINDOW - pendingInboundBytes).coerceIn(0, ADVERTISED_WINDOW)

  /** Emits at most one [ConnDiag.uploadSample] per [SAMPLE_INTERVAL_NANOS] — called from the reader thread after every accepted TUN chunk, so sampling cadence tracks actual traffic rather than wall-clock idle time. */
  fun maybeSampleUpload() {
   val now = System.nanoTime()
   if (now - lastUploadSampleNanos < SAMPLE_INTERVAL_NANOS) return
   lastUploadSampleNanos = now
   ConnDiag.uploadSample(
    connId, now - createdAtNanos, diagBytesReceivedFromTun, diagBytesQueuedForUpstream, diagBytesWrittenToSocket,
    pendingInboundBytes, diagTunPacketsReceived, diagSocketWriteCalls, diagPartialWrites, diagZeroByteWrites,
    selKey?.let { if (it.isValid) it.interestOps() else -1 } ?: -1, sink.currentGlobalBufferedBytes(), pendingInboundBytes,
   )
  }

  /** Same cadence idea as [maybeSampleUpload], for the download direction — called from the selector thread after every [handler]-driven read of the real socket. */
  fun maybeSampleDownload() {
   val now = System.nanoTime()
   if (now - lastDownloadSampleNanos < SAMPLE_INTERVAL_NANOS) return
   lastDownloadSampleNanos = now
   ConnDiag.downloadSample(connId, now - createdAtNanos, diagBytesReadFromSocket, diagBytesWrittenToTun, pendingOutboundBytes, clientWindow, outstanding())
  }

  // Each Tcb is driven from two threads: the TUN reader (via TcpProxy.onOutbound, which holds this
  // object's monitor for its whole per-connection section below) and the selector thread (these
  // three callbacks). @Synchronized keeps a connection's own state/socket calls serialized between
  // the two; it never blocks a *different* connection's traffic.
  // Upload Throughput Root Cause Gate item 5: these three used to be plain @Synchronized methods
  // (equivalent to `synchronized(this) { <whole body> }`); now explicit so wait/hold time around
  // that same monitor can be measured via LockDiag without changing which thread ever blocks on
  // what — the try/finally preserves every original early-return exactly as @Synchronized did.
  val handler = object : SelectHandler {
   override fun onConnectable() {
    val lockWaitStart = System.nanoTime()
    synchronized(this) {
     LockDiag.recordWait(System.nanoTime() - lockWaitStart)
     val lockHoldStart = System.nanoTime()
     try {
      ConnDiag.event(connId, "SELECTOR_WOKE_CONNECTABLE")
      try {
       ConnDiag.event(connId, "FINISH_CONNECT_CALLED")
       val finished = channel.finishConnect()
       ConnDiag.event(connId, "FINISH_CONNECT_RESULT", "finished=$finished")
       if (!finished) return
       val elapsed = System.currentTimeMillis() - createdAt
       if (elapsed > 1000) sink.onEvidence("TcpProxy.slowConnect", "OBSERVED", "port=${key.remotePort} elapsedMs=$elapsed")
       state = State.ESTABLISHED
       sendControl(Tcp.SYN or Tcp.ACK, mss = OUR_MSS)
       ConnDiag.event(connId, "SYNACK_SENT_TO_TUN")
       // Routed through runOnSelectorThread even though onConnectable() is often already running on
       // the selector thread (it executes immediately in that case) — see its doc for why this
       // matters when onConnectable() runs from the reader thread instead (the "connected
       // immediately" case below).
       sink.runOnSelectorThread { selKey = channel.register(sink.selector, SelectionKey.OP_READ, this) }
      } catch (e: Exception) {
       ConnDiag.event(connId, "FINISH_CONNECT_EXCEPTION", "${e.javaClass.simpleName}: ${e.message}")
       sink.observe(NetworkObservation.ConnectionFailed(Instant.now(), NetworkObservation.Protocol.TCP, describeV4(remoteIpBytes), key.remotePort, NetworkObservation.FailureReason.CONNECT_REFUSED, "${e.javaClass.simpleName}: ${e.message}"))
       sendReset()
       close("connect failed: ${e.javaClass.simpleName}")
      }
     } finally { LockDiag.recordHold(System.nanoTime() - lockHoldStart) }
    }
   }

   override fun onReadable() {
    val lockWaitStart = System.nanoTime()
    synchronized(this) {
     LockDiag.recordWait(System.nanoTime() - lockWaitStart)
     val lockHoldStart = System.nanoTime()
     try {
      if (state != State.ESTABLISHED) return
      if (!sink.tryReserveGlobalBuffer(outgoingMss)) { // engine-wide backstop: pause rather than buffer unboundedly
       sink.observe(NetworkObservation.ResourceLimitExceeded(Instant.now(), "maxTotalBufferedBytes", -1, sink.limits.maxTotalBufferedBytes))
       pausedReadingRemote = true; updateInterest(); return
      }
      val buf = ByteBuffer.allocate(outgoingMss)
      val n = try { channel.read(buf) } catch (e: Exception) { sink.releaseGlobalBuffer(outgoingMss); remoteEnded(fatal = true, detail = "${e.javaClass.simpleName}: ${e.message}"); return }
      if (n < 0) { sink.releaseGlobalBuffer(outgoingMss); remoteEnded(fatal = false, detail = "EOF"); return }
      if (n == 0) { sink.releaseGlobalBuffer(outgoingMss); return }
      if (n < outgoingMss) sink.releaseGlobalBuffer(outgoingMss - n) // hand back the unused part of the reservation
      if (!loggedFirstUpstreamRead) { loggedFirstUpstreamRead = true; ConnDiag.event(connId, "FIRST_UPSTREAM_READ", "bytes=$n") }
      diagBytesReadFromSocket += n
      buf.flip()
      val bytes = ByteArray(n); buf.get(bytes)
      downloadedBytes += n
      pendingOutbound.addLast(bytes); pendingOutboundBytes += n
      flushOutbound()
      if (pendingOutboundBytes > sink.limits.maxQueuedBytesPerConnection && !pausedReadingRemote) { pausedReadingRemote = true; updateInterest() }
      maybeSampleDownload()
     } finally { LockDiag.recordHold(System.nanoTime() - lockHoldStart) }
    }
   }

   override fun onWritable() {
    val lockWaitStart = System.nanoTime()
    synchronized(this) {
     LockDiag.recordWait(System.nanoTime() - lockWaitStart)
     val lockHoldStart = System.nanoTime()
     try { flushInbound() } finally { LockDiag.recordHold(System.nanoTime() - lockHoldStart) }
    }
   }
  }

  /** Reserves its share of the engine-wide buffer budget; returns false (queuing nothing) if that would exceed it — the caller must not ACK data it didn't queue, so the client naturally retransmits later. */
  fun queueInbound(payload: ByteArray): Boolean {
   if (!sink.tryReserveGlobalBuffer(payload.size)) {
    sink.observe(NetworkObservation.ResourceLimitExceeded(Instant.now(), "maxTotalBufferedBytes", -1, sink.limits.maxTotalBufferedBytes))
    return false
   }
   if (!loggedFirstClientPayload) { loggedFirstClientPayload = true; ConnDiag.event(connId, "FIRST_CLIENT_PAYLOAD_RECEIVED", "bytes=${payload.size}") }
   diagBytesQueuedForUpstream += payload.size
   pendingInbound.addLast(payload); pendingInboundBytes += payload.size
   flushInbound()
   return true
  }

  fun flushInbound() {
   while (pendingInbound.isNotEmpty()) {
    val chunk = pendingInbound.first()
    val requested = chunk.size - pendingInboundOffset
    val buf = ByteBuffer.wrap(chunk, pendingInboundOffset, requested)
    val n = try { channel.write(buf) } catch (e: Exception) { remoteEnded(fatal = true, detail = "write: ${e.javaClass.simpleName}: ${e.message}"); return }
    diagSocketWriteCalls++
    if (n == 0) diagZeroByteWrites++ else if (n < requested) diagPartialWrites++
    if (n <= 0) break
    diagBytesWrittenToSocket += n
    if (!loggedFirstUpstreamWrite) { loggedFirstUpstreamWrite = true; ConnDiag.event(connId, "FIRST_UPSTREAM_WRITE", "bytes=$n") }
    pendingInboundOffset += n; pendingInboundBytes -= n
    if (pendingInboundOffset >= chunk.size) {
     pendingInbound.removeFirst(); pendingInboundOffset = 0
     sink.releaseGlobalBuffer(chunk.size)
    }
   }
   if (clientDone && pendingInbound.isEmpty() && !remoteShutdown) {
    remoteShutdown = true
    try { channel.shutdownOutput() } catch (_: Exception) {}
   }
   // Upload Throughput Root Cause Gate root cause: draining pendingInbound here (called from the
   // selector thread's onWritable, once the real socket becomes writable again) can turn our
   // advertised window from 0 back to non-zero — but that alone never reaches the client. Every
   // OTHER place we send a window value is a *reaction* to a packet the client just sent
   // (sendAckOnly in onOutbound), and a client sitting on a zero window sends nothing more to
   // react to: it is waiting for exactly the notification this method is otherwise never
   // responsible for producing. Measured effect of the omission (see HARDENING_GATE artifacts):
   // three independent connections (two providers, one confirmed non-Cloudflare-specific) each
   // filled to the full 65535-byte cap, drained it completely within ~1-2s of real socket writes,
   // then went silent for 30-60+ seconds until the client's own RFC 1122 §4.2.2.17 zero-window
   // persist-timer probe finally elicited a fresh ACK — by which point the application-level
   // timeout (30s in this harness) had already fired. A TCP receiver MUST notify the sender
   // promptly when a previously-closed window reopens; sending one extra ACK only in the specific
   // "we had advertised zero and now have room" transition is the minimal, targeted fix — not
   // general SWS-avoidance tuning, which nothing here has shown to be necessary.
   if (shouldSendWindowReopenAck(lastAdvertisedWindowSent, advertisedWindow())) {
    ConnDiag.event(connId, "WINDOW_REOPENED_ACK_SENT", "window=${advertisedWindow()}")
    sendAckOnly()
   }
   updateInterest()
   maybeSampleUpload()
  }

  fun flushOutbound() {
   while (pendingOutbound.isNotEmpty()) {
    val allowed = (clientWindow.toLong() - outstanding()).coerceAtLeast(0)
    if (allowed <= 0) break
    val chunk = pendingOutbound.first()
    val available = chunk.size - pendingOutboundOffset
    val toSend = minOf(available, outgoingMss, allowed.toInt())
    if (toSend <= 0) break
    sendData(chunk, pendingOutboundOffset, toSend)
    pendingOutboundOffset += toSend; pendingOutboundBytes -= toSend
    if (pendingOutboundOffset >= chunk.size) {
     pendingOutbound.removeFirst(); pendingOutboundOffset = 0
     sink.releaseGlobalBuffer(chunk.size)
    }
   }
   if (pausedReadingRemote && pendingOutboundBytes < sink.limits.maxQueuedBytesPerConnection) { pausedReadingRemote = false; updateInterest() }
   maybeFinish()
  }

  private fun updateInterest() {
   val key = selKey ?: return
   // pausedReadingRemote/pendingInbound are only ever safely read while holding this Tcb's
   // handler monitor, which the caller of updateInterest() always already holds — so compute the
   // desired ops *now*, synchronously, and only defer the mechanical interestOps() call itself.
   var ops = if (pausedReadingRemote) 0 else SelectionKey.OP_READ
   if (pendingInbound.isNotEmpty()) ops = ops or SelectionKey.OP_WRITE
   // Upload Throughput Root Cause Gate item 4: measured directly (see HARDENING_GATE artifacts) —
   // of 11,052 interestOps requests generated during one instrumented 6-connection upload run,
   // 11,034 (99.8%) asked for the exact same ops value already in effect, because this runs from
   // the TUN reader thread on every single accepted chunk regardless of whether flushInbound's own
   // drain changed anything. Each one still cost a queue entry plus a selector.wakeup() (an actual
   // syscall) even though nothing needed to change. Coalescing was NOT applied speculatively —
   // this comment records the measurement that justified it, per the gate's "do not implement
   // coalescing unless measurement shows this is happening" instruction. Note this was a real,
   // measured inefficiency but not the primary cause of the reported throughput ceiling — see
   // flushInbound's window-update fix for that.
   val redundant = ops == lastRequestedOps
   ConnDiag.interestOpsRequested(connId, ops, redundant)
   if (redundant) return // already requested (applied or in-flight) — nothing to add to the queue
   lastRequestedOps = ops
   // Same root cause and fix as registration (see TunSink.runOnSelectorThread's doc):
   // SelectionKey.interestOps() is documented to block "until the other operation is complete"
   // when called concurrently with a select() in progress — and this runs on the TUN reader
   // thread every time the client sends a new chunk of upload data (via flushInbound).
   sink.runOnSelectorThread { if (key.isValid) key.interestOps(ops) }
  }

  /** The real destination closed its read side (EOF) or errored; propagate as our FIN (clean) or RST (error) to the client. */
  private fun remoteEnded(fatal: Boolean, detail: String) {
   ConnDiag.event(connId, "REMOTE_ENDED", "fatal=$fatal detail=$detail")
   if (fatal) {
    sink.observe(NetworkObservation.ConnectionFailed(Instant.now(), NetworkObservation.Protocol.TCP, describeV4(remoteIpBytes), key.remotePort, NetworkObservation.FailureReason.OTHER, detail))
    sendReset()
    close("remote error: $detail")
    return
   }
   if (!weSentFin) {
    weSentFin = true
    finSeq = ourNextSeq
    sendControl(Tcp.FIN or Tcp.ACK)
    ourNextSeq = (ourNextSeq + 1) and 0xFFFFFFFFL
   }
   maybeFinish()
  }

  fun sendAckOnly() { sendControl(Tcp.ACK) }
  /** Public wrapper so the connect-timeout path in [sweepIdle] — outside this inner class — can signal the client promptly instead of leaving it to its own timeout (see item 7 of the root-cause gate). */
  fun sendReset() { sendControl(Tcp.RST or Tcp.ACK) }

  private fun sendControl(flags: Int, mss: Int? = null) {
   val seqToSend = if (flags and Tcp.SYN != 0) ourIsn else ourNextSeq
   writePacket(seqToSend, clientNextSeq, flags, advertisedWindow(), mss, EMPTY, 0, 0)
   if (flags and Tcp.FIN != 0 || flags and Tcp.RST != 0) ConnDiag.event(connId, "FIN_OR_RST_SENT", "flags=$flags")
  }

  private fun sendData(chunk: ByteArray, off: Int, len: Int) {
   writePacket(ourNextSeq, clientNextSeq, Tcp.PSH or Tcp.ACK, advertisedWindow(), null, chunk, off, len)
   if (!loggedFirstDownstreamSent) { loggedFirstDownstreamSent = true; ConnDiag.event(connId, "FIRST_DOWNSTREAM_PAYLOAD_SENT", "bytes=$len") }
   diagBytesWrittenToTun += len
   ourNextSeq = (ourNextSeq + len) and 0xFFFFFFFFL
  }

  private fun writePacket(seq: Long, ack: Long, flags: Int, window: Int, mss: Int?, payload: ByteArray, off: Int, len: Int) {
   val packet = ByteArray(20 + Tcp.HEADER_LEN + 4 + len)
   val tcpLen = Tcp.writeSegment(packet, 20, key.remotePort, key.localPort, seq, ack, flags, window, remoteIpBytes, sink.tunAddress, mss, payload, off, len)
   Ipv4.writeHeader(packet, 0, Ipv4.PROTO_TCP, remoteIpBytes, sink.tunAddress, tcpLen, idSeq.getAndIncrement() and 0xFFFF)
   sink.writeToTun(packet, 20 + tcpLen)
   lastAdvertisedWindowSent = window // every packet we send carries a window value — this is the single choke point for tracking what the client was last told, see flushInbound's use of it
  }

  fun maybeFinish() {
   if (weSentFin && weFinAcked && clientFinSeen && pendingOutbound.isEmpty()) close("both sides closed")
  }

  fun close(reason: String) {
   if (state == State.CLOSED) return
   state = State.CLOSED
   ConnDiag.event(connId, "CONNECTION_CLOSED", "reason=$reason ageMs=${(System.nanoTime() - createdAtNanos) / 1_000_000}")
   if (tcbs.remove(key, this)) {
    activeConnections.decrementAndGet()
    // Anything still queued was reserved against the global budget and is being discarded now — give it back.
    if (pendingInboundBytes > 0) sink.releaseGlobalBuffer(pendingInboundBytes)
    if (pendingOutboundBytes > 0) sink.releaseGlobalBuffer(pendingOutboundBytes)
    sink.observe(
     NetworkObservation.ConnectionClosed(
      Instant.now(), connId, NetworkObservation.Protocol.TCP, describeV4(remoteIpBytes), key.remotePort,
      startTime, Instant.now(), uploadedBytes, downloadedBytes,
     )
    )
   }
   try { selKey?.cancel() } catch (_: Exception) {}
   try { channel.close() } catch (_: Exception) {}
  }
 }

 private val tcbs = ConcurrentHashMap<Key, Tcb>()
 private val activeConnections = java.util.concurrent.atomic.AtomicLong(0)
 private val idSeq = java.util.concurrent.atomic.AtomicInteger(0)
 private val secureRandom = SecureRandom()

 /**
  * [ipOff] points at the start of the IPv4 header; [tcpOff] at the start of the TCP header within
  * [buf]; [length] is the number of bytes actually read from the TUN starting at [ipOff] — the
  * bound to validate against, since [buf] itself may be a larger, reused read buffer.
  *
  * Every field below is read from a packet the sandboxed app controls; nothing is trusted before
  * its bounds are checked against [length], and a checksum-invalid or structurally-invalid segment
  * is dropped without being handed to any TCB. This does not assume [ForwardingEngine.dispatch]
  * already checked that a full TCP header fits — it is re-checked here too, so this method is safe
  * to call directly (as the test suite does).
  */
 fun onOutbound(buf: ByteArray, ipOff: Int, tcpOff: Int, length: Int) {
  if (ipOff < 0 || tcpOff < ipOff + 20 || tcpOff + Tcp.HEADER_LEN > ipOff + length || tcpOff + Tcp.HEADER_LEN > buf.size) return // not even a full IP+TCP header present
  val hdrLen = Tcp.dataOffset(buf, tcpOff)
  if (hdrLen < Tcp.HEADER_LEN) { sink.onEvidence("TcpProxy.malformed", "DROP", "dataOffset<20"); return } // below the minimum valid TCP header
  val ipHdrLen = Ipv4.ihl(buf, ipOff)
  val ipTotalLen = Ipv4.totalLength(buf, ipOff)
  val payloadLen = ipTotalLen - ipHdrLen - hdrLen
  if (ipTotalLen < ipHdrLen || ipTotalLen > length || payloadLen < 0 || tcpOff + hdrLen + payloadLen > ipOff + length) {
   sink.onEvidence("TcpProxy.malformed", "DROP", "inconsistent length fields"); return
  }
  val segmentLen = hdrLen + payloadLen
  val remoteIpBytes = Ipv4.dstIp(buf, ipOff)
  val srcIpBytes = Ipv4.srcIp(buf, ipOff)
  if (!Tcp.checksumValid(buf, tcpOff, segmentLen, srcIpBytes, remoteIpBytes)) { sink.onEvidence("TcpProxy.checksum", "FAIL", "port=${Tcp.dstPort(buf, tcpOff)}"); return }

  val remoteIp = ipToInt(remoteIpBytes)
  val remotePort = Tcp.dstPort(buf, tcpOff)
  val localPort = Tcp.srcPort(buf, tcpOff)
  val key = Key(remoteIp, remotePort, localPort)
  val flags = Tcp.flags(buf, tcpOff)
  val seq = Tcp.seq(buf, tcpOff)
  val ackVal = Tcp.ack(buf, tcpOff)
  val window = Tcp.window(buf, tcpOff)

  if (flags and Tcp.SYN != 0 && flags and Tcp.ACK == 0) {
   val connId = connectionIdOf(key)
   ConnDiag.event(connId, "SYN_RECEIVED_FROM_TUN", "dst=${describeV4(remoteIpBytes)}:$remotePort")
   if (tcbs.containsKey(key)) return // already connecting/established: a retransmitted SYN, ignore
   val decision = DestinationPolicy.evaluate(DestinationPolicy.Destination.V4(remoteIpBytes), sink.localSubnets)
   ConnDiag.event(connId, "DESTINATION_POLICY_COMPLETED", "verdict=${decision.verdict}")
   if (decision.verdict == DestinationPolicy.Verdict.DENY) {
    sink.observe(NetworkObservation.ConnectionFailed(Instant.now(), NetworkObservation.Protocol.TCP, describeV4(remoteIpBytes), remotePort, NetworkObservation.FailureReason.POLICY_DENIED, decision.reason))
    return
   }
   if (activeConnections.get() >= sink.limits.maxConcurrentTcpConnections) {
    sink.observe(NetworkObservation.ResourceLimitExceeded(Instant.now(), "maxConcurrentTcpConnections", activeConnections.get(), sink.limits.maxConcurrentTcpConnections.toLong()))
    return
   }
   val mss = minOf(Tcp.readMss(buf, tcpOff) ?: 536, OUR_MSS)
   val tcb = Tcb(key, remoteIpBytes, srcIpBytes, seq, mss)
   ConnDiag.event(connId, "TCB_CREATED")
   try {
    val channel = SocketChannel.open()
    ConnDiag.event(connId, "SOCKET_CHANNEL_CREATED")
    channel.configureBlocking(false)
    val protected = sink.protectSocket(channel.socket())
    ConnDiag.event(connId, "PROTECT_RESULT", "protected=$protected")
    if (!protected) throw java.io.IOException("VpnService.protect refused")
    tcb.channel = channel
    tcbs[key] = tcb
    activeConnections.incrementAndGet()
    sink.observe(NetworkObservation.ConnectionOpened(Instant.now(), connId, NetworkObservation.Protocol.TCP, describeV4(remoteIpBytes), remotePort))
    ConnDiag.event(connId, "CONNECT_CALLED")
    val inspectionPort = if (remotePort == 443 || remotePort == 80 || remotePort == 8080) sink.httpsInspectionPort else null
    val targetAddress = if (inspectionPort != null) InetSocketAddress("127.0.0.1", inspectionPort) else InetSocketAddress(InetAddress.getByAddress(remoteIpBytes), remotePort)
    if (inspectionPort != null) {
     tcb.isInspected = true
    }
    val connectedImmediately = channel.connect(targetAddress)
    if (connectedImmediately) {
     ConnDiag.event(connId, "CONNECT_RETURNED_IMMEDIATELY")
     tcb.handler.onConnectable()
    }
    else {
     // Root cause of the multi-second connect latencies (see TunSink.runOnSelectorThread's doc):
     // this used to be `tcb.selKey = channel.register(sink.selector, OP_CONNECT, tcb.handler)`
     // called directly from this (reader) thread, immediately followed by `sink.selector.wakeup()`.
     // Confirmed via ConnDiag timestamps that under concurrent connection bursts specifically, the
     // entire delay — in exact multiples of the selector's 1000ms poll timeout — sat right here.
     sink.runOnSelectorThread { tcb.selKey = channel.register(sink.selector, SelectionKey.OP_CONNECT, tcb.handler); ConnDiag.event(connId, "OP_CONNECT_REGISTERED") }
    }
   } catch (e: Exception) {
    ConnDiag.event(connId, "CONNECT_SETUP_EXCEPTION", "${e.javaClass.simpleName}: ${e.message}")
    sink.observe(NetworkObservation.ConnectionFailed(Instant.now(), NetworkObservation.Protocol.TCP, describeV4(remoteIpBytes), remotePort, NetworkObservation.FailureReason.OTHER, "${e.javaClass.simpleName}: ${e.message}"))
    if (tcbs.remove(key, tcb)) activeConnections.decrementAndGet()
   }
   return
  }

  val tcb = tcbs[key] ?: return // no matching connection; drop rather than risk a malformed synthetic RST
  // Upload Throughput Root Cause Gate item 5: this is the one place the TUN reader thread contends
  // with the selector thread for a given connection's lock (the selector-thread side is the
  // @Synchronized handler callbacks below) — wait time here is "how long did an incoming upload
  // chunk sit blocked behind a connectable/readable/writable callback for this same connection",
  // and hold time is "how long did we occupy that lock", both reported in aggregate via LockDiag.
  val lockWaitStart = System.nanoTime()
  synchronized(tcb.handler) { // pairs with the @Synchronized selector-thread callbacks above — same monitor as the handler object
   LockDiag.recordWait(System.nanoTime() - lockWaitStart)
   val lockHoldStart = System.nanoTime()
   if (payloadLen > 0) { tcb.diagBytesReceivedFromTun += payloadLen; tcb.diagTunPacketsReceived++ }
   tcb.lastActivity = System.currentTimeMillis()
   if (flags and Tcp.RST != 0) { ConnDiag.event(tcb.connId, "RST_RECEIVED_FROM_TUN"); tcb.close("client RST"); LockDiag.recordHold(System.nanoTime() - lockHoldStart); return }
   if (tcb.state != State.ESTABLISHED) { LockDiag.recordHold(System.nanoTime() - lockHoldStart); return } // handshake still pending on the real side

   tcb.clientWindow = window
   val advance = (ackVal - tcb.lastAckFromClient) and 0xFFFFFFFFL
   if (advance in 0 until (1L shl 31)) tcb.lastAckFromClient = ackVal // accept forward-in-window acks only; ignore stale/bogus ones
   val fin = tcb.finSeq
   if (fin != null && !tcb.weFinAcked && ((tcb.lastAckFromClient - fin) and 0xFFFFFFFFL) in 0 until (1L shl 31) && tcb.lastAckFromClient != fin) tcb.weFinAcked = true
   tcb.flushOutbound()

   if (payloadLen > 0) {
    if (seq == tcb.clientNextSeq) {
     val payload = buf.copyOfRange(tcpOff + hdrLen, tcpOff + hdrLen + payloadLen)
     tcb.uploadedBytes += payloadLen
     tcb.clientNextSeq = (tcb.clientNextSeq + payloadLen) and 0xFFFFFFFFL
     if (tcb.isInspected && !tcb.preambleSent) {
      tcb.trySendPreamble(payload)
     } else {
      tcb.queueInbound(payload)
     }
    }
    // else: old retransmit, out-of-order, or the global buffer budget is exhausted — re-ACK the
    // current (unadvanced) expectation below so the client's own retransmit timer retries later.
    tcb.sendAckOnly()
   }

   if (flags and Tcp.FIN != 0 && !tcb.clientFinSeen) {
    ConnDiag.event(tcb.connId, "FIN_RECEIVED_FROM_TUN")
    tcb.clientFinSeen = true
    tcb.clientNextSeq = (tcb.clientNextSeq + 1) and 0xFFFFFFFFL
    tcb.clientDone = true
    tcb.sendAckOnly()
    tcb.flushInbound() // triggers shutdownOutput() once any trailing buffered bytes are flushed
   }
   tcb.maybeFinish()
   LockDiag.recordHold(System.nanoTime() - lockHoldStart)
  }
 }

 /**
  * Item 2 of the root-cause gate ("a failure in one connection must never terminate processing for
  * unrelated connections") applies here too: this used to be an unguarded `forEach`, so one TCB
  * throwing during cleanup would abort the sweep for every TCB after it in iteration order — and
  * because this runs on the engine's selector thread (see [ForwardingEngine.selectLoop]), an
  * uncaught exception here would kill that thread outright, silently stopping *all* future
  * connection processing. Each TCB's cleanup is now independently guarded.
  */
 fun sweepIdle() {
  val now = System.currentTimeMillis()
  val cutoff = now - sink.limits.tcpIdleTimeoutMs
  val connectCutoff = now - sink.limits.tcpConnectTimeoutMs
  tcbs.values.toList().forEach { tcb ->
   try {
    synchronized(tcb.handler) {
     if (tcb.state == State.CONNECTING && tcb.createdAt < connectCutoff) {
      val elapsedMs = now - tcb.createdAt
      ConnDiag.event(tcb.connId, "CONNECT_TIMEOUT_FIRED", "elapsedMs=$elapsedMs")
      sink.observe(NetworkObservation.ConnectionFailed(Instant.now(), NetworkObservation.Protocol.TCP, describeV4(tcb.remoteIpBytes), tcb.key.remotePort, NetworkObservation.FailureReason.CONNECT_TIMEOUT, "elapsedMs=$elapsedMs"))
      tcb.sendReset() // item 7: fail the sandboxed app promptly instead of leaving it to its own timeout
      tcb.close("connect timed out")
     } else if (tcb.state == State.ESTABLISHED && tcb.lastActivity < cutoff) tcb.close("idle")
    }
   } catch (e: Exception) {
    sink.onEvidence("TcpProxy.sweepIdle", "FAIL", "connId=${tcb.connId} ${e.javaClass.simpleName}: ${e.message}")
   }
  }
 }

 fun activeCount(): Int = tcbs.size

 /** Closes every open connection's real socket — call when the engine is shutting down, or these leak until GC. Each close is independently guarded for the same reason as [sweepIdle]. */
 fun closeAll() {
  tcbs.values.toList().forEach { tcb ->
   try { synchronized(tcb.handler) { tcb.close("engine stopping") } }
   catch (e: Exception) { sink.onEvidence("TcpProxy.closeAll", "FAIL", "connId=${tcb.connId} ${e.javaClass.simpleName}: ${e.message}") }
  }
 }

 /**
  * Item 4 of the root-cause gate: internal invariants a healthy engine must always satisfy.
  * Returns the list of violations found (empty = healthy) rather than throwing, so a caller can
  * assert on it in tests or just log it during a diagnostic run without crashing anything.
  */
 fun checkInvariants(): List<String> {
  val violations = ArrayList<String>()
  val reportedCount = tcbs.size
  val actualLiveCount = activeConnections.get()
  if (reportedCount.toLong() != actualLiveCount) violations += "activeConnections counter ($actualLiveCount) != tcbs.size ($reportedCount)"
  tcbs.forEach { (key, tcb) ->
   if (tcb.state == State.CLOSED) violations += "tcbs still holds a CLOSED connection: connId=${tcb.connId}"
   if (tcb.key != key) violations += "tcbs map key mismatch for connId=${tcb.connId}"
   val selKey = tcb.selKey
   if (selKey != null && !selKey.isValid && tcb.state != State.CLOSED) violations += "connId=${tcb.connId} has a cancelled/invalid SelectionKey but state=${tcb.state}"
   // Upload Throughput Root Cause Gate item 3's required invariant: queued upstream bytes with no
   // path back to a socket-writable notification is exactly the class of bug this gate exists to
   // catch. lastRequestedOps having the bit set covers "a selector-thread task enabling it may
   // still be pending" without needing to read the key from the wrong thread for that half of the
   // check — reading interestOps() itself is fine from any thread (see selKey.interestOps() below).
   if (tcb.state == State.ESTABLISHED && tcb.pendingInboundBytes > 0) {
    val opsHaveWrite = selKey != null && selKey.isValid && (selKey.interestOps() and SelectionKey.OP_WRITE) != 0
    val requestedHasWrite = (tcb.lastRequestedOps and SelectionKey.OP_WRITE) != 0
    if (!opsHaveWrite && !requestedHasWrite) violations += "connId=${tcb.connId} has ${tcb.pendingInboundBytes} pendingInboundBytes but neither the live SelectionKey nor the last requested ops include OP_WRITE"
   }
  }
  return violations
 }

 companion object {
  private const val OUR_MSS = 1400
  private const val ADVERTISED_WINDOW = 65535 // unscaled: our SYN-ACK never negotiates window scaling
  /** Upload Throughput Root Cause Gate item 1's "100 or 250ms" sampling interval. */
  private const val SAMPLE_INTERVAL_NANOS = 200_000_000L
  /**
   * Pure decision logic for the Upload Throughput Root Cause Gate's root-cause fix, extracted the
   * same way [PacketValidation.classify] was — so the specific defect (a client stalled on a zero
   * window never gets told when it reopens, because nothing but a reaction to new client data ever
   * sends a window value, and a zero-window client sends no new data to react to) is directly unit
   * testable without a real socket round-trip. Deliberately narrow: only the zero-to-nonzero
   * transition is covered, since that is the specific case measurement showed causing multi-second
   * to multi-minute stalls (see HARDENING_GATE_ROOT_CAUSE artifacts) — this is not general SWS
   * avoidance, which nothing measured here has shown to be necessary.
   */
  internal fun shouldSendWindowReopenAck(previouslyAdvertisedWindow: Int, currentAdvertisedWindow: Int): Boolean =
   previouslyAdvertisedWindow == 0 && currentAdvertisedWindow > 0
  private val EMPTY = ByteArray(0)
  private fun ipToInt(ip: ByteArray): Int =
   ((ip[0].toInt() and 0xFF) shl 24) or ((ip[1].toInt() and 0xFF) shl 16) or ((ip[2].toInt() and 0xFF) shl 8) or (ip[3].toInt() and 0xFF)
  private fun describeV4(ip: ByteArray) = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
  private fun connectionIdOf(key: Key): Long = (key.remoteIp.toLong() shl 32) or ((key.remotePort.toLong() shl 16) or key.localPort.toLong())
 }
}

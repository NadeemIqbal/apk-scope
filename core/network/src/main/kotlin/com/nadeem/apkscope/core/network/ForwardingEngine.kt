package com.nadeem.apkscope.core.network

import com.nadeem.apkscope.core.model.NetworkObservation
import com.nadeem.apkscope.core.model.NetworkObservationSink

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.Socket
import java.nio.channels.ClosedSelectorException
import java.nio.channels.Selector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Spike B: pumps packets between the forwarding TUN and the real network, restoring connectivity
 * through the monitored VPN instead of the Spike A TUN's deliberate black hole. IPv4 UDP and TCP
 * only — see [UdpNat] and [TcpProxy] for what each does and does not implement. Everything else
 * (IPv6, ICMP, anything else) is dropped and counted, not forwarded.
 *
 * Two threads: [readLoop] blocks on TUN reads and feeds [UdpNat]/[TcpProxy]; [selectLoop] drives
 * every real protected socket's readiness and periodic idle sweeps. The two meet only through
 * [writeToTun] (lock-guarded), the engine-wide buffer budget ([tryReserveGlobalBuffer]/
 * [releaseGlobalBuffer], the [EngineLimits.maxTotalBufferedBytes] backstop), and each flow's own
 * object monitor (see [TcpProxy]'s Tcb doc) — no global lock across flows.
 *
 * Every packet [readLoop] hands to [dispatch] is untrusted input from the sandboxed app: [dispatch]
 * itself validates length/version/header-length/checksum before anything downstream sees the
 * packet, and [UdpNat]/[TcpProxy] validate their own protocol fields again before trusting them.
 */
class ForwardingEngine(
 private val vpnService: VpnService,
 /** Owned by this engine from construction on: [stop] closes it, which is what unblocks [readLoop]'s pending read(). */
 private val tunFd: ParcelFileDescriptor,
 override val tunAddress: ByteArray,
 override val limits: EngineLimits = EngineLimits.DEFAULT,
 override val localSubnets: List<DestinationPolicy.LocalSubnet> = emptyList(),
 private val observationSink: NetworkObservationSink = NetworkObservationSink {},
 private val caStorageDir: java.io.File? = null,
 private val onEvidenceCb: (test: String, result: String, detail: String) -> Unit,
 private val sessionId: String? = null,
 private val targetPackage: String? = null
) : TunSink {

 private val input = FileInputStream(tunFd.fileDescriptor)
 private val output = FileOutputStream(tunFd.fileDescriptor)
 private val writeLock = Any()
 override val selector: Selector = Selector.open()
 private val udpNat = UdpNat(this)
 private val tcpProxy = TcpProxy(this)
 private val caManager = caStorageDir?.let { com.nadeem.apkscope.core.network.https.CaManager(it) }
 private val httpsEngine = caManager?.let { com.nadeem.apkscope.core.network.https.HttpsInspectionEngine(this, it, sessionId, targetPackage) }

 override val httpsInspectionPort: Int?
  get() = if (com.nadeem.apkscope.core.network.https.HttpsInspectionConfig.isEnabled) httpsEngine?.boundPort else null

 override val httpsInspectionAuthToken: ByteArray?
  get() = if (com.nadeem.apkscope.core.network.https.HttpsInspectionConfig.isEnabled) httpsEngine?.authToken else null

 private val running = AtomicBoolean(false)
 private var readerThread: Thread? = null
 private var selectorThread: Thread? = null
 private val globalBufferedBytes = AtomicLong(0)

 @Volatile var packetsIn = 0L; private set
 @Volatile var packetsOut = 0L; private set
 @Volatile var droppedIpv6 = 0L; private set
 @Volatile var droppedOversized = 0L; private set
 @Volatile var droppedMalformed = 0L; private set
 @Volatile var droppedOther = 0L; private set

 override fun writeToTun(packet: ByteArray, length: Int) {
  synchronized(writeLock) {
   try { output.write(packet, 0, length); packetsOut++ } catch (e: Exception) { onEvidenceCb("ForwardingEngine.writeToTun", "FAIL", e.toString()) }
  }
 }

 override fun protectSocket(socket: Socket): Boolean = vpnService.protect(socket)
 override fun protectDatagram(socket: DatagramSocket): Boolean = vpnService.protect(socket)
 override fun onEvidence(test: String, result: String, detail: String) = onEvidenceCb(test, result, detail)
 override fun observe(observation: NetworkObservation) = observationSink.onObservation(observation)

 override fun tryReserveGlobalBuffer(bytes: Int): Boolean {
  while (true) {
   val current = globalBufferedBytes.get()
   val next = current + bytes
   if (next > limits.maxTotalBufferedBytes) return false
   if (globalBufferedBytes.compareAndSet(current, next)) return true
  }
 }

 override fun releaseGlobalBuffer(bytes: Int) {
  if (bytes <= 0) return
  globalBufferedBytes.updateAndGet { (it - bytes).coerceAtLeast(0) }
 }

 override fun currentGlobalBufferedBytes(): Long = globalBufferedBytes.get()

 // Milestone 9 (userspace traffic ownership verification) — cache lifetime, verified explicitly
 // rather than assumed, across four scenarios:
 //  1. Target installation: before install, `getPackageUid` throws `NameNotFoundException` every
 //     call — nothing is cached, so the very next call after install completes gets a fresh, real
 //     attempt instead of being stuck on a permanently-cached "not found" (this is the exact
 //     install-race window verified on-device this milestone: UNKNOWN, then MATCHED once resolved).
 //  2. Target removal (mid-session uninstall): this field is instance-scoped, not persisted, but
 //     that alone would still let a *stale* success outlive an uninstall for the rest of this
 //     engine's life. Fixed below: every call re-queries `PackageManager` (this runs at most once
 //     per new TCP connection — never a per-packet cost, same budget as
 //     [resolveConnectionOwnerUid]) rather than trusting a stale cache indefinitely, and a
 //     definitive `NameNotFoundException` (the package is genuinely gone) invalidates any prior
 //     cached value and returns null instead of the stale UID — a removed target must not keep
 //     "resolving" to a UID that no longer names anything real.
 //  3. Session changes: this field lives on a `ForwardingEngine` instance, and a new sandbox
 //     session always gets a fresh instance (`SandboxVpnService.onStartCommand` constructs a new
 //     `ForwardingEngine` per VPN establishment — verified by reading that call site) — so a stale
 //     value can never carry over from a previous session into a new one.
 //  4. Process lifecycle: this is a plain in-memory field with no persistence of its own: if the
 //     VPN service process is killed and restarted, `onStartCommand` runs again with a brand new
 //     `ForwardingEngine`, and this field starts `null` again — no cross-process staleness is
 //     possible by construction.
 // The one case a re-query cannot fully rule out: Android reusing a freed appId for a *different*
 // package after the target is uninstalled and something else is installed in the same boot (rare,
 // and not observable from this API alone) — flagged in STATE.md as a known, bounded residual risk
 // rather than silently assumed away.
 @Volatile private var lastKnownTargetUid: Int? = null

 override fun resolveTargetUid(targetPackage: String): Int? {
  return try {
   val resolved = vpnService.packageManager.getPackageUid(targetPackage, 0)
   lastKnownTargetUid = resolved
   resolved
  } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
   // Definitive "not installed" — never fall back to a stale cached value here; a removed target
   // must resolve to null, not to whatever UID it used to have.
   lastKnownTargetUid = null
   null
  } catch (_: Exception) {
   // A transient PackageManager/Binder failure, not a "not found" — distinct from the case above:
   // fall back to the last genuinely-resolved value (if any) rather than manufacturing a spurious
   // UNKNOWN from a momentary hiccup unrelated to the package's actual install state.
   lastKnownTargetUid
  }
 }

 override fun resolveConnectionOwnerUid(localIp: ByteArray, localPort: Int, remoteIp: ByteArray, remotePort: Int): OwnerUidLookup {
  val cm = vpnService.getSystemService(android.net.ConnectivityManager::class.java)
   ?: return OwnerUidLookup.Unknown("ConnectivityManager unavailable")

  // Verify, don't assume: TunSink's own doc says localIp is expected to equal this tunnel's
  // assigned address because of how Android routes VPN traffic — but that is a documented
  // *expectation* about platform behavior, not something this code enforces elsewhere, so a
  // silent violation of it would otherwise be invisible. Use the real observed localIp for the
  // actual lookup regardless (it is the more correct value by construction — it's what the OS
  // itself captured on the wire), and only log if it ever diverges from tunAddress.
  if (!localIp.contentEquals(tunAddress)) {
   onEvidenceCb(
    "ForwardingEngine.resolveConnectionOwnerUid", "OBSERVED",
    "localIp (${localIp.joinToString(".") { (it.toInt() and 0xFF).toString() }}) != tunAddress " +
     "(${tunAddress.joinToString(".") { (it.toInt() and 0xFF).toString() }}) — VPN routing " +
     "assumption violated for this connection; proceeding with the observed localIp anyway"
   )
  }

  fun attempt(): OwnerUidLookup {
   return try {
    val local = java.net.InetSocketAddress(java.net.InetAddress.getByAddress(localIp), localPort)
    val remote = java.net.InetSocketAddress(java.net.InetAddress.getByAddress(remoteIp), remotePort)
    val uid = cm.getConnectionOwnerUid(android.system.OsConstants.IPPROTO_TCP, local, remote)
    if (uid == android.os.Process.INVALID_UID) {
     OwnerUidLookup.Unknown("INVALID_UID")
    } else {
     OwnerUidLookup.Resolved(uid)
    }
   } catch (e: SecurityException) {
    OwnerUidLookup.Unknown("SecurityException: ${e.message}")
   } catch (e: Exception) {
    OwnerUidLookup.Unknown("${e.javaClass.simpleName}: ${e.message}")
   }
  }

  val first = attempt()
  if (first is OwnerUidLookup.Resolved) return first
  // Bounded single retry — "connection lifetime races": the OS's own connection-owner table can
  // briefly lag a just-connected socket by a few milliseconds under load. One retry, one short
  // fixed delay, never more, and never on a per-packet basis (this whole function runs at most
  // once per connection, at preamble-send time).
  try { Thread.sleep(25) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return first }
  return attempt()
 }

 // See TunSink.runOnSelectorThread's doc: this is the root-cause-gate fix for cross-thread
 // Selector.register() races. Registrations queued here are drained at the top of every
 // selectLoop iteration, strictly before the next blocking select() call.
 //
 // Upload Throughput Root Cause Gate item 4: each entry now carries its enqueue timestamp so
 // drainPendingSelectorTasks can measure how long a task actually waited, and the counters below
 // track queue depth/throughput — this queue is on the upload hot path (TcpProxy.updateInterest
 // calls runOnSelectorThread on every accepted upload chunk), so it is a prime suspect.
 private class PendingSelectorTask(val enqueuedAtNanos: Long, val run: () -> Unit)
 private val pendingSelectorTasks = java.util.concurrent.ConcurrentLinkedQueue<PendingSelectorTask>()
 private val tasksQueuedTotal = AtomicLong(0)
 private val tasksExecutedTotal = AtomicLong(0)
 private val taskWaitNanosSum = AtomicLong(0)
 private val taskWaitNanosMax = AtomicLong(0)
 @Volatile private var maxQueueDepthObserved = 0

 override fun runOnSelectorThread(task: () -> Unit) {
  if (Thread.currentThread() === selectorThread) {
   try { task() } catch (e: Exception) { onEvidenceCb("ForwardingEngine.runOnSelectorThread", "FAIL", "${e.javaClass.simpleName}: ${e.message}") }
   return
  }
  pendingSelectorTasks.add(PendingSelectorTask(System.nanoTime(), task))
  tasksQueuedTotal.incrementAndGet()
  val depth = pendingSelectorTasks.size // diagnostic only: benign race against concurrent poll()s, not used for any admission decision
  if (depth > maxQueueDepthObserved) maxQueueDepthObserved = depth
  try { selector.wakeup() } catch (_: Exception) {}
 }

 private fun drainPendingSelectorTasks() {
  while (true) {
   val pending = pendingSelectorTasks.poll() ?: break
   val waitNanos = System.nanoTime() - pending.enqueuedAtNanos
   taskWaitNanosSum.addAndGet(waitNanos)
   taskWaitNanosMax.updateAndGet { maxOf(it, waitNanos) }
   tasksExecutedTotal.incrementAndGet()
   try { pending.run() } catch (e: Exception) { onEvidenceCb("ForwardingEngine.runOnSelectorThread", "FAIL", "${e.javaClass.simpleName}: ${e.message}") }
  }
 }

 fun start() {
  if (!running.compareAndSet(false, true)) return
  readerThread = Thread({ readLoop() }, "spike-tun-reader").apply { isDaemon = true; start() }
  selectorThread = Thread({ selectLoop() }, "spike-tun-selector").apply { isDaemon = true; start() }
  httpsEngine?.start()
  onEvidenceCb("ForwardingEngine.start", "PASS", "")
 }

 fun stop() {
  if (!running.compareAndSet(true, false)) return
  val udpSessions = udpNat.activeCount(); val tcpConnections = tcpProxy.activeCount() // snapshot before closeAll() zeroes them
  try { selector.wakeup() } catch (_: Exception) {}
  try { tunFd.close() } catch (_: Exception) {} // closes the underlying fd, which unblocks readLoop's pending read()
  try { readerThread?.join(2000) } catch (_: Exception) {}
  try { selectorThread?.join(2000) } catch (_: Exception) {}
  udpNat.closeAll(); tcpProxy.closeAll() // release every real socket the engine opened — these otherwise leak until GC
  httpsEngine?.stop()
  try { selector.close() } catch (_: Exception) {}
  val idle = checkFullyIdle()
  val snap = DiagSnapshot.take()
  onEvidenceCb(
   "ForwardingEngine.stop", "PASS",
   "packetsIn=$packetsIn packetsOut=$packetsOut udpSessions=$udpSessions tcpConnections=$tcpConnections " +
    "droppedIpv6=$droppedIpv6 droppedOversized=$droppedOversized droppedMalformed=$droppedMalformed droppedOther=$droppedOther " +
    "globalBufferedBytesAtStop=${globalBufferedBytes.get()} invariantViolations=${idle.size} " +
    "threads=${snap.threads} vmRssKb=${snap.vmRssKb} javaHeapUsed=${snap.javaHeapUsedBytes} nativeHeap=${snap.nativeHeapAllocatedBytes}"
  )
  idle.forEach { ConnDiag.invariantViolation("stop", it) }
 }

 /** True once [stop] has fully released every socket/thread this engine owns — see the hardening gate's socket/thread-cleanup checks. */
 fun isFullyStopped(): Boolean =
  !running.get() && udpNat.activeCount() == 0 && tcpProxy.activeCount() == 0 &&
   (readerThread == null || !readerThread!!.isAlive) && (selectorThread == null || !selectorThread!!.isAlive)

 private fun readLoop() {
  val buf = ByteArray(limits.maxAcceptedPacketBytes + 1) // +1 so a packet that exactly fills the limit is distinguishable from one that overflowed it
  while (running.get()) {
   val n = try { input.read(buf) } catch (e: Exception) { if (running.get()) onEvidenceCb("ForwardingEngine.readLoop", "FAIL", e.toString()); break }
   if (n <= 0) continue
   packetsIn++
   if (n > limits.maxAcceptedPacketBytes) {
    droppedOversized++
    observe(NetworkObservation.ResourceLimitExceeded(java.time.Instant.now(), "maxAcceptedPacketBytes", n.toLong(), limits.maxAcceptedPacketBytes.toLong()))
    continue
   }
   try { dispatch(buf, n) } catch (e: Exception) { onEvidenceCb("ForwardingEngine.dispatch", "FAIL", "${e.javaClass.simpleName}: ${e.message}") }
  }
 }

 private fun dispatch(buf: ByteArray, n: Int) {
  when (val result = PacketValidation.classify(buf, n)) {
   is PacketValidation.Result.Udp -> udpNat.onOutbound(buf, 0, result.ihl, n)
   is PacketValidation.Result.Tcp -> tcpProxy.onOutbound(buf, 0, result.ihl, n)
   PacketValidation.Result.Ipv6 -> droppedIpv6++
   PacketValidation.Result.Malformed -> droppedMalformed++
   PacketValidation.Result.Other -> droppedOther++ // ICMP and anything else: not forwarded by this spike
  }
 }

 /**
  * Root-cause gate item 1/2: every iteration is timed end-to-end with a monotonic clock, and a
  * stall past 100/500/1000ms is reported via [ConnDiag.selectorStall] (with a thread dump above
  * 500ms) — this is the instrument that would catch "the selector thread was busy/blocked and
  * didn't service ready connections for hundreds of milliseconds", which a thread that just looks
  * alive in `dumpsys`/`/proc` would not reveal.
  */
 private fun selectLoop() {
  var lastSweep = System.currentTimeMillis()
  var lastDiagSample = System.currentTimeMillis()
  while (running.get()) {
   drainPendingSelectorTasks() // apply any registration queued by the reader thread before blocking again — see runOnSelectorThread's doc
   val iterationStart = System.nanoTime()
   val selectStart = iterationStart
   val ready = try { selector.select(1000) } catch (e: ClosedSelectorException) { break } catch (e: Exception) {
    if (running.get()) onEvidenceCb("ForwardingEngine.selectLoop", "FAIL", e.toString()); break
   }
   val selectNanos = System.nanoTime() - selectStart
   if (!running.get()) break
   if (ready > 0) {
    val it = selector.selectedKeys().iterator()
    while (it.hasNext()) {
     val key = it.next(); it.remove()
     val handler = key.attachment() as? SelectHandler ?: continue
     // Item 2, explicitly: one connection's handler throwing must never stop the rest of this
     // batch (or the loop itself) from being processed — this try/catch is the guarantee.
     try {
      if (key.isValid && key.isConnectable) handler.onConnectable()
      if (key.isValid && key.isReadable) handler.onReadable()
      if (key.isValid && key.isWritable) handler.onWritable()
     } catch (e: Exception) { onEvidenceCb("ForwardingEngine.selectHandler", "FAIL", "${e.javaClass.simpleName}: ${e.message}") }
    }
   }
   val now = System.currentTimeMillis()
   if (now - lastSweep > 15_000) {
    // Guarded even though TcpProxy/UdpNat now guard each item internally — belt and suspenders
    // against exactly the "one bad TCB kills the selector thread" failure mode this gate exists
    // to rule out; TcpProxy's sweepIdle previously had no such guard at all (see its doc comment).
    try { udpNat.sweepIdle() } catch (e: Exception) { onEvidenceCb("ForwardingEngine.udpSweep", "FAIL", e.toString()) }
    try { tcpProxy.sweepIdle() } catch (e: Exception) { onEvidenceCb("ForwardingEngine.tcpSweep", "FAIL", e.toString()) }
    lastSweep = now
   }
   // Upload Throughput Root Cause Gate items 4/5: cheap enough to run every ~250ms for the whole
   // gate, same reasoning as selectorTick below.
   if (now - lastDiagSample > 250) {
    ConnDiag.taskQueueSample(tasksQueuedTotal.get(), tasksExecutedTotal.get(), pendingSelectorTasks.size, maxQueueDepthObserved, taskWaitNanosSum.get(), taskWaitNanosMax.get())
    LockDiag.emitSample()
    lastDiagSample = now
   }
   val iterationNanos = System.nanoTime() - iterationStart
   ConnDiag.selectorTick(iterationNanos, selectNanos, ready, tcpProxy.activeCount(), udpNat.activeCount(), globalBufferedBytes.get(), Thread.activeCount())
   // A stall is time this thread spent NOT legitimately blocked in select()'s own requested
   // 1000ms timeout — select() itself returning at ~1000ms with ready=0 is completely normal
   // idle behavior, not a stall, and must not be flagged as one (an earlier version of this
   // check did exactly that and buried every real signal under one false alarm per idle second).
   // Two distinct things ARE worth flagging: (a) our own post-select() processing (handler
   // dispatch + periodic sweep) taking too long, and (b) select() itself overshooting its
   // requested budget, which points at OS-level scheduling delay for this thread rather than
   // anything our code did.
   val processingNanos = (iterationNanos - selectNanos).coerceAtLeast(0)
   val selectOvershootNanos = selectNanos - 1_000_000_000L
   val processingMs = processingNanos / 1_000_000
   val selectOvershootMs = selectOvershootNanos / 1_000_000
   when {
    processingMs >= 1000 -> ConnDiag.selectorStall(1000, processingNanos, "post-select processing took ${processingMs}ms, ready=$ready")
    processingMs >= 500 -> ConnDiag.selectorStall(500, processingNanos, "post-select processing took ${processingMs}ms, ready=$ready")
    processingMs >= 100 -> ConnDiag.selectorStall(100, processingNanos, "post-select processing took ${processingMs}ms, ready=$ready")
    selectOvershootMs >= 100 -> ConnDiag.selectorStall(100, selectOvershootNanos, "select(1000) itself returned ${selectOvershootMs}ms late — thread scheduling delay, not our processing")
   }
  }
 }

 /**
  * Item 4 of the root-cause gate: engine-wide invariants, combining [TcpProxy.checkInvariants]
  * with the counters this class itself owns. Empty list = healthy. Intended to be called after
  * every workload in a diagnostic run, not only at [stop] time.
  */
 fun checkInvariants(): List<String> {
  val violations = ArrayList<String>(tcpProxy.checkInvariants())
  violations += udpNat.checkInvariants()
  val buffered = globalBufferedBytes.get()
  if (buffered < 0) violations += "globalBufferedBytes is negative: $buffered"
  if (buffered > limits.maxTotalBufferedBytes) violations += "globalBufferedBytes ($buffered) exceeds configured maximum (${limits.maxTotalBufferedBytes})"
  return violations
 }

 /** Item 4's "after everything becomes idle" checks — a stricter version of [checkInvariants] for use once a workload has fully drained. */
 fun checkFullyIdle(): List<String> {
  val violations = ArrayList<String>(checkInvariants())
  if (tcpProxy.activeCount() != 0) violations += "activeTcp expected 0, was ${tcpProxy.activeCount()}"
  if (udpNat.activeCount() != 0) violations += "activeUdp expected 0, was ${udpNat.activeCount()}"
  if (globalBufferedBytes.get() != 0L) violations += "globalBufferedBytes expected 0, was ${globalBufferedBytes.get()}"
  return violations
 }
}

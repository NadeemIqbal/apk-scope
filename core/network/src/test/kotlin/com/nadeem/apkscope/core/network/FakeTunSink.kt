package com.nadeem.apkscope.core.network

import com.nadeem.apkscope.core.model.NetworkObservation

import java.net.DatagramSocket
import java.net.Socket
import java.nio.channels.Selector
import java.util.concurrent.atomic.AtomicLong

/** Test double for [TunSink]: no Android dependency, no real VPN — records everything instead of touching a device. Open so a test can override e.g. [protectSocket] to simulate a specific failure. */
open class FakeTunSink(
 override val limits: EngineLimits = EngineLimits.DEFAULT,
 override val localSubnets: List<DestinationPolicy.LocalSubnet> = emptyList(),
) : TunSink {
 override val selector: Selector = Selector.open()
 override val tunAddress: ByteArray = TestPackets.CLIENT_TUN_ADDR
 val writtenPackets = mutableListOf<ByteArray>()
 val observations = mutableListOf<NetworkObservation>()
 val evidence = mutableListOf<Triple<String, String, String>>()
 private val globalBuffered = AtomicLong(0)

 override fun writeToTun(packet: ByteArray, length: Int) { writtenPackets.add(packet.copyOf(length)) }
 open override fun protectSocket(socket: Socket): Boolean = true // no real VpnService in a JVM unit test
 override fun protectDatagram(socket: DatagramSocket): Boolean = true
 override fun onEvidence(test: String, result: String, detail: String) { evidence.add(Triple(test, result, detail)) }
 override fun observe(observation: NetworkObservation) { observations.add(observation) }

 override fun tryReserveGlobalBuffer(bytes: Int): Boolean {
  while (true) {
   val current = globalBuffered.get()
   val next = current + bytes
   if (next > limits.maxTotalBufferedBytes) return false
   if (globalBuffered.compareAndSet(current, next)) return true
  }
 }

 override fun releaseGlobalBuffer(bytes: Int) {
  if (bytes <= 0) return
  globalBuffered.updateAndGet { (it - bytes).coerceAtLeast(0) }
 }

 override fun currentGlobalBufferedBytes(): Long = globalBuffered.get()

 // These tests have no separate selector thread at all — everything runs on the calling test
 // thread — so there is nothing to queue: just run the registration immediately, same as the
 // pre-fix behavior these tests were written against.
 override fun runOnSelectorThread(task: () -> Unit) { task() }

 fun globalBufferedBytes(): Long = globalBuffered.get()
 fun closeSelector() { try { selector.close() } catch (_: Exception) {} }
}

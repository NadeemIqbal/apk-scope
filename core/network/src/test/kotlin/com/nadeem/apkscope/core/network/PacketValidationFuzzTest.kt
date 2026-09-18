package com.nadeem.apkscope.core.network

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * Property-style fuzzing of the packet-parser security boundary: [PacketValidation.classify] must
 * never throw, and must never hand out an `ihl` that lets a caller read outside `[0, n)`, no matter
 * what garbage bytes it is given. Uses a fixed seed so a failure is always reproducible.
 */
class PacketValidationFuzzTest {
 @Test fun neverThrowsOnPureRandomBytesOfAnyLength() {
  val random = Random(42)
  repeat(20_000) {
   val bufSize = random.nextInt(0, 2000)
   val buf = ByteArray(bufSize)
   random.nextBytes(buf)
   // claimedLen deliberately ranges past buf.size too, to probe the "caller lied about n" defense.
   val claimedLen = random.nextInt(0, bufSize + 200)
   val result = try { PacketValidation.classify(buf, claimedLen) } catch (e: Throwable) {
    fail("classify() threw ${e.javaClass.simpleName} for claimedLen=$claimedLen buf.size=$bufSize: ${e.message}"); return
   }
   assertIhlIsSafe(result, minOf(claimedLen, bufSize))
  }
 }

 @Test fun neverThrowsWhenOnlyTheIpHeaderIsRandomButLengthsAreConsistent() {
  // A more "nearly valid" corpus: correct total length/IHL bookkeeping but random flags/protocol/payload —
  // closer to what a buggy-but-not-adversarial peer might send, and to what a deliberately hostile
  // packet with a valid envelope but garbage protocol semantics looks like.
  val random = Random(7)
  repeat(10_000) {
   val payloadLen = random.nextInt(0, 128)
   val payload = ByteArray(payloadLen); random.nextBytes(payload)
   val protocol = random.nextInt(0, 256)
   val packet = TestPackets.ip(protocol, TestPackets.PUBLIC_IP, TestPackets.CLIENT_TUN_ADDR, payload)
   // Corrupt a random single byte of the header sometimes, to probe the checksum/ihl/totalLength checks specifically.
   if (random.nextBoolean()) packet[random.nextInt(0, 20)] = random.nextInt(0, 256).toByte()
   val result = try { PacketValidation.classify(packet, packet.size) } catch (e: Throwable) {
    fail("classify() threw ${e.javaClass.simpleName}: ${e.message}"); return
   }
   assertIhlIsSafe(result, packet.size)
  }
 }

 private fun assertIhlIsSafe(result: PacketValidation.Result, n: Int) {
  when (result) {
   is PacketValidation.Result.Udp -> {
    assertTrue("Udp ihl=${result.ihl} must be >=20 and leave room for a UDP header within n=$n", result.ihl in 20..n && result.ihl + Udp.HEADER_LEN <= n)
   }
   is PacketValidation.Result.Tcp -> {
    assertTrue("Tcp ihl=${result.ihl} must be >=20 and leave room for a TCP header within n=$n", result.ihl in 20..n && result.ihl + Tcp.HEADER_LEN <= n)
   }
   else -> {} // Ipv6/Malformed/Other carry no offset a caller could misuse
  }
 }
}

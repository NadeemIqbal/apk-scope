package com.nadeem.apkscope.core.network

/** Builds well-formed IPv4 packets for tests — the mirror image of what a real Android kernel would hand the TUN. */
object TestPackets {
 fun ip(protocol: Int, srcIp: ByteArray, dstIp: ByteArray, payload: ByteArray, id: Int = 1): ByteArray {
  val packet = ByteArray(20 + payload.size)
  Ipv4.writeHeader(packet, 0, protocol, srcIp, dstIp, payload.size, id)
  System.arraycopy(payload, 0, packet, 20, payload.size)
  return packet
 }

 fun udp(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, payload: ByteArray): ByteArray {
  val seg = ByteArray(Udp.HEADER_LEN + payload.size)
  Udp.writeDatagram(seg, 0, srcPort, dstPort, srcIp, dstIp, payload, 0, payload.size)
  return ip(Ipv4.PROTO_UDP, srcIp, dstIp, seg)
 }

 fun tcpSyn(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, seq: Long = 1000L): ByteArray {
  val seg = ByteArray(Tcp.HEADER_LEN)
  val len = Tcp.writeSegment(seg, 0, srcPort, dstPort, seq, 0L, Tcp.SYN, 65535, srcIp, dstIp)
  return ip(Ipv4.PROTO_TCP, srcIp, dstIp, seg.copyOf(len))
 }

 val PUBLIC_IP = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34) // example.com-range public address, not used for real connections in these tests
 val TEST_NET = byteArrayOf(192.toByte(), 0, 2, 1) // RFC 5737 TEST-NET-1: reserved, never routed — safe for tests that must exercise "policy allows, socket opens"
 val LOOPBACK = byteArrayOf(127, 0, 0, 1)
 val LINK_LOCAL_METADATA = byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte())
 val PRIVATE_10 = byteArrayOf(10, 1, 2, 3)
 val PRIVATE_172 = byteArrayOf(172.toByte(), 20, 0, 1)
 val PRIVATE_192 = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)
 val MULTICAST = byteArrayOf(224.toByte(), 0, 0, 1)
 val BROADCAST = byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte())
 val THIS_NETWORK = byteArrayOf(0, 1, 2, 3)
 val CLIENT_TUN_ADDR = byteArrayOf(10, 123, 0, 1)
}

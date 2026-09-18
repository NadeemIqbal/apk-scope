package com.nadeem.apkscope.core.network

/**
 * The first line of defense against hostile TUN input: classifies a raw packet buffer into
 * "hand this to UdpNat", "hand this to TcpProxy", or one of the drop reasons — without trusting a
 * single header field until its bounds and internal consistency are checked. Pulled out of
 * [ForwardingEngine] as a pure function (no Android dependency) specifically so it can be unit- and
 * fuzz-tested on its own; [ForwardingEngine.dispatch] is a thin wrapper around [classify].
 */
object PacketValidation {
 sealed interface Result {
  /** [ihl] is safe to use as the transport-header offset into a buffer of at least [n] valid bytes. */
  data class Udp(val ihl: Int) : Result
  data class Tcp(val ihl: Int) : Result
  data object Ipv6 : Result
  data object Malformed : Result
  /** A structurally valid IPv4 header for a protocol this engine does not forward (e.g. ICMP). */
  data object Other : Result
 }

 fun classify(buf: ByteArray, n: Int): Result {
  if (n < 20 || n > buf.size) return Result.Malformed // shorter than a minimal IPv4 header, or a caller lied about how much of buf is valid
  if (Ipv4.version(buf, 0) != 4) return Result.Ipv6 // IPv6 forwarding is out of scope for this spike — see DestinationPolicy's doc
  val ihl = Ipv4.ihl(buf, 0)
  if (ihl < 20 || ihl > n) return Result.Malformed
  val totalLen = Ipv4.totalLength(buf, 0)
  if (totalLen < ihl || totalLen > n) return Result.Malformed // header lies about the packet's own length
  if (!Ipv4.headerChecksumValid(buf, 0, ihl)) return Result.Malformed
  return when (Ipv4.protocol(buf, 0)) {
   Ipv4.PROTO_UDP -> if (ihl + Udp.HEADER_LEN <= n) Result.Udp(ihl) else Result.Malformed
   Ipv4.PROTO_TCP -> if (ihl + Tcp.HEADER_LEN <= n) Result.Tcp(ihl) else Result.Malformed
   else -> Result.Other
  }
 }
}

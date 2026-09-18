package com.nadeem.apkscope.core.network

/**
 * Central allow/deny decision for every destination the forwarding engine is about to open a real,
 * protected socket to — consulted by [UdpNat] and [TcpProxy] *before* `SocketChannel.open()`/
 * `DatagramChannel.open()`, so a denied destination never gets a real socket at all.
 *
 * ## Chosen default (explicit, not silent)
 * **Default-allow the open internet; default-deny loopback, link-local, multicast, broadcast, and
 * RFC 1918 private ranges — including the device's own current local subnet, when known.**
 *
 * The sandbox's job is to observe how an untrusted app behaves against the real internet. A
 * default-deny-everything policy would defeat that purpose. But nothing about that job requires
 * letting a sandboxed app use APK Scope's `protect()`ed sockets as a pivot to probe the phone's
 * own loopback interface, its Wi-Fi LAN (printers, routers, IoT, other phones), or link-local
 * metadata-style addresses (`169.254.169.254` is the AWS/GCP/Azure cloud metadata convention, and
 * blocking all of `169.254.0.0/16` costs nothing on a phone, which has no legitimate use for it).
 * Those ranges are denied unconditionally, regardless of what the underlying network is.
 *
 * IPv6 destinations are all denied here too, but this is redundant in the current architecture:
 * [ForwardingEngine] never routes IPv6 into the TUN in the first place (see its class doc), so an
 * IPv6 [Destination] should never reach this policy. The check exists anyway as defense in depth,
 * in case that invariant is ever broken by a future change.
 */
object DestinationPolicy {
 enum class Verdict { ALLOW, DENY }

 data class Decision(val verdict: Verdict, val reason: String)

 sealed interface Destination {
  data class V4(val octets: ByteArray) : Destination
  data class V6(val bytes: ByteArray) : Destination
 }

 /** A local subnet to additionally deny, as CIDR (address + prefix length). Populate from the underlying network's own LinkProperties — see [forCurrentNetwork]. */
 data class LocalSubnet(val network: ByteArray, val prefixLength: Int)

 private val ALLOW = Decision(Verdict.ALLOW, "not in any denied range")

 fun evaluate(destination: Destination, localSubnets: List<LocalSubnet> = emptyList()): Decision = when (destination) {
  is Destination.V4 -> evaluateV4(destination.octets, localSubnets)
  is Destination.V6 -> evaluateV6(destination.bytes)
 }

 private fun evaluateV4(ip: ByteArray, localSubnets: List<LocalSubnet>): Decision {
  require(ip.size == 4) { "IPv4 address must be 4 bytes" }
  val a = ip[0].toInt() and 0xFF
  val b = ip[1].toInt() and 0xFF
  if (a == 0) return Decision(Verdict.DENY, "0.0.0.0/8 (this network)")
  if (a == 127) return Decision(Verdict.DENY, "127.0.0.0/8 (loopback)")
  if (a == 169 && b == 254) return Decision(Verdict.DENY, "169.254.0.0/16 (link-local / cloud metadata range)")
  if (a in 224..239) return Decision(Verdict.DENY, "224.0.0.0/4 (multicast)")
  if (a == 255 && b == 255 && ip[2].toInt() and 0xFF == 255 && ip[3].toInt() and 0xFF == 255) return Decision(Verdict.DENY, "255.255.255.255 (limited broadcast)")
  if (a == 10) return Decision(Verdict.DENY, "10.0.0.0/8 (RFC 1918 private)")
  if (a == 172 && b in 16..31) return Decision(Verdict.DENY, "172.16.0.0/12 (RFC 1918 private)")
  if (a == 192 && b == 168) return Decision(Verdict.DENY, "192.168.0.0/16 (RFC 1918 private)")
  for (subnet in localSubnets) {
   if (subnet.network.size == 4 && matchesPrefix(ip, subnet.network, subnet.prefixLength)) {
    return Decision(Verdict.DENY, "device's current local subnet (${describeV4(subnet.network)}/${subnet.prefixLength})")
   }
  }
  return ALLOW
 }

 private fun evaluateV6(ip: ByteArray): Decision {
  require(ip.size == 16) { "IPv6 address must be 16 bytes" }
  if (ip.all { it == 0.toByte() } || (ip.copyOfRange(0, 15).all { it == 0.toByte() } && ip[15] == 1.toByte())) {
   return Decision(Verdict.DENY, "::1 (IPv6 loopback) — unreachable anyway, no IPv6 route exists")
  }
  if ((ip[0].toInt() and 0xFF) == 0xFE && (ip[1].toInt() and 0xC0) == 0x80) return Decision(Verdict.DENY, "fe80::/10 (IPv6 link-local) — unreachable anyway")
  if ((ip[0].toInt() and 0xFE) == 0xFC) return Decision(Verdict.DENY, "fc00::/7 (IPv6 unique local) — unreachable anyway")
  return Decision(Verdict.DENY, "IPv6 is not forwarded by this engine at all (fail-closed by design)")
 }

 private fun matchesPrefix(ip: ByteArray, network: ByteArray, prefixLength: Int): Boolean {
  if (prefixLength !in 0..32) return false
  var remaining = prefixLength
  for (i in ip.indices) {
   if (remaining <= 0) break
   val bits = remaining.coerceAtMost(8)
   val mask = if (bits == 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
   if ((ip[i].toInt() and mask) != (network[i].toInt() and mask)) return false
   remaining -= bits
  }
  return true
 }

 private fun describeV4(ip: ByteArray) = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
}

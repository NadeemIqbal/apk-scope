package com.nadeem.apkscope.spike
import android.net.VpnService
import android.net.ConnectivityManager
import android.app.*
import android.content.Intent
import android.os.ParcelFileDescriptor
import com.nadeem.apkscope.core.network.DestinationPolicy
import com.nadeem.apkscope.core.network.EngineLimits
import com.nadeem.apkscope.core.network.ForwardingEngine
import com.nadeem.apkscope.core.report.NetworkObservationLog

/**
 * Spike A action ("ESTABLISH"): lifecycle and lockdown probe only, no forwarding — the TUN is a
 * deliberate black hole. Spike B action ("ESTABLISH_FORWARDING"): the same lockdown, but backed by
 * [ForwardingEngine] so IPv4 TCP/UDP traffic actually reaches the real network. Only one TUN is
 * ever open at a time; switching modes closes whichever is currently active first.
 */
class GateVpnService:VpnService() {
 private var tun:ParcelFileDescriptor?=null
 private var forwarding:ForwardingEngine?=null
 private val forwardingTunAddress=byteArrayOf(10,123,0,1)
 // Root-caused during the Forwarding Reliability Root Cause Gate's 20-establish/stop-cycle memory
 // test: NetworkObservationLog's constructor starts a background writer thread that runs forever
 // (blocked on its queue) — constructing a *fresh* one on every ESTABLISH_FORWARDING leaked
 // exactly one thread per cycle, confirmed via /proc/<pid>/status showing perfectly linear growth
 // (27, 28, 29, ... 46 threads across 20 cycles, no plateau). One instance for this Service's whole
 // lifetime, reused across every ForwardingEngine it constructs, fixes it.
 private val networkObservationLog by lazy { NetworkObservationLog(this) }
 override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int {
  val manager=getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel("spike-vpn","Spike VPN test",NotificationManager.IMPORTANCE_LOW))
  val notification=Notification.Builder(this,"spike-vpn").setSmallIcon(android.R.drawable.ic_lock_lock)
   .setContentTitle("Spike A — no forwarding") .setContentText("Network traffic is blocked for this test.").build()
  startForeground(42,notification)
  try {
   // VpnService.Builder().establish() silently returns null (no exception, no system log) unless
   // this package is the system's currently "prepared" VPN app for this user — that registration
   // only happens via a successful VpnService.prepare() call (either the user approving the
   // consent UI it returns, or, as here, an appops ACTIVATE_VPN grant letting prepare() bypass the
   // UI and return null immediately since this is the profile owner's own admin package). Every
   // establish path needs this, not just the first one ever run per device.
   if (i?.action == "ESTABLISH" || i?.action == "ESTABLISH_FORWARDING") {
    val consentIntent = prepare(this)
    if (consentIntent != null) {
     Evidence.record(this, "VpnService.prepare", "FAIL", "consent required — grant appops ACTIVATE_VPN for this package/profile or approve the VPN consent UI once")
     return START_NOT_STICKY
    }
   }
   if(i?.action=="CLOSE") { closeAll(); Evidence.record(this,"TunClosed","PASS","Lockdown retained; no forwarding") }
   else if(i?.action=="ESTABLISH") {
    closeAll()
    tun=Builder().setSession("Spike A non-forwarding test").addAddress("10.123.0.1",32)
     .addAddress("fd12:3456::1",128).addRoute("0.0.0.0",0).addRoute("::",0).establish()
    Evidence.record(this,"VpnService.establish",if(tun!=null) "PASS" else "FAIL","uid=${android.os.Process.myUid()}; no forwarding")
   } else if(i?.action=="ESTABLISH_FORWARDING") {
    closeAll()
    // Snapshot the real underlying network's subnet(s) *before* establish() below — once the VPN is
    // up, activeNetwork could reflect our own TUN instead of the real Wi-Fi/cellular link.
    val localSubnets=currentLocalSubnets()
    // Checkpoint 5.1, item 11's isolated DNS experiment: `--ez useDnsServer true` adds an explicit
    // VPN DNS server, testing whether that alone is enough to route *ordinary* app DNS resolution
    // (InetAddress/HttpsURLConnection, not a raw UDP/53 socket) through this observable TUN. Disposable
    // test-only branch — the production SandboxVpnService is untouched by this.
    val useDnsServer = i.getBooleanExtra("useDnsServer", false)
    Evidence.record(this,"dnsExperiment","STARTED","useDnsServer=$useDnsServer")
    // IPv4 only: no ::/0 route here, so IPv6 flows fail closed immediately (ENETUNREACH-style)
    // instead of being silently blackholed by an engine that doesn't parse IPv6 at all.
    val builder = Builder().setSession("Spike B forwarding test").setMtu(1500)
     .addAddress("10.123.0.1",32).addRoute("0.0.0.0",0)
    if (useDnsServer) builder.addDnsServer("1.1.1.1")
    val builtTun=builder.establish()
    if(builtTun==null) { Evidence.record(this,"VpnService.establish","FAIL","forwarding TUN") }
    else {
     tun=builtTun
     val engine=ForwardingEngine(
      this,builtTun,forwardingTunAddress,
      limits=EngineLimits.DEFAULT,
      localSubnets=localSubnets,
      observationSink=networkObservationLog,
      caStorageDir=null,
      onEvidenceCb={ test,result,detail -> Evidence.record(this,test,result,detail) },
      sessionId=null,
      targetPackage=null
     )
     forwarding=engine
     Evidence.record(this,"VpnService.establish","PASS","uid=${android.os.Process.myUid()}; forwarding enabled (Spike B)")
     engine.start()
    }
   } else Evidence.record(this,"AlwaysOnServiceStart","PASS","Service started without TUN; lockdown should block traffic")
  } catch(e:Exception) { Evidence.record(this,"VPN lifecycle","FAIL",e.toString()) }
  return START_NOT_STICKY
 }
 /** The device's real underlying network's own IPv4 address(es)/prefix — denied under [DestinationPolicy] as "the device's current local subnet", in addition to the unconditional RFC 1918 ranges. */
 private fun currentLocalSubnets(): List<DestinationPolicy.LocalSubnet> {
  return try {
   val cm=getSystemService(ConnectivityManager::class.java)
   val network=cm.activeNetwork?:return emptyList()
   val props=cm.getLinkProperties(network)?:return emptyList()
   props.linkAddresses.mapNotNull { la ->
    val addr=la.address.address
    if(addr.size==4) DestinationPolicy.LocalSubnet(addr, la.prefixLength) else null
   }
  } catch(e:Exception) { Evidence.record(this,"currentLocalSubnets","FAIL",e.toString()); emptyList() }
 }
 private fun closeAll() {
  // The engine (when present) owns the shared ParcelFileDescriptor and closes it itself in stop() —
  // never close `tun` again afterward, since closing an already-closed ParcelFileDescriptor can throw.
  val engine=forwarding
  if(engine!=null) { forwarding=null;engine.stop();tun=null } else { tun?.close();tun=null }
 }
 override fun onDestroy() { closeAll();super.onDestroy() }
 override fun onRevoke() { Evidence.record(this,"VPN revoked","OBSERVED");closeAll();stopSelf() }
}

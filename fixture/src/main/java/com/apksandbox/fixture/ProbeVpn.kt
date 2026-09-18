package com.apksandbox.fixture
class ProbeVpn:android.net.VpnService() {
 override fun onStartCommand(i:android.content.Intent?,flags:Int,id:Int):Int {
  try {
   val tun=Builder().setSession("Unauthorized fixture probe").addAddress("10.111.0.1",32).addRoute("0.0.0.0",0).establish()
   android.util.Log.i("SandboxFixture",if(tun==null) "UNAUTHORIZED_VPN_DENIED" else "UNAUTHORIZED_VPN_ESTABLISHED")
   tun?.close()
  } catch(e:Exception) { android.util.Log.i("SandboxFixture","UNAUTHORIZED_VPN_DENIED $e") }
  stopSelf();return START_NOT_STICKY
 }
}

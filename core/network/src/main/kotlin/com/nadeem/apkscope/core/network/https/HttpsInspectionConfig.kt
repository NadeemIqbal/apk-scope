package com.nadeem.apkscope.core.network.https

/**
 * Configuration and feature toggle for the HTTPS traffic inspection POC.
 *
 * Requirements:
 * - Disabled by default behind an explicit toggle.
 * - Limited to controlled test destinations and fixture package.
 */
object HttpsInspectionConfig {
 @Volatile
 var isEnabled: Boolean = false

 @Volatile
 var targetPackage: String? = null

 @Volatile
 var inspectAllHosts: Boolean = true

 /**
  * List of supported/controlled test destinations for inspection.
  */
 val targetHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
  add("httpbin.org")
  add("jsonplaceholder.typicode.com")
  add("example.com")
  add("dummyjson.com")
  add("badssl.com")
  add("echo.websocket.org")
  add("piehost.com")
  add("socketsbay.com")
  add("reqres.in")
 }

 /**
  * Returns whether the given hostname is targeted for inspection.
  */
 fun isTargetDestination(host: String): Boolean {
  if (inspectAllHosts) return true
  val cleanHost = host.lowercase().trim()
  return targetHosts.any { target ->
   cleanHost == target || cleanHost.endsWith(".$target")
  }
 }

 fun reset() {
  isEnabled = false
  targetPackage = null
  inspectAllHosts = true
 }
}

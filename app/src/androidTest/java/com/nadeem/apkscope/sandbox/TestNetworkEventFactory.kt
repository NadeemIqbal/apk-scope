package com.nadeem.apkscope.sandbox

import android.app.admin.ConnectEvent
import android.app.admin.DnsEvent

object TestNetworkEventFactory {

 init {
  try {
   val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
   val getDeclaredMethod = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
   val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
   val getRuntimeMethod = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as java.lang.reflect.Method
   val setHiddenApiExemptionsMethod = getDeclaredMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions", arrayOf(arrayOf<String>()::class.java)) as java.lang.reflect.Method
   val vmRuntimeInstance = getRuntimeMethod.invoke(null)
   setHiddenApiExemptionsMethod.invoke(vmRuntimeInstance, arrayOf("L"))
  } catch (_: Throwable) {
  }
 }

 fun createDnsEvent(
  id: Long = 1L,
  hostname: String = "example.com",
  ipAddresses: Array<String> = arrayOf("93.184.216.34"),
  ipAddressesCount: Int = 1,
  packageName: String = "com.example.target",
  timestamp: Long = System.currentTimeMillis(),
 ): DnsEvent {
  val dnsClass = DnsEvent::class.java
  val constructor = dnsClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 5 }
   ?: dnsClass.declaredConstructors.firstOrNull { it.parameterTypes.size >= 4 }
   ?: dnsClass.declaredConstructors.first()

  constructor.isAccessible = true
  val event = when (constructor.parameterTypes.size) {
   5 -> constructor.newInstance(hostname, ipAddresses, ipAddressesCount, packageName, timestamp) as DnsEvent
   4 -> constructor.newInstance(hostname, ipAddresses, packageName, timestamp) as DnsEvent
   else -> {
    val instance = constructor.newInstance() as DnsEvent
    setField(instance, "mHostname", hostname)
    setField(instance, "mIpAddresses", ipAddresses)
    setField(instance, "mIpAddressesCount", ipAddressesCount)
    setField(instance, "mPackageName", packageName)
    setField(instance, "mTimestamp", timestamp)
    instance
   }
  }

  setField(event, "mId", id)
  return event
 }

 fun createConnectEvent(
  id: Long = 2L,
  ipAddress: String = "93.184.216.34",
  port: Int = 443,
  packageName: String = "com.example.target",
  timestamp: Long = System.currentTimeMillis(),
 ): ConnectEvent {
  val connectClass = ConnectEvent::class.java
  val constructor = connectClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 4 }
   ?: connectClass.declaredConstructors.first()

  constructor.isAccessible = true
  val event = when (constructor.parameterTypes.size) {
   4 -> constructor.newInstance(ipAddress, port, packageName, timestamp) as ConnectEvent
   else -> {
    val instance = constructor.newInstance() as ConnectEvent
    setField(instance, "mIpAddress", ipAddress)
    setField(instance, "mPort", port)
    setField(instance, "mPackageName", packageName)
    setField(instance, "mTimestamp", timestamp)
    instance
   }
  }

  setField(event, "mId", id)
  return event
 }

 private fun setField(target: Any, fieldName: String, value: Any?) {
  var clazz: Class<*>? = target.javaClass
  while (clazz != null && clazz != Any::class.java) {
   try {
    val field = clazz.getDeclaredField(fieldName)
    field.isAccessible = true
    field.set(target, value)
    return
   } catch (_: NoSuchFieldException) {
    clazz = clazz.superclass
   }
  }
 }
}

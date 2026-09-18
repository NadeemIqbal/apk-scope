package com.nadeem.apkscope.core.network

/**
 * Item 8 of the root-cause gate ("record RSS/heap/thread count") without any hidden API: reads
 * `/proc/self/status` (public, always readable by the process itself — no permission needed) for
 * process-level thread count and RSS, and `Runtime`/`android.os.Debug` (both public SDK) for JVM
 * and native heap. No supported API exposes discrete GC *events* without VM-internal hooks, so
 * that specific ask is not implemented — heap-usage trend across repeated cycles is used instead,
 * per item 8's own "look for monotonic growth" guidance.
 */
object DiagSnapshot {
 data class Snapshot(val threads: Int, val vmRssKb: Long, val javaHeapUsedBytes: Long, val nativeHeapAllocatedBytes: Long)

 fun take(): Snapshot {
  var threads = -1
  var vmRssKb = -1L
  try {
   java.io.File("/proc/self/status").forEachLine { line ->
    when {
     line.startsWith("Threads:") -> threads = line.substringAfter(":").trim().toIntOrNull() ?: -1
     line.startsWith("VmRSS:") -> vmRssKb = line.substringAfter(":").trim().removeSuffix("kB").trim().toLongOrNull() ?: -1L
    }
   }
  } catch (_: Exception) {}
  val rt = Runtime.getRuntime()
  val javaHeapUsed = rt.totalMemory() - rt.freeMemory()
  val nativeHeap = try { android.os.Debug.getNativeHeapAllocatedSize() } catch (_: Exception) { -1L }
  return Snapshot(threads, vmRssKb, javaHeapUsed, nativeHeap)
 }
}

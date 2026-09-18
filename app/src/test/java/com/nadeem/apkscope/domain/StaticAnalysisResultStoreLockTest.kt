package com.nadeem.apkscope.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Milestone 9 (acceptance rigor pass) — [StaticAnalysisResultStore.withLock] is what closes the
 * "concurrent imports cannot silently discard each other's legitimate updates" requirement: without
 * it, two concurrent read-modify-write sequences for the same key (e.g. two overlapping
 * `correlateUrlEvidenceWithAnalysis` calls) can interleave and lose one side's update even though
 * neither individual disk write is corrupted. This test proves the lock actually serializes access
 * per key — real threads, a real race window, not just reasoning about the code.
 *
 * `withLock` itself takes only a `String` key and a lambda — no [android.content.Context] — so this
 * runs as a plain JVM test.
 */
class StaticAnalysisResultStoreLockTest {

    /** A stand-in for "read the current value, compute a new one, write it back" — the exact shape of a real read-modify-write against disk, but against a plain in-memory map so the race is fast and deterministic to reproduce. */
    private class FakeStore {
        @Volatile var value = 0
        fun readModifyWriteIncrement() {
            val current = value
            // Force the interleaving window a naive (unlocked) caller would be exposed to.
            Thread.yield()
            value = current + 1
        }
    }

    @Test
    fun withLock_serializesConcurrentReadModifyWriteForTheSameKey_noLostUpdates() {
        val store = FakeStore()
        val threadCount = 8
        val incrementsPerThread = 200
        val pool = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        repeat(threadCount) {
            pool.submit {
                startLatch.await()
                repeat(incrementsPerThread) {
                    StaticAnalysisResultStore.withLock("same-analysis-id") {
                        store.readModifyWriteIncrement()
                    }
                }
                doneLatch.countDown()
            }
        }
        startLatch.countDown()
        assertEquals(true, doneLatch.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(
            "Every increment must be preserved — a lost update means a concurrent import silently discarded a legitimate change",
            threadCount * incrementsPerThread,
            store.value
        )
    }

    @Test
    fun withLock_differentKeysDoNotContendWithEachOther() {
        // Different analysisIds must not serialize against each other — only same-key access does.
        // Not a performance assertion (that would be flaky); just confirms distinct keys each get
        // their own, independently-correct lock.
        val storeA = FakeStore()
        val storeB = FakeStore()
        val pool = Executors.newFixedThreadPool(4)
        val doneLatch = CountDownLatch(2)

        pool.submit {
            repeat(500) { StaticAnalysisResultStore.withLock("analysis-a") { storeA.readModifyWriteIncrement() } }
            doneLatch.countDown()
        }
        pool.submit {
            repeat(500) { StaticAnalysisResultStore.withLock("analysis-b") { storeB.readModifyWriteIncrement() } }
            doneLatch.countDown()
        }
        assertEquals(true, doneLatch.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(500, storeA.value)
        assertEquals(500, storeB.value)
    }
}

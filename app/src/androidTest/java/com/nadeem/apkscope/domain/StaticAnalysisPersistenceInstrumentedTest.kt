package com.nadeem.apkscope.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.core.staticanalysis.ApkMetadata
import com.nadeem.apkscope.core.staticanalysis.StaticAnalysisCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Milestone 9 (second acceptance rigor pass, round 3, item 6): "the absence of network engine
 * changes does not eliminate the need to verify changed Android persistence behavior" — this pass's
 * changes to [StaticAnalysisResultStore]/[StaticAnalysisFileStore]/[AtomicFileWriter] are exercised
 * here against a **real Android [android.content.Context]** on a real device/emulator filesystem
 * (`context.filesDir`), not merely the plain-`File`-based JVM tests in `StaticAnalysisFileStoreTest`
 * (which are genuine, real file I/O too, just against the test JVM's own host filesystem rather than
 * an actual app-private Android storage area under a real package/profile UID).
 *
 * Scope note: [StaticAnalysisResultStore] is a process-wide singleton with an in-memory cache that
 * short-circuits `get()` for any sessionId already written by `put()` in this same test process —
 * several tests below deliberately write the on-disk envelope directly via [StaticAnalysisFileStore]
 * (the same production file-format code `put()`/`get()` call, just without going through the cache)
 * so a *disk-only* read genuinely exercises the real file-format/corruption-recovery path, not a
 * cache hit that would mask it.
 */
@RunWith(AndroidJUnit4::class)
class StaticAnalysisPersistenceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sampleMetadata(urlCount: Int = 1): ApkMetadata = ApkMetadata(
        sha256 = "", packageName = "com.example.test", versionName = "1.0", versionCode = 1,
        minSdkVersion = 21, targetSdkVersion = 34, requestedPermissions = emptyList(), components = emptyList(),
        debuggable = false, nativeLibraryAbis = emptyList(), networkSecurityConfigPresent = null,
        usesCleartextTraffic = false, intentFilters = emptyList(),
        embeddedUrls = List(urlCount) { i ->
            com.nadeem.apkscope.core.staticanalysis.DexUrlCandidate(
                originalString = "https://example.com/v$i",
                normalizedUrl = "https://example.com/v$i",
                host = "example.com",
                scheme = "https",
                dexEntry = "classes.dex",
                provenance = com.nadeem.apkscope.core.staticanalysis.UrlProvenance.PRESENT_IN_DEX,
            )
        },
        detectedSdks = emptyList(), apiFindings = emptyList(),
        staticCoverage = StaticAnalysisCoverage(),
    )

    /** Same convention as [StaticAnalysisResultStore]'s private `fileFor()` — duplicated here deliberately, matching its own class KDoc's documented, stable on-disk layout, so tests can reach the raw file without a package-private hook. */
    private fun rawFileFor(sessionId: String): File {
        val dir = File(context.applicationContext.filesDir, "analysis_store")
        dir.mkdirs()
        return File(dir, "$sessionId.bin")
    }

    @Test
    fun putThenGet_throughRealAndroidContext_roundTripsCorrectly_andWritesTheNewEnvelopeFormatToRealDeviceStorage() {
        val id = "instr-${UUID.randomUUID()}"
        val data = sampleMetadata(urlCount = 2)

        StaticAnalysisResultStore.put(context, id, data)
        val restored = StaticAnalysisResultStore.get(context, id)

        assertEquals(2, restored?.embeddedUrls?.size)
        assertEquals("https://example.com/v0", restored?.embeddedUrls?.get(0)?.originalString)

        // Confirm real bytes landed on the real device filesystem in the documented envelope
        // format (magic header first four bytes), not merely that the in-memory cache round-tripped.
        val file = rawFileFor(id)
        assertTrue("expected on-disk file at ${file.path}", file.exists())
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(4)
            raf.readFully(header)
            val magic = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            assertEquals(0x41535253, magic) // "ASRS" — StaticAnalysisFileStore.MAGIC
        }
    }

    @Test
    fun secondPut_forTheSameId_replacesRatherThanAccumulates_onRealDeviceStorage() {
        val id = "instr-${UUID.randomUUID()}"
        StaticAnalysisResultStore.put(context, id, sampleMetadata(urlCount = 1))
        StaticAnalysisResultStore.put(context, id, sampleMetadata(urlCount = 5))

        // Ground truth is the real on-disk file, read via the real production reader directly
        // (bypassing the singleton's own cache, which would just reflect whichever value it last saw).
        val onDisk = StaticAnalysisFileStore.read(rawFileFor(id))
        assertEquals("second write's data must fully replace the first, not merge with it", 5, onDisk?.embeddedUrls?.size)

        // Exactly one file for this id — no orphaned/duplicate envelope left behind by the
        // temp-file-then-rename atomic write sequence.
        val dir = File(context.applicationContext.filesDir, "analysis_store")
        val matching = dir.listFiles { f -> f.name == "$id.bin" || f.name.startsWith("$id.bin.") }.orEmpty()
        assertEquals("expected exactly one file for $id, found ${matching.map { it.name }}", 1, matching.size)
    }

    @Test
    fun corruptedPayload_onRealDeviceStorage_isTreatedAsACleanCacheMiss_notACrash() {
        val id = "instr-${UUID.randomUUID()}"
        // Write real, valid data directly via the production file-format writer — this id is never
        // touched by StaticAnalysisResultStore.put(), so the object's in-memory cache genuinely has
        // nothing for it, and the get() call below is forced to hit disk.
        val file = rawFileFor(id)
        StaticAnalysisFileStore.write(file, PersistedStaticData(sampleMetadata(1).embeddedUrls, emptyList(), emptyList(), sampleMetadata(1).staticCoverage))

        // Flip one payload byte in place on the real device file (header/length/CRC all left
        // otherwise intact — mirrors StaticAnalysisFileStoreTest's JVM bit-flip test, now against a
        // real Android app-private storage file rather than a JVM temp directory).
        RandomAccessFile(file, "rw").use { raf ->
            val payloadStart = 20 // 4 (magic) + 4 (version) + 4 (length) + 8 (crc)
            raf.seek((payloadStart + 5).toLong())
            val b = raf.readByte()
            raf.seek((payloadStart + 5).toLong())
            raf.writeByte((b.toInt() xor 0xFF))
        }

        val result = StaticAnalysisResultStore.get(context, id)
        assertNull("corrupted payload must be a clean cache miss, never a crash or wrong data", result)
    }

    @Test
    fun writeFailure_directoryNotWritable_preservesThePreviousGoodAnalysis_bothOnDiskAndInTheCache() {
        val id = "instr-${UUID.randomUUID()}"
        val goodData = sampleMetadata(urlCount = 1)
        StaticAnalysisResultStore.put(context, id, goodData)
        assertEquals(1, StaticAnalysisResultStore.get(context, id)?.embeddedUrls?.size)

        val dir = File(context.applicationContext.filesDir, "analysis_store")
        assertTrue("failed to make analysis_store read-only for this test", dir.setWritable(false))
        try {
            // AtomicFileWriter.writeAtomically's File.createTempFile call needs write access to this
            // exact directory — a real, on-device I/O failure (not a mocked/injected exception).
            StaticAnalysisResultStore.put(context, id, sampleMetadata(urlCount = 9))
        } finally {
            assertTrue("failed to restore analysis_store writability", dir.setWritable(true))
        }

        // The observable failure this round's fix guards: a failed write must never leave the
        // in-memory cache claiming a newer state than what is durably on disk. Both must still show
        // the *old*, good value — never the failed write's partial/absent one, and never a mismatch
        // between them.
        val cached = StaticAnalysisResultStore.get(context, id)
        assertEquals("in-memory cache must not advance past a write that never actually persisted", 1, cached?.embeddedUrls?.size)

        val onDisk = StaticAnalysisFileStore.read(rawFileFor(id))
        assertEquals("on-disk file must remain the previous good analysis after a failed write", 1, onDisk?.embeddedUrls?.size)
        assertFalse("a failed write must not silently succeed", onDisk?.embeddedUrls?.size == 9)
    }
}

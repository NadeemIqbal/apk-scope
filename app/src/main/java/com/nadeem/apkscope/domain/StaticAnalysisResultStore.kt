package com.nadeem.apkscope.domain

import android.content.Context
import android.util.Log
import com.nadeem.apkscope.core.staticanalysis.ApiFinding
import com.nadeem.apkscope.core.staticanalysis.ApkMetadata
import com.nadeem.apkscope.core.staticanalysis.DexUrlCandidate
import com.nadeem.apkscope.core.staticanalysis.NetworkSecurityConfigSummary
import com.nadeem.apkscope.core.staticanalysis.SdkFinding
import com.nadeem.apkscope.core.staticanalysis.StaticAnalysisCoverage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap

data class PersistedStaticData(
    val embeddedUrls: List<DexUrlCandidate>,
    val detectedSdks: List<SdkFinding>,
    val apiFindings: List<ApiFinding>,
    val staticCoverage: StaticAnalysisCoverage,
    /**
     * Milestone 10 (Security Audit), Phase 10.3 correction — added alongside the pre-existing
     * fields above, using this store's existing disk-cache mechanism, not a new one. Stated
     * accurately rather than assumed: because [PersistedStaticData] has no explicit
     * `serialVersionUID`, adding this field changes the class's auto-computed one, so a cache file
     * under the `analysis_store` directory (see [fileFor]) written before this change will fail Java
     * deserialization entirely (`InvalidClassException`) on first read after upgrading — this class's default
     * constructor value plays no role in that failure path; Java serialization does not gracefully
     * default one missing field while keeping the rest. This is safe, not silent data corruption,
     * because [StaticAnalysisResultStore.get] already treats *any* deserialization failure as a
     * cache miss (see that class's own doc comment) — the affected session's rich static-analysis
     * display fields (embedded URLs, detected SDKs, API findings, and now this) simply read back
     * empty/null until that APK is re-imported, exactly the same pre-existing limitation this
     * store's format already had for any prior field addition, not a new one introduced here.
     */
    val networkSecurityConfig: NetworkSecurityConfigSummary? = null,
    /** Carried alongside [networkSecurityConfig] rather than derived from it — `networkSecurityConfig == null` is ambiguous between "no attribute declared" ([networkSecurityConfigPresent] `false`/`null`) and "attribute declared but content unavailable" (which is instead represented as a non-null [networkSecurityConfig] with a set `unavailableReason`); this field disambiguates without guessing. */
    val networkSecurityConfigPresent: Boolean? = null,
) : Serializable

/**
 * Process-wide store for rich static analysis inspection findings
 * (embedded URLs, detected SDKs, security API references, and analysis coverage),
 * backed by a disk cache so findings survive process restarts and cold starts.
 *
 * Milestone 9 (persistence hardening, 2026-09-13 — MS9-PER01/MS9-PER02):
 *  - **Storage location**: was a hardcoded absolute path
 *    (`/data/data/com.nadeem.apkscope/files/analysis_store`) — brittle (wrong on a device with a
 *    different package-data root, e.g. a work profile's own `/data/user/<id>/...`) and untestable
 *    without a real device filesystem at that exact path. Now derived from
 *    `context.applicationContext.filesDir`, matching every other store in this codebase.
 *  - **Format validation + integrity check, precisely scoped** (correction, 2026-09-13: the previous
 *    version of this comment called this an "integrity gate" without qualification, which overstated
 *    what a bare magic/version check catches — corrected here, and the check itself strengthened to
 *    match the stronger claim rather than just the wording being softened). The on-disk envelope is
 *    now `[MAGIC][FORMAT_VERSION][payload length][CRC32 of the payload bytes][payload bytes]`. The
 *    magic/version pair is **format validation**: it rejects a file that isn't this store's own
 *    format at all (foreign content, a future/incompatible version) before any deserialization is
 *    attempted. The CRC32 is a **real integrity check** on top of that: it detects payload
 *    corruption (a bit-flip, a truncated file, disk damage) that would otherwise still look
 *    "well-formed" to the magic/version check alone and get fed into `readObject()`. **What this
 *    still does not do**: it does not distinguish a legitimate write of this store's own from a
 *    deliberately crafted malicious payload that happens to carry a correct magic/version/CRC32 (an
 *    attacker able to write arbitrary bytes to this exact path could compute a valid CRC32 over
 *    whatever malicious serialized object graph they constructed) — CRC32 is an integrity check
 *    against accidental corruption, not a cryptographic authentication mechanism, and
 *    `ObjectInputStream.readObject()` is still called on the payload once the header and checksum
 *    match. Full elimination of arbitrary-class deserialization would mean replacing
 *    `PersistedStaticData`'s wire format entirely (a manual/versioned encoding for every nested
 *    model class) — a substantially larger change than this pass made, and **not done** — do not
 *    read this store's deserialization as safe merely because a header/checksum is present.
 *  - **Observable failure**: every silent `catch (_: Throwable) {}` around a write or a
 *    header/deserialization rejection now logs via `Log.w`/`Log.e` — still not a durable,
 *    UI-visible status the way [com.nadeem.apkscope.domain.sandbox.UrlEvidenceImportStatusStore] is for
 *    URL-evidence imports specifically (that store has a natural per-analysis UI surface already;
 *    this one's failures are much rarer — an actual disk error, not "no evidence yet" — and a
 *    dedicated durable/UI-facing status for it was judged disproportionate to add this pass).
 *
 * The actual file I/O (header envelope, atomic write, corruption-safe read) is implemented against
 * a plain [File] in [StaticAnalysisFileStore] specifically so it can be unit-tested directly
 * without a real Android [Context] or Robolectric — see `StaticAnalysisFileStoreTest`. This object
 * is the thin, [Context]-dependent, in-memory-caching shell around it.
 */
object StaticAnalysisResultStore {
    private const val TAG = "StaticAnalysisResultStore"
    private val cache = ConcurrentHashMap<String, ApkMetadata>()

    // Milestone 9 (acceptance rigor pass, 2026-09-13) — "ensure concurrent imports cannot silently
    // discard each other's legitimate updates": get()+put() are each individually safe (atomic file
    // replace, no torn writes), but nothing previously serialized the *read-modify-write cycle* two
    // independent callers (e.g. two concurrent correlateUrlEvidenceWithAnalysis calls for the same
    // analysisId, or a correlation racing a fresh persistCompletedAnalysis) perform against the same
    // key — two such cycles interleaving is a classic lost-update race: both read the old value,
    // both compute their own update independently, and whichever write() call happens to run last
    // silently discards the other's legitimate change, with no corruption and no error to signal it.
    // [withLock] gives callers doing a read-modify-write sequence a per-analysisId mutex to close
    // that race — see its use in SandboxSessionCoordinator.correlateUrlEvidenceWithAnalysis.
    //
    // **Scope, stated precisely (2026-09-13, third round)**: this is an in-process lock — a plain
    // `ConcurrentHashMap`-backed JVM `synchronized`, valid only for threads/coroutines inside a
    // single process. It provides **no protection whatsoever against a second OS process** writing
    // the same file concurrently. That is not a gap in practice, though, because no second process
    // ever can: `fileFor()` derives its path from `context.applicationContext.filesDir`, and Android
    // gives every (package, user-profile) pair its own private `filesDir` — the Work-profile install
    // of this same app package runs as a different UID with a physically separate storage area
    // (`/data/user/<workProfileId>/com.nadeem.apkscope/...`), so even if Work-side code also called this
    // object, it would be reading/writing an entirely different on-disk file, not contending for
    // this one. Every real writer of a given profile's `analysis_store` file is that same profile's
    // single app process — the exact set this lock covers. If a future change ever had two distinct
    // processes legitimately write the *same* path (e.g. a shared/external storage location), this
    // lock would need to become a real cross-process file lock (e.g. `FileChannel.lock()`) — it does
    // not become one automatically, and must not be described as if it already were.
    private val locks = ConcurrentHashMap<String, Any>()

    /** Runs [block] (a get-then-put sequence, typically) holding this store's own per-[sessionId] lock, so a concurrent caller for the *same* sessionId **within this process** cannot interleave with it and silently lose either side's update. Different sessionIds never contend with each other. Does not, and cannot, protect against a second process — see the scope note above. */
    fun <T> withLock(sessionId: String, block: () -> T): T {
        val lock = locks.computeIfAbsent(sessionId) { Any() }
        synchronized(lock) { return block() }
    }

    private fun fileFor(context: Context, sessionId: String): File {
        val dir = File(context.applicationContext.filesDir, "analysis_store")
        dir.mkdirs()
        return File(dir, "$sessionId.bin")
    }

    fun put(context: Context, sessionId: String, metadata: ApkMetadata) {
        val data = PersistedStaticData(
            embeddedUrls = metadata.embeddedUrls,
            detectedSdks = metadata.detectedSdks,
            apiFindings = metadata.apiFindings,
            staticCoverage = metadata.staticCoverage,
            networkSecurityConfig = metadata.networkSecurityConfig,
            networkSecurityConfigPresent = metadata.networkSecurityConfigPresent,
        )
        try {
            StaticAnalysisFileStore.write(fileFor(context, sessionId), data)
            // Only update the in-memory cache after the disk write actually succeeds — a failed
            // write must not leave the cache claiming a newer state than what is durably on disk
            // (the very next process restart would otherwise read back the *old* disk content,
            // silently contradicting what this process's own cache said moments before it died).
            cache[sessionId] = metadata
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to persist static analysis data for $sessionId: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun get(context: Context, sessionId: String): ApkMetadata? {
        cache[sessionId]?.let { return it }

        val data = try {
            StaticAnalysisFileStore.read(fileFor(context, sessionId))
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to read persisted static analysis data for $sessionId: ${e.javaClass.simpleName}: ${e.message}")
            null
        } ?: return null

        val restored = ApkMetadata(
            sha256 = "",
            packageName = "",
            versionName = null,
            versionCode = 0,
            minSdkVersion = 0,
            targetSdkVersion = 0,
            requestedPermissions = emptyList(),
            components = emptyList(),
            debuggable = false,
            nativeLibraryAbis = emptyList(),
            networkSecurityConfigPresent = data.networkSecurityConfigPresent,
            networkSecurityConfig = data.networkSecurityConfig,
            usesCleartextTraffic = false,
            intentFilters = emptyList(),
            embeddedUrls = data.embeddedUrls,
            detectedSdks = data.detectedSdks,
            apiFindings = data.apiFindings,
            staticCoverage = data.staticCoverage
        )
        cache[sessionId] = restored
        return restored
    }

    fun clear(context: Context, sessionId: String) {
        cache.remove(sessionId)
        try {
            fileFor(context, sessionId).delete()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to delete persisted static analysis data for $sessionId: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Clears orphaned rich-analysis cache files as part of explicit user-requested cleanup. */
    fun clearAll(context: Context) {
        cache.clear()
        try {
            val directory = File(context.applicationContext.filesDir, "analysis_store")
            directory.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to delete all persisted static analysis data: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

/**
 * The actual on-disk format for [PersistedStaticData]:
 * `[4-byte MAGIC][4-byte FORMAT_VERSION][4-byte payload length][8-byte CRC32 of the payload bytes]
 * [payload bytes — Java-serialized PersistedStaticData]`. Pure [File] I/O, no [Context] dependency,
 * so it is directly unit-testable (`StaticAnalysisFileStoreTest`) without Robolectric.
 *
 * Magic/version are **format validation** (reject a file that isn't this store's own format at all);
 * the CRC32 is a **real integrity check** on top of that (reject a structurally-plausible file whose
 * payload bytes were corrupted after being written — a bit-flip, a truncation, disk damage — before
 * ever calling `readObject()` on it). Neither is a cryptographic authentication mechanism: a party
 * able to write arbitrary bytes to this exact path could construct a payload with a correct
 * magic/version/CRC32 of their own choosing. See [com.nadeem.apkscope.domain.StaticAnalysisResultStore]'s
 * own class doc for the precise, non-overstated scope of what this does and does not guard against.
 */
object StaticAnalysisFileStore {
    /** Arbitrary 4-byte marker distinguishing this codebase's own envelope from anything else that might occupy this path (a foreign file, a future format, plain corruption). */
    private const val MAGIC = 0x41535253 // "ASRS" as bytes, read as a big-endian int
    private const val FORMAT_VERSION = 1
    private const val HEADER_BYTES = 20L
    private const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024

    fun write(target: File, data: PersistedStaticData) {
        val payload = java.io.ByteArrayOutputStream().also { buf ->
            ObjectOutputStream(buf).use { it.writeObject(data) }
        }.toByteArray()
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Static analysis cache exceeds 64 MiB" }
        val crc = java.util.zip.CRC32().apply { update(payload) }.value

        // Keep the same envelope, but stream it into the atomic temporary file instead of
        // allocating another full-size buffer and then copying that buffer into a byte array.
        AtomicFileWriter.writeAtomically(target) { output ->
            DataOutputStream(output).apply {
                writeInt(MAGIC)
                writeInt(FORMAT_VERSION)
                writeInt(payload.size)
                writeLong(crc)
                write(payload)
                flush()
            }
        }
    }

    /** Returns null for: file absent, header mismatch (wrong magic/version — a foreign or corrupted file), a length/CRC32 mismatch (payload corruption), or any deserialization failure — every one of these is treated as a cache miss, never as a reason to propagate a parsed-but-wrong object. */
    fun read(source: File): PersistedStaticData? {
        if (!source.exists() || !source.canRead()) return null
        FileInputStream(source).use { fileIn ->
            val fileSize = fileIn.channel.size()
            if (fileSize < HEADER_BYTES || fileSize - HEADER_BYTES > MAX_PAYLOAD_BYTES) return null
            val header = DataInputStream(fileIn)
            val magic = header.readInt()
            val version = header.readInt()
            // Fails closed on a foreign/corrupt/unsupported file: the payload/CRC32 read below, and
            // the object-deserialization step after it, are only ever reached once these two
            // primitive ints match exactly — a header mismatch never reaches `readObject()` at all.
            if (magic != MAGIC || version != FORMAT_VERSION) return null
            val payloadLength = header.readInt()
            if (payloadLength < 0 || payloadLength.toLong() != fileSize - HEADER_BYTES) return null
            val expectedCrc = header.readLong()
            val payload = ByteArray(payloadLength)
            header.readFully(payload) // throws EOFException on a truncated file — caught by StaticAnalysisResultStore's own try/catch, treated as a failed read, not a crash
            val actualCrc = java.util.zip.CRC32().apply { update(payload) }.value
            // The real integrity check: a structurally-valid header whose payload bytes were
            // corrupted after the fact (not merely truncated — readFully already catches that) is
            // rejected here, before `readObject()` ever sees it.
            if (actualCrc != expectedCrc) return null
            return ObjectInputStream(java.io.ByteArrayInputStream(payload)).use { it.readObject() as? PersistedStaticData }
        }
    }
}

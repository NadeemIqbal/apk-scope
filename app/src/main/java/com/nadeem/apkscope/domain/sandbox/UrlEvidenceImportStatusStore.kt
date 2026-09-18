package com.nadeem.apkscope.domain.sandbox

import android.content.Context
import com.nadeem.apkscope.domain.AtomicFileWriter
import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Milestone 9 (persistence hardening, 2026-09-13) — durable record of the outcome of the most
 * recent URL-evidence import attempt for a given analysis, so the Embedded URLs UI can show *why*
 * no new evidence appeared (a failed cross-profile pull, a rejected/invalid artifact, a correlation
 * exception, or a truncated export) instead of a silent no-op that is indistinguishable from
 * genuine success with nothing new to report. Previously this information existed only as
 * `android.util.Log` lines (`UrlEvidenceCorrelation` tag) — invisible to the app itself and to the
 * user, and gone the moment the process was killed.
 *
 * **Retention policy**: keeps only the *most recent* outcome per analysis id — a small, bounded
 * footprint answering "did the last import work, and if not, why," not a full import history.
 *
 * **Corrected, Pixel 8 acceptance (fifth pass, 2026-09-13)**: this store previously hardcoded
 * `/data/data/com.nadeem.apkscope/files/...` instead of deriving the path from
 * `context.applicationContext.filesDir` — the exact same fragility class MS9-PER02 already fixed for
 * `StaticAnalysisResultStore`, missed here because every call site to date happens to run in the
 * Personal profile (user 0), where `/data/data/<pkg>` and `context.filesDir` currently resolve to
 * the same real directory. Found while tracing why a genuinely-completed physical-device session's
 * import status file was missing: a write to a subtly wrong/inaccessible path on that specific
 * device would fail silently (caught by this store's own tolerant `catch`), producing exactly the
 * "no status file at all" symptom observed — indistinguishable, from the outside, from the import
 * never having run at all. Not confirmed as *the* cause (see the new stage-by-stage diagnostics
 * this pass adds instead of guessing), but a real, latent bug regardless of whether it was the
 * proximate one here, fixed on the same "verify, do not assume the old path is safe" basis as
 * MS9-PER02's own fix.
 */
object UrlEvidenceImportStatusStore {
    /**
     * Milestone 9 (Pixel 8 acceptance, fifth pass, item 3): distinct, explicit outcomes — "not
     * requested" is represented separately, by the plain *absence* of any [Outcome] record at all
     * ([get] returning null), never by one of these values; an empty successful export ([EMPTY])
     * must never be confused with an attempt that never ran, and a genuinely interrupted attempt
     * ([PENDING] that was never overwritten) must never be confused with either.
     */
    enum class Status {
        /** [record] was called at the very start of an attempt, before the cross-profile query even dispatched — if this is still the most recent value, the attempt was interrupted (a killed process, a crashed coroutine) before it could reach any concluding outcome. Never overwritten by a stale later write — see [record]'s own ordering guarantee. */
        PENDING,
        /** The import genuinely ran to completion and found nothing to correlate — a real, valid outcome, not a failure. */
        EMPTY,
        /** The import genuinely ran to completion and correlated at least one entry. */
        IMPORTED,
        /** The import ran but did not complete successfully — see [Outcome.error] for why. */
        FAILED,
    }

    data class Outcome(
        val importedAtEpochMs: Long,
        val entryCount: Int,
        val truncated: Boolean,
        /** Null on success (a correlation attempt was made without error — it may still have found nothing new, which is not itself an error). */
        val error: String?,
        /** Defaults to a value inferred from [error]/[entryCount] for any [Outcome] built before this field existed — real callers now always set it explicitly via [markPending]/[record]. */
        val status: Status = if (error != null) Status.FAILED else if (entryCount > 0) Status.IMPORTED else Status.EMPTY,
    ) : Serializable

    private val cache = ConcurrentHashMap<String, Outcome>()

    private fun storageDir(context: Context): File = File(context.applicationContext.filesDir, "url_evidence_import_status")

    /**
     * Milestone 9 (Pixel 8 acceptance, fifth pass, item 3): call this *before* dispatching the
     * cross-profile query — the one durable write this store makes that does not depend on the
     * attempt having reached any conclusion yet. If the process dies (or the coroutine is otherwise
     * abandoned) before a later [record] call overwrites it, this [Status.PENDING] row is exactly
     * what a later read finds — a real, honest "started but never concluded" signal, distinct from
     * both "never attempted" (no row at all) and any concluded outcome.
     */
    fun markPending(context: Context, analysisId: String) {
        record(context, analysisId, Outcome(System.currentTimeMillis(), 0, false, error = null, status = Status.PENDING))
    }

    fun record(context: Context, analysisId: String, outcome: Outcome) {
        cache[analysisId] = outcome
        try {
            val dir = storageDir(context)
            dir.mkdirs()
            AtomicFileWriter.writeObject(File(dir, "$analysisId.bin"), outcome)
        } catch (_: Throwable) {
            // Best-effort durability, matching every other store in this codebase's own tolerance
            // (StaticAnalysisResultStore, AndroidEvidenceStore): a failure to persist the *status*
            // record itself must not throw back into the caller's own import/correlation flow.
        }
    }

    fun get(context: Context, analysisId: String): Outcome? {
        cache[analysisId]?.let { return it }
        return try {
            val file = File(storageDir(context), "$analysisId.bin")
            if (!file.exists() || !file.canRead()) return null
            val outcome = ObjectInputStream(FileInputStream(file)).use { it.readObject() as? Outcome }
            outcome?.also { cache[analysisId] = it }
        } catch (_: Throwable) {
            null
        }
    }

    fun clear(context: Context, analysisId: String) {
        cache.remove(analysisId)
        try { File(storageDir(context), "$analysisId.bin").delete() } catch (_: Throwable) {}
    }

    /** Clears orphaned per-analysis import-status files during explicit storage cleanup. */
    fun clearAll(context: Context) {
        cache.clear()
        try {
            storageDir(context).listFiles()?.forEach { file -> if (file.isFile) file.delete() }
        } catch (_: Throwable) {}
    }
}

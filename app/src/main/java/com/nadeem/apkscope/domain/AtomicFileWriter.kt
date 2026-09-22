package com.nadeem.apkscope.domain

import java.io.File
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * Milestone 9 (persistence hardening, 2026-09-13) — writes an object to disk so a process death
 * mid-write can never leave a *partially-written, corrupt* target file. The previous pattern this
 * codebase used (`ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(data) }`,
 * writing directly to the real target path) has no such guarantee: a kill between the file being
 * truncated/opened and the write completing leaves a truncated `.bin` file, and the next `get()`'s
 * `readObject()` throws mid-stream — indistinguishable, under a broad `catch (_: Throwable) {}`,
 * from "nothing was ever saved," silently losing a previously-good analysis.
 *
 * The standard fix: write to a temporary file in the *same directory* (so the follow-up rename is
 * on the same filesystem/volume, which is what makes it atomic), then [File.renameTo] over the
 * real target. A crash before the rename leaves the old file untouched; a crash during the rename
 * itself is not possible on POSIX filesystems (`rename()` is atomic) — the target is always either
 * the old, fully-written file or the new, fully-written file, never a partial mix of both.
 */
object AtomicFileWriter {
    /**
     * Writes [data] to [target] atomically via Java object serialization. Throws on failure
     * (matching the underlying I/O calls' own contract) — callers that want a best-effort,
     * never-throw write (as this codebase's stores already do at their own call sites) should wrap
     * this call in their own `try`/`catch`, same as they did around the old direct-write call.
     */
    fun writeObject(target: File, data: Serializable) {
        writeAtomically(target) { out -> ObjectOutputStream(out).use { it.writeObject(data) } }
    }

    /**
     * Writes [bytes] to [target] atomically, verbatim — no object-serialization wrapper. Used by
     * callers (e.g. [StaticAnalysisFileStore]) that need to write their own already-assembled wire
     * format (a header envelope followed by a payload) without an extra Java-serialization layer
     * around the whole thing, which would otherwise defeat a header check meant to run *before* any
     * object deserialization is attempted.
     */
    fun writeBytes(target: File, bytes: ByteArray) {
        writeAtomically(target) { out -> out.write(bytes) }
    }

    internal fun writeAtomically(target: File, writeTo: (java.io.OutputStream) -> Unit) {
        val dir = target.parentFile ?: throw java.io.IOException("target file has no parent directory: $target")
        dir.mkdirs()
        val temp = File.createTempFile("${target.name}.", ".tmp", dir)
        try {
            java.io.FileOutputStream(temp).use { writeTo(it) }
            if (!temp.renameTo(target)) {
                // renameTo can fail cross-volume or on some platform/filesystem combinations;
                // surface it rather than silently leaving the old file in place with no signal.
                throw java.io.IOException("atomic rename failed: ${temp.path} -> ${target.path}")
            }
        } finally {
            // If the rename succeeded, temp no longer exists under this name — delete() is then a
            // harmless no-op. If it failed or an exception was thrown before it, this cleans up
            // the leftover temp file rather than accumulating one per failed write.
            temp.delete()
        }
    }
}

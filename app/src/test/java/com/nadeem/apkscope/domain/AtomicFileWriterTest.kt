package com.nadeem.apkscope.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * Milestone 9 (persistence hardening) — [AtomicFileWriter] is the fix for
 * [com.nadeem.apkscope.domain.StaticAnalysisResultStore]'s previous direct-write pattern, which could
 * leave a truncated/corrupt file behind if the process died mid-write. Runs against a real
 * temporary directory (no Android dependency) so it can assert the actual on-disk guarantee, not
 * just mocked behavior.
 */
class AtomicFileWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private data class Payload(val value: String) : Serializable

    /** Serializes fine up to a point, then throws — simulating a process death mid-write. */
    private class ExplodingPayload(val value: String, private val explode: Boolean) : Serializable {
        @Suppress("unused")
        private fun writeObject(oos: ObjectOutputStream) {
            oos.defaultWriteObject()
            if (explode) throw java.io.IOException("simulated crash mid-write")
        }
    }

    private fun <T> readBack(file: File): T {
        @Suppress("UNCHECKED_CAST")
        return ObjectInputStream(file.inputStream()).use { it.readObject() as T }
    }

    @Test
    fun writeThenRead_roundTrips() {
        val target = File(tempFolder.root, "data.bin")
        AtomicFileWriter.writeObject(target, Payload("hello"))

        val restored = readBack<Payload>(target)
        assertEquals("hello", restored.value)
    }

    @Test
    fun successfulWrite_leavesNoTempFileBehind() {
        val target = File(tempFolder.root, "data.bin")
        AtomicFileWriter.writeObject(target, Payload("hello"))

        val leftoverTempFiles = tempFolder.root.listFiles { f -> f.name != "data.bin" }
        assertTrue(
            "No leftover .tmp files expected after a successful atomic write, found: ${leftoverTempFiles?.map { it.name }}",
            leftoverTempFiles.isNullOrEmpty()
        )
    }

    @Test
    fun failedWrite_neverCorruptsOrTruncatesAnExistingTargetFile() {
        val target = File(tempFolder.root, "data.bin")
        // A real, fully-written, good file already exists (from a prior successful import).
        AtomicFileWriter.writeObject(target, Payload("good-existing-data"))
        assertEquals("good-existing-data", readBack<Payload>(target).value)

        // Now simulate an interrupted write (process death mid-serialization) attempting to
        // replace it.
        try {
            AtomicFileWriter.writeObject(target, ExplodingPayload("would-have-been-new-data", explode = true))
        } catch (_: Exception) {
            // Expected — writeObject's own contract is to throw on failure (see its doc comment).
        }

        // The original, good file must be completely untouched — not corrupted, not truncated,
        // not replaced with a half-written temp file.
        assertEquals(
            "A failed write must never corrupt or replace an existing good file — this is exactly " +
                "the defect the previous direct-write pattern had",
            "good-existing-data",
            readBack<Payload>(target).value
        )
    }

    @Test
    fun failedWrite_leavesNoLeftoverTempFile() {
        val target = File(tempFolder.root, "data.bin")
        try {
            AtomicFileWriter.writeObject(target, ExplodingPayload("data", explode = true))
        } catch (_: Exception) {
            // Expected.
        }

        assertFalse("The failed write's own target file must not exist (nothing was ever successfully committed)", target.exists())
        val leftoverTempFiles = tempFolder.root.listFiles()
        assertTrue(
            "A failed write must clean up its own temp file rather than accumulating one per failure, found: ${leftoverTempFiles?.map { it.name }}",
            leftoverTempFiles.isNullOrEmpty()
        )
    }

    @Test
    fun repeatedWrites_eachFullyReplaceThePrevious_noDuplication() {
        val target = File(tempFolder.root, "data.bin")
        AtomicFileWriter.writeObject(target, Payload("version-1"))
        AtomicFileWriter.writeObject(target, Payload("version-2"))
        AtomicFileWriter.writeObject(target, Payload("version-3"))

        // Only one file at the target path — a "retry" is a full replace, never an appended or
        // duplicated second record.
        assertEquals("version-3", readBack<Payload>(target).value)
        assertEquals(listOf("data.bin"), tempFolder.root.listFiles()?.map { it.name })
    }
}

package com.nadeem.apkscope.poc.apkrepack

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * APK alignment utility documentation.
 *
 * Alignment is now handled directly within [ReVancedApkRepacker] during the
 * repacking stage to ensure correct byte offsets for native libraries.
 *
 * Native libraries (.so files) are 4096-byte aligned using the 0xd935 extra field
 * tag, and all other entries are 4-byte aligned. This satisfies Android 11+
 * requirements for direct memory-mapping of uncompressed native libraries.
 */
object ApkAligner {

    /**
     * Pass-through for backwards compatibility. Alignment is now handled in the repacker.
     *
     * @param inputApk The APK file to "align"
     * @param outputApk Where to write the APK
     */
    fun align(inputApk: File, outputApk: File) {
        inputApk.copyTo(outputApk, overwrite = true)
    }
}

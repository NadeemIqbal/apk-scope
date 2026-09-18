package com.nadeem.apkscope.core.common

import java.io.InputStream
import java.io.OutputStream

/** Copies untrusted provider input without exceeding a caller-selected byte budget. Promoted unchanged from the spike's `com.nadeem.apkscope.spike.BoundedCopy`. */
object BoundedCopy {
    fun copy(input: InputStream, output: OutputStream, limit: Long): Long {
        require(limit >= 0)
        val buffer = ByteArray(32768)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            require(count.toLong() <= limit - total) { "Transfer too large" }
            output.write(buffer, 0, count)
            total += count
        }
    }
}

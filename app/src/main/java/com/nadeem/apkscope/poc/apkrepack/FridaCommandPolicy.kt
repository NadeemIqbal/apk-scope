package com.nadeem.apkscope.poc.apkrepack

import java.security.MessageDigest

/** Pure target-binding checks shared by the socket server and unit tests. */
object FridaCommandPolicy {
    fun acceptsHello(
        expectedPackage: String?,
        expectedToken: String?,
        helloPackage: String,
        helloToken: String,
    ): Boolean {
        // A target-bound monitor must never accept an unauthenticated or partially-bound
        // session. In particular, null expectations are not wildcards: they mean that the
        // Work-side session has not been provisioned and the client must be rejected.
        if (expectedPackage.isNullOrBlank() || expectedToken.isNullOrBlank()) return false
        if (helloPackage.isBlank() || helloToken.isBlank()) return false
        if (helloPackage != expectedPackage) return false
        return MessageDigest.isEqual(
            expectedToken.toByteArray(Charsets.UTF_8),
            helloToken.toByteArray(Charsets.UTF_8),
        )
    }

    fun acceptsCommandTarget(expectedPackage: String?, requestedPackage: String): Boolean {
        return !expectedPackage.isNullOrBlank() && requestedPackage == expectedPackage
    }
}

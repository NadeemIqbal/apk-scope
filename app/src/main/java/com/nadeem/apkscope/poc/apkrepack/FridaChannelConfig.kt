package com.nadeem.apkscope.poc.apkrepack

import android.content.Context
import java.security.SecureRandom

/**
 * Shared configuration for the experimental, repack-scoped Frida command channel.
 *
 * The token is generated inside the Work Profile and kept only in APK Scope's private
 * session preferences. The target APK contains no token; its injected script asks the
 * Work-profile APK Scope content provider for the token at runtime, and that provider
 * authorizes the request using the target process's Android UID/package identity.
 */
object FridaChannelConfig {
    const val PREFS = "frida_channel"
    const val PROTOCOL_VERSION = 2
    const val MAX_COMMAND_CHARS = 256 * 1024

    private const val TOKEN_KEY_PREFIX = "token_"
    private const val PACKAGE_KEY_PREFIX = "package_"
    private const val CREATED_AT_KEY_PREFIX = "created_"
    private const val TOKEN_BYTES = 32
    private const val TOKEN_HEX_LENGTH = TOKEN_BYTES * 2

    fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    fun tokenKey(sessionId: String): String = "$TOKEN_KEY_PREFIX$sessionId"

    fun storeToken(context: Context, sessionId: String, targetPackage: String, token: String) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(targetPackage.isNotBlank()) { "targetPackage must not be blank" }
        require(isValidToken(token)) { "token has an invalid format" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(tokenKey(sessionId), token)
            .putString(packageKey(sessionId), targetPackage)
            .putLong(createdAtKey(sessionId), System.currentTimeMillis())
            .apply()
    }

    fun readToken(context: Context, sessionId: String): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(tokenKey(sessionId), null)
    }

    fun clearToken(context: Context, sessionId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(tokenKey(sessionId))
            .remove(packageKey(sessionId))
            .remove(createdAtKey(sessionId))
            .apply()
    }

    /**
     * Returns the token for the exact Android identity that owns [callingUid].
     *
     * The provider is intentionally callable by an injected target without a shared signature
     * permission, but it never treats the caller-supplied package name as authoritative. The
     * package must be present in PackageManager's UID mapping and match a stored session binding.
     */
    fun readTokenForCallingPackage(context: Context, callingUid: Int): String? {
        if (callingUid < 0) return null
        val callerPackages = runCatching {
            context.packageManager.getPackagesForUid(callingUid)?.toSet().orEmpty()
        }.getOrElse { emptySet() }
        if (callerPackages.isEmpty()) return null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.keys.asSequence()
            .filter { it.startsWith(TOKEN_KEY_PREFIX) }
            .mapNotNull { key ->
                val sessionId = key.removePrefix(TOKEN_KEY_PREFIX).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val token = prefs.getString(key, null) ?: return@mapNotNull null
                val targetPackage = prefs.getString(packageKey(sessionId), null) ?: return@mapNotNull null
                if (targetPackage !in callerPackages || !isValidToken(token)) return@mapNotNull null
                Triple(prefs.getLong(createdAtKey(sessionId), 0L), targetPackage, token)
            }
            .maxByOrNull { it.first }
            ?.third
    }

    fun isValidToken(token: String): Boolean =
        token.length == TOKEN_HEX_LENGTH && token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun packageKey(sessionId: String): String = "$PACKAGE_KEY_PREFIX$sessionId"

    private fun createdAtKey(sessionId: String): String = "$CREATED_AT_KEY_PREFIX$sessionId"
}

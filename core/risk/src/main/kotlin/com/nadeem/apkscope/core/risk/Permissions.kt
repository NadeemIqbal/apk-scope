package com.nadeem.apkscope.core.risk

/**
 * Raw manifest permission string constants used by the rules below. `core:risk` is a plain
 * Kotlin/JVM module (no Android framework dependency, by design — see this module's
 * `build.gradle.kts`), so these cannot be `android.Manifest.permission.*` references; they are the
 * same literal strings the checkpoint-2 UI already hardcodes in `StaticResultViewModel`'s
 * `buildNotes()` for the same reason.
 */
internal object Permissions {
 const val CAMERA = "android.permission.CAMERA"
 const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
 const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
 const val ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
 const val ACCESS_BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
 const val BIND_ACCESSIBILITY_SERVICE = "android.permission.BIND_ACCESSIBILITY_SERVICE"
 const val SYSTEM_ALERT_WINDOW = "android.permission.SYSTEM_ALERT_WINDOW"
 const val READ_SMS = "android.permission.READ_SMS"
 const val RECEIVE_SMS = "android.permission.RECEIVE_SMS"
 const val SEND_SMS = "android.permission.SEND_SMS"
 const val REQUEST_INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"
 const val RECEIVE_BOOT_COMPLETED = "android.permission.RECEIVE_BOOT_COMPLETED"
 const val QUERY_ALL_PACKAGES = "android.permission.QUERY_ALL_PACKAGES"
 const val INTERNET = "android.permission.INTERNET"
 const val READ_CONTACTS = "android.permission.READ_CONTACTS"
}

internal fun List<String>.containsAny(vararg values: String): Boolean = values.any { it in this }

package com.nadeem.apkscope.sandbox

import android.content.Context

/** Personal-profile marker for the Android installer id explicitly superseded by Reinstall. */
internal object InstallRetryMarker {
 private const val PREFS = "sandbox_install_retry"

 private fun prefs(context: Context) =
  context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

 fun mark(context: Context, sessionId: String, supersededInstallSessionId: Int?) {
  if (supersededInstallSessionId == null || supersededInstallSessionId < 0) return
  val preferences = prefs(context)
  val ids = preferences.getStringSet("ids_$sessionId", emptySet()).orEmpty() + supersededInstallSessionId.toString()
  check(preferences.edit().putStringSet("ids_$sessionId", ids).commit())
 }

 fun isSuperseded(context: Context, sessionId: String, report: SandboxStatusReport): Boolean =
  report.installSessionId != null &&
   (prefs(context).getStringSet("ids_$sessionId", emptySet()).orEmpty().contains(report.installSessionId.toString()) ||
    prefs(context).getInt(sessionId, Int.MIN_VALUE) == report.installSessionId)
}

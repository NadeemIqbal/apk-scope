package com.nadeem.apkscope.sandbox

import android.content.Context

/**
 * Work-profile-local identity for one logical installation attempt.
 *
 * Android's PackageInstaller session id is not enough here: a retry creates a new platform
 * session while callbacks and cross-profile messages for the abandoned session can still arrive.
 * This small, synchronous preference record lets every producer decide whether it still belongs to
 * the current attempt before it mutates durable evidence.
 */
internal object InstallAttemptStore {
 // All installer commands and result callbacks run in one process per profile. Hold this across
 // identity checks, Android side effects and evidence writes, including suspension points.
 val mutex = kotlinx.coroutines.sync.Mutex()
 private const val COUNTER_PREFIX = "install_attempt_counter_"
 private const val CURRENT_PREFIX = "install_attempt_current_"
 private const val REINSTALLING_PREFIX = "install_reinstalling_"
 private const val SESSION_PREFIX = "install_attempt_session_"

 private fun prefs(context: Context) =
  context.getSharedPreferences(SandboxWorkerService.PREFS, Context.MODE_PRIVATE)

 fun begin(context: Context, sessionId: String, reinstalling: Boolean = false): Long {
  val preferences = prefs(context)
  val next = preferences.getLong(COUNTER_PREFIX + sessionId, 0L) + 1L
  check(preferences.edit()
   .putLong(COUNTER_PREFIX + sessionId, next)
   .putLong(CURRENT_PREFIX + sessionId, next)
   .putBoolean(REINSTALLING_PREFIX + sessionId, reinstalling)
   .commit())
  return next
 }

 fun bind(context: Context, installSessionId: Int, sessionId: String, attemptId: Long, packageName: String? = null) {
  val edit = prefs(context).edit()
   .putString(SESSION_PREFIX + installSessionId, sessionId)
   .putString("session_$installSessionId", sessionId)
   .putLong("attempt_$installSessionId", attemptId)
  packageName?.let { edit.putString("package_$installSessionId", it) }
  check(edit.commit())
}

 fun removeBinding(context: Context, installSessionId: Int) {
  prefs(context).edit()
   .remove(SESSION_PREFIX + installSessionId)
   .remove("attempt_$installSessionId")
   .remove("session_$installSessionId")
   .remove("package_$installSessionId")
   .remove("pending_$installSessionId")
   .commit()
 }

 fun sessionFor(context: Context, installSessionId: Int): String? =
  prefs(context).getString(SESSION_PREFIX + installSessionId, null)

 fun attemptFor(context: Context, installSessionId: Int): Long? =
  prefs(context).getLong("attempt_$installSessionId", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

 fun current(context: Context, sessionId: String): Long? =
  prefs(context).getLong(CURRENT_PREFIX + sessionId, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }

 fun isCurrent(context: Context, sessionId: String, attemptId: Long?): Boolean =
  attemptId != null && current(context, sessionId) == attemptId

 fun isReinstalling(context: Context, sessionId: String): Boolean =
  prefs(context).getBoolean(REINSTALLING_PREFIX + sessionId, false)

 fun finishReinstall(context: Context, sessionId: String) {
  prefs(context).edit().putBoolean(REINSTALLING_PREFIX + sessionId, false).commit()
 }
}

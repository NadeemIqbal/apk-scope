package com.nadeem.apkscope.sandbox

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Checkpoint 4.1: the personal-side half of the pull/reconciliation transport — a `suspend` bridge
 * over `startActivityForResult`, so `SandboxSessionCoordinator` (a plain class, not an Activity) can
 * launch [SandboxWorkQueryActivity] across the profile boundary and await its real answer, using the
 * one [Activity][ComponentActivity] this single-Activity app actually has.
 *
 * `registerForActivityResult` must be called unconditionally before the host reaches `STARTED` —
 * [register] is called from `MainActivity.onCreate`, before `setContent`, satisfying that
 * requirement the same way `activity-compose`'s own launchers do.
 *
 * Only one query can be in flight at a time (a `startActivityForResult` call is answered by exactly
 * one result); queries are serialized with a Mutex so concurrent query requests never collide or
 * drop continuation resumes.
 */
object CrossProfileQueryBridge {
 private val mutex = Mutex()
 private var launcher: ActivityResultLauncher<Intent>? = null
 private var pending: CancellableContinuation<ActivityResult>? = null

 fun register(activity: ComponentActivity) {
  launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
   pending?.let { cont -> pending = null; if (cont.isActive) cont.resume(result) }
  }
 }

 /** Suspends until the cross-profile query Activity finishes and its result is delivered back to this task — not until any further processing of that result. Throws [IllegalStateException] if [register] was never called (should not happen outside a test harness). */
 suspend fun launchForResult(intent: Intent): ActivityResult = mutex.withLock {
  val activeLauncher = launcher ?: error("CrossProfileQueryBridge.register was never called")
  suspendCancellableCoroutine { cont ->
   pending = cont
   cont.invokeOnCancellation {
    if (pending === cont) pending = null
   }
   activeLauncher.launch(intent)
  }
 }
}

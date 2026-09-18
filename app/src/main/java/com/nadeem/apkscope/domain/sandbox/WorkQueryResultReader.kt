package com.nadeem.apkscope.domain.sandbox

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResult
import com.nadeem.apkscope.core.common.BoundedCopy
import org.json.JSONObject
import java.io.File

/**
 * Checkpoint 4.1 §16: the one place a [SandboxWorkQueryActivity][com.nadeem.apkscope.sandbox.SandboxWorkQueryActivity]
 * `ActivityResult` is turned into a `JSONObject` — shared by [CrossProfileNetworkIsolationVerifier]
 * and `DefaultSandboxSessionCoordinator.importEvidence`/`importRuntimeArtifact` so the security
 * properties apply uniformly: a size-bounded copy (`BoundedCopy`, never trusting the provider's own
 * reported length) into this app's own private storage *before* the URI grant is used for anything
 * else, and a `resultCode` check before touching `data` at all. The temp file this creates is
 * deleted immediately after being read — nothing from a cross-profile response is ever left sitting
 * in personal storage longer than the single call that consumes it.
 *
 * Checkpoint 5, item 20: [maxBytes] is now a parameter rather than one hardcoded constant — the
 * runtime-observation-artifact pull needs
 * [com.nadeem.apkscope.sandbox.RuntimeObservationArtifact.MAX_ARTIFACT_BYTES], a genuinely larger,
 * separately-justified bound than the small hand-built JSON answers [DEFAULT_MAX_RESULT_BYTES]
 * still covers.
 */
internal object WorkQueryResultReader {
 const val DEFAULT_MAX_RESULT_BYTES = 64L * 1024

 fun readJson(context: Context, result: ActivityResult, maxBytes: Long = DEFAULT_MAX_RESULT_BYTES): JSONObject? {
  // Milestone 9 (Pixel 8 acceptance, fifth pass, item 1 investigation): the Personal-side mirror of
  // SandboxWorkQueryActivity.respondJson's own new logging — a caller getting an unexpected `null`
  // back is otherwise indistinguishable from "the Work side never answered" vs. "it answered
  // RESULT_CANCELED" vs. "it answered RESULT_OK but this side's own read/parse failed".
  if (result.resultCode != Activity.RESULT_OK) {
   Log.w("SandboxWorkQuery", "readJson: resultCode=${result.resultCode} (not RESULT_OK), returning null")
   return null
  }
  val uri = result.data?.data
  if (uri == null) {
   Log.w("SandboxWorkQuery", "readJson: RESULT_OK but data URI was null, returning null")
   return null
  }
  val temp = File(context.cacheDir, "work_query_result/${System.nanoTime()}.json")
  return try {
   temp.parentFile?.mkdirs()
   val opened = context.contentResolver.openInputStream(uri)?.use { input ->
    temp.outputStream().use { out -> BoundedCopy.copy(input, out, maxBytes) }
   }
   if (opened == null) {
    Log.w("SandboxWorkQuery", "readJson: openInputStream returned null for $uri, returning null")
    return null
   }
   JSONObject(temp.readText())
  } catch (e: Exception) {
   Log.w("SandboxWorkQuery", "readJson: failed reading/parsing $uri: ${e.javaClass.simpleName}: ${e.message}")
   null
  } finally {
   temp.delete()
  }
 }
}

internal fun readBoundedResultJson(context: Context, result: ActivityResult, maxBytes: Long = WorkQueryResultReader.DEFAULT_MAX_RESULT_BYTES): JSONObject? =
 WorkQueryResultReader.readJson(context, result, maxBytes)

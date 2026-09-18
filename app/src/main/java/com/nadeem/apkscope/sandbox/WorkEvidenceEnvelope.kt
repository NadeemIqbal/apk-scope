package com.nadeem.apkscope.sandbox

import org.json.JSONObject

/**
 * Checkpoint 4.1 §16: the wire/validation shape for `SandboxWorkQueryActivity`'s
 * `EXPORT_EVIDENCE` answer — pure and Android-free so its security validation
 * ([isValidFor]) is directly unit-testable, unlike the `ActivityResult`/`ContentResolver` plumbing
 * around it.
 */
data class WorkEvidenceEnvelope(val sessionId: String, val packageName: String?, val report: SandboxStatusReport?) {
 /** Item 16's security validation: the envelope's `sessionId` must match exactly (never merge evidence answering a different session), and if both sides know a `packageName`, they must agree. An envelope with no `report` at all is valid but carries nothing to merge. */
 fun isValidFor(expectedSessionId: String, expectedPackageName: String): Boolean {
  if (sessionId.isEmpty() || sessionId != expectedSessionId) return false
  if (!packageName.isNullOrEmpty() && expectedPackageName.isNotEmpty() && packageName != expectedPackageName) return false
  return true
 }

 companion object {
  fun fromJson(json: JSONObject): WorkEvidenceEnvelope {
   val sessionId = json.optString("sessionId", "")
   val packageName = json.optString("packageName", "").takeIf { it.isNotEmpty() }
   val report = json.optJSONObject("report")?.let { runCatching { SandboxStatusReport.fromJson(it) }.getOrNull() }
   return WorkEvidenceEnvelope(sessionId, packageName, report)
  }
 }
}

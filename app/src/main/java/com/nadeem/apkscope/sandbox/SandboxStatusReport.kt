package com.nadeem.apkscope.sandbox

import com.nadeem.apkscope.core.model.CleanupSummary
import com.nadeem.apkscope.core.model.EnforcementMechanism
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.Recoverability
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxPolicyType
import com.nadeem.apkscope.core.model.SandboxSessionState
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one wire format every work-profile → personal-profile status report uses (checkpoint 4,
 * items 5/7/9/15/16/18) — sent as a small JSON file via the exact same `Handoff.send` mechanism
 * already validated for the APK transfer itself (see `CrossProfileContract`'s doc comment: no new,
 * unvalidated bare-`Intent`-extras cross-profile path is introduced). `org.json` is part of the
 * Android SDK already, so this adds no new dependency.
 */
data class SandboxStatusReport(
 val sessionId: String,
 val state: SandboxSessionState,
 val installSessionId: Int? = null,
 val installedVersionCode: Long? = null,
 val enforcements: List<PolicyEnforcementResult> = emptyList(),
 val error: SandboxError? = null,
 val dataClearRequestedAtEpochMs: Long? = null,
 val dataClearCompletedAtEpochMs: Long? = null,
 val dataClearResult: Boolean? = null,
 val cleanup: CleanupSummary? = null,
) {
 fun toJson(): JSONObject = JSONObject().apply {
  put("sessionId", sessionId)
  put("state", state.name)
  installSessionId?.let { put("installSessionId", it) }
  installedVersionCode?.let { put("installedVersionCode", it) }
  put("enforcements", JSONArray().apply {
   enforcements.forEach { e ->
    put(JSONObject().apply {
     put("policy", e.policy.name); put("status", e.status.name)
     e.mechanism?.let { put("mechanism", it.name) }
     e.message?.let { put("message", it) }
    })
   }
  })
  error?.let {
   put("errorCode", it.code.name); put("errorUserMessage", it.userMessage)
   it.technicalDetail?.let { d -> put("errorTechnicalDetail", d) }
   put("errorRecoverability", it.recoverability.name)
  }
  dataClearRequestedAtEpochMs?.let { put("dataClearRequestedAtEpochMs", it) }
  dataClearCompletedAtEpochMs?.let { put("dataClearCompletedAtEpochMs", it) }
  dataClearResult?.let { put("dataClearResult", it) }
  cleanup?.let {
   put("cleanup", JSONObject().apply {
    put("appDataCleared", it.appDataCleared); put("apkRemoved", it.apkRemoved)
    put("workTempApkDeleted", it.workTempApkDeleted); put("personalTempApkDeleted", it.personalTempApkDeleted)
    put("uriGrantReleased", it.uriGrantReleased); put("networkSessionClosed", it.networkSessionClosed)
   })
  }
 }

 companion object {
  fun fromJson(json: JSONObject): SandboxStatusReport {
   val enforcements = (0 until json.optJSONArray("enforcements").let { it?.length() ?: 0 }).map { i ->
    val e = json.getJSONArray("enforcements").getJSONObject(i)
    PolicyEnforcementResult(
     policy = SandboxPolicyType.valueOf(e.getString("policy")),
     status = EnforcementStatus.valueOf(e.getString("status")),
     mechanism = e.optString("mechanism", "").takeIf { it.isNotEmpty() }?.let(EnforcementMechanism::valueOf),
     message = e.optString("message", "").takeIf { it.isNotEmpty() },
    )
   }
   val error = if (json.has("errorCode")) SandboxError(
    code = SandboxErrorCode.valueOf(json.getString("errorCode")),
    userMessage = json.optString("errorUserMessage", ""),
    technicalDetail = json.optString("errorTechnicalDetail", "").takeIf { it.isNotEmpty() },
    recoverability = Recoverability.valueOf(json.optString("errorRecoverability", Recoverability.TERMINAL.name)),
   ) else null
   val cleanup = if (json.has("cleanup")) json.getJSONObject("cleanup").let {
    CleanupSummary(
     appDataCleared = it.getBoolean("appDataCleared"), apkRemoved = it.getBoolean("apkRemoved"),
     workTempApkDeleted = it.getBoolean("workTempApkDeleted"), personalTempApkDeleted = it.getBoolean("personalTempApkDeleted"),
     uriGrantReleased = it.getBoolean("uriGrantReleased"), networkSessionClosed = it.getBoolean("networkSessionClosed"),
    )
   } else null
   return SandboxStatusReport(
    sessionId = json.getString("sessionId"),
    state = SandboxSessionState.valueOf(json.getString("state")),
    installSessionId = if (json.has("installSessionId")) json.getInt("installSessionId") else null,
    installedVersionCode = if (json.has("installedVersionCode")) json.getLong("installedVersionCode") else null,
    enforcements = enforcements,
    error = error,
    dataClearRequestedAtEpochMs = if (json.has("dataClearRequestedAtEpochMs")) json.getLong("dataClearRequestedAtEpochMs") else null,
    dataClearCompletedAtEpochMs = if (json.has("dataClearCompletedAtEpochMs")) json.getLong("dataClearCompletedAtEpochMs") else null,
    dataClearResult = if (json.has("dataClearResult")) json.getBoolean("dataClearResult") else null,
    cleanup = cleanup,
   )
  }
 }
}

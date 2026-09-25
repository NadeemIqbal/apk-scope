package com.nadeem.apkscope.domain.sandbox

import com.nadeem.apkscope.core.database.SandboxPolicyEnforcementEntity
import com.nadeem.apkscope.core.database.SandboxSessionEntity
import com.nadeem.apkscope.core.database.SandboxSessionWithEnforcements
import com.nadeem.apkscope.core.model.CleanupSummary
import com.nadeem.apkscope.core.model.EnforcementMechanism
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.Recoverability
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxPolicy
import com.nadeem.apkscope.core.model.SandboxPolicyType
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxSessionState
import java.time.Instant

/** Both directions of the `SandboxSession` <-> Room mapping (checkpoint 4, item 2) — kept in one file next to `PersistedAnalysis.kt`'s equivalent for the static-analysis side. */
fun SandboxSessionWithEnforcements.toDomain(): SandboxSession {
 val s = session
 return SandboxSession(
  id = s.sessionId,
  analysisId = s.analysisId,
  packageName = s.packageName,
  state = SandboxSessionState.valueOf(s.state),
  requestedPolicy = SandboxPolicy(
   denyCamera = s.policyDenyCamera, denyMicrophone = s.policyDenyMicrophone, denyLocation = s.policyDenyLocation,
   alwaysOnVpnLockdown = s.policyAlwaysOnVpnLockdown, disposableSession = s.policyDisposableSession,
  ),
  enforcementResults = enforcements.map { it.toDomain() },
  createdAt = Instant.ofEpochMilli(s.createdAtEpochMs),
  startedAt = s.startedAtEpochMs?.let(Instant::ofEpochMilli),
  endedAt = s.endedAtEpochMs?.let(Instant::ofEpochMilli),
  error = s.errorCode?.let {
   SandboxError(
    code = SandboxErrorCode.valueOf(it),
    userMessage = s.errorUserMessage.orEmpty(),
    technicalDetail = s.errorTechnicalDetail,
    recoverability = s.errorRecoverability?.let(Recoverability::valueOf) ?: Recoverability.TERMINAL,
   )
  },
  personalApkPath = s.personalApkPath,
  installSessionId = s.installSessionId,
  installAttemptId = s.installAttemptId,
  installedVersionCode = s.installedVersionCode,
  dataClearRequestedAt = s.dataClearRequestedAtEpochMs?.let(Instant::ofEpochMilli),
  dataClearCompletedAt = s.dataClearCompletedAtEpochMs?.let(Instant::ofEpochMilli),
  dataClearResult = s.dataClearResult,
  cleanupSummary = s.cleanupAppDataCleared?.let {
   CleanupSummary(
    appDataCleared = s.cleanupAppDataCleared ?: false,
    apkRemoved = s.cleanupApkRemoved ?: false,
    workTempApkDeleted = s.cleanupWorkTempApkDeleted ?: false,
    personalTempApkDeleted = s.cleanupPersonalTempApkDeleted ?: false,
    uriGrantReleased = s.cleanupUriGrantReleased ?: false,
    networkSessionClosed = s.cleanupNetworkSessionClosed ?: false,
   )
  },
 )
}

private fun SandboxPolicyEnforcementEntity.toDomain() = PolicyEnforcementResult(
 policy = SandboxPolicyType.valueOf(policy),
 status = EnforcementStatus.valueOf(status),
 mechanism = mechanism?.let(EnforcementMechanism::valueOf),
 message = message,
)

fun SandboxSession.toEntity(): SandboxSessionEntity = SandboxSessionEntity(
 sessionId = id, analysisId = analysisId, packageName = packageName, state = state.name,
 policyDenyCamera = requestedPolicy.denyCamera, policyDenyMicrophone = requestedPolicy.denyMicrophone,
 policyDenyLocation = requestedPolicy.denyLocation, policyAlwaysOnVpnLockdown = requestedPolicy.alwaysOnVpnLockdown,
 policyDisposableSession = requestedPolicy.disposableSession,
 createdAtEpochMs = createdAt.toEpochMilli(), startedAtEpochMs = startedAt?.toEpochMilli(), endedAtEpochMs = endedAt?.toEpochMilli(),
 errorCode = error?.code?.name, errorUserMessage = error?.userMessage, errorTechnicalDetail = error?.technicalDetail,
 errorRecoverability = error?.recoverability?.name,
 personalApkPath = personalApkPath, installSessionId = installSessionId, installedVersionCode = installedVersionCode,
 installAttemptId = installAttemptId,
 dataClearRequestedAtEpochMs = dataClearRequestedAt?.toEpochMilli(), dataClearCompletedAtEpochMs = dataClearCompletedAt?.toEpochMilli(),
 dataClearResult = dataClearResult,
 cleanupAppDataCleared = cleanupSummary?.appDataCleared, cleanupApkRemoved = cleanupSummary?.apkRemoved,
 cleanupWorkTempApkDeleted = cleanupSummary?.workTempApkDeleted, cleanupPersonalTempApkDeleted = cleanupSummary?.personalTempApkDeleted,
 cleanupUriGrantReleased = cleanupSummary?.uriGrantReleased, cleanupNetworkSessionClosed = cleanupSummary?.networkSessionClosed,
)

fun SandboxSession.enforcementEntities(): List<SandboxPolicyEnforcementEntity> = enforcementResults.map {
 SandboxPolicyEnforcementEntity(sessionId = id, policy = it.policy.name, status = it.status.name, mechanism = it.mechanism?.name, message = it.message)
}

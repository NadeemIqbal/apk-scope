package com.nadeem.apkscope.core.model

import java.time.Instant

/** Shared minimal-valid-session builder for every sandbox-session test in this module. */
internal fun testSession(
 id: String = "session-1",
 analysisId: String = "analysis-1",
 packageName: String = "com.example.test",
 state: SandboxSessionState = SandboxSessionState.CREATED,
 requestedPolicy: SandboxPolicy = SandboxPolicy(),
 enforcementResults: List<PolicyEnforcementResult> = emptyList(),
 createdAt: Instant = Instant.EPOCH,
 startedAt: Instant? = null,
 endedAt: Instant? = null,
 error: SandboxError? = null,
 personalApkPath: String? = "/data/user/0/com.nadeem.apkscope/files/sandbox/session-1.apk",
 installSessionId: Int? = null,
 installedVersionCode: Long? = null,
 dataClearRequestedAt: Instant? = null,
 dataClearCompletedAt: Instant? = null,
 dataClearResult: Boolean? = null,
 cleanupSummary: CleanupSummary? = null,
) = SandboxSession(
 id, analysisId, packageName, state, requestedPolicy, enforcementResults, createdAt, startedAt, endedAt, error,
 personalApkPath, installSessionId, installedVersionCode, dataClearRequestedAt, dataClearCompletedAt, dataClearResult, cleanupSummary,
)

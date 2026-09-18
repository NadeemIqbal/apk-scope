package com.nadeem.apkscope.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * The narrow database surface used by Personal's storage-management screen.
 *
 * This deliberately owns only persisted analysis artifacts. It never deletes the
 * Work Profile database, installed sandbox applications, or policy configuration.
 */
@Dao
interface StorageDao {
    @Query("SELECT sessionId FROM analysis_sessions")
    suspend fun getAnalysisIds(): List<String>

    @Query("SELECT sessionId FROM sandbox_sessions")
    suspend fun getSandboxSessionIds(): List<String>

    @Query("SELECT DISTINCT analysisId FROM sandbox_sessions")
    suspend fun getDynamicAnalysisIds(): List<String>

    /**
     * SQLite does not expose a reliable per-row file allocation. This is a conservative
     * database-payload estimate used for the UI breakdown, including text payloads and a fixed
     * allowance for numeric/indexed columns. The app still reports the actual APK/cache file
     * sizes for file-backed artifacts.
     */
    @Query(
        """
        SELECT COALESCE(
            (SELECT SUM(
                192 + LENGTH(sessionId) + LENGTH(analysisId) + LENGTH(packageName) +
                COALESCE(LENGTH(state), 0) + COALESCE(LENGTH(errorCode), 0) +
                COALESCE(LENGTH(errorUserMessage), 0) + COALESCE(LENGTH(errorTechnicalDetail), 0) +
                COALESCE(LENGTH(errorRecoverability), 0) + COALESCE(LENGTH(personalApkPath), 0)
            ) FROM sandbox_sessions), 0
            + (SELECT SUM(
                96 + LENGTH(sessionId) + COALESCE(LENGTH(policy), 0) +
                COALESCE(LENGTH(status), 0) + COALESCE(LENGTH(mechanism), 0) +
                COALESCE(LENGTH(message), 0)
            ) FROM sandbox_policy_enforcements), 0
            + (SELECT SUM(
                96 + LENGTH(sessionId)
            ) FROM runtime_observation_summaries), 0
            + (SELECT SUM(
                192 + LENGTH(sessionId) + COALESCE(LENGTH(type), 0) +
                COALESCE(LENGTH(protocol), 0) + COALESCE(LENGTH(destinationIp), 0) +
                COALESCE(LENGTH(failureReason), 0) + COALESCE(LENGTH(failureDetail), 0) +
                COALESCE(LENGTH(hostname), 0) + COALESCE(LENGTH(resolvedAddressesCsv), 0) +
                COALESCE(LENGTH(limitName), 0)
            ) FROM network_observations), 0
            + (SELECT SUM(
                128 + LENGTH(sessionId) + LENGTH(packageName) + LENGTH(hostname) +
                LENGTH(resolvedAddressesCsv)
            ) FROM android_dns_evidence), 0
            + (SELECT SUM(
                96 + LENGTH(sessionId) + LENGTH(destinationAddress)
            ) FROM android_connect_evidence), 0
            + (SELECT SUM(
                96 + LENGTH(sessionId) + COALESCE(LENGTH(status), 0)
            ) FROM android_evidence_summaries), 0
        )
        """
    )
    suspend fun estimateDynamicDataBytes(): Long

    @Transaction
    suspend fun deleteDynamicDataForSession(sessionId: String) {
        deleteNetworkObservations(sessionId)
        deleteRuntimeSummary(sessionId)
        deleteDnsEvidence(sessionId)
        deleteConnectEvidence(sessionId)
        deleteAndroidEvidenceSummary(sessionId)
        deleteReportsForSession(sessionId)
        deleteSandboxSession(sessionId)
    }

    @Transaction
    suspend fun deleteStaticDataForAnalysis(analysisId: String) {
        // A sandbox run cannot remain addressable once its parent static analysis is removed.
        deleteDynamicEvidenceForAnalysis(analysisId)
        deleteReportsForAnalysis(analysisId)
        deleteAuditsForAnalysis(analysisId)
        deleteSandboxSessionsForAnalysis(analysisId)
        deleteAnalysisSession(analysisId)
    }

    @Transaction
    suspend fun deleteAllPersistedData() {
        deleteAllNetworkObservations()
        deleteAllRuntimeSummaries()
        deleteAllDnsEvidence()
        deleteAllConnectEvidence()
        deleteAllAndroidEvidenceSummaries()
        deleteAllReports()
        deleteAllAudits()
        deleteAllSandboxSessions()
        deleteAllAnalysisSessions()
    }

    @Query("DELETE FROM network_observations WHERE sessionId = :sessionId")
    suspend fun deleteNetworkObservations(sessionId: String)

    @Query("DELETE FROM runtime_observation_summaries WHERE sessionId = :sessionId")
    suspend fun deleteRuntimeSummary(sessionId: String)

    @Query("DELETE FROM android_dns_evidence WHERE sessionId = :sessionId")
    suspend fun deleteDnsEvidence(sessionId: String)

    @Query("DELETE FROM android_connect_evidence WHERE sessionId = :sessionId")
    suspend fun deleteConnectEvidence(sessionId: String)

    @Query("DELETE FROM android_evidence_summaries WHERE sessionId = :sessionId")
    suspend fun deleteAndroidEvidenceSummary(sessionId: String)

    @Query("DELETE FROM final_reports WHERE sessionId = :sessionId")
    suspend fun deleteReportsForSession(sessionId: String)

    @Query("DELETE FROM sandbox_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSandboxSession(sessionId: String)

    @Query("DELETE FROM network_observations WHERE sessionId IN (SELECT sessionId FROM sandbox_sessions WHERE analysisId = :analysisId)")
    suspend fun deleteDynamicNetworkEvidenceForAnalysis(analysisId: String)

    @Query("DELETE FROM runtime_observation_summaries WHERE sessionId IN (SELECT sessionId FROM sandbox_sessions WHERE analysisId = :analysisId)")
    suspend fun deleteDynamicSummariesForAnalysis(analysisId: String)

    @Query("DELETE FROM android_dns_evidence WHERE sessionId IN (SELECT sessionId FROM sandbox_sessions WHERE analysisId = :analysisId)")
    suspend fun deleteDynamicDnsEvidenceForAnalysis(analysisId: String)

    @Query("DELETE FROM android_connect_evidence WHERE sessionId IN (SELECT sessionId FROM sandbox_sessions WHERE analysisId = :analysisId)")
    suspend fun deleteDynamicConnectEvidenceForAnalysis(analysisId: String)

    @Query("DELETE FROM android_evidence_summaries WHERE sessionId IN (SELECT sessionId FROM sandbox_sessions WHERE analysisId = :analysisId)")
    suspend fun deleteDynamicAndroidSummariesForAnalysis(analysisId: String)

    @Query("DELETE FROM final_reports WHERE analysisId = :analysisId")
    suspend fun deleteReportsForAnalysis(analysisId: String)

    @Query("DELETE FROM security_audits WHERE analysisId = :analysisId")
    suspend fun deleteAuditsForAnalysis(analysisId: String)

    @Query("DELETE FROM sandbox_sessions WHERE analysisId = :analysisId")
    suspend fun deleteSandboxSessionsForAnalysis(analysisId: String)

    @Query("DELETE FROM analysis_sessions WHERE sessionId = :analysisId")
    suspend fun deleteAnalysisSession(analysisId: String)

    @Query("DELETE FROM network_observations WHERE 1 = 1")
    suspend fun deleteAllNetworkObservations()

    @Query("DELETE FROM runtime_observation_summaries WHERE 1 = 1")
    suspend fun deleteAllRuntimeSummaries()

    @Query("DELETE FROM android_dns_evidence WHERE 1 = 1")
    suspend fun deleteAllDnsEvidence()

    @Query("DELETE FROM android_connect_evidence WHERE 1 = 1")
    suspend fun deleteAllConnectEvidence()

    @Query("DELETE FROM android_evidence_summaries WHERE 1 = 1")
    suspend fun deleteAllAndroidEvidenceSummaries()

    @Query("DELETE FROM final_reports WHERE 1 = 1")
    suspend fun deleteAllReports()

    @Query("DELETE FROM security_audits WHERE 1 = 1")
    suspend fun deleteAllAudits()

    @Query("DELETE FROM sandbox_sessions WHERE 1 = 1")
    suspend fun deleteAllSandboxSessions()

    @Query("DELETE FROM analysis_sessions WHERE 1 = 1")
    suspend fun deleteAllAnalysisSessions()

    private suspend fun deleteDynamicEvidenceForAnalysis(analysisId: String) {
        deleteDynamicNetworkEvidenceForAnalysis(analysisId)
        deleteDynamicSummariesForAnalysis(analysisId)
        deleteDynamicDnsEvidenceForAnalysis(analysisId)
        deleteDynamicConnectEvidenceForAnalysis(analysisId)
        deleteDynamicAndroidSummariesForAnalysis(analysisId)
    }
}

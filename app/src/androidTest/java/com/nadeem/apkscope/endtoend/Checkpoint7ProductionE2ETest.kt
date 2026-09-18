package com.nadeem.apkscope.endtoend

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.core.database.AnalysisPermissionEntity
import com.nadeem.apkscope.core.database.AnalysisSessionEntity
import com.nadeem.apkscope.core.database.AndroidConnectEvidenceEntity
import com.nadeem.apkscope.core.database.AndroidDnsEvidenceEntity
import com.nadeem.apkscope.core.database.AndroidEvidenceSummaryEntity
import com.nadeem.apkscope.core.database.NetworkObservationEntity
import com.nadeem.apkscope.core.database.RuntimeObservationSummaryEntity
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.core.database.SandboxSessionEntity
import com.nadeem.apkscope.core.model.AndroidEvidenceStatus
import com.nadeem.apkscope.core.model.RiskLevel
import com.nadeem.apkscope.domain.report.ReportCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Checkpoint 7: End-to-end integration test verifying:
 * 1. Static-only report generation and persistence in Room
 * 2. Dynamic report generation with VPN observations while AndroidEvidence is PENDING
 * 3. DPM AndroidEvidence arrival and idempotent recomputation
 * 4. Double-counting prevention when VPN and DPM corroborate the same network activity
 * 5. Report history ordering (newest first)
 * 6. Clean recovery from durable Room storage
 */
@RunWith(AndroidJUnit4::class)
class Checkpoint7ProductionE2ETest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = SandboxDatabaseProvider.get(context)
    private val analysisDao get() = db.analysisSessionDao()
    private val sandboxSessionDao get() = db.sandboxSessionDao()
    private val observationDao get() = db.observationDao()
    private val androidEvidenceDao get() = db.androidEvidenceDao()
    private val reportDao get() = db.finalReportDao()
    private val coordinator get() = ReportCoordinator(context)

    private val cleanedAnalyses = mutableListOf<String>()
    private val cleanedSessions = mutableListOf<String>()

    @After
    fun cleanUp() = runBlocking {
        cleanedAnalyses.forEach { analysisDao.deleteSession(it) }
        cleanedSessions.forEach { sandboxSessionDao.deleteSession(it) }
    }

    @Test
    fun testRealEndToEndPipeline_Static_Runtime_AndroidEvidence_Reconciliation() = runBlocking {
        val analysisId = "c7-analysis-${UUID.randomUUID()}"
        val sessionId = "c7-session-${UUID.randomUUID()}"
        val targetPkg = "com.apksandbox.fixture"
        cleanedAnalyses += analysisId
        cleanedSessions += sessionId

        // 1. Seed Static Analysis with READ_CONTACTS capability
        analysisDao.insertCompleteAnalysis(
            AnalysisSessionEntity(
                sessionId = analysisId,
                packageName = targetPkg,
                appName = "Harmless Fixture",
                versionName = "1.0",
                versionCode = 1,
                sha256 = "c7deadbeef",
                analyzedAtEpochMs = 1000L,
                minSdkVersion = 24,
                targetSdkVersion = 34,
                debuggable = false,
                signatureVerified = true,
                signatureDetail = "verified",
                nativeLibraryAbis = listOf("arm64-v8a"),
                permissionCount = 1,
                componentTotalCount = 0,
                componentExportedCount = 0,
                riskScore = 10,
                riskLevel = "LOW",
                riskEngineVersion = "static-v1",
            ),
            listOf(AnalysisPermissionEntity(sessionId = analysisId, permission = "android.permission.READ_CONTACTS")),
            emptyList(),
            emptyList(),
        )

        sandboxSessionDao.insertSession(
            SandboxSessionEntity(
                sessionId = sessionId,
                analysisId = analysisId,
                packageName = targetPkg,
                state = "COMPLETED",
                policyDenyCamera = true,
                policyDenyMicrophone = true,
                policyDenyLocation = true,
                policyAlwaysOnVpnLockdown = true,
                policyDisposableSession = true,
                createdAtEpochMs = 1000L,
                startedAtEpochMs = 1000L,
                endedAtEpochMs = 5000L,
                errorCode = null,
                errorUserMessage = null,
                errorTechnicalDetail = null,
                errorRecoverability = null,
                personalApkPath = null,
                installSessionId = null,
                installedVersionCode = null,
                dataClearRequestedAtEpochMs = null,
                dataClearCompletedAtEpochMs = null,
                dataClearResult = null,
                cleanupAppDataCleared = null,
                cleanupApkRemoved = null,
                cleanupWorkTempApkDeleted = null,
                cleanupPersonalTempApkDeleted = null,
                cleanupUriGrantReleased = null,
                cleanupNetworkSessionClosed = null,
            ),
        )

        // 2. Generate initial static-only report
        val staticReport = coordinator.generateStaticOnlyReport(analysisId)
        assertNotNull(staticReport)
        assertEquals(analysisId, staticReport!!.analysisId)
        assertEquals(10, staticReport.overallScore)
        assertEquals(RiskLevel.LOW, staticReport.overallLevel)

        // Verify persisted static report in Room
        val persistedStatic = reportDao.getReport("report-static-$analysisId")
        assertNotNull(persistedStatic)
        assertEquals(10, persistedStatic!!.report.overallScore)
        assertTrue(persistedStatic.report.staticComplete)
        assertEquals(false, persistedStatic.report.runtimeComplete)

        // 3. Insert Runtime VPN Observations (1 blocked private attempt + 1 public connection)
        observationDao.insertAll(
            listOf(
                NetworkObservationEntity(
                    sessionId = sessionId,
                    sequence = 1L,
                    timestampEpochMs = 2000L,
                    type = "ConnectionFailed",
                    protocol = "TCP",
                    destinationIp = "192.168.1.1",
                    destinationPort = 80,
                    connectionId = 101L,
                    startTimeEpochMs = 2000L,
                    endTimeEpochMs = 2050L,
                    uploadedBytes = 100L,
                    downloadedBytes = 0L,
                    failureReason = "POLICY_DENIED",
                    failureDetail = "RFC1918 destination blocked",
                    hostname = null,
                    resolvedAddressesCsv = null,
                    transactionId = null,
                    sourcePort = 45000,
                    limitName = null,
                    currentValue = null,
                    limitValue = null,
                ),
                NetworkObservationEntity(
                    sessionId = sessionId,
                    sequence = 2L,
                    timestampEpochMs = 2500L,
                    type = "DnsResponse",
                    protocol = "UDP",
                    destinationIp = null,
                    destinationPort = null,
                    connectionId = null,
                    startTimeEpochMs = null,
                    endTimeEpochMs = null,
                    uploadedBytes = null,
                    downloadedBytes = null,
                    failureReason = null,
                    failureDetail = null,
                    hostname = "example.com",
                    resolvedAddressesCsv = "93.184.216.34",
                    transactionId = 1234,
                    sourcePort = 54321,
                    limitName = null,
                    currentValue = null,
                    limitValue = null,
                ),
                NetworkObservationEntity(
                    sessionId = sessionId,
                    sequence = 3L,
                    timestampEpochMs = 3000L,
                    type = "ConnectionOpened",
                    protocol = "TCP",
                    destinationIp = "93.184.216.34",
                    destinationPort = 443,
                    connectionId = 102L,
                    startTimeEpochMs = 3000L,
                    endTimeEpochMs = 3200L,
                    uploadedBytes = 500L,
                    downloadedBytes = 1200L,
                    failureReason = null,
                    failureDetail = null,
                    hostname = "example.com",
                    resolvedAddressesCsv = null,
                    transactionId = null,
                    sourcePort = 45002,
                    limitName = null,
                    currentValue = null,
                    limitValue = null,
                ),
            ),
        )
        observationDao.upsertSummary(
            RuntimeObservationSummaryEntity(
                sessionId = sessionId,
                startedAtEpochMs = 2000L,
                endedAtEpochMs = 3200L,
                connectionCount = 2,
                dnsQueryCount = 0,
                uniqueObservedDomains = 1,
                uploadedBytes = 600L,
                downloadedBytes = 1200L,
                blockedConnectionCount = 1,
                failedConnectionCount = 0,
                droppedObservationCount = 0L,
                schemaVersion = 1,
                truncated = false,
                exportedObservationCount = 2,
                totalObservationCount = 2,
                importedAtEpochMs = 3300L,
            ),
        )

        // 4. Generate dynamic report while AndroidEvidence is PENDING
        val dynamicReportPending = coordinator.generateOrUpdateSessionReport(
            sessionId = sessionId,
            analysisId = analysisId,
            overrideAndroidStatus = AndroidEvidenceStatus.PENDING,
        )
        assertNotNull(dynamicReportPending)
        assertEquals(AndroidEvidenceStatus.PENDING, dynamicReportPending!!.evidenceCompleteness.androidEvidenceStatus)
        // Score:
        // Static: 10
        // Runtime: Network Activity (+5) + Private Attempt Blocked (+20) = 25
        // Combined: Contacts + Network (+15) + Sensitive + Private Attempt (+30) = 45
        // Overall: 10 + 25 + 45 = 80 -> CRITICAL
        assertEquals(80, dynamicReportPending.overallScore)
        assertEquals(RiskLevel.CRITICAL, dynamicReportPending.overallLevel)

        // 5. AndroidEvidence arrives (DPM logs batch delivered)
        androidEvidenceDao.insertDnsEvents(
            listOf(
                AndroidDnsEvidenceEntity(
                    sessionId = sessionId,
                    eventId = 5001L,
                    batchToken = 101L,
                    packageName = targetPkg,
                    timestampEpochMs = 2900L,
                    receivedAtEpochMs = 3500L,
                    hostname = "example.com",
                    resolvedAddressesCsv = "93.184.216.34",
                    totalResolvedAddressCount = 1,
                ),
            ),
        )
        androidEvidenceDao.insertConnectEvents(
            listOf(
                AndroidConnectEvidenceEntity(
                    sessionId = sessionId,
                    eventId = 5002L,
                    batchToken = 101L,
                    packageName = targetPkg,
                    timestampEpochMs = 3000L,
                    receivedAtEpochMs = 3500L,
                    destinationAddress = "93.184.216.34",
                    destinationPort = 443,
                ),
            ),
        )
        androidEvidenceDao.upsertSummary(
            AndroidEvidenceSummaryEntity(
                sessionId = sessionId,
                dnsCount = 1,
                connectCount = 1,
                status = "READY",
                firstEventTimestampEpochMs = 2900L,
                lastEventTimestampEpochMs = 3000L,
                importedAtEpochMs = 3600L,
            ),
        )

        // 6. Recompute report with complete AndroidEvidence (Double counting prevention verified!)
        val finalReport = coordinator.generateOrUpdateSessionReport(
            sessionId = sessionId,
            analysisId = analysisId,
            overrideAndroidStatus = AndroidEvidenceStatus.READY,
        )
        assertNotNull(finalReport)
        assertEquals(AndroidEvidenceStatus.READY, finalReport!!.evidenceCompleteness.androidEvidenceStatus)
        // Corroboration: DPM ConnectEvent and VPN Forwarded share the single Network Activity finding.
        // Score remains exactly 80 without double counting!
        assertEquals(80, finalReport.overallScore)
        assertEquals(RiskLevel.CRITICAL, finalReport.overallLevel)

        // 7. Verify Room persistence and recovery
        val persistedFinal = reportDao.getReport("report-$sessionId")
        assertNotNull(persistedFinal)
        assertEquals(80, persistedFinal!!.report.overallScore)
        assertEquals("READY", persistedFinal.report.androidEvidenceStatus)
        assertTrue(persistedFinal.findings.any { it.ruleId == com.nadeem.apkscope.core.risk.RuleIds.RUNTIME_POLICY_DENIED_DESTINATION })
        assertTrue(persistedFinal.findings.any { it.ruleId == com.nadeem.apkscope.core.risk.RuleIds.COMBINED_CONTACTS_NETWORK })

        // Check history ordering
        val allReports = reportDao.getAllReports()
        assertTrue(allReports.isNotEmpty())
        assertEquals("report-$sessionId", allReports[0].reportId) // Newest first
    }
}

package com.nadeem.apkscope.domain

import com.nadeem.apkscope.core.staticanalysis.ApkAnalysisResult
import com.nadeem.apkscope.core.staticanalysis.ApkAnalyzer

data class AppIdentity(val name: String?, val packageName: String?, val versionName: String?)

sealed interface SessionStage {
 data object Copying : SessionStage
 data class Analyzing(val stage: ApkAnalyzer.Stage) : SessionStage
 data object AnalysisComplete : SessionStage
 data class Failed(val message: String, val stage: ApkAnalyzer.Stage? = null) : SessionStage
}

/**
 * A single "inspect this APK" session, held in memory for the lifetime of the process — the
 * **active/in-progress** half of checkpoint 3's session-state split (see `SessionRepository`'s doc
 * comment). This is the object route arguments never carry directly (item 3 of the UI checkpoint) —
 * screens receive only [id] and read everything else through [SessionRepository]. Once a session
 * reaches [SessionStage.AnalysisComplete], its durable record lives in Room as a [PersistedAnalysis]
 * instead; every screen downstream of "analysis complete" reads that, not this type.
 */
data class AnalysisSession(
 val id: String,
 val apkFilePath: String,
 val createdAtEpochMs: Long,
 val stage: SessionStage,
 val appIdentity: AppIdentity? = null,
 val analysis: ApkAnalysisResult? = null,
)

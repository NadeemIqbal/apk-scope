package com.nadeem.apkscope.sandbox

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.model.SandboxSessionState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Android integration regression for the query that previously promoted an existing package to
 * INSTALLED without a successful installer callback. Uses synthetic session records and the real
 * installed host package; this does not establish end-to-end Work Profile installation support.
 */
@RunWith(AndroidJUnit4::class)
class InstallEvidenceReconciliationInstrumentedTest {
 private val context = InstrumentationRegistry.getInstrumentation().targetContext

 @Test fun missingInstallerSessionDoesNotInventCancellation() = runBlocking {
  val sessionId = "missing-installer-${UUID.randomUUID()}"
  val store = WorkEvidenceStore(context)
  try {
   val attempt = InstallAttemptStore.begin(context, sessionId)
   InstallAttemptStore.bind(context, Int.MAX_VALUE, sessionId, attempt, "com.example.absent.installfixture")
   store.recordFact(sessionId, "com.example.absent.installfixture", SandboxStatusReport(sessionId,
    SandboxSessionState.INSTALLING, installSessionId = Int.MAX_VALUE, installAttemptId = attempt))
   val intent = Intent(context, SandboxWorkQueryActivity::class.java)
    .putExtra(CrossProfileContract.SESSION_ID, sessionId)
    .putExtra(CrossProfileContract.QUERY_TYPE, SandboxWorkQueryActivity.QUERY_TYPE_EXPORT_EVIDENCE)
   ActivityScenario.launchActivityForResult<SandboxWorkQueryActivity>(intent).use { scenario ->
    assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
    val report = requireNotNull(store.getReport(sessionId))
    assertEquals(SandboxSessionState.INSTALLING, report.state)
    assertNull(report.error)
   }
  } finally {
   store.clear(sessionId)
   InstallAttemptStore.removeBinding(context, Int.MAX_VALUE)
   File(context.filesDir, "work_query_export/$sessionId.json").delete()
  }
 }

 @Test fun existingPackageDoesNotCompleteAnUnconfirmedInstall() = runBlocking {
  assertEquals(context.packageName, context.packageManager.getPackageInfo(context.packageName, 0).packageName)
  val store = WorkEvidenceStore(context)
  for (state in listOf(SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION, SandboxSessionState.INSTALLING)) {
   val sessionId = "install-reconciliation-${UUID.randomUUID()}"
   try {
    store.recordFact(sessionId, context.packageName, SandboxStatusReport(sessionId, state))
    val intent = Intent(context, SandboxWorkQueryActivity::class.java)
     .putExtra(CrossProfileContract.SESSION_ID, sessionId)
     .putExtra(CrossProfileContract.QUERY_TYPE, SandboxWorkQueryActivity.QUERY_TYPE_EXPORT_EVIDENCE)
    ActivityScenario.launchActivityForResult<SandboxWorkQueryActivity>(intent).use { scenario ->
     // ActivityScenario waits for finish/result; no fixed sleep or screen-coordinate assumptions.
     val result = scenario.result
     assertEquals(Activity.RESULT_OK, result.resultCode)
     val uri = requireNotNull(result.resultData.data)
     val envelope = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { JSONObject(it.readText()) }
     val exported = SandboxStatusReport.fromJson(envelope.getJSONObject("report"))
     assertEquals(state, exported.state)
     assertNull(exported.installedVersionCode)
     assertEquals(state, store.getReport(sessionId)?.state)
    }
   } finally {
    store.clear(sessionId)
    File(context.filesDir, "work_query_export/$sessionId.json").delete()
   }
  }
 }
}

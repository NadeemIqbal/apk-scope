package com.nadeem.apkscope.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 18/21: "a session must never report COMPLETED before required cleanup" — [CleanupSummary.isComplete] is the single source of truth the coordinator gates `CLEANUP -> COMPLETED` on. */
class CleanupSummaryTest {
 private val allDone = CleanupSummary(true, true, true, true, true, true)

 @Test fun completeWhenEveryStepPasses() {
  assertTrue(allDone.isComplete)
 }

 @Test fun incompleteWhenAnySingleStepIsMissing() {
  assertFalse(allDone.copy(appDataCleared = false).isComplete)
  assertFalse(allDone.copy(apkRemoved = false).isComplete)
  assertFalse(allDone.copy(workTempApkDeleted = false).isComplete)
  assertFalse(allDone.copy(personalTempApkDeleted = false).isComplete)
  assertFalse(allDone.copy(uriGrantReleased = false).isComplete)
  assertFalse(allDone.copy(networkSessionClosed = false).isComplete)
 }

 @Test fun defaultSummaryIsIncomplete() {
  assertFalse(CleanupSummary().isComplete)
 }
}

package com.nadeem.apkscope.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Checkpoint 4.1 §19: real Room persistence coverage for [WorkEvidenceDao] — the Work-local durable
 * evidence store. [evidenceSurvivesReopeningTheDatabase] is the one that actually proves "evidence
 * survives Work service restart" (item 19's own wording): a plain in-memory Room instance would
 * pass that test trivially and dishonestly, since it is destroyed and recreated fresh every test
 * method regardless — this test instead uses a real on-disk database file and opens it with **two
 * separate `Room` instances** in sequence, exactly modeling "the service process died and a new one
 * opened the same file," which an in-memory-only test cannot distinguish from "the data was never
 * really durable at all."
 */
@RunWith(AndroidJUnit4::class)
class WorkEvidenceDaoTest {
 private lateinit var database: WorkEvidenceDatabase
 private lateinit var dao: WorkEvidenceDao

 @Before fun createDatabase() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  database = Room.inMemoryDatabaseBuilder(context, WorkEvidenceDatabase::class.java).allowMainThreadQueries().build()
  dao = database.workEvidenceDao()
 }

 @After fun closeDatabase() { database.close() }

 private fun entity(sessionId: String, json: String = """{"sessionId":"$sessionId","state":"PREPARING"}""") =
  WorkSessionEvidenceEntity(sessionId = sessionId, packageName = "com.example.fixture", latestReportJson = json, updatedAtEpochMs = 1000L)

 @Test fun insertAndReadEvidence() = runBlocking {
  dao.upsert(entity("s1"))
  val loaded = dao.get("s1")
  assertEquals("com.example.fixture", loaded?.packageName)
 }

 @Test fun reUpsertReplacesNotDuplicates() = runBlocking {
  dao.upsert(entity("s1", """{"sessionId":"s1","state":"PREPARING"}"""))
  dao.upsert(entity("s1", """{"sessionId":"s1","state":"INSTALLED"}"""))
  val loaded = dao.get("s1")
  assertEquals("""{"sessionId":"s1","state":"INSTALLED"}""", loaded?.latestReportJson)
  assertEquals(1, dao.count("s1")) // never duplicated — a genuine count, not a null-check false positive
 }

 @Test fun deleteReallyRemovesTheRow() = runBlocking {
  dao.upsert(entity("s1"))
  dao.delete("s1")
  assertNull(dao.get("s1"))
  assertEquals(0, dao.count("s1"))
 }

 @Test fun unknownSessionReturnsNull() = runBlocking {
  assertNull(dao.get("never-existed"))
 }

 @Test fun evidenceSurvivesReopeningTheDatabase() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val dbFile = File(context.getDatabasePath("work_evidence_test.db").path)
  dbFile.delete()
  try {
   runBlocking {
    val first = Room.databaseBuilder(context, WorkEvidenceDatabase::class.java, dbFile.absolutePath).build()
    first.workEvidenceDao().upsert(entity("restart-session", """{"sessionId":"restart-session","state":"CLEARING_DATA","dataClearResult":true}"""))
    first.close() // simulates the Work process dying — the Service instance and its Room connection are gone.

    val reopened = Room.databaseBuilder(context, WorkEvidenceDatabase::class.java, dbFile.absolutePath).build()
    val recovered = reopened.workEvidenceDao().get("restart-session")
    assertEquals("com.example.fixture", recovered?.packageName)
    assertEquals("""{"sessionId":"restart-session","state":"CLEARING_DATA","dataClearResult":true}""", recovered?.latestReportJson)
    reopened.close()
   }
  } finally {
   dbFile.delete()
  }
 }
}

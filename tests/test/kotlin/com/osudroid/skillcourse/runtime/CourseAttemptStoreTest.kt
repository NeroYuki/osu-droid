package com.osudroid.skillcourse.runtime

import androidx.room.Room
import com.osudroid.data.DroidDatabase
import com.osudroid.data.SkillCourseAttemptEntity
import com.osudroid.skillcourse.CourseCodec
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseRepositoryCore
import com.osudroid.skillcourse.CourseTestFixtures
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

private val TEST_JSON = Json { encodeDefaults = true }

class CourseAttemptStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun newRoot() = temp.newFolder("SkillCourses", "Attempts")

    private fun result(
        root: File,
        id: String = UUID.randomUUID().toString(),
        outcome: String = "in_progress",
        reason: String? = null,
        courseId: String = "course-1",
        courseHash: String = CourseCodec.hash(CourseTestFixtures.definition()),
        definition: CourseDefinition = CourseTestFixtures.definition()
    ): CourseResult = CourseResult(
        clientAttemptId = id,
        courseId = courseId,
        courseHash = courseHash,
        definition = definition,
        playerName = "Player",
        createdAt = 1_234L,
        outcome = outcome,
        reason = reason,
        order = listOf(0),
        components = listOf(component()),
        rawExTotal = 42L,
        activeNanos = 999L,
        replayDirectory = File(root, id).absolutePath,
        additionalModsJson = "[]",
        speeds = mapOf(0 to 1.0f)
    )

    private fun component() = CourseComponentResult(
        originalIndex = 0,
        playedIndex = 0,
        mapHash = CourseTestFixtures.HASH_A,
        score = 1_000L,
        maxCombo = 10,
        hit300 = 5,
        hit100 = 1,
        hit50 = 1,
        misses = 0,
        sliderHeadHits = 1,
        sliderTickHits = 1,
        sliderRepeatHits = 0,
        sliderEndHits = 1,
        modsJson = "[]",
        rawExContribution = 10L,
        startingHp = 100,
        endingHp = 95,
        activeNanos = 500L,
        completed = true,
        replayFilename = "0.odr",
        replaySha256 = "abc",
        eventsFilename = "0.events.json"
    )

    private fun entity(result: CourseResult) = SkillCourseAttemptEntity(
        id = result.clientAttemptId,
        courseId = result.courseId,
        courseHash = result.courseHash,
        definitionJson = CourseCodec.encode(result.definition),
        rulesetVersion = result.rulesetVersion,
        outcome = result.outcome,
        createdAt = result.createdAt,
        resultJson = TEST_JSON.encodeToString(result),
        replayDirectory = result.replayDirectory
    )

    private fun writeJournal(root: File, result: CourseResult) {
        val directory = File(root, result.clientAttemptId)
        assertTrue(directory.mkdirs() || directory.isDirectory)
        File(directory, CourseAttemptStoreCore.RESULT_FILE)
            .writeText(TEST_JSON.encodeToString(result), Charsets.UTF_8)
    }

    @Test
    fun `Legacy journals recover without new per-map choices or auxiliary counts`() {
        val root = newRoot()
        val old = result(root, outcome = "completed").copy(additionalModsJson = "[{\"acronym\":\"HR\"}]")
        val encoded = TEST_JSON.parseToJsonElement(TEST_JSON.encodeToString(old)).jsonObject
        val componentJson = TEST_JSON.parseToJsonElement(TEST_JSON.encodeToString(old.components.single())).jsonObject
        val legacy = JsonObject(encoded - "additionalModsByIndexJson" + ("components" to JsonArray(listOf(
            JsonObject(componentJson - "hit300k" - "hit100k")
        ))))
        val directory = File(root, old.clientAttemptId).apply { mkdirs() }
        File(directory, CourseAttemptStoreCore.RESULT_FILE).writeText(legacy.toString())
        val sink = FakeSink()
        val report = CourseAttemptStoreCore(root, sink).recover()
        assertTrue(report.succeeded)
        val recovered = TEST_JSON.decodeFromString<CourseResult>(sink.attempts.getValue(old.clientAttemptId).resultJson)
        assertEquals(old.additionalModsJson, recovered.additionalModsJson)
        assertTrue(recovered.additionalModsByIndexJson.isEmpty())
        assertEquals(0, recovered.components.single().hit300k)
        assertEquals(0, recovered.components.single().hit100k)
    }

    @Test
    fun `Attempt journal preserves per-map choices and native auxiliary judgments`() {
        val root = newRoot()
        val original = result(root, outcome = "completed").copy(
            additionalModsByIndexJson = mapOf(0 to "[{\"acronym\":\"HR\"}]"),
            components = listOf(component().copy(hit300k = 3, hit100k = 1))
        )
        val sink = FakeSink()
        CourseAttemptStoreCore(root, sink).persist(original)
        val recovered = TEST_JSON.decodeFromString<CourseResult>(sink.attempts.getValue(original.clientAttemptId).resultJson)
        assertEquals(original, recovered)
        val secondSink = FakeSink()
        assertTrue(CourseAttemptStoreCore(root, secondSink).recover().succeeded)
        assertEquals(original, TEST_JSON.decodeFromString<CourseResult>(secondSink.attempts.getValue(original.clientAttemptId).resultJson))
    }

    private class FakeSink : CourseAttemptSink {
        val attempts = LinkedHashMap<String, SkillCourseAttemptEntity>()
        var failSaves = false
        var failLists = false
        var onSave: ((SkillCourseAttemptEntity) -> Unit)? = null

        override fun getAttempt(id: String): SkillCourseAttemptEntity? = attempts[id]

        override fun saveAttempt(attempt: SkillCourseAttemptEntity): SkillCourseAttemptEntity {
            onSave?.invoke(attempt)
            if (failSaves) {
                throw IOException("database unavailable")
            }
            attempts[attempt.id] = attempt
            return attempt
        }

        override fun listUnfinishedAttempts(): List<SkillCourseAttemptEntity> {
            if (failLists) {
                throw IOException("database unavailable")
            }
            return attempts.values.filter { it.outcome == "in_progress" }
        }
    }

    @Test
    fun `persist writes the typed journal before the database and stores matching metadata`() {
        val root = newRoot()
        val sink = FakeSink().apply {
            onSave = { attempt ->
                assertTrue(
                    File(root, "${attempt.id}/${CourseAttemptStoreCore.RESULT_FILE}").isFile
                )
            }
        }
        val store = CourseAttemptStoreCore(root, sink)
        val result = result(root)

        val stored = store.persist(result)

        val journal = File(root, "${result.clientAttemptId}/${CourseAttemptStoreCore.RESULT_FILE}")
        assertTrue(journal.isFile)
        assertEquals(result, TEST_JSON.decodeFromString<CourseResult>(journal.readText()))
        assertEquals(stored.resultJson, journal.readText())
        assertEquals(result.clientAttemptId, stored.id)
        assertEquals(result.courseId, stored.courseId)
        assertEquals(result.courseHash, stored.courseHash)
        assertEquals(CourseCodec.encode(result.definition), stored.definitionJson)
        assertEquals(result.replayDirectory, stored.replayDirectory)
        assertEquals(stored, sink.attempts.getValue(result.clientAttemptId))
    }

    @Test
    fun `database failure retains the journal and a later recovery repairs it`() {
        val root = newRoot()
        val sink = FakeSink().apply { failSaves = true }
        val store = CourseAttemptStoreCore(root, sink)
        val result = result(root)

        assertThrows(IOException::class.java) { store.persist(result) }

        val journal = File(root, "${result.clientAttemptId}/${CourseAttemptStoreCore.RESULT_FILE}")
        assertTrue(journal.isFile)
        assertEquals(result, TEST_JSON.decodeFromString<CourseResult>(journal.readText()))
        assertTrue(sink.attempts.isEmpty())

        sink.failSaves = false
        val report = store.recover()

        assertTrue(report.errors.isEmpty())
        assertEquals(1, report.journalsScanned)
        assertEquals(1, report.journalsReconciled)
        assertEquals(1, report.unfinishedAttemptsInterrupted)
        val repaired = sink.attempts.getValue(result.clientAttemptId)
        assertEquals("interrupted", repaired.outcome)
        val decoded = TEST_JSON.decodeFromString<CourseResult>(repaired.resultJson)
        assertEquals("interrupted", decoded.outcome)
        assertEquals(CourseAttemptStoreCore.INTERRUPTED_REASON, decoded.reason)
        // The durable journal is kept as evidence and still records the original state.
        assertEquals(result, TEST_JSON.decodeFromString<CourseResult>(journal.readText()))
    }

    @Test
    fun `recover preserves terminal outcomes and is idempotent`() {
        val root = newRoot()
        val sink = FakeSink()
        val store = CourseAttemptStoreCore(root, sink)
        val results = listOf("completed", "failed", "aborted").map { outcome ->
            result(root, outcome = outcome, reason = "reason-$outcome")
        }

        results.forEach { store.persist(it) }
        sink.attempts.clear()

        val first = store.recover()

        assertTrue(first.errors.isEmpty())
        assertEquals(3, first.journalsScanned)
        assertEquals(3, first.journalsReconciled)
        assertEquals(0, first.unfinishedAttemptsInterrupted)
        results.forEach { expected ->
            val stored = sink.attempts.getValue(expected.clientAttemptId)
            assertEquals(expected.outcome, stored.outcome)
            val decoded = TEST_JSON.decodeFromString<CourseResult>(stored.resultJson)
            assertEquals(expected.outcome, decoded.outcome)
            assertEquals(expected.reason, decoded.reason)
        }

        val second = store.recover()
        assertTrue(second.errors.isEmpty())
        assertEquals(3, second.journalsScanned)
        assertEquals(0, second.journalsReconciled)
        assertEquals(0, second.unfinishedAttemptsInterrupted)
    }

    @Test
    fun `recover interrupts unfinished database attempts without a course row`() {
        val root = File(temp.root, "SkillCourses/Attempts")
        val sink = FakeSink()
        val result = result(root, courseId = "deleted-course")
        sink.attempts[result.clientAttemptId] = entity(result)

        val report = CourseAttemptStoreCore(root, sink).recover()

        assertTrue(report.errors.isEmpty())
        assertEquals(0, report.journalsScanned)
        assertEquals(1, report.unfinishedAttemptsInterrupted)
        val stored = sink.attempts.getValue(result.clientAttemptId)
        assertEquals("interrupted", stored.outcome)
        val decoded = TEST_JSON.decodeFromString<CourseResult>(stored.resultJson)
        assertEquals("interrupted", decoded.outcome)
        assertEquals(CourseAttemptStoreCore.INTERRUPTED_REASON, decoded.reason)
        assertEquals(result.courseId, stored.courseId)

        val second = CourseAttemptStoreCore(root, sink).recover()
        assertEquals(0, second.unfinishedAttemptsInterrupted)
        assertTrue(second.errors.isEmpty())
    }

    @Test
    fun `damaged and misdirected journals are isolated per record`() {
        val root = newRoot()
        val sink = FakeSink()
        val store = CourseAttemptStoreCore(root, sink)

        val valid = result(root, outcome = "completed", reason = "done")
        writeJournal(root, valid)

        val badJson = File(root, "bad-json").apply { mkdirs() }
        File(badJson, CourseAttemptStoreCore.RESULT_FILE).writeText("{not json")

        val badUtf8 = File(root, "bad-utf8").apply { mkdirs() }
        File(badUtf8, CourseAttemptStoreCore.RESULT_FILE).writeBytes(byteArrayOf(0xC3.toByte()))

        val mismatched = result(root)
        val mismatchedDir = File(root, "mismatched").apply { mkdirs() }
        File(mismatchedDir, CourseAttemptStoreCore.RESULT_FILE)
            .writeText(TEST_JSON.encodeToString(mismatched))

        val outside = temp.newFolder("outside")
        val escaped = result(root, outcome = "completed").copy(
            replayDirectory = outside.absolutePath
        )
        writeJournal(root, escaped)

        val report = store.recover()

        assertFalse(report.succeeded)
        assertEquals(5, report.journalsScanned)
        assertEquals(setOf(valid.clientAttemptId), sink.attempts.keys)
        assertEquals("completed", sink.attempts.getValue(valid.clientAttemptId).outcome)
        val errorIds = report.errors.mapNotNull { it.attemptId }.toSet()
        assertTrue("bad-json" in errorIds)
        assertTrue("bad-utf8" in errorIds)
        assertTrue("mismatched" in errorIds)
        assertTrue(escaped.clientAttemptId in errorIds)
        assertFalse(File(outside, CourseAttemptStoreCore.RESULT_FILE).exists())
    }

    @Test
    fun `persist rejects unsafe ids and replay directories without writing`() {
        val root = newRoot()
        val sink = FakeSink()
        val store = CourseAttemptStoreCore(root, sink)
        val outside = temp.newFolder("outside")

        val wrongDirectory = result(root).copy(replayDirectory = outside.absolutePath)
        assertThrows(IllegalArgumentException::class.java) { store.persist(wrongDirectory) }
        assertFalse(File(root, wrongDirectory.clientAttemptId).exists())

        val unsafeId = result(root, id = "../escape")
        assertThrows(IllegalArgumentException::class.java) { store.persist(unsafeId) }

        assertTrue(sink.attempts.isEmpty())
        assertFalse(File(checkNotNull(root.parentFile), "escape").exists())
    }

    @Test
    fun `failure before atomic replace keeps the previous journal and native evidence`() {
        val root = newRoot()
        val sink = FakeSink()
        val store = CourseAttemptStoreCore(root, sink)
        val original = result(root)
        store.persist(original)

        val directory = File(root, original.clientAttemptId)
        val replay = File(directory, "0.odr").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val events = File(directory, "0.events.json").apply { writeText("[]") }
        val journal = File(directory, CourseAttemptStoreCore.RESULT_FILE)
        val journalBefore = journal.readText()

        // Force a failure while preparing the replacement, before any rename happens.
        File(directory, "${CourseAttemptStoreCore.RESULT_FILE}.tmp").mkdirs()

        val updated = original.copy(outcome = "completed", playerName = "Updated")
        assertThrows(IOException::class.java) { store.persist(updated) }

        assertEquals(journalBefore, journal.readText())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), replay.readBytes())
        assertEquals("[]", events.readText())
        assertEquals(original, TEST_JSON.decodeFromString<CourseResult>(journal.readText()))
        assertEquals("in_progress", sink.attempts.getValue(original.clientAttemptId).outcome)

        // A subsequent recovery still sees the intact in-progress journal.
        assertTrue(File(directory, "${CourseAttemptStoreCore.RESULT_FILE}.tmp").delete())
        val report = store.recover()
        assertTrue(report.errors.isEmpty())
        assertEquals("interrupted", sink.attempts.getValue(original.clientAttemptId).outcome)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), replay.readBytes())
        assertEquals("[]", events.readText())
    }

    @Test
    fun `oversized journals are rejected without blocking valid records`() {
        val root = newRoot()
        val sink = FakeSink()
        val valid = result(root, outcome = "completed")
        writeJournal(root, valid)

        val oversized = File(root, "oversized").apply { mkdirs() }
        RandomAccessFile(
            File(oversized, CourseAttemptStoreCore.RESULT_FILE),
            "rw"
        ).use { it.setLength(CourseAttemptStoreCore.MAX_RESULT_BYTES + 1L) }

        val report = CourseAttemptStoreCore(root, sink).recover()

        assertFalse(report.succeeded)
        assertEquals(2, report.journalsScanned)
        assertEquals(setOf(valid.clientAttemptId), sink.attempts.keys)
        assertTrue(report.errors.any { it.attemptId == "oversized" })
    }

    @Test
    fun `recover reports a database listing failure instead of claiming success`() {
        val sink = FakeSink().apply { failLists = true }

        val report = CourseAttemptStoreCore(newRoot(), sink).recover()

        assertFalse(report.succeeded)
        assertTrue(report.errors.any { it.attemptId == null })
    }

    @Test
    fun `staging directories without a committed snapshot are ignored`() {
        val root = newRoot()
        val sink = FakeSink()
        File(root, "staging").mkdirs()

        val report = CourseAttemptStoreCore(root, sink).recover()

        assertTrue(report.succeeded)
        assertEquals(0, report.journalsScanned)
        assertTrue(sink.attempts.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
class CourseAttemptStoreRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: DroidDatabase
    private lateinit var repository: CourseRepositoryCore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DroidDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = CourseRepositoryCore(database.getCourseTable()) { 1_000L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private class RepositorySink(
        private val repository: CourseRepositoryCore
    ) : CourseAttemptSink {
        override fun getAttempt(id: String): SkillCourseAttemptEntity? =
            repository.getAttempt(id)

        override fun saveAttempt(attempt: SkillCourseAttemptEntity): SkillCourseAttemptEntity =
            repository.saveAttempt(attempt)

        override fun listUnfinishedAttempts(): List<SkillCourseAttemptEntity> =
            repository.listUnfinishedAttempts()
    }

    @Test
    fun `recover interrupts unfinished attempts after their course is deleted`() {
        val definition = CourseTestFixtures.definition()
        val course = repository.create(definition)
        val root = File(temp.root, "SkillCourses/Attempts")
        val attemptId = UUID.randomUUID().toString()
        val result = CourseResult(
            clientAttemptId = attemptId,
            courseId = course.id,
            courseHash = course.courseHash,
            definition = definition,
            playerName = "Player",
            createdAt = 1_234L,
            outcome = "in_progress",
            order = listOf(0),
            components = emptyList(),
            rawExTotal = 0L,
            activeNanos = 0L,
            replayDirectory = File(root, attemptId).absolutePath
        )
        repository.saveAttempt(
            SkillCourseAttemptEntity(
                id = result.clientAttemptId,
                courseId = result.courseId,
                courseHash = result.courseHash,
                definitionJson = course.definitionJson,
                outcome = result.outcome,
                createdAt = result.createdAt,
                resultJson = TEST_JSON.encodeToString(result),
                replayDirectory = result.replayDirectory
            )
        )

        assertTrue(repository.delete(course.id))
        assertEquals(1, repository.listUnfinishedAttempts().size)

        val report = CourseAttemptStoreCore(root, RepositorySink(repository)).recover()

        assertTrue(report.errors.isEmpty())
        assertEquals(1, report.unfinishedAttemptsInterrupted)
        val stored = repository.getAttempt(result.clientAttemptId)
        assertNotNull(stored)
        assertEquals("interrupted", stored!!.outcome)
        val decoded = TEST_JSON.decodeFromString<CourseResult>(stored.resultJson)
        assertEquals("interrupted", decoded.outcome)
        assertEquals(CourseAttemptStoreCore.INTERRUPTED_REASON, decoded.reason)
        assertTrue(repository.listUnfinishedAttempts().isEmpty())
    }
}

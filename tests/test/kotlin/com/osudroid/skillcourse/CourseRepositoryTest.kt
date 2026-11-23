package com.osudroid.skillcourse

import androidx.room.Room
import com.osudroid.data.DroidDatabase
import com.osudroid.data.SkillCourseAttemptEntity
import com.osudroid.data.SkillCourseEntity
import com.osudroid.skillcourse.CourseTestFixtures.apiMod
import com.osudroid.skillcourse.CourseTestFixtures.definition
import com.osudroid.skillcourse.CourseTestFixtures.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CourseRepositoryTest {

    private lateinit var database: DroidDatabase
    private lateinit var repository: CourseRepositoryCore
    private var now = 1_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DroidDatabase::class.java
        ).allowMainThreadQueries().build()

        repository = CourseRepositoryCore(database.getCourseTable()) { now++ }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun playable() = definition(
        maps = listOf(
            map(mods = listOf(apiMod("HD"))),
            map(hash = CourseTestFixtures.HASH_B)
        )
    )

    private fun attempt(course: SkillCourseEntity, id: String = "attempt-1") =
        SkillCourseAttemptEntity(
            id = id,
            courseId = course.id,
            courseHash = course.courseHash,
            definitionJson = course.definitionJson,
            outcome = "completed",
            createdAt = now++,
            resultJson = "{\"components\":[]}"
        )

    @Test
    fun `Empty draft can be created and decoded`() {
        val draft = definition(maps = emptyList())
        val entity = repository.create(draft)

        assertFalse(entity.locked)
        assertNotNull(repository.getEntity(entity.id))
        assertEquals(draft, repository.getCourse(entity.id))
        assertEquals(1, repository.listCourses().size)
    }

    @Test
    fun `Import is idempotent and does not reset lock state`() {
        val text = CourseCodec.encode(playable())

        val first = repository.importCourse(text)
        assertTrue(first.locked)

        val unlocked = repository.setLocked(first.id, false)
        assertFalse(unlocked.locked)

        val second = repository.importCourse(text)
        assertEquals(first.id, second.id)
        assertFalse(second.locked)
        assertEquals(1, repository.listCourses().size)
    }

    @Test
    fun `Locked courses reject save and toggle`() {
        val imported = repository.importCourse(CourseCodec.encode(playable()))

        assertThrows(CourseRepositoryException::class.java) {
            repository.save(imported.id, playable().copy(courseName = "Changed"))
        }
        assertThrows(CourseRepositoryException::class.java) {
            repository.toggleMap(imported.id, map(hash = CourseTestFixtures.HASH_C))
        }

        repository.setLocked(imported.id, false)
        repository.save(imported.id, playable().copy(courseName = "Changed"))
        assertEquals("Changed", repository.getEntity(imported.id)!!.courseName)
    }

    @Test
    fun `Save changes content hash and summary transactionally`() {
        val course = repository.create(playable())
        val oldHash = course.courseHash

        val changed = playable().copy(courseName = "Changed Course", author = "Changed Author")
        val updated = repository.save(course.id, changed)

        assertNotEquals(oldHash, updated.courseHash)
        assertEquals("Changed Course", updated.courseName)
        assertEquals("Changed Author", updated.author)
        assertEquals(changed, repository.getCourse(course.id))
        assertEquals(updated.courseHash, repository.getEntity(course.id)!!.courseHash)
    }

    @Test
    fun `Toggle map adds and removes by MD5 regardless of mods`() {
        val course = repository.create(definition(maps = emptyList()))
        val mapA = map(hash = CourseTestFixtures.HASH_A, mods = listOf(apiMod("HD")))

        repository.toggleMap(course.id, mapA)
        val added = repository.getCourse(course.id)!!
        assertEquals(1, added.maps.size)
        assertEquals(mapA, added.maps[0])

        val removalSnapshot = mapA.copy(mods = listOf(apiMod("DT")))
        repository.toggleMap(course.id, removalSnapshot)
        val removed = repository.getCourse(course.id)!!
        assertTrue(removed.maps.isEmpty())

        repository.toggleMap(course.id, removalSnapshot)
        val readded = repository.getCourse(course.id)!!
        assertEquals(1, readded.maps.size)
        assertEquals(removalSnapshot, readded.maps[0])
    }

    @Test
    fun `Delete preserves attempt history and the frozen snapshot`() {
        val course = repository.create(playable())
        val frozenJson = course.definitionJson
        val attempt = repository.saveAttempt(attempt(course))

        assertTrue(repository.delete(course.id))
        assertNull(repository.getEntity(course.id))

        val stored = repository.getAttempt(attempt.id)
        assertNotNull(stored)
        assertEquals(frozenJson, stored!!.definitionJson)
        assertEquals(1, repository.listAttempts(course.id).size)
    }

    @Test
    fun `Attempt snapshot is not rewritten when the course changes`() {
        val course = repository.create(playable())
        val attempt = repository.saveAttempt(attempt(course))

        repository.save(
            course.id,
            playable().copy(maps = listOf(map(hash = CourseTestFixtures.HASH_C)))
        )

        val stored = repository.getAttempt(attempt.id)!!
        assertEquals(course.courseHash, stored.courseHash)
        assertEquals(course.definitionJson, stored.definitionJson)
    }

    @Test
    fun `Create rejects duplicate content and attempts reject mismatched hashes`() {
        val course = repository.create(playable())

        assertThrows(CourseRepositoryException::class.java) {
            repository.create(playable())
        }

        val mismatched = attempt(course).copy(courseHash = "deadbeef")
        assertThrows(CourseRepositoryException::class.java) {
            repository.saveAttempt(mismatched)
        }

        repository.saveAttempt(attempt(course, id = "attempt-2"))
        assertEquals(1, repository.listAttempts(course.id).size)
    }

    @Test
    fun `History unions the row with the current hash and deduplicates newest first`() {
        val first = repository.create(playable())
        val edited = repository.saveAttempt(attempt(first, id = "edited"))
        val current = repository.save(first.id, playable().copy(courseName = "Edited Course"))

        // A previous local row for the same definition contributed this attempt before deletion.
        val previousRow = repository.saveAttempt(
            attempt(current, id = "previous-row").copy(courseId = "deleted-row")
        )
        // This attempt matches both the current id and the current hash and must appear once.
        val currentRow = repository.saveAttempt(attempt(current, id = "current-row"))

        val other = repository.create(definition(courseName = "Other Course"))
        val unrelated = repository.saveAttempt(attempt(other, id = "unrelated"))

        val history = repository.listAttempts(current.id, current.courseHash)

        assertEquals(
            listOf(currentRow.id, previousRow.id, edited.id),
            history.map { it.id }
        )
        assertEquals(1, history.count { it.id == currentRow.id })
        assertFalse(history.any { it.id == unrelated.id })

        // The earlier definition's frozen snapshot is returned unchanged, not rewritten to the
        // course's current definition.
        val preserved = history.first { it.id == edited.id }
        assertEquals(edited, preserved)
        assertEquals(first.courseHash, preserved.courseHash)
        assertEquals(first.definitionJson, preserved.definitionJson)
    }

    @Test
    fun `Reimporting a deleted definition exposes its previous attempts`() {
        val text = CourseCodec.encode(playable())
        val original = repository.importCourse(text)
        val stored = repository.saveAttempt(attempt(original))

        assertTrue(repository.delete(original.id))
        val reimported = repository.importCourse(text)

        assertNotEquals(original.id, reimported.id)
        assertEquals(original.courseHash, reimported.courseHash)

        // The captured hash makes the deleted row's attempts visible to the new row, and the
        // fallback lookup from the current row behaves the same.
        assertEquals(listOf(stored), repository.listAttempts(reimported.id, reimported.courseHash))
        assertEquals(listOf(stored), repository.listAttempts(reimported.id))
    }
}

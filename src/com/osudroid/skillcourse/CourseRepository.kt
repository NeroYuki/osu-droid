package com.osudroid.skillcourse

import com.osudroid.data.DatabaseManager
import com.osudroid.data.ICourseDAO
import com.osudroid.data.SkillCourseAttemptEntity
import com.osudroid.data.SkillCourseEntity
import com.osudroid.skillcourse.recovery.CourseAvailabilityCache
import java.util.UUID

/**
 * Thrown when a course repository operation cannot be completed safely.
 */
class CourseRepositoryException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Testable repository implementation. The public [CourseRepository] supplies the live Room DAO.
 */
internal class CourseRepositoryCore(
    private val dao: ICourseDAO,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    fun listCourses(): List<SkillCourseEntity> = dao.listCourses()

    fun getEntity(id: String): SkillCourseEntity? = dao.getCourse(id)

    fun getEntityByHash(hash: String): SkillCourseEntity? = dao.getCourseByHash(hash)

    fun getCourse(id: String): CourseDefinition? =
        dao.getCourse(id)?.let { decodeDefinition(it) }

    fun create(definition: CourseDefinition, locked: Boolean = false): SkillCourseEntity {
        CourseCodec.validate(definition, allowDraft = true)
        val hash = CourseCodec.hash(definition, allowDraft = true)

        if (dao.getCourseByHash(hash) != null) {
            throw CourseRepositoryException("A course with identical content already exists.")
        }

        val now = clock()
        val entity = SkillCourseEntity(
            id = UUID.randomUUID().toString(),
            definitionJson = CourseCodec.encode(definition, allowDraft = true),
            courseHash = hash,
            courseName = definition.courseName,
            author = definition.author,
            locked = locked,
            createdAt = now,
            updatedAt = now
        )

        dao.upsertCourse(entity)
        return entity
    }

    /**
     * Imports a playable definition.
     *
     * Imported courses are locked by default. Re-importing identical content returns the existing
     * row and does not reset its lock state.
     */
    fun importCourse(text: String): SkillCourseEntity {
        val definition = CourseCodec.decode(text, allowDraft = false)
        val hash = CourseCodec.hash(definition, allowDraft = false)

        dao.getCourseByHash(hash)?.let { return it }

        val now = clock()
        val entity = SkillCourseEntity(
            id = UUID.randomUUID().toString(),
            definitionJson = CourseCodec.encode(definition, allowDraft = false),
            courseHash = hash,
            courseName = definition.courseName,
            author = definition.author,
            locked = true,
            createdAt = now,
            updatedAt = now
        )

        dao.upsertCourse(entity)
        return entity
    }

    fun save(id: String, definition: CourseDefinition): SkillCourseEntity {
        val existing = dao.getCourse(id)
            ?: throw CourseRepositoryException("Course '$id' does not exist.")

        if (existing.locked) {
            throw CourseRepositoryException("Course '$id' is locked and cannot be modified.")
        }

        CourseCodec.validate(definition, allowDraft = true)
        val hash = CourseCodec.hash(definition, allowDraft = true)

        val conflicting = dao.getCourseByHash(hash)
        if (conflicting != null && conflicting.id != id) {
            throw CourseRepositoryException("A course with identical content already exists.")
        }

        val updated = existing.copy(
            definitionJson = CourseCodec.encode(definition, allowDraft = true),
            courseHash = hash,
            courseName = definition.courseName,
            author = definition.author,
            updatedAt = clock()
        )

        dao.upsertCourse(updated)
        return updated
    }

    fun setLocked(id: String, locked: Boolean): SkillCourseEntity {
        val existing = dao.getCourse(id)
            ?: throw CourseRepositoryException("Course '$id' does not exist.")

        val updated = existing.copy(locked = locked, updatedAt = clock())
        dao.upsertCourse(updated)
        return updated
    }

    /**
     * Deletes only the course row. Attempts intentionally have no foreign key and are retained.
     */
    fun delete(id: String): Boolean {
        if (dao.getCourse(id) == null) {
            return false
        }

        dao.deleteCourse(id)
        return true
    }

    /**
     * Adds the supplied snapshot or removes the existing entry with the same map MD5, regardless of
     * either entry's mods.
     */
    fun toggleMap(id: String, map: CourseMap): SkillCourseEntity {
        val existing = dao.getCourse(id)
            ?: throw CourseRepositoryException("Course '$id' does not exist.")

        if (existing.locked) {
            throw CourseRepositoryException("Course '$id' is locked and cannot be modified.")
        }

        val definition = decodeDefinition(existing)
        val maps = if (definition.maps.any { it.mapHash == map.mapHash }) {
            definition.maps.filterNot { it.mapHash == map.mapHash }
        } else {
            definition.maps + map
        }

        return save(id, definition.copy(maps = maps))
    }

    /**
     * Lists the local attempt history for a course row.
     *
     * The history is the union of every attempt recorded against [courseId] (including attempts
     * from earlier definitions of that row after it was edited) and every attempt recorded against
     * the current [courseHash] (including attempts left by a previous local row for the same
     * definition after it was deleted and imported again).
     *
     * Attempts are deduplicated by id and returned newest first. Stored snapshots are returned
     * untouched.
     *
     * A caller that already holds the row should pass its [courseHash]. It is otherwise read from
     * the current row, which may no longer exist.
     */
    fun listAttempts(
        courseId: String,
        courseHash: String? = null
    ): List<SkillCourseAttemptEntity> {
        val currentHash = courseHash ?: dao.getCourse(courseId)?.courseHash
        val attempts = LinkedHashMap<String, SkillCourseAttemptEntity>()
        for (attempt in dao.listAttempts(courseId)) {
            attempts[attempt.id] = attempt
        }
        if (currentHash != null) {
            for (attempt in dao.listAttemptsByHash(currentHash)) {
                if (attempt.id !in attempts) {
                    attempts[attempt.id] = attempt
                }
            }
        }
        return attempts.values.sortedWith(
            compareByDescending<SkillCourseAttemptEntity> { it.createdAt }.thenBy { it.id }
        )
    }

    /** Lists unfinished attempts without consulting the course table. */
    fun listUnfinishedAttempts(): List<SkillCourseAttemptEntity> = dao.listUnfinishedAttempts()

    fun getAttempt(id: String): SkillCourseAttemptEntity? = dao.getAttempt(id)

    /**
     * Persists an immutable attempt snapshot.
     *
     * The stored definition is revalidated as playable and must hash to the entity's course hash.
     * The attempt does not need a current course row, so history survives course deletion/edit.
     */
    fun saveAttempt(attempt: SkillCourseAttemptEntity): SkillCourseAttemptEntity {
        if (attempt.id.isBlank()) {
            throw CourseRepositoryException("Attempt id must not be blank.")
        }
        if (attempt.courseId.isBlank()) {
            throw CourseRepositoryException("Attempt courseId must not be blank.")
        }
        if (attempt.outcome.isBlank()) {
            throw CourseRepositoryException("Attempt outcome must not be blank.")
        }

        val definition = try {
            CourseCodec.decode(attempt.definitionJson, allowDraft = false)
        } catch (e: IllegalArgumentException) {
            throw CourseRepositoryException("Attempt definition snapshot is invalid.", e)
        }

        val hash = CourseCodec.hash(definition, allowDraft = false)
        if (hash != attempt.courseHash) {
            throw CourseRepositoryException(
                "Attempt courseHash does not match its frozen definition snapshot."
            )
        }

        dao.insertAttempt(attempt)
        return attempt
    }

    private fun decodeDefinition(entity: SkillCourseEntity): CourseDefinition =
        try {
            CourseCodec.decode(entity.definitionJson, allowDraft = true)
        } catch (e: IllegalArgumentException) {
            throw CourseRepositoryException("Stored course '${entity.id}' is invalid.", e)
        }
}

/**
 * The Skill Course local repository.
 *
 * This object performs no UI or network work. It treats the encoded definition as the authoritative
 * ordered snapshot and keeps immutable attempt snapshots independent of course edits/deletion.
 */
object CourseRepository {

    private fun core() = CourseRepositoryCore(DatabaseManager.courseTable)

    @JvmStatic
    fun listCourses(): List<SkillCourseEntity> = core().listCourses()

    @JvmStatic
    fun getEntity(id: String): SkillCourseEntity? = core().getEntity(id)

    @JvmStatic
    fun getEntityByHash(hash: String): SkillCourseEntity? = core().getEntityByHash(hash)

    @JvmStatic
    fun getCourse(id: String): CourseDefinition? = core().getCourse(id)

    @JvmStatic
    @JvmOverloads
    fun create(definition: CourseDefinition, locked: Boolean = false): SkillCourseEntity {
        val entity = DatabaseManager.runInTransaction { core().create(definition, locked) }
        CourseAvailabilityCache.onCourseChanged(null, entity.courseHash)
        return entity
    }

    @JvmStatic
    fun importCourse(text: String): SkillCourseEntity {
        val entity = DatabaseManager.runInTransaction { core().importCourse(text) }
        CourseAvailabilityCache.onCourseChanged(null, entity.courseHash)
        return entity
    }

    @JvmStatic
    fun save(id: String, definition: CourseDefinition): SkillCourseEntity {
        val previousHash = core().getEntity(id)?.courseHash
        val entity = DatabaseManager.runInTransaction { core().save(id, definition) }
        CourseAvailabilityCache.onCourseChanged(previousHash, entity.courseHash)
        return entity
    }

    @JvmStatic
    fun setLocked(id: String, locked: Boolean): SkillCourseEntity {
        // Lock state does not change availability. Do not invalidate or rescan the cache here.
        return DatabaseManager.runInTransaction { core().setLocked(id, locked) }
    }

    @JvmStatic
    fun delete(id: String): Boolean {
        val courseHash = core().getEntity(id)?.courseHash
        val deleted = DatabaseManager.runInTransaction { core().delete(id) }
        if (deleted && courseHash != null) {
            CourseAvailabilityCache.evictCourse(courseHash)
        }
        return deleted
    }

    @JvmStatic
    fun toggleMap(id: String, map: CourseMap): SkillCourseEntity {
        val previousHash = core().getEntity(id)?.courseHash
        val entity = DatabaseManager.runInTransaction { core().toggleMap(id, map) }
        CourseAvailabilityCache.onCourseChanged(previousHash, entity.courseHash)
        return entity
    }

    @JvmStatic
    @JvmOverloads
    fun listAttempts(courseId: String, courseHash: String? = null): List<SkillCourseAttemptEntity> =
        core().listAttempts(courseId, courseHash)

    @JvmStatic
    fun listUnfinishedAttempts(): List<SkillCourseAttemptEntity> = core().listUnfinishedAttempts()

    @JvmStatic
    fun getAttempt(id: String): SkillCourseAttemptEntity? = core().getAttempt(id)

    @JvmStatic
    fun saveAttempt(attempt: SkillCourseAttemptEntity): SkillCourseAttemptEntity =
        DatabaseManager.runInTransaction { core().saveAttempt(attempt) }
}

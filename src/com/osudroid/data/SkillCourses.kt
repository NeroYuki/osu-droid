package com.osudroid.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A locally stored Skill Course.
 *
 * [definitionJson] is the authoritative ordered portable snapshot. The summary columns are
 * denormalized for listing only; the course name is not an identity. [locked] is local state and is
 * never part of the portable definition or its hash.
 */
@Entity(
    tableName = "SkillCourse",
    indices = [
        Index(name = "index_SkillCourse_courseHash", value = ["courseHash"], unique = true)
    ]
)
data class SkillCourseEntity(
    @PrimaryKey
    val id: String,

    val definitionJson: String,

    val courseHash: String,

    val courseName: String,

    val author: String,

    val locked: Boolean,

    val createdAt: Long,

    val updatedAt: Long
)

/**
 * An immutable local attempt snapshot.
 *
 * There is deliberately no cascading foreign key to [SkillCourseEntity]: deleting or editing a
 * course must never remove or rewrite historical attempts. [definitionJson] freezes the definition
 * the attempt was played against, and [resultJson] reserves the typed future result/component
 * snapshot (including per-map replay references).
 */
@Entity(
    tableName = "SkillCourseAttempt",
    indices = [
        Index(name = "index_SkillCourseAttempt_courseId", value = ["courseId"]),
        Index(name = "index_SkillCourseAttempt_courseHash", value = ["courseHash"])
    ]
)
data class SkillCourseAttemptEntity(
    @PrimaryKey
    val id: String,

    val courseId: String,

    val courseHash: String,

    val definitionJson: String,

    val rulesetVersion: Int = 1,

    val outcome: String,

    val createdAt: Long,

    val resultJson: String,

    val replayDirectory: String? = null
)

@Dao
interface ICourseDAO {

    @Query("SELECT * FROM SkillCourse ORDER BY courseName COLLATE NOCASE ASC, createdAt ASC")
    fun listCourses(): List<SkillCourseEntity>

    @Query("SELECT * FROM SkillCourse WHERE id = :id")
    fun getCourse(id: String): SkillCourseEntity?

    @Query("SELECT * FROM SkillCourse WHERE courseHash = :courseHash LIMIT 1")
    fun getCourseByHash(courseHash: String): SkillCourseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCourse(course: SkillCourseEntity)

    @Query("DELETE FROM SkillCourse WHERE id = :id")
    fun deleteCourse(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAttempt(attempt: SkillCourseAttemptEntity)

    @Query("SELECT * FROM SkillCourseAttempt WHERE id = :id")
    fun getAttempt(id: String): SkillCourseAttemptEntity?

    @Query("SELECT * FROM SkillCourseAttempt WHERE courseId = :courseId ORDER BY createdAt DESC, id ASC")
    fun listAttempts(courseId: String): List<SkillCourseAttemptEntity>

    @Query("SELECT * FROM SkillCourseAttempt WHERE courseHash = :courseHash ORDER BY createdAt DESC, id ASC")
    fun listAttemptsByHash(courseHash: String): List<SkillCourseAttemptEntity>

    /**
     * Deliberately not joined to [SkillCourseEntity], so attempts survive course deletion and can
     * still be repaired after process death.
     */
    @Query("SELECT * FROM SkillCourseAttempt WHERE outcome = 'in_progress' ORDER BY createdAt DESC, id ASC")
    fun listUnfinishedAttempts(): List<SkillCourseAttemptEntity>
}

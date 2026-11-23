package com.osudroid.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Local per-map play preferences for a Skill Course.
 *
 * The key deliberately uses the local course row ID and the authored map MD5:
 * - two courses that use the same map keep independent preferences,
 * - reordering a course keeps each preference bound to its map,
 * - nothing here participates in the portable definition or its content hash.
 *
 * There is intentionally no foreign key to [SkillCourseEntity]. Preferences are local convenience
 * state, not historical attempt data, and deleting a course must not mutate attempts.
 */
@Entity(
    tableName = "CoursePlayPreference",
    primaryKeys = ["courseId", "mapHash"]
)
data class CoursePlayPreferenceEntity(
    val courseId: String,

    val mapHash: String,

    @ColumnInfo(defaultValue = "100")
    val speedHundredths: Int = 100,

    @ColumnInfo(defaultValue = "'[]'")
    val additionalModsJson: String = "[]",

    val updatedAt: Long
)

/**
 * Separate DAO for local play preferences so the Skill Course definition/attempt DAO stays focused
 * on the portable and frozen history contracts.
 */
@Dao
interface ICoursePlayPreferenceDAO {

    @Query("SELECT * FROM CoursePlayPreference WHERE courseId = :courseId AND mapHash = :mapHash")
    fun get(courseId: String, mapHash: String): CoursePlayPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(preference: CoursePlayPreferenceEntity)

    @Query("DELETE FROM CoursePlayPreference WHERE courseId = :courseId AND mapHash = :mapHash")
    fun delete(courseId: String, mapHash: String)
}

package com.osudroid.skillcourse

import com.osudroid.data.CoursePlayPreferenceEntity
import com.osudroid.data.DatabaseManager
import com.osudroid.data.ICoursePlayPreferenceDAO
import com.osudroid.mods.Mod
import com.osudroid.utils.ModHashMap
import java.util.Locale

/**
 * Thrown when a local play preference cannot be read or written safely.
 */
class CoursePlayPreferencesException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Testable preference repository implementation. The public [CoursePlayPreferences] supplies the
 * live Room DAO and a transaction runner.
 *
 * Updates always copy the stored row and only replace the field owned by the caller, so saving a
 * speed never clears selected optional mods and saving mods never resets the speed.
 */
internal class CoursePlayPreferencesCore(
    private val dao: ICoursePlayPreferenceDAO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val transactionRunner: ((() -> Unit) -> Unit)? = null
) {

    fun get(courseId: String, mapHash: String): CoursePlayPreferenceEntity? {
        val course = requireCourseId(courseId)
        val map = canonicalMapHash(mapHash)
        return dao.get(course, map)
    }

    fun saveSpeed(courseId: String, mapHash: String, speedHundredths: Int): CoursePlayPreferenceEntity {
        val course = requireCourseId(courseId)
        val map = canonicalMapHash(mapHash)

        if (!CourseSpeed.isValid(speedHundredths)) {
            throw CoursePlayPreferencesException(
                "Speed must be between 0.50 and 3.00 in 0.05 increments."
            )
        }

        return runAtomically {
            val existing = dao.get(course, map)
            val now = clock()
            val updated = (existing ?: newPreference(course, map, now)).copy(
                speedHundredths = speedHundredths,
                updatedAt = now
            )

            dao.upsert(updated)
            updated
        }
    }

    fun saveMods(courseId: String, mapHash: String, mods: ModHashMap, speedHundredths: Int? = null): CoursePlayPreferenceEntity {
        val course = requireCourseId(courseId)
        val map = canonicalMapHash(mapHash)
        val serialized = serializeMods(mods)
        require(speedHundredths == null || CourseSpeed.isValid(speedHundredths)) { "Invalid course speed." }

        return runAtomically {
            val existing = dao.get(course, map)
            val now = clock()
            val updated = (existing ?: newPreference(course, map, now)).copy(
                additionalModsJson = serialized,
                speedHundredths = speedHundredths ?: existing?.speedHundredths ?: CourseSpeed.DEFAULT_HUNDREDTHS,
                updatedAt = now
            )

            dao.upsert(updated)
            updated
        }
    }

    private fun newPreference(courseId: String, mapHash: String, updatedAt: Long) = CoursePlayPreferenceEntity(
        courseId = courseId,
        mapHash = mapHash,
        updatedAt = updatedAt
    )

    private fun <T> runAtomically(block: () -> T): T {
        val runner = transactionRunner ?: return block()

        var result: T? = null
        runner { result = block() }

        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun requireCourseId(courseId: String): String {
        if (courseId.isBlank()) {
            throw CoursePlayPreferencesException("Course ID must not be blank.")
        }

        return courseId
    }

    private fun canonicalMapHash(mapHash: String): String {
        val trimmed = mapHash.trim()
        if (!MAP_HASH.matches(trimmed)) {
            throw CoursePlayPreferencesException("Map hash must be a 32-character MD5 hex string.")
        }

        return trimmed.lowercase(Locale.ROOT)
    }

    /**
     * Persists player additions with the standard native serialization policy.
     *
     * Non-playable mods and non-finite numeric settings are rejected instead of being written as
     * silently clamped or unusable JSON. Irrelevant mods are kept because the player explicitly
     * selected them, so a toggle or a default setting can be remembered even when it currently has
     * no gameplay effect.
     */
    private fun serializeMods(mods: ModHashMap): String {
        for (mod in mods.values) {
            validateMod(mod)
        }

        return try {
            mods.serializeMods(includeIrrelevantMods = true)
        } catch (e: Exception) {
            throw CoursePlayPreferencesException("Selected mods could not be serialized.", e)
        }
    }

    private fun validateMod(mod: Mod) {
        if (!mod.isUserPlayable) {
            throw CoursePlayPreferencesException("Mod ${mod.acronym} is not user-playable.")
        }

        for (setting in mod.settings) {
            when (val value = setting.value) {
                is Float -> if (!value.isFinite()) {
                    throw CoursePlayPreferencesException(
                        "Mod ${mod.acronym} setting '${setting.name}' must be a finite number."
                    )
                }

                is Double -> if (!value.isFinite()) {
                    throw CoursePlayPreferencesException(
                        "Mod ${mod.acronym} setting '${setting.name}' must be a finite number."
                    )
                }
            }
        }
    }

    private companion object {
        val MAP_HASH = Regex("^[0-9a-fA-F]{32}$")
    }
}

/**
 * Local per-map Skill Course preferences.
 *
 * The storage key is `(courseId, mapHash)`, never the course content hash or a map index. Portable
 * course files and their hashes never include any preference data.
 */
object CoursePlayPreferences {

    private fun core() = CoursePlayPreferencesCore(
        dao = DatabaseManager.coursePlayPreferenceTable,
        transactionRunner = { block -> DatabaseManager.runInTransaction(block) }
    )

    @JvmStatic
    fun get(courseId: String, mapHash: String): CoursePlayPreferenceEntity? =
        core().get(courseId, mapHash)

    @JvmStatic
    fun saveSpeed(courseId: String, mapHash: String, speedHundredths: Int): CoursePlayPreferenceEntity =
        core().saveSpeed(courseId, mapHash, speedHundredths)

    @JvmStatic
    @JvmOverloads
    fun saveMods(courseId: String, mapHash: String, mods: ModHashMap, speedHundredths: Int? = null): CoursePlayPreferenceEntity =
        core().saveMods(courseId, mapHash, mods, speedHundredths)
}

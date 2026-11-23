package com.osudroid.skillcourse.runtime

import com.osudroid.data.BeatmapInfo
import com.osudroid.skillcourse.CourseMap
import com.osudroid.skillcourse.CourseTestFixtures
import com.osudroid.skillcourse.recovery.CourseAvailabilitySnapshot
import com.osudroid.skillcourse.recovery.CourseMapAvailability
import com.osudroid.skillcourse.recovery.CourseMapStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseViewProjectionTest {

    @Test
    fun `ready and outdated candidates are projected while missing entries stay hidden`() {
        val ready = CourseTestFixtures.map(hash = HASH_A)
        val outdated = CourseTestFixtures.map(hash = HASH_B)
        val missing = CourseTestFixtures.map(hash = HASH_C)

        val projection = projectCourseView(
            snapshot(
                entry(0, ready, CourseMapStatus.READY, HASH_A),
                entry(1, outdated, CourseMapStatus.OUTDATED, HASH_B, HASH_C),
                entry(2, missing, CourseMapStatus.MISSING),
            )
        )

        assertEquals(setOf(HASH_A, HASH_B, HASH_C), projection.mapByInstalledHash.keys)
        assertEquals(ready, projection.mapByInstalledHash[HASH_A])
        assertEquals(outdated, projection.mapByInstalledHash[HASH_B])
        assertEquals(outdated, projection.mapByInstalledHash[HASH_C])
        assertEquals(setOf(HASH_B, HASH_C), projection.outdated)
    }

    @Test
    fun `a refreshed snapshot replaces obsolete installed identities instead of merging them`() {
        val courseMap = CourseTestFixtures.map(hash = HASH_A)
        val before = projectCourseView(
            snapshot(entry(0, courseMap, CourseMapStatus.OUTDATED, HASH_B))
        )

        val after = projectCourseView(
            snapshot(entry(0, courseMap, CourseMapStatus.READY, HASH_A))
        )

        assertTrue(before.mapByInstalledHash.containsKey(HASH_B))
        assertFalse(after.mapByInstalledHash.containsKey(HASH_B))
        assertEquals(setOf(HASH_A), after.mapByInstalledHash.keys)
        assertTrue(before.outdated.contains(HASH_B))
        assertTrue(after.outdated.isEmpty())
    }

    @Test
    fun `invalid entries and blank candidate hashes never become selectable`() {
        val courseMap = CourseTestFixtures.map(hash = HASH_A)
        val projection = projectCourseView(
            snapshot(
                entry(0, courseMap, CourseMapStatus.INVALID, HASH_A),
                entry(1, courseMap, CourseMapStatus.OUTDATED, " "),
            )
        )

        assertTrue(projection.mapByInstalledHash.isEmpty())
        assertTrue(projection.outdated.isEmpty())
    }

    private fun snapshot(vararg entries: CourseMapAvailability) = CourseAvailabilitySnapshot(
        entries = entries.toList(),
        allReady = entries.isNotEmpty() && entries.all { it.status == CourseMapStatus.READY },
        issues = emptyList(),
        libraryRevision = 1L,
    )

    private fun entry(
        index: Int,
        courseMap: CourseMap,
        status: CourseMapStatus,
        vararg candidateHashes: String,
    ): CourseMapAvailability {
        val candidates = candidateHashes.map(::row)
        return CourseMapAvailability(
            originalIndex = index,
            courseMap = courseMap,
            status = status,
            candidates = candidates,
            resolvedBeatmap = candidates.firstOrNull(),
            reason = null,
        )
    }

    private fun row(md5: String) = BeatmapInfo(
        filename = "map.osu",
        md5 = md5,
        id = 42L,
        audioFilename = "audio.mp3",
        backgroundFilename = null,
        status = null,
        setDirectory = "set-a",
        setId = 1,
        title = "Title",
        titleUnicode = "Title",
        artist = "Artist",
        artistUnicode = "Artist",
        creator = "Mapper",
        version = "Difficulty",
        tags = "",
        source = "",
        dateImported = 0L,
        approachRate = 9f,
        overallDifficulty = 8f,
        circleSize = 4f,
        hpDrainRate = 5f,
        droidStarRating = 0f,
        standardStarRating = 0f,
        bpmMax = 120f,
        bpmMin = 120f,
        mostCommonBPM = 120f,
        length = 1000L,
        previewTime = 0,
        hitCircleCount = 10,
        spinnerCount = 1,
        sliderCount = 5,
        maxCombo = 0,
        epilepsyWarning = false,
    )

    private companion object {
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val HASH_C = "cccccccccccccccccccccccccccccccc"
    }
}

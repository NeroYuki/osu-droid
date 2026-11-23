package com.osudroid.skillcourse

import com.osudroid.data.BeatmapInfo
import com.osudroid.mods.ModCustomSpeed
import com.osudroid.mods.ModHidden
import com.osudroid.mods.ModReplayV6
import com.osudroid.utils.ModHashMap
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseMapTest {

    @Test
    fun `fromBeatmap snapshots only portable fields and user-playable mods`() {
        val beatmap = BeatmapInfo(
            filename = "map.osu",
            md5 = CourseTestFixtures.HASH_A,
            id = 123L,
            audioFilename = "audio.mp3",
            backgroundFilename = "bg.jpg",
            status = 1,
            setDirectory = "set-directory",
            setId = 42,
            title = "Title",
            titleUnicode = "TitleU",
            artist = "Artist",
            artistUnicode = "ArtistU",
            creator = "Mapper",
            version = "Difficulty",
            tags = "tags",
            source = "source",
            dateImported = 100L,
            approachRate = 9f,
            overallDifficulty = 8f,
            circleSize = 4f,
            hpDrainRate = 6f,
            droidStarRating = 5f,
            standardStarRating = 6f,
            bpmMax = 180f,
            bpmMin = 120f,
            mostCommonBPM = 150f,
            length = 60_000L,
            previewTime = 1_000,
            hitCircleCount = 10,
            sliderCount = 5,
            spinnerCount = 1,
            maxCombo = 30,
            epilepsyWarning = false
        )

        val mods = ModHashMap().apply {
            put(ModHidden())
            put(ModCustomSpeed(1.25f))
            put(ModReplayV6())
        }

        val map = CourseMap.fromBeatmap(beatmap, mods)

        assertEquals(CourseTestFixtures.HASH_A, map.mapHash)
        assertEquals(listOf(10, 5, 1), map.objectCounts)
        assertEquals("Artist", map.artist)
        assertEquals("ArtistU", map.artistUnicode)
        assertEquals("Title", map.title)
        assertEquals("TitleU", map.titleUnicode)
        assertEquals("Mapper", map.mapper)
        assertEquals("Difficulty", map.difficulty)
        assertEquals(123L, map.beatmapid)

        assertEquals(listOf("CS", "HD"), map.mods.map { it.acronym })
        assertEquals(
            1.25,
            map.mods.first { it.acronym == "CS" }.settings!!["rateMultiplier"]!!.jsonPrimitive.double,
            0.0
        )
        assertNull(map.mods.first { it.acronym == "HD" }.settings)
    }

    @Test
    fun `fromBeatmap converts non-positive online ids to null`() {
        val beatmap = BeatmapInfo(
            filename = "map.osu",
            md5 = CourseTestFixtures.HASH_A,
            id = -1L,
            audioFilename = "audio.mp3",
            backgroundFilename = null,
            status = null,
            setDirectory = "set",
            setId = null,
            title = "Title",
            titleUnicode = "",
            artist = "Artist",
            artistUnicode = "",
            creator = "Mapper",
            version = "Difficulty",
            tags = "",
            source = "",
            dateImported = 0L,
            approachRate = 9f,
            overallDifficulty = 8f,
            circleSize = 4f,
            hpDrainRate = 6f,
            droidStarRating = null,
            standardStarRating = null,
            bpmMax = 60f,
            bpmMin = 60f,
            mostCommonBPM = 60f,
            length = 1000L,
            previewTime = 0,
            hitCircleCount = 1,
            sliderCount = 0,
            spinnerCount = 0,
            maxCombo = 1,
            epilepsyWarning = false
        )

        assertNull(CourseMap.fromBeatmap(beatmap, ModHashMap()).beatmapid)
    }
}

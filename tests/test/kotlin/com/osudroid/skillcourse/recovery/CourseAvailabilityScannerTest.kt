package com.osudroid.skillcourse.recovery

import com.osudroid.data.BeatmapInfo
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseMap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.nsu.ccfit.zuev.osu.Config

class CourseAvailabilityScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var previousBeatmapPath: String? = null

    @Before
    fun setUp() {
        previousBeatmapPath = Config.getBeatmapPath()
        Config.setBeatmapPath(temp.root.absolutePath + File.separator)
    }

    @After
    fun tearDown() {
        previousBeatmapPath?.let { Config.setBeatmapPath(it) }
    }

    @Test
    fun `Exact installed map with matching counts is ready`() {
        val file = osuFile("set-a", "map.osu")
        val row = row("map.osu", "set-a", HASH_A, 10, 5, 2)
        val snapshot = scan(
            map = map(HASH_A, 10, 5, 2),
            rows = listOf(row),
            identities = mapOf(file to identity(HASH_A, 10, 5, 2)),
        )

        val entry = snapshot.entries.single()
        assertEquals(CourseMapStatus.READY, entry.status)
        assertEquals(row, entry.resolvedBeatmap)
        assertTrue(snapshot.allReady)
        assertTrue(snapshot.issues.isEmpty())
    }

    @Test
    fun `Exact hash with mismatched object counts is invalid`() {
        val file = osuFile("set-a", "map.osu")
        val snapshot = scan(
            map = map(HASH_A, 10, 5, 2),
            rows = listOf(row("map.osu", "set-a", HASH_A, 9, 5, 2)),
            identities = mapOf(file to identity(HASH_A, 9, 5, 2)),
        )

        assertEquals(CourseMapStatus.INVALID, snapshot.entries.single().status)
        assertNull(snapshot.entries.single().resolvedBeatmap)
        assertTrue(snapshot.issues.single().reason!!.contains("object counts"))
    }

    @Test
    fun `Unreadable exact candidate is invalid`() {
        osuFile("set-a", "map.osu")
        val snapshot = scan(
            map = map(HASH_A, 10, 5, 2),
            rows = listOf(row("map.osu", "set-a", HASH_A, 10, 5, 2)),
            identities = emptyMap(),
        )

        assertEquals(CourseMapStatus.INVALID, snapshot.entries.single().status)
    }

    @Test
    fun `Stale database hash is ignored when the file changed and metadata matches`() {
        val file = osuFile("set-a", "map.osu")
        val snapshot = scan(
            map = map(HASH_A, 10, 5, 2),
            rows = listOf(row("map.osu", "set-a", HASH_A, 10, 5, 2)),
            identities = mapOf(file to identity(HASH_B, 10, 5, 2)),
        )

        val entry = snapshot.entries.single()
        assertEquals(CourseMapStatus.OUTDATED, entry.status)
        assertEquals(HASH_B, entry.resolvedBeatmap?.md5)
    }

    @Test
    fun `Stale database metadata is bypassed when the actual file matches the authored tuple`() {
        val file = osuFile("set-a", "map.osu")
        val snapshot = scan(
            map = map(HASH_A, 10, 5, 2),
            rows = listOf(
                row("map.osu", "set-a", HASH_B, 10, 5, 2, title = "Stale Stored Title")
            ),
            identities = mapOf(file to identity(HASH_C, 10, 5, 2)),
        )

        val entry = snapshot.entries.single()
        assertEquals(CourseMapStatus.OUTDATED, entry.status)
        assertEquals(HASH_C, entry.resolvedBeatmap?.md5)
    }

    @Test
    fun `Stale database metadata is bypassed when the actual file is the exact revision`() {
        val file = osuFile("set-a", "map.osu")
        val snapshot = scan(
            map = map(HASH_A, 10, 5, 2),
            rows = listOf(
                row("map.osu", "set-a", HASH_B, 10, 5, 2, title = "Stale Stored Title")
            ),
            identities = mapOf(file to identity(HASH_A, 10, 5, 2)),
        )

        assertEquals(CourseMapStatus.READY, snapshot.entries.single().status)
        assertTrue(snapshot.allReady)
    }

    @Test
    fun `Stale database row whose file is missing does not become ready`() {
        val snapshot = scan(
            map = map(HASH_A, 10, 5, 2),
            rows = listOf(row("map.osu", "set-a", HASH_A, 10, 5, 2)),
            identities = emptyMap(),
        )

        assertEquals(CourseMapStatus.MISSING, snapshot.entries.single().status)
    }

    @Test
    fun `Missing map has no candidates`() {
        val file = osuFile("set-a", "map.osu")
        val snapshot = scan(
            map = map(HASH_A, 10, 5, 2),
            rows = listOf(
                row("map.osu", "set-a", HASH_B, 1, 1, 1, title = "Another Song")
            ),
            identities = mapOf(file to identity(HASH_B, 1, 1, 1, title = "Another Song")),
        )

        val entry = snapshot.entries.single()
        assertEquals(CourseMapStatus.MISSING, entry.status)
        assertTrue(entry.candidates.isEmpty())
        assertFalse(snapshot.allReady)
    }

    @Test
    fun `Multiple outdated metadata candidates are exposed without an arbitrary pick`() {
        val first = osuFile("set-a", "map.osu")
        val second = osuFile("set-b", "map.osu")
        val snapshot = scan(
            map = map(HASH_A, 10, 5, 2),
            rows = listOf(
                row("map.osu", "set-a", HASH_B, 10, 5, 2),
                row("map.osu", "set-b", HASH_C, 10, 5, 2),
            ),
            identities = mapOf(
                first to identity(HASH_B, 10, 5, 2),
                second to identity(HASH_C, 10, 5, 2),
            ),
        )

        val entry = snapshot.entries.single()
        assertEquals(CourseMapStatus.OUTDATED, entry.status)
        assertEquals(2, entry.candidates.size)
        assertNull(entry.resolvedBeatmap)
        assertTrue(entry.reason!!.contains("2 installed maps"))
    }

    @Test
    fun `Metadata normalization is NFC trim and locale independent case folding`() {
        val file = osuFile("set-a", "map.osu")
        val authored = map(
            hash = HASH_A,
            circle = 10,
            slider = 5,
            spinner = 2,
            artist = "  CAFÉ  ",
            title = "SONG TITLE",
            mapper = "MAPPER",
            difficulty = "DIFFICULTY",
        )
        val installed = identity(
            md5 = HASH_B,
            circle = 10,
            slider = 5,
            spinner = 2,
            artist = "Cafe\u0301",
            title = "song title",
            creator = "mapper",
            version = "difficulty",
        )

        val snapshot = scan(
            map = authored,
            rows = listOf(
                row(
                    "map.osu",
                    "set-a",
                    HASH_B,
                    10,
                    5,
                    2,
                    artist = "Cafe\u0301",
                    title = "song title",
                    creator = "mapper",
                    version = "difficulty",
                )
            ),
            identities = mapOf(file to installed),
        )

        assertEquals(CourseMapStatus.OUTDATED, snapshot.entries.single().status)
    }

    @Test
    fun `Unicode display preference does not change identity`() {
        val file = osuFile("set-a", "map.osu")
        val row = row(
            "map.osu",
            "set-a",
            HASH_B,
            10,
            5,
            2,
            artist = "Romanized Artist",
            title = "Romanized Title",
        ).apply { titleUnicode = "Localized Title"; artistUnicode = "Localized Artist" }

        val snapshot = scan(
            map = map(HASH_A, 10, 5, 2, artist = "Romanized Artist", title = "Romanized Title"),
            rows = listOf(row),
            identities = mapOf(
                file to identity(
                    HASH_B,
                    10,
                    5,
                    2,
                    artist = "Romanized Artist",
                    title = "Romanized Title",
                )
            ),
        )

        assertEquals(CourseMapStatus.OUTDATED, snapshot.entries.single().status)
    }

    @Test
    fun `Multiple exact copies are all retained and resolved deterministically`() {
        val first = osuFile("set-b", "z.osu")
        val second = osuFile("set-a", "a.osu")
        val rows = listOf(
            row("z.osu", "set-b", HASH_A, 10, 5, 2),
            row("a.osu", "set-a", HASH_A, 10, 5, 2),
        )
        val snapshot = scan(
            map = map(HASH_A, 10, 5, 2),
            rows = rows,
            identities = mapOf(
                first to identity(HASH_A, 10, 5, 2),
                second to identity(HASH_A, 10, 5, 2),
            ),
        )

        val entry = snapshot.entries.single()
        assertEquals(CourseMapStatus.READY, entry.status)
        assertEquals(2, entry.candidates.size)
        assertEquals("set-a", entry.resolvedBeatmap!!.setDirectory)
    }

    @Test
    fun `Empty course is not considered playable`() {
        val snapshot = CourseAvailabilityScanner.scan(
            CourseDefinition(courseName = "Empty", author = "Tester", maps = emptyList()),
            emptyList(),
            BeatmapFileIdentityReader { null },
            1L,
        )

        assertTrue(snapshot.entries.isEmpty())
        assertFalse(snapshot.allReady)
    }

    private fun scan(
        map: CourseMap,
        rows: List<BeatmapInfo>,
        identities: Map<File, BeatmapFileIdentity?>,
    ): CourseAvailabilitySnapshot {
        val reader = BeatmapFileIdentityReader { file ->
            val path = file.absolutePath.replace('\\', '/')
            identities.entries.firstOrNull {
                it.key.absolutePath.replace('\\', '/') == path
            }?.value
        }
        val definition = CourseDefinition(courseName = "Test", author = "Tester", maps = listOf(map))
        return CourseAvailabilityScanner.scan(definition, rows, reader, 7L)
    }

    private fun osuFile(setDirectory: String, filename: String): File {
        val directory = File(temp.root, setDirectory)
        directory.mkdirs()
        return File(directory, filename).apply { writeText("") }
    }

    private fun identity(
        md5: String,
        circle: Int,
        slider: Int,
        spinner: Int,
        artist: String = "Artist",
        title: String = "Title",
        creator: String = "Mapper",
        version: String = "Difficulty",
    ) = BeatmapFileIdentity(
        md5 = md5,
        circleCount = circle,
        sliderCount = slider,
        spinnerCount = spinner,
        artist = artist,
        title = title,
        creator = creator,
        version = version,
    )

    private fun map(
        hash: String,
        circle: Int,
        slider: Int,
        spinner: Int,
        artist: String = "Artist",
        title: String = "Title",
        mapper: String = "Mapper",
        difficulty: String = "Difficulty",
        beatmapId: Long? = 42L,
    ) = CourseMap(
        mapHash = hash,
        objectCounts = listOf(circle, slider, spinner),
        artist = artist,
        artistUnicode = artist,
        title = title,
        titleUnicode = title,
        mapper = mapper,
        difficulty = difficulty,
        beatmapid = beatmapId,
        mods = emptyList(),
    )

    private fun row(
        filename: String,
        setDirectory: String,
        md5: String,
        circle: Int,
        slider: Int,
        spinner: Int,
        artist: String = "Artist",
        title: String = "Title",
        creator: String = "Mapper",
        version: String = "Difficulty",
    ) = BeatmapInfo(
        filename = filename,
        md5 = md5,
        id = 42L,
        audioFilename = "audio.mp3",
        backgroundFilename = null,
        status = null,
        setDirectory = setDirectory,
        setId = 1,
        title = title,
        titleUnicode = title,
        artist = artist,
        artistUnicode = artist,
        creator = creator,
        version = version,
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
        hitCircleCount = circle,
        spinnerCount = spinner,
        sliderCount = slider,
        maxCombo = 0,
        epilepsyWarning = false,
    )

    private companion object {
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val HASH_C = "cccccccccccccccccccccccccccccccc"
    }
}

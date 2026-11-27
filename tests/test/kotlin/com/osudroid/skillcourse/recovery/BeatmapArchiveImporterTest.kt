package com.osudroid.skillcourse.recovery

import android.app.Application
import androidx.room.Room
import com.osudroid.beatmaplisting.BeatmapArchiveImporter
import com.osudroid.beatmaplisting.BeatmapArchiveImporterHooks
import com.osudroid.beatmaplisting.BeatmapImportFailure
import com.osudroid.beatmaplisting.BeatmapImportResult
import com.osudroid.beatmaplisting.ImportCommitPoint
import com.osudroid.beatmaplisting.SimulatedProcessDeath
import com.osudroid.beatmaps.parser.BeatmapParser
import com.osudroid.data.BeatmapInfo
import com.osudroid.data.BeatmapOptions
import com.osudroid.data.DroidDatabase
import com.osudroid.skillcourse.CourseMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ru.nsu.ccfit.zuev.osu.Config

@RunWith(RobolectricTestRunner::class)
class BeatmapArchiveImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: DroidDatabase
    private lateinit var beatmapPath: File
    private var previousBeatmapPath: String? = null
    private var previousCorePath: String? = null
    private var previousDeleteOsz: Boolean = false

    private val reloadedDirectories = ArrayList<Collection<String>>()

    @Before
    fun setUp() {
        previousBeatmapPath = Config.getBeatmapPath()
        previousCorePath = Config.getCorePath()
        previousDeleteOsz = Config.isDELETE_OSZ()
        Config.setDELETE_OSZ(false)

        beatmapPath = File(temp.root, "Songs").apply { mkdirs() }
        Config.setBeatmapPath(beatmapPath.absolutePath + File.separator)
        Config.setCorePath(temp.root.absolutePath + File.separator)

        val application: Application = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(
            application,
            DroidDatabase::class.java,
        ).allowMainThreadQueries().build()

        BeatmapArchiveImporterHooks.daoProvider = { database.getBeatmapInfoTable() }
        BeatmapArchiveImporterHooks.libraryReload = { directories ->
            reloadedDirectories += directories.toList()
        }
        BeatmapArchiveImporterHooks.cacheInvalidator = {}
        BeatmapArchiveImporterHooks.fault = {}
    }

    @After
    fun tearDown() {
        BeatmapArchiveImporterHooks.resetForTesting()
        database.close()
        Config.setDELETE_OSZ(previousDeleteOsz)
        previousBeatmapPath?.let { Config.setBeatmapPath(it) }
        previousCorePath?.let { Config.setCorePath(it) }
    }

    @Test
    fun `Same filename and key is overwritten with fresh hash and counts`() {
        val oldBytes = osuBytes(title = "Song", circleCount = 1)
        val newBytes = osuBytes(title = "Song", circleCount = 3, sliderCount = 1)
        val oldMd5 = md5(oldBytes)
        val newMd5 = md5(newBytes)

        installSet(
            directory = "Set A",
            filename = "map.osu",
            bytes = oldBytes,
            circleCount = 1,
            sliderCount = 0,
            spinnerCount = 1,
        )

        val result = BeatmapArchiveImporter.importArchive(
            archive(
                mapOf(
                    "Set A/map.osu" to newBytes,
                    "Set A/audio.mp3" to "audio".toByteArray(),
                )
            ),
            listOf(expectedMap(newMd5, circle = 3, slider = 1, spinner = 1)),
        )

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertEquals(BeatmapImportFailure.NONE, result.failure)
        assertEquals(newMd5, md5(File(beatmapPath, "Set A/map.osu").readBytes()))
        assertEquals(listOf("Set A"), reloadedDirectories.single())

        val rows = database.getBeatmapInfoTable().getBeatmapsInSet("Set A")
        assertEquals(1, rows.size)
        assertEquals(newMd5, rows.single().md5)
        assertEquals(3, rows.single().hitCircleCount)
        assertFalse(rows.any { it.md5 == oldMd5 })
    }

    @Test
    fun `Renamed difficulty removes only the superseded primary key`() {
        val oldBytes = osuBytes(title = "Song", circleCount = 1)
        val newBytes = osuBytes(title = "Song", circleCount = 2)
        val siblingBytes = osuBytes(title = "Song", version = "Sibling", circleCount = 5)
        val newMd5 = md5(newBytes)

        installSet("Set A", "old.osu", oldBytes, 1, 0, 1)
        installSet("Set A", "sibling.osu", siblingBytes, 5, 0, 1)

        val result = BeatmapArchiveImporter.importArchive(
            archive(mapOf("Set A/new.osu" to newBytes)),
            listOf(expectedMap(newMd5, circle = 2, slider = 0, spinner = 1)),
        )

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertFalse(File(beatmapPath, "Set A/old.osu").exists())
        assertTrue(File(beatmapPath, "Set A/new.osu").isFile)
        assertTrue(File(beatmapPath, "Set A/sibling.osu").isFile)

        val keys = database.getBeatmapInfoTable().getBeatmapsInSet("Set A")
            .map { it.filename }.toSet()
        assertEquals(setOf("new.osu", "sibling.osu"), keys)
    }

    @Test
    fun `Sibling options collections and history are preserved`() {
        val oldBytes = osuBytes(title = "Song", circleCount = 1)
        val siblingBytes = osuBytes(title = "Song", version = "Sibling", circleCount = 5)
        val newBytes = osuBytes(title = "Song", circleCount = 2)
        val newMd5 = md5(newBytes)

        installSet("Set A", "old.osu", oldBytes, 1, 0, 1)
        installSet("Set A", "sibling.osu", siblingBytes, 5, 0, 1)
        database.getBeatmapOptionsTable().insert(BeatmapOptions("Set A", true, 12))
        database.getBeatmapCollectionsTable().insertCollection("Favourites")
        database.getBeatmapCollectionsTable().addBeatmap("Favourites", "Set A")

        val result = BeatmapArchiveImporter.importArchive(
            archive(mapOf("Set A/new.osu" to newBytes)),
            listOf(expectedMap(newMd5, circle = 2, slider = 0, spinner = 1)),
        )

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertEquals("Set A", database.getBeatmapInfoTable().getBeatmapsInSet("Set A").first().setDirectory)
        assertEquals(true, database.getBeatmapOptionsTable().getOptions("Set A")!!.isFavorite)
        assertEquals(12, database.getBeatmapOptionsTable().getOptions("Set A")!!.offset)
        assertTrue(database.getBeatmapCollectionsTable().inCollection("Favourites", "Set A"))
        assertTrue(database.getBeatmapInfoTable().getBeatmapsInSet("Set A")
            .any { it.filename == "sibling.osu" })
    }

    @Test
    fun `Wrong revision leaves the installed candidate untouched`() {
        Config.setDELETE_OSZ(true)
        val oldBytes = osuBytes(title = "Song", circleCount = 1)
        val wrongBytes = osuBytes(title = "Song", circleCount = 7)
        val requiredMd5 = md5(osuBytes(title = "Song", circleCount = 9))

        installSet("Set A", "map.osu", oldBytes, 1, 0, 1)
        val oldFile = File(beatmapPath, "Set A/map.osu")
        val before = oldFile.readBytes()
        val source = archive(mapOf("Set A/map.osu" to wrongBytes))

        val result = BeatmapArchiveImporter.importArchive(
            source,
            listOf(expectedMap(requiredMd5, circle = 9, slider = 0, spinner = 1)),
        )

        assertFalse(result.success)
        assertEquals(BeatmapImportFailure.EXPECTED_REVISION_UNAVAILABLE, result.failure)
        assertTrue(source.isFile)
        assertTrue(oldFile.readBytes().contentEquals(before))
        assertEquals(md5(oldBytes), database.getBeatmapInfoTable().getBeatmapsInSet("Set A").single().md5)
        assertTrue(reloadedDirectories.isEmpty())
    }

    @Test
    fun `Invalid archive is rejected without touching an existing install`() {
        Config.setDELETE_OSZ(true)
        val oldBytes = osuBytes(title = "Song", circleCount = 1)
        installSet("Set A", "map.osu", oldBytes, 1, 0, 1)

        val badArchive = File(temp.root, "bad.osz").apply { writeText("not a zip") }
        val before = File(beatmapPath, "Set A/map.osu").readBytes()

        val result = BeatmapArchiveImporter.importArchive(
            badArchive,
            listOf(expectedMap(md5(osuBytes(title = "Song", circleCount = 9)), 9)),
        )

        assertFalse(result.success)
        assertEquals(BeatmapImportFailure.INVALID_ARCHIVE, result.failure)
        assertTrue(badArchive.isFile)
        assertTrue(File(beatmapPath, "Set A/map.osu").readBytes().contentEquals(before))
    }

    @Test
    fun `Path traversal entry is rejected before extraction`() {
        val result = BeatmapArchiveImporter.importArchive(
            archive(mapOf("../evil.osu" to osuBytes(title = "Evil", circleCount = 1))),
        )

        assertFalse(result.success)
        assertEquals(BeatmapImportFailure.INVALID_ARCHIVE, result.failure)
        assertFalse(File(temp.root, "evil.osu").exists())
        assertFalse(File(beatmapPath.parentFile, "evil.osu").exists())
    }

    @Test
    fun `File failure before mutation commit leaves the installed revision intact`() {
        val oldBytes = osuBytes(title = "Song", circleCount = 1)
        val newBytes = osuBytes(title = "Song", circleCount = 2)
        installSet("Set A", "map.osu", oldBytes, 1, 0, 1)
        val oldFile = File(beatmapPath, "Set A/map.osu")
        val before = oldFile.readBytes()

        BeatmapArchiveImporterHooks.fault = { point ->
            if (point == ImportCommitPoint.AFTER_BACKUP) {
                throw IOException("simulated storage failure")
            }
        }

        val result = BeatmapArchiveImporter.importArchive(
            archive(mapOf("Set A/map.osu" to newBytes)),
            listOf(expectedMap(md5(newBytes), circle = 2, slider = 0, spinner = 1)),
        )

        assertFalse(result.success)
        assertTrue(oldFile.readBytes().contentEquals(before))
        assertEquals(md5(oldBytes), database.getBeatmapInfoTable().getBeatmapsInSet("Set A").single().md5)
        assertTrue(reloadedDirectories.isEmpty())
    }

    @Test
    fun `Database failure rolls files back and does not publish success`() {
        Config.setDELETE_OSZ(true)
        val oldBytes = osuBytes(title = "Song", circleCount = 1)
        val newBytes = osuBytes(title = "Song", circleCount = 2)
        installSet("Set A", "map.osu", oldBytes, 1, 0, 1)
        val oldFile = File(beatmapPath, "Set A/map.osu")
        val before = oldFile.readBytes()
        val source = archive(mapOf("Set A/map.osu" to newBytes))

        BeatmapArchiveImporterHooks.fault = { point ->
            if (point == ImportCommitPoint.BEFORE_DATABASE) {
                throw IOException("simulated database failure")
            }
        }

        val result = BeatmapArchiveImporter.importArchive(
            source,
            listOf(expectedMap(md5(newBytes), circle = 2, slider = 0, spinner = 1)),
        )

        assertFalse(result.success)
        assertTrue(source.isFile)
        assertTrue(oldFile.readBytes().contentEquals(before))
        assertEquals(md5(oldBytes), database.getBeatmapInfoTable().getBeatmapsInSet("Set A").single().md5)
        assertTrue(reloadedDirectories.isEmpty())
        assertTrue(BeatmapArchiveImporter.recoverPendingImports().isEmpty())
    }

    @Test
    fun `Journal is recovered after process death between files and database`() {
        val oldBytes = osuBytes(title = "Song", circleCount = 1)
        val newBytes = osuBytes(title = "Song", circleCount = 2)
        installSet("Set A", "map.osu", oldBytes, 1, 0, 1)

        BeatmapArchiveImporterHooks.fault = { point ->
            if (point == ImportCommitPoint.AFTER_FILES_APPLIED) {
                throw SimulatedProcessDeath()
            }
        }

        try {
            BeatmapArchiveImporter.importArchive(
                archive(mapOf("Set A/map.osu" to newBytes)),
                listOf(expectedMap(md5(newBytes), circle = 2, slider = 0, spinner = 1)),
            )
            throw AssertionError("Expected simulated process death")
        } catch (_: SimulatedProcessDeath) {
        }

        BeatmapArchiveImporterHooks.fault = {}
        val errors = BeatmapArchiveImporter.recoverPendingImports()

        assertTrue(errors.joinToString("\n"), errors.isEmpty())
        val row = database.getBeatmapInfoTable().getBeatmapsInSet("Set A").single()
        assertEquals(md5(newBytes), row.md5)
        assertEquals(2, row.hitCircleCount)
        assertEquals(1, reloadedDirectories.size)
    }

    @Test
    fun `Differing shared asset installs the revision in a distinct managed directory`() {
        val oldBytes = osuBytes(title = "Song", circleCount = 1)
        val siblingBytes = osuBytes(title = "Song", version = "Sibling", circleCount = 5)
        val newBytes = osuBytes(title = "Song", circleCount = 2)
        val newMd5 = md5(newBytes)

        installSet("Set A", "map.osu", oldBytes, 1, 0, 1)
        installSet("Set A", "sibling.osu", siblingBytes, 5, 0, 1)
        File(beatmapPath, "Set A/audio.mp3").writeText("old audio")

        val result = BeatmapArchiveImporter.importArchive(
            archive(
                mapOf(
                    "Set A/map.osu" to newBytes,
                    "Set A/audio.mp3" to "new audio".toByteArray(),
                )
            ),
            listOf(expectedMap(newMd5, circle = 2, slider = 0, spinner = 1)),
        )

        assertTrue(result.errors.joinToString("\n"), result.success)
        // Existing sibling and its asset are untouched.
        assertEquals("old audio", File(beatmapPath, "Set A/audio.mp3").readText())
        assertTrue(File(beatmapPath, "Set A/sibling.osu").isFile)
        assertEquals(md5(oldBytes), database.getBeatmapInfoTable()
            .getBeatmapsInSet("Set A").first { it.filename == "map.osu" }.md5)

        val installedDirectory = result.expectedMaps.single().installedDirectory!!
        assertTrue(installedDirectory.startsWith("Set A (imported"))
        assertEquals(newMd5, md5(File(beatmapPath, "$installedDirectory/map.osu").readBytes()))
        assertEquals("new audio", File(beatmapPath, "$installedDirectory/audio.mp3").readText())
    }

    @Test
    fun `Exact installed map with matching disk identity is not rewritten`() {
        val expectedBytes = osuBytes(title = "Song", circleCount = 2)
        val expectedMd5 = md5(expectedBytes)

        installSet("Set A", "map.osu", expectedBytes, 2, 0, 1)

        val result = BeatmapArchiveImporter.importArchive(
            archive(mapOf("Set A/map.osu" to expectedBytes)),
            listOf(expectedMap(expectedMd5, circle = 2, spinner = 1)),
        )

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertTrue(result.importedBeatmaps.isEmpty())
        assertEquals("Set A", result.expectedMaps.single().installedDirectory)
        assertEquals(expectedMd5, database.getBeatmapInfoTable().getBeatmapsInSet("Set A").single().md5)
    }

    @Test
    fun `Replaced installed file is rewritten even when the stored row still matches`() {
        val expectedBytes = osuBytes(title = "Song", circleCount = 2)
        val expectedMd5 = md5(expectedBytes)

        installSet("Set A", "map.osu", expectedBytes, 2, 0, 1)
        // The file changed outside the app; the database row is now stale.
        File(beatmapPath, "Set A/map.osu").writeBytes(osuBytes(title = "Song", circleCount = 1))

        val result = BeatmapArchiveImporter.importArchive(
            archive(mapOf("Set A/map.osu" to expectedBytes)),
            listOf(expectedMap(expectedMd5, circle = 2, spinner = 1)),
        )

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertEquals(expectedMd5, md5(File(beatmapPath, "Set A/map.osu").readBytes()))
        assertEquals(expectedMd5, database.getBeatmapInfoTable().getBeatmapsInSet("Set A").single().md5)
    }

    @Test
    fun `Truncated installed file is rewritten even when the stored row still matches`() {
        val expectedBytes = osuBytes(title = "Song", circleCount = 2)
        val expectedMd5 = md5(expectedBytes)

        installSet("Set A", "map.osu", expectedBytes, 2, 0, 1)
        File(beatmapPath, "Set A/map.osu").writeBytes(expectedBytes.copyOf(expectedBytes.size / 3))

        val result = BeatmapArchiveImporter.importArchive(
            archive(mapOf("Set A/map.osu" to expectedBytes)),
            listOf(expectedMap(expectedMd5, circle = 2, spinner = 1)),
        )

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertEquals(expectedMd5, md5(File(beatmapPath, "Set A/map.osu").readBytes()))
    }

    @Test
    fun `Stale stored hash is not treated as an installed exact revision`() {
        val expectedBytes = osuBytes(title = "Song", circleCount = 2)
        val expectedMd5 = md5(expectedBytes)

        installSet("Set A", "map.osu", expectedBytes, 2, 0, 1)
        File(beatmapPath, "Set A/map.osu").writeBytes(osuBytes(title = "Song", circleCount = 3))

        val result = BeatmapArchiveImporter.importArchive(
            archive(mapOf("Other/other.osu" to osuBytes(title = "Other", circleCount = 1))),
            listOf(expectedMap(expectedMd5, circle = 2, spinner = 1)),
        )

        assertFalse(result.success)
        assertEquals(BeatmapImportFailure.EXPECTED_REVISION_UNAVAILABLE, result.failure)
    }

    @Test
    fun `Archive without the requested revision is a no-op success when it is already installed`() {
        val expectedBytes = osuBytes(title = "Song", circleCount = 2)
        val expectedMd5 = md5(expectedBytes)

        installSet("Set A", "map.osu", expectedBytes, 2, 0, 1)

        val result = BeatmapArchiveImporter.importArchive(
            // A wrong revision of the same set: the authored revision is already installed.
            archive(mapOf("Set A/map.osu" to osuBytes(title = "Song", circleCount = 3))),
            listOf(expectedMap(expectedMd5, circle = 2, spinner = 1)),
        )

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertEquals(BeatmapImportFailure.NONE, result.failure)
        assertTrue(result.importedBeatmaps.isEmpty())
        assertEquals("Set A", result.expectedMaps.single().installedDirectory)
        assertTrue(reloadedDirectories.isEmpty())
    }

    @Test
    fun `Exact installed map restores a missing archive asset without rewriting the map`() {
        val expectedBytes = osuBytes(title = "Song", circleCount = 2)
        val expectedMd5 = md5(expectedBytes)

        installSet("Set A", "map.osu", expectedBytes, 2, 0, 1)
        assertFalse(File(beatmapPath, "Set A/audio.mp3").exists())

        val result = BeatmapArchiveImporter.importArchive(
            archive(
                mapOf(
                    "Set A/map.osu" to expectedBytes,
                    "Set A/audio.mp3" to "audio".toByteArray(),
                )
            ),
            listOf(expectedMap(expectedMd5, circle = 2, spinner = 1)),
        )

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertEquals("audio", File(beatmapPath, "Set A/audio.mp3").readText())
        assertEquals(expectedMd5, md5(File(beatmapPath, "Set A/map.osu").readBytes()))
        assertTrue(result.importedBeatmaps.isEmpty())
    }

    @Test
    fun `Exact installed map replaces a differing asset when the directory has no sibling`() {
        val expectedBytes = osuBytes(title = "Song", circleCount = 2)
        val expectedMd5 = md5(expectedBytes)

        installSet("Set A", "map.osu", expectedBytes, 2, 0, 1)
        File(beatmapPath, "Set A/audio.mp3").writeText("old audio")

        val result = BeatmapArchiveImporter.importArchive(
            archive(
                mapOf(
                    "Set A/map.osu" to expectedBytes,
                    "Set A/audio.mp3" to "new audio".toByteArray(),
                )
            ),
            listOf(expectedMap(expectedMd5, circle = 2, spinner = 1)),
        )

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertEquals("new audio", File(beatmapPath, "Set A/audio.mp3").readText())
        assertTrue(result.importedBeatmaps.isEmpty())
    }

    @Test
    fun `Exact installed map does not overwrite an asset shared with an unrelated sibling`() {
        val expectedBytes = osuBytes(title = "Song", circleCount = 2)
        val siblingBytes = osuBytes(title = "Song", version = "Sibling", circleCount = 5)
        val expectedMd5 = md5(expectedBytes)

        installSet("Set A", "map.osu", expectedBytes, 2, 0, 1)
        installSet("Set A", "sibling.osu", siblingBytes, 5, 0, 1)
        File(beatmapPath, "Set A/audio.mp3").writeText("old audio")

        val result = BeatmapArchiveImporter.importArchive(
            archive(
                mapOf(
                    "Set A/map.osu" to expectedBytes,
                    "Set A/audio.mp3" to "new audio".toByteArray(),
                )
            ),
            listOf(expectedMap(expectedMd5, circle = 2, spinner = 1)),
        )

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertEquals("old audio", File(beatmapPath, "Set A/audio.mp3").readText())
        assertTrue(File(beatmapPath, "Set A/sibling.osu").isFile)
        assertEquals(expectedMd5, md5(File(beatmapPath, "Set A/map.osu").readBytes()))
    }

    @Test
    fun `Asset reconciliation cannot escape the beatmap path`() {
        val expectedBytes = osuBytes(title = "Song", circleCount = 2)
        val expectedMd5 = md5(expectedBytes)

        installSet("Set A", "map.osu", expectedBytes, 2, 0, 1)

        val result = BeatmapArchiveImporter.importArchive(
            archive(
                mapOf(
                    "Set A/map.osu" to expectedBytes,
                    "Set A/../../escape.txt" to "escape".toByteArray(),
                )
            ),
            listOf(expectedMap(expectedMd5, circle = 2, spinner = 1)),
        )

        assertFalse(result.success)
        assertEquals(BeatmapImportFailure.INVALID_ARCHIVE, result.failure)
        assertFalse(File(temp.root, "escape.txt").exists())
    }

    @Test
    fun `Successful ordinary import deletes the source archive when configured`() {
        Config.setDELETE_OSZ(true)
        val bytes = osuBytes(title = "Song", circleCount = 2)
        val source = archive(mapOf("Set A/map.osu" to bytes))

        val result = BeatmapArchiveImporter.importArchive(source)

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertEquals(BeatmapImportFailure.NONE, result.failure)
        assertFalse(source.exists())
        assertTrue(File(beatmapPath, "Set A/map.osu").isFile)
        assertEquals(
            listOf("map.osu"),
            database.getBeatmapInfoTable().getBeatmapsInSet("Set A").map { it.filename },
        )
    }

    @Test
    fun `Successful ordinary import retains the source archive when deletion is disabled`() {
        Config.setDELETE_OSZ(false)
        val source = archive(mapOf("Set A/map.osu" to osuBytes(title = "Song", circleCount = 2)))

        val result = BeatmapArchiveImporter.importArchive(source)

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertTrue(source.isFile)
        assertTrue(File(beatmapPath, "Set A/map.osu").isFile)
    }

    @Test
    fun `Mixed mode archive imports only standard difficulties`() {
        Config.setDELETE_OSZ(true)
        val standardBytes = osuBytes(title = "Song", circleCount = 2)
        val source = archive(
            mapOf(
                "Set A/standard.osu" to standardBytes,
                "Set A/taiko.osu" to osuBytes(title = "Song", version = "Taiko", circleCount = 1, mode = 1),
                "Set A/catch.osu" to osuBytes(title = "Song", version = "Catch", circleCount = 1, mode = 2),
                "Set A/mania.osu" to osuBytes(title = "Song", version = "Mania", circleCount = 1, mode = 3),
            )
        )

        val result = BeatmapArchiveImporter.importArchive(source)

        assertTrue(result.errors.joinToString("\n"), result.success)
        assertEquals(BeatmapImportFailure.NONE, result.failure)
        assertTrue(result.errors.isEmpty())
        assertFalse(source.exists())

        val installedDifficulties = File(beatmapPath, "Set A").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".osu", ignoreCase = true) }
            .map { it.name }
            .toSet()
        assertEquals(setOf("standard.osu"), installedDifficulties)
        assertEquals(
            listOf("standard.osu"),
            database.getBeatmapInfoTable().getBeatmapsInSet("Set A").map { it.filename },
        )
        assertEquals(md5(standardBytes), database.getBeatmapInfoTable()
            .getBeatmapsInSet("Set A").single().md5)
    }

    @Test
    fun `Unsupported-only archive fails concisely and retains its source`() {
        Config.setDELETE_OSZ(true)
        val source = archive(
            mapOf(
                "Set A/taiko.osu" to osuBytes(title = "Song", version = "Taiko", circleCount = 1, mode = 1),
                "Set A/mania.osu" to osuBytes(title = "Song", version = "Mania", circleCount = 1, mode = 3),
            )
        )

        val result = BeatmapArchiveImporter.importArchive(source)

        assertFalse(result.success)
        assertEquals(BeatmapImportFailure.INVALID_ARCHIVE, result.failure)
        assertEquals(1, result.errors.size)
        assertTrue(
            result.errors.single(),
            result.errors.single().contains("no supported osu!standard difficulties", ignoreCase = true),
        )
        assertTrue(result.errors.single().length <= 180)
        assertTrue(source.isFile)
        assertTrue(database.getBeatmapInfoTable().getAll().isEmpty())
        assertFalse(File(beatmapPath, "Set A").exists())
    }

    @Test
    fun `Bounded error summary keeps diagnostics intact`() {
        val diagnostics = (1..5).map { "diagnostic $it: " + "x".repeat(200) }
        val base = BeatmapImportResult(
            success = false,
            archiveName = "long.osz",
            affectedDirectories = emptyList(),
            importedBeatmaps = emptyList(),
            identities = emptyList(),
            expectedMaps = emptyList(),
            errors = emptyList(),
            failure = BeatmapImportFailure.INVALID_ARCHIVE,
        )
        val result = base.copy(errors = diagnostics)

        val summary = result.errorSummary

        assertEquals(diagnostics, result.errors)
        assertEquals(180, summary.length)
        assertFalse(summary.contains('\n'))
        assertTrue(summary.startsWith("diagnostic 1:"))
        assertTrue(summary.endsWith("\u2026"))
        assertEquals(
            "first line second line",
            base.copy(errors = listOf("first line\nsecond line")).errorSummary,
        )
        assertEquals("Beatmap import failed.", base.errorSummary)
    }

    @Test
    fun `Malformed difficulty remains an error and preserves the source archive`() {
        Config.setDELETE_OSZ(true)
        val source = archive(
            mapOf(
                "Set A/standard.osu" to osuBytes(title = "Song", circleCount = 2),
                "Set A/broken.osu" to "invalid osu file format".toByteArray(),
            )
        )

        val result = BeatmapArchiveImporter.importArchive(source)

        assertFalse(result.success)
        assertTrue(result.errors.any { it.contains("broken.osu") })
        assertTrue(source.isFile)
    }

    @Test
    fun `Partial course repair retains its archive even after installing available revisions`() {
        Config.setDELETE_OSZ(true)
        val available = osuBytes(title = "Song", circleCount = 2)
        val missing = osuBytes(title = "Song", circleCount = 3)
        val source = archive(mapOf("Set A/map.osu" to available))

        val result = BeatmapArchiveImporter.importArchive(
            source,
            listOf(
                expectedMap(md5(available), circle = 2, slider = 0, spinner = 1),
                expectedMap(md5(missing), circle = 3, slider = 0, spinner = 1),
            ),
        )

        assertFalse(result.success)
        assertEquals(BeatmapImportFailure.EXPECTED_REVISION_UNAVAILABLE, result.failure)
        assertTrue(result.expectedMatched(md5(available)))
        assertFalse(result.expectedMatched(md5(missing)))
        assertTrue(File(beatmapPath, "Set A/map.osu").isFile)
        assertTrue(source.isFile)
    }

    private fun installSet(
        directory: String,
        filename: String,
        bytes: ByteArray,
        circleCount: Int,
        sliderCount: Int,
        spinnerCount: Int,
    ) {
        val setDir = File(beatmapPath, directory).apply { mkdirs() }
        val file = File(setDir, filename)
        file.writeBytes(bytes)

        val data = BeatmapParser(file).parse(true)
        val row = BeatmapInfo(data, setDir.lastModified(), false)
            .copy(filename = filename, setDirectory = directory)

        database.getBeatmapInfoTable().insertAll(listOf(row))
    }

    private fun expectedMap(
        md5: String,
        circle: Int,
        slider: Int = 0,
        spinner: Int = 1,
    ) = CourseMap(
        mapHash = md5,
        objectCounts = listOf(circle, slider, spinner),
        artist = "Test Artist",
        artistUnicode = "Test Artist",
        title = "Song",
        titleUnicode = "Song",
        mapper = "Test Mapper",
        difficulty = "Test Diff",
        beatmapid = 123L,
        mods = emptyList(),
    )

    private fun osuBytes(
        title: String,
        version: String = "Test Diff",
        circleCount: Int,
        sliderCount: Int = 0,
        mode: Int = 0,
    ): ByteArray {
        val hits = buildString {
            var time = 1000
            repeat(circleCount) {
                appendLine("256,192,$time,1,0,0:0:0:0:")
                time += 500
            }
            repeat(sliderCount) {
                appendLine("256,192,$time,2,0,B|300:200|350:200,1,500")
                time += 500
            }
            appendLine("256,192,$time,8,0,${time + 1000},0:0:0:0:")
        }

        return buildString {
            appendLine("osu file format v14")
            appendLine()
            appendLine("[General]")
            appendLine("AudioFilename: audio.mp3")
            appendLine("Mode: $mode")
            appendLine("PreviewTime: 0")
            appendLine()
            appendLine("[Metadata]")
            appendLine("Title:$title")
            appendLine("TitleUnicode:$title")
            appendLine("Artist:Test Artist")
            appendLine("ArtistUnicode:Test Artist")
            appendLine("Creator:Test Mapper")
            appendLine("Version:$version")
            appendLine("BeatmapID:123")
            appendLine("BeatmapSetID:456")
            appendLine()
            appendLine("[Difficulty]")
            appendLine("HPDrainRate:5")
            appendLine("CircleSize:4")
            appendLine("OverallDifficulty:8")
            appendLine("ApproachRate:9")
            appendLine("SliderMultiplier:1.4")
            appendLine("SliderTickRate:1")
            appendLine()
            appendLine("[TimingPoints]")
            appendLine("0,500,4,2,0,100,1,0")
            appendLine()
            appendLine("[HitObjects]")
            append(hits)
        }.toByteArray(Charsets.UTF_8)
    }

    private fun archive(entries: Map<String, ByteArray>): File {
        val file = File(temp.root, "import-${System.nanoTime()}.osz")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

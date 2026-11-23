package com.osudroid.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.osudroid.beatmaps.sections.BeatmapDifficulty
import com.osudroid.mods.*
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.deserializeMods
import java.io.IOException
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val testDb = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DroidDatabase::class.java
    )

    @Test
    @Throws(IOException::class)
    fun `Test all migrations`() {
        helper.createDatabase(testDb, 1).close()

        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            DroidDatabase::class.java,
            testDb
        ).addMigrations(*ALL_MIGRATIONS).build().apply {
            openHelper.writableDatabase.close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun `Test migration from version 1 to 2`() {
        helper.createDatabase(testDb, 1).use {
            // Insert a fake beatmap.
            it.execSQL(
                "INSERT INTO BeatmapInfo (filename, md5, audioFilename, setDirectory, title, titleUnicode, artist, " +
                "artistUnicode, creator, version, tags, source, dateImported, approachRate, overallDifficulty, " +
                "circleSize, hpDrainRate, bpmMax, bpmMin, mostCommonBPM, length, previewTime, hitCircleCount, " +
                "sliderCount, spinnerCount, maxCombo) VALUES ('', 'md5', '', '', '', '', '', '', '', '', '', '', 0, " +
                "9, 8, 4, 6, 60, 0, 60, 0, 0, 0, 0, 0, 0)"
            )

            val scores = mutableListOf<String>()

            fun addScore(mods: String, md5: String? = null) {
                scores.add("('${md5 ?: "md5"}', '', '', '$mods', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)")
            }

            // A normal score with mods.
            addScore("rhd|x1.10")
            // A score with mods that can be migrated, but with a beatmap that isn't in the database.
            addScore("m", "md5_2")
            // A score with mods that can be migrated and with a beatmap that is in the database.
            addScore("m")

            it.execSQL(
                "INSERT INTO ScoreInfo (beatmapMD5, playerName, replayFilename, mods, score, maxCombo, mark, " +
                "hit300k, hit300, hit100k, hit100, hit50, misses, time) VALUES " +
                scores.joinToString(",")
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 2, true, MIGRATION_1_2)

        db.query("SELECT id, mods FROM ScoreInfo").use {
            val difficulty = BeatmapDifficulty(4f, 9f, 8f, 6f)

            while (it.moveToNext()) {
                val id = it.getLong(0)
                val mods = it.getString(1)

                // Check if the mods are migrated correctly.
                when (id) {
                    1L -> {
                        val modMap = LegacyModConverter.convert("rhd|x1.10", difficulty)
                        modMap.put(ModReplayV6())

                        Assert.assertEquals(modMap.serializeMods(), mods)
                    }

                    2L -> {
                        val modMap = LegacyModConverter.convert("m")
                        modMap.put(ModReplayV6())

                        Assert.assertEquals(modMap.serializeMods(), mods)
                    }

                    3L -> {
                        val modMap = LegacyModConverter.convert("m", difficulty)
                        modMap.put(ModReplayV6())

                        Assert.assertEquals(modMap.serializeMods(), mods)
                    }

                    else -> throw IllegalStateException("Unknown score ID: $id")
                }
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun `Test migration from version 2 to 3`() {
        helper.createDatabase(testDb, 2).use {
            val scores = mutableListOf<String>()

            fun addScore(mods: ModHashMap, time: Long = 1752863880000L) {
                scores.add("('md5', '', '', '${mods.serializeMods()}', 1000, 0, 0, 0, 0, 0, 0, 0, 0, $time)")
            }

            fun addScore(mods: String) {
                scores.add("('md5', '', '', '${mods}', 1000, 0, 0, 0, 0, 0, 0, 0, 0, 0)")
            }

            // A score with an unmigrated mod. Only the mods should be migrated and nothing else.
            addScore("dm|")

            // A score without stacked ModRateAdjust mods and no ModReplayV6 added.
            addScore(ModHashMap().apply {
                put(ModDoubleTime())
            })

            // A score with stacked ModRateAdjust mods, but before the score date cutoff, so only it should only have
            // ModReplayV6 added.
            addScore(ModHashMap().apply {
                put(ModDoubleTime())
                put(ModCustomSpeed(0.85f))
            }, 0L)

            // A score with stacked ModRateAdjust mods that should have its score migrated and no ModReplayV6 added.
            addScore(ModHashMap().apply {
                put(ModDoubleTime())
                put(ModCustomSpeed(0.85f))
            })

            it.execSQL(
                "INSERT INTO ScoreInfo (beatmapMD5, playerName, replayFilename, mods, score, maxCombo, mark, " +
                        "hit300k, hit300, hit100k, hit100, hit50, misses, time) VALUES " +
                        scores.joinToString(",")
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 3, true, MIGRATION_2_3)

        db.query("SELECT id, score, mods FROM ScoreInfo").use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val score = it.getInt(1)
                val mods = deserializeMods(it.getString(2))

                // Check if the scores are migrated correctly.
                when (id) {
                    1L -> {
                        // Score with an unmigrated mod, should only be migrated with ModReplayV6 added and nothing else.
                        Assert.assertEquals(3, mods.size)
                        Assert.assertTrue(ModDoubleTime::class in mods)
                        Assert.assertTrue(ModSmallCircle::class in mods)
                        Assert.assertTrue(ModReplayV6::class in mods)

                        Assert.assertEquals(1000, score)
                    }

                    2L -> {
                        // No stacked ModRateAdjust mods, score should remain unchanged.
                        Assert.assertEquals(1000, score)
                        Assert.assertEquals(1, mods.size)
                    }

                    3L -> {
                        // Stacked ModRateAdjust mods before score date cutoff, score should remain unchanged.
                        // Only ModReplayV6 should be added.
                        Assert.assertEquals(1000, score)
                        Assert.assertEquals(3, mods.size)
                        Assert.assertTrue(ModReplayV6::class in mods)
                    }

                    4L -> {
                        // Stacked ModRateAdjust mods, score should be recalculated.
                        Assert.assertEquals(1960, score)
                        Assert.assertEquals(2, mods.size)
                    }

                    else -> throw IllegalStateException("Unknown score ID: $id")
                }
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun `Test migration from version 3 to 4`() {
        helper.createDatabase(testDb, 3).use {
            // A score without Flashlight mod. This should not be recalculated.
            it.execSQL(
                "INSERT INTO ScoreInfo (beatmapMD5, playerName, replayFilename, mods, score, maxCombo, mark, " +
                "hit300k, hit300, hit100k, hit100, hit50, misses, time, sliderTickHits, sliderEndHits) VALUES ('md5', " +
                "'', '', '', 1000, 0, '', 0, 0, 0, 0, 0, 0, 0, 0, 0)"
            )

            // A score with Flashlight mod in default settings. This should not be recalculated.
            it.execSQL(
                "INSERT INTO ScoreInfo (beatmapMD5, playerName, replayFilename, mods, score, maxCombo, mark, " +
                "hit300k, hit300, hit100k, hit100, hit50, misses, time, sliderTickHits, sliderEndHits) VALUES ('md5', " +
                "'', '', '[{\"acronym\":\"FL\"}]', 1120, 0, '', 0, 0, 0, 0, 0, 0, 0, 0, 0)"
            )

            // A score with Flashlight mod with custom settings. This should be recalculated.
            it.execSQL(
                "INSERT INTO ScoreInfo (beatmapMD5, playerName, replayFilename, mods, score, maxCombo, mark, " +
                "hit300k, hit300, hit100k, hit100, hit50, misses, time, sliderTickHits, sliderEndHits) VALUES ('md5', " +
                "'', '', '[{\"acronym\":\"FL\",\"settings\":{\"areaFollowDelay\":0.24}}]', 1120, 0, '', 0, 0, 0, 0, 0, 0, 0, 0, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 4, true, MIGRATION_3_4)

        db.query("SELECT id, score, sliderHeadHits, sliderRepeatHits FROM ScoreInfo").use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val score = it.getInt(1)

                when (id) {
                    1L -> {
                        // Score without Flashlight mod, should remain unchanged.
                        Assert.assertEquals(1000, score)

                        // Only check the newly added columns in the first row because the others are the same.
                        Assert.assertTrue(it.isNull(2))
                        Assert.assertTrue(it.isNull(3))
                    }

                    2L -> {
                        // Score with default Flashlight mod, should remain unchanged.
                        Assert.assertEquals(1120, score)
                    }

                    3L -> {
                        // Score with custom Flashlight mod, should be recalculated.
                        Assert.assertEquals(1000, score)
                    }
                }
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun `Test migration from version 4 to 5`() {
        val beatmapMD5 = "md5"
        val missingBeatmapMD5 = "md5_missing"

        // Beatmap difficulty used to compute DA mod's score multiplier during migration.
        val difficulty = BeatmapDifficulty(4f, 9f, 8f, 6f)

        helper.createDatabase(testDb, 4).use {
            it.execSQL(
                "INSERT INTO BeatmapInfo (filename, md5, audioFilename, setDirectory, title, titleUnicode, artist, " +
                "artistUnicode, creator, version, tags, source, dateImported, approachRate, overallDifficulty, " +
                "circleSize, hpDrainRate, bpmMax, bpmMin, mostCommonBPM, length, previewTime, hitCircleCount, " +
                "sliderCount, spinnerCount, maxCombo, epilepsyWarning) VALUES " +
                "('', '$beatmapMD5', '', '', '', '', '', '', '', '', '', '', 0, " +
                "${difficulty.ar}, ${difficulty.od}, ${difficulty.difficultyCS}, ${difficulty.hp}, 60.0, 0.0, 60.0, " +
                "0, 0, 0, 0, 0, 0, 0)"
            )

            val hdMods = ModHashMap().apply { put(ModHidden()) }.serializeMods()

            val daMods = ModHashMap().apply {
                put(ModDifficultyAdjust(cs = 7f))
            }.serializeMods()

            fun insertScore(md5: String, mods: String, score: Int) {
                it.execSQL(
                    "INSERT INTO ScoreInfo (beatmapMD5, playerName, replayFilename, mods, score, maxCombo, mark, " +
                    "hit300k, hit300, hit100k, hit100, hit50, misses, time, sliderHeadHits, sliderTickHits, " +
                    "sliderRepeatHits, sliderEndHits) VALUES " +
                    "('$md5', '', '', '$mods', $score, 0, '', 0, 0, 0, 0, 0, 0, 0, null, null, null, null)"
                )
            }

            // Multiplier = 1.0; effectiveScore = 1000; expected raw = 1000
            insertScore(beatmapMD5, "", 1000)
            // Multiplier = 1.06; effectiveScore = round(1000 * 1.06) = 1060; expected raw = 1000
            insertScore(beatmapMD5, hdMods, 1060)
            // Multiplier = 1 + 0.0075 * (7-4)^1.5 ≈ 1.038971; effectiveScore = round(1000 * 1.038971) = 1039; expected raw = 1000
            insertScore(beatmapMD5, daMods, 1039)
            // DA mod, beatmap absent; sentinel -1
            insertScore(missingBeatmapMD5, daMods, 1039)
        }

        val db = helper.runMigrationsAndValidate(testDb, 5, true, MIGRATION_4_5)

        db.query("SELECT id, score, needsScoreMigration FROM ScoreInfo").use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val score = it.getInt(1)
                val needsScoreMigration = it.getInt(2) != 0

                when (id) {
                    1L -> {
                        Assert.assertEquals("no-mods score unchanged", 1000, score)
                        Assert.assertFalse("no-mods score needs no migration", needsScoreMigration)
                    }
                    2L -> {
                        Assert.assertEquals("HD score divided by 1.06", 1000, score)
                        Assert.assertFalse("HD score needs no migration", needsScoreMigration)
                    }
                    3L -> {
                        Assert.assertEquals("DA score divided using beatmap difficulty", 1000, score)
                        Assert.assertFalse("DA score with beatmap needs no migration", needsScoreMigration)
                    }
                    4L -> {
                        Assert.assertEquals("DA score without beatmap kept as-is", 1039, score)
                        Assert.assertTrue("DA score without beatmap flagged for migration", needsScoreMigration)
                    }
                    else -> throw IllegalStateException("Unknown score ID: $id")
                }
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun `Test migration from version 5 to 6 preserves existing rows and history`() {
        helper.createDatabase(testDb, 5).use {
            it.execSQL(
                "INSERT INTO BeatmapInfo (filename, md5, audioFilename, setDirectory, title, titleUnicode, " +
                    "artist, artistUnicode, creator, version, tags, source, dateImported, approachRate, " +
                    "overallDifficulty, circleSize, hpDrainRate, bpmMax, bpmMin, mostCommonBPM, length, " +
                    "previewTime, hitCircleCount, sliderCount, spinnerCount, maxCombo, epilepsyWarning) VALUES " +
                    "('map.osu', 'md5', 'audio.mp3', 'set', 'Title', 'TitleU', 'Artist', 'ArtistU', " +
                    "'Mapper', 'Diff', 'tags', 'source', 123, 9.0, 8.0, 4.0, 6.0, 60.0, 0.0, 60.0, " +
                    "1000, 0, 10, 5, 1, 20, 1)"
            )

            it.execSQL(
                "INSERT INTO ScoreInfo (beatmapMD5, playerName, replayFilename, mods, score, maxCombo, " +
                    "mark, hit300k, hit300, hit100k, hit100, hit50, misses, time, needsScoreMigration) VALUES " +
                    "('md5', 'player', 'replay.odr', '[]', 1234, 10, 'S', 1, 2, 1, 3, 0, 4, 123456789, 0)"
            )

            it.execSQL("INSERT INTO BeatmapSetCollection (name) VALUES ('collection')")
            it.execSQL(
                "INSERT INTO BeatmapSetCollection_BeatmapSetInfo (collectionName, setDirectory) " +
                    "VALUES ('collection', 'set')"
            )
            it.execSQL(
                "INSERT INTO BeatmapOptions (setDirectory, isFavorite, offset) VALUES ('set', 1, 42)"
            )
            it.execSQL(
                "INSERT INTO ModPreset (name, serializedMods) VALUES ('preset', '[]')"
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 6, true, MIGRATION_5_6)

        db.query("SELECT filename, md5, title, hitCircleCount, sliderCount, spinnerCount FROM BeatmapInfo").use {
            Assert.assertTrue(it.moveToFirst())
            Assert.assertEquals("map.osu", it.getString(0))
            Assert.assertEquals("md5", it.getString(1))
            Assert.assertEquals("Title", it.getString(2))
            Assert.assertEquals(10, it.getInt(3))
            Assert.assertEquals(5, it.getInt(4))
            Assert.assertEquals(1, it.getInt(5))
            Assert.assertFalse(it.moveToNext())
        }

        db.query("SELECT score, playerName, mods, needsScoreMigration FROM ScoreInfo").use {
            Assert.assertTrue(it.moveToFirst())
            Assert.assertEquals(1234, it.getInt(0))
            Assert.assertEquals("player", it.getString(1))
            Assert.assertEquals("[]", it.getString(2))
            Assert.assertEquals(0, it.getInt(3))
            Assert.assertFalse(it.moveToNext())
        }

        db.query("SELECT name FROM BeatmapSetCollection").use {
            Assert.assertTrue(it.moveToFirst())
            Assert.assertEquals("collection", it.getString(0))
        }

        db.query("SELECT collectionName, setDirectory FROM BeatmapSetCollection_BeatmapSetInfo").use {
            Assert.assertTrue(it.moveToFirst())
            Assert.assertEquals("collection", it.getString(0))
            Assert.assertEquals("set", it.getString(1))
        }

        db.query("SELECT setDirectory, isFavorite, offset FROM BeatmapOptions").use {
            Assert.assertTrue(it.moveToFirst())
            Assert.assertEquals("set", it.getString(0))
            Assert.assertEquals(1, it.getInt(1))
            Assert.assertEquals(42, it.getInt(2))
        }

        db.query("SELECT name, serializedMods FROM ModPreset").use {
            Assert.assertTrue(it.moveToFirst())
            Assert.assertEquals("preset", it.getString(0))
            Assert.assertEquals("[]", it.getString(1))
        }

        // New tables are usable, and deleting a course must not remove its historical attempts.
        db.execSQL(
            "INSERT INTO SkillCourse (id, definitionJson, courseHash, courseName, author, locked, " +
                "createdAt, updatedAt) VALUES ('course', '{}', 'hash', 'name', 'author', 1, 1, 2)"
        )
        db.execSQL(
            "INSERT INTO SkillCourseAttempt (id, courseId, courseHash, definitionJson, rulesetVersion, " +
                "outcome, createdAt, resultJson, replayDirectory) VALUES " +
                "('attempt', 'course', 'hash', '{}', 1, 'completed', 3, '{}', null)"
        )
        db.execSQL("DELETE FROM SkillCourse WHERE id = 'course'")

        db.query("SELECT COUNT(*) FROM SkillCourseAttempt WHERE courseId = 'course'").use {
            Assert.assertTrue(it.moveToFirst())
            Assert.assertEquals(1, it.getInt(0))
        }
    }

    @Test
    @Throws(IOException::class)
    fun `Test migration from version 6 to 7 preserves courses and attempts`() {
        helper.createDatabase(testDb, 6).use {
            it.execSQL(
                "INSERT INTO SkillCourse (id, definitionJson, courseHash, courseName, author, locked, " +
                    "createdAt, updatedAt) VALUES ('course', '{\"maps\":[]}', 'hash', 'name', 'author', 1, 1, 2)"
            )
            it.execSQL(
                "INSERT INTO SkillCourseAttempt (id, courseId, courseHash, definitionJson, rulesetVersion, " +
                    "outcome, createdAt, resultJson, replayDirectory) VALUES " +
                    "('attempt', 'course', 'hash', '{}', 1, 'completed', 3, '{}', null)"
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 7, true, MIGRATION_6_7)

        db.query("SELECT definitionJson, courseHash, courseName, author, locked FROM SkillCourse").use {
            Assert.assertTrue(it.moveToFirst())
            Assert.assertEquals("{\"maps\":[]}", it.getString(0))
            Assert.assertEquals("hash", it.getString(1))
            Assert.assertEquals("name", it.getString(2))
            Assert.assertEquals("author", it.getString(3))
            Assert.assertEquals(1, it.getInt(4))
            Assert.assertFalse(it.moveToNext())
        }

        db.query(
            "SELECT courseId, courseHash, definitionJson, outcome, resultJson, replayDirectory " +
                "FROM SkillCourseAttempt"
        ).use {
            Assert.assertTrue(it.moveToFirst())
            Assert.assertEquals("course", it.getString(0))
            Assert.assertEquals("hash", it.getString(1))
            Assert.assertEquals("{}", it.getString(2))
            Assert.assertEquals("completed", it.getString(3))
            Assert.assertEquals("{}", it.getString(4))
            Assert.assertTrue(it.isNull(5))
            Assert.assertFalse(it.moveToNext())
        }

        // Preferences are independent per course and map and can be inserted after the migration.
        db.execSQL(
            "INSERT INTO CoursePlayPreference (courseId, mapHash, speedHundredths, additionalModsJson, updatedAt) " +
                "VALUES ('course', '00000000000000000000000000000001', 125, '[]', 10)"
        )
        db.execSQL(
            "INSERT INTO CoursePlayPreference (courseId, mapHash, speedHundredths, additionalModsJson, updatedAt) " +
                "VALUES ('course', '00000000000000000000000000000002', 175, '[]', 11)"
        )
        db.execSQL(
            "INSERT INTO CoursePlayPreference (courseId, mapHash, speedHundredths, additionalModsJson, updatedAt) " +
                "VALUES ('other', '00000000000000000000000000000001', 225, '[]', 12)"
        )

        db.query(
            "SELECT courseId, mapHash, speedHundredths FROM CoursePlayPreference " +
                "ORDER BY courseId ASC, mapHash ASC"
        ).use {
            Assert.assertTrue(it.moveToFirst())
            Assert.assertEquals("course", it.getString(0))
            Assert.assertEquals("00000000000000000000000000000001", it.getString(1))
            Assert.assertEquals(125, it.getInt(2))

            Assert.assertTrue(it.moveToNext())
            Assert.assertEquals("course", it.getString(0))
            Assert.assertEquals("00000000000000000000000000000002", it.getString(1))
            Assert.assertEquals(175, it.getInt(2))

            Assert.assertTrue(it.moveToNext())
            Assert.assertEquals("other", it.getString(0))
            Assert.assertEquals("00000000000000000000000000000001", it.getString(1))
            Assert.assertEquals(225, it.getInt(2))
            Assert.assertFalse(it.moveToNext())
        }

        // The composite primary key keeps the same map independent per course and rejects duplicates.
        try {
            db.execSQL(
                "INSERT INTO CoursePlayPreference (courseId, mapHash, speedHundredths, additionalModsJson, updatedAt) " +
                    "VALUES ('course', '00000000000000000000000000000001', 300, '[]', 13)"
            )
            Assert.fail("Duplicate (courseId, mapHash) preference should be rejected")
        } catch (_: Exception) {
            // Expected: the composite primary key is enforced.
        }
    }
}
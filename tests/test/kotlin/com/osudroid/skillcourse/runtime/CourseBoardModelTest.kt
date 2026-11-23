package com.osudroid.skillcourse.runtime

import com.osudroid.data.SkillCourseAttemptEntity
import com.osudroid.skillcourse.CourseCodec
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseTestFixtures
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseBoardModelTest {

    private val json = Json { encodeDefaults = true }

    private val definition: CourseDefinition = CourseTestFixtures.definition(
        maps = listOf(
            CourseTestFixtures.map(hash = CourseTestFixtures.HASH_A, title = "First"),
            CourseTestFixtures.map(hash = CourseTestFixtures.HASH_B, title = "Second")
        ),
        scoring = "scorev1"
    )
    private val activeHash = CourseCodec.hash(definition)
    private val oldHash = "f".repeat(32)

    @Test
    fun `exact definition hash keeps current attempts and skips corrupt or in progress ones`() {
        val current = result("current", components = listOf(component(0, CourseTestFixtures.HASH_A, score = 100)))
        val old = result(
            "old",
            courseHash = oldHash,
            definition = CourseTestFixtures.definition(),
            components = listOf(component(0, CourseTestFixtures.HASH_A, score = 900))
        )
        val inProgress = result("running", outcome = "in_progress")
        val corrupt = entity(result("corrupt"), resultJson = "{not valid json")

        val parsed = CourseBoardModel.parseCourseAttempts(
            activeHash,
            listOf(entity(current), entity(old), entity(inProgress), corrupt),
            json
        )

        assertEquals(listOf("current"), parsed.attempts.map { it.result.clientAttemptId })
        assertEquals(1, parsed.failed)
        assertTrue(parsed.attempts.all { it.entity.courseHash == activeHash })
    }

    @Test
    fun `map board only contains components for the selected authored map`() {
        val first = result(
            "first",
            components = listOf(
                component(0, CourseTestFixtures.HASH_A, score = 100, rawEx = 4),
                component(1, CourseTestFixtures.HASH_B, score = 900, rawEx = 50)
            )
        )
        val second = result(
            "second",
            components = listOf(
                component(0, CourseTestFixtures.HASH_A, score = 300, rawEx = -2),
                component(1, CourseTestFixtures.HASH_B, score = 800, rawEx = 40)
            )
        )
        val state = CourseViewState(
            courseId = "course",
            courseHash = activeHash,
            definition = definition,
            mapByInstalledHash = mapOf(
                CourseTestFixtures.HASH_A to definition.maps[0],
                CourseTestFixtures.HASH_B to definition.maps[1]
            ),
            outdated = emptySet(),
            generation = 7
        )
        val parsed = CourseBoardModel.parseCourseAttempts(
            activeHash,
            listOf(
                entity(first), entity(second),
                entity(result("misattributed", components = listOf(
                    component(0, CourseTestFixtures.HASH_B, score = 99_999)
                )))
            ),
            json
        )

        val rows = CourseBoardModel.mapRows(state, CourseTestFixtures.HASH_A, parsed.attempts)

        assertEquals(2, rows.size)
        assertEquals(setOf("first", "second"), rows.map { it.attemptId }.toSet())
        assertTrue(rows.all { it.originalIndex == 0 })
        assertEquals(listOf(1, 2), rows.map { it.rank })
        assertTrue(rows.any { it.detail.contains("EX -2") })
        assertFalse(rows.any { it.detail.contains("EX 50") })
    }

    @Test
    fun `total uses published ex while component ex stays signed`() {
        val exDefinition = definition.copy(scoring = "exscore")
        val exHash = CourseCodec.hash(exDefinition)
        val negative = result(
            "negative",
            courseHash = exHash,
            definition = exDefinition,
            outcome = "completed",
            rawExTotal = -7,
            components = listOf(component(0, CourseTestFixtures.HASH_A, score = 100, rawEx = -7))
        )
        val state = CourseViewState(
            "course",
            exHash,
            exDefinition,
            mapOf(CourseTestFixtures.HASH_A to exDefinition.maps[0]),
            emptySet(),
            1
        )
        val parsed = CourseBoardModel.parseCourseAttempts(exHash, listOf(entity(negative)), json)

        val total = CourseBoardModel.totalRows(exDefinition, parsed.attempts).single()
        val map = CourseBoardModel.mapRows(state, CourseTestFixtures.HASH_A, parsed.attempts).single()

        assertTrue(total.title.contains("EX 0"))
        assertFalse(total.title.contains("EX -7"))
        assertTrue(map.title.contains("EX -7"))
        assertTrue(map.detail.contains("EX -7"))
    }

    @Test
    fun `completed attempts sort before failures and speedrun ties use active time then stable ids`() {
        val speedrunDefinition = definition.copy(isSpeedrun = true)
        val speedrunHash = CourseCodec.hash(speedrunDefinition)

        fun attempt(
            id: String,
            score: Long,
            outcome: String = "completed",
            activeNanos: Long,
            createdAt: Long
        ): CourseAttempt {
            val courseResult = result(
                id,
                courseHash = speedrunHash,
                definition = speedrunDefinition,
                outcome = outcome,
                createdAt = createdAt,
                activeNanos = activeNanos,
                components = listOf(component(0, CourseTestFixtures.HASH_A, score = score))
            )
            return CourseAttempt(entity(courseResult), courseResult)
        }

        val attempts = listOf(
            attempt("a", score = 1_000, activeNanos = 500, createdAt = 1),
            attempt("b", score = 2_000, activeNanos = 900, createdAt = 1),
            attempt("c", score = 3_000, outcome = "failed", activeNanos = 1, createdAt = 1),
            attempt("d", score = 2_000, activeNanos = 700, createdAt = 1),
            attempt("e", score = 2_000, activeNanos = 700, createdAt = 2),
            attempt("f", score = 2_000, activeNanos = 700, createdAt = 2)
        )

        val rows = CourseBoardModel.totalRows(speedrunDefinition, attempts)

        assertEquals(listOf("e", "f", "d", "b", "a", "c"), rows.map { it.attemptId })
        assertEquals((1..6).toList(), rows.map { it.rank })
        assertEquals("failed", rows.last().title.substringAfter('#').substringAfter(' ').substringBefore(" · "))
    }

    @Test
    fun `accuracy and exscore metric helpers use pooled values and never invent pp`() {
        val accuracy = result(
            "accuracy",
            components = listOf(
                component(0, CourseTestFixtures.HASH_A, score = 100, hit300 = 7, hit100 = 2, hit50 = 0, misses = 1),
                component(1, CourseTestFixtures.HASH_B, score = 50, hit300 = 3, hit100 = 0, hit50 = 0, misses = 0)
            )
        )

        assertEquals(
            (300L * 10 + 100L * 2) / (300.0 * 13.0),
            accuracy.totalMetric("accuracy"),
            0.0000001
        )
        assertTrue(accuracy.totalMetricText("accuracy").startsWith("Accuracy "))
        assertEquals("Score 150", accuracy.totalMetricText("scorev1"))
        assertFalse(BoardModePolicy.label(BoardMode.ONLINE_PP).contains("score", ignoreCase = true))
    }

    private fun result(
        id: String,
        courseHash: String = activeHash,
        definition: CourseDefinition = this.definition,
        outcome: String = "completed",
        createdAt: Long = 1L,
        activeNanos: Long = 1_000L,
        rawExTotal: Long = 0,
        components: List<CourseComponentResult> = emptyList()
    ) = CourseResult(
        clientAttemptId = id,
        courseId = "course",
        courseHash = courseHash,
        definition = definition,
        playerName = "Player $id",
        createdAt = createdAt,
        outcome = outcome,
        order = definition.maps.indices.toList(),
        components = components,
        rawExTotal = rawExTotal,
        activeNanos = activeNanos,
        replayDirectory = "attempts/$id"
    )

    private fun component(
        originalIndex: Int,
        mapHash: String,
        score: Long,
        rawEx: Long = 0,
        hit300: Int = 10,
        hit100: Int = 0,
        hit50: Int = 0,
        misses: Int = 0
    ) = CourseComponentResult(
        originalIndex = originalIndex,
        playedIndex = originalIndex,
        mapHash = mapHash,
        score = score,
        maxCombo = 10,
        hit300 = hit300,
        hit100 = hit100,
        hit50 = hit50,
        misses = misses,
        sliderHeadHits = 0,
        sliderTickHits = 0,
        sliderRepeatHits = 0,
        sliderEndHits = 0,
        modsJson = "[]",
        rawExContribution = rawEx,
        startingHp = null,
        endingHp = null,
        activeNanos = 1_000L,
        completed = true,
        replayFilename = null,
        replaySha256 = null,
        eventsFilename = null
    )

    private fun entity(
        result: CourseResult,
        resultJson: String = json.encodeToString(result)
    ) = SkillCourseAttemptEntity(
        id = result.clientAttemptId,
        courseId = result.courseId,
        courseHash = result.courseHash,
        definitionJson = CourseCodec.encode(result.definition),
        outcome = result.outcome,
        createdAt = result.createdAt,
        resultJson = resultJson,
        replayDirectory = result.replayDirectory
    )
}

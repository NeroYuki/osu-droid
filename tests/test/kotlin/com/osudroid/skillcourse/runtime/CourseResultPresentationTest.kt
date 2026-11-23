package com.osudroid.skillcourse.runtime

import com.osudroid.skillcourse.CourseTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseResultPresentationTest {

    private fun component() = CourseComponentResult(
        originalIndex = 1,
        playedIndex = 0,
        mapHash = CourseTestFixtures.HASH_B,
        score = 1_000L,
        maxCombo = 42,
        hit300 = 10,
        hit100 = 2,
        hit50 = 1,
        misses = 1,
        sliderHeadHits = 0,
        sliderTickHits = 0,
        sliderRepeatHits = 0,
        sliderEndHits = 0,
        modsJson = "[]",
        rawExContribution = 7L,
        startingHp = 80,
        endingHp = 75,
        activeNanos = 65_000_000_000L,
        completed = false,
        replayFilename = "0.odr",
        replaySha256 = null,
        eventsFilename = "0.events.json",
        hit300k = 3,
        hit100k = 1
    )

    private fun result(): CourseResult {
        val definition = CourseTestFixtures.definition(
            maps = listOf(
                CourseTestFixtures.map(hash = CourseTestFixtures.HASH_A),
                CourseTestFixtures.map(hash = CourseTestFixtures.HASH_B)
            ),
            isSpeedrun = true
        )
        return CourseResult(
            clientAttemptId = "attempt",
            courseId = "course",
            courseHash = "hash",
            definition = definition,
            playerName = "Player",
            createdAt = 0L,
            outcome = "interrupted",
            order = listOf(1, 0),
            components = listOf(component()),
            rawExTotal = 7L,
            activeNanos = 65_000_000_000L,
            replayDirectory = "attempts/attempt"
        )
    }

    @Test
    fun `aggregate counts include native auxiliary judgments`() {
        val aggregate = result().aggregateCounts()

        assertEquals(1_000L, aggregate.score)
        assertEquals(42, aggregate.maxCombo)
        assertEquals(10L, aggregate.hit300)
        assertEquals(2L, aggregate.hit100)
        assertEquals(1L, aggregate.hit50)
        assertEquals(1L, aggregate.misses)
        assertEquals(3L, aggregate.hit300k)
        assertEquals(1L, aggregate.hit100k)
        assertEquals(
            (300L * 10 + 100L * 2 + 50L) / (300.0 * 14.0),
            aggregate.accuracy,
            0.0000001
        )
    }

    @Test
    fun `aggregate counts sum every played component`() {
        val first = component()
        val second = component().copy(
            originalIndex = 0,
            playedIndex = 1,
            mapHash = CourseTestFixtures.HASH_A,
            score = 500L,
            maxCombo = 88,
            hit300 = 5,
            hit100 = 1,
            hit50 = 2,
            misses = 3,
            hit300k = 4,
            hit100k = 2
        )
        val aggregate = result().copy(components = listOf(first, second)).aggregateCounts()

        assertEquals(1_500L, aggregate.score)
        assertEquals(88, aggregate.maxCombo)
        assertEquals(15L, aggregate.hit300)
        assertEquals(3L, aggregate.hit100)
        assertEquals(3L, aggregate.hit50)
        assertEquals(4L, aggregate.misses)
        assertEquals(7L, aggregate.hit300k)
        assertEquals(3L, aggregate.hit100k)
    }

    @Test
    fun `breakdown falls back to authored order when no realized order exists`() {
        val pages = result().copy(order = emptyList(), components = emptyList()).breakdownPages()

        assertEquals(listOf(0, 1), pages.map { it.originalIndex })
        assertTrue(pages.none { it.isPlayed })
        assertTrue(pages.none { it.isPartial })
    }

    @Test
    fun `ellipsize never splits supplementary code points`() {
        val emoji = "\uD83D\uDE00"
        val fixedAdvance: (Int) -> Float = { 10f }

        assertEquals("$emoji...", ellipsize(emoji.repeat(6), 45f, fixedAdvance))
        assertEquals("A...", ellipsize("A$emoji BCDEF", 45f, fixedAdvance))
        assertEquals("...", ellipsize("anything", 25f, fixedAdvance))

        assertFalse(hasUnpairedSurrogate(ellipsize("A$emoji BCDEF", 45f, fixedAdvance)))
        assertEquals("$emoji$emoji", ellipsize("$emoji$emoji", 100f, fixedAdvance))
    }

    private fun hasUnpairedSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) {
                        return true
                    }
                    index += 2
                }
                Character.isLowSurrogate(character) -> return true
                else -> index++
            }
        }
        return false
    }

    @Test
    fun `breakdown keeps realized play order and marks the unplayed prefix remainder`() {
        val pages = result().breakdownPages()

        assertEquals(2, pages.size)
        assertEquals(1, pages[0].originalIndex)
        assertTrue(pages[0].isPlayed)
        assertTrue(pages[0].isPartial)
        assertEquals(0, pages[1].originalIndex)
        assertFalse(pages[1].isPlayed)
        assertFalse(pages[1].isPartial)
    }

    @Test
    fun `non completed outcomes are failed and grades stay native`() {
        assertTrue(result().isFailedOutcome())
        assertEquals("S", component().copy(hit100 = 1, hit50 = 0, misses = 0).nativeGrade())
        assertEquals("X", component().copy(hit100 = 0, hit50 = 0, misses = 0).nativeGrade())
        assertEquals("C", component().nativeGrade())
    }

    @Test
    fun `compact duration and score padding remain stable`() {
        assertEquals("1:05.000", formatCompactDuration(65_000_000_000L))
        assertEquals("0:12.463", formatCompactDuration(12_463_330_761L))
        assertEquals("1:01:05.000", formatCompactDuration(3_665_000_000_000L))
        assertEquals("00001234", scoreDigitsText(1_234L, padTo = 8))
        assertEquals("+7", formatSigned(7L))
        assertEquals("-2", formatSigned(-2L))
    }
}

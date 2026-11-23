package com.osudroid.skillcourse

import com.osudroid.skillcourse.CourseTestFixtures.definition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseAccumulatorTest {

    @Test
    fun `Default global gauge trace ends at 76 and EX 2`() {
        val sequential = CourseAccumulator(definition())
        val batch = CourseAccumulator(definition())

        val trace = listOf(
            CourseJudgment.HIT300,
            CourseJudgment.HIT100,
            CourseJudgment.HIT50,
            CourseJudgment.HIT0,
            CourseJudgment.SLIDERBREAK,
            CourseJudgment.SLIDEREND
        )

        trace.forEach(sequential::onJudgment)
        batch.onJudgmentBatch(trace)

        assertEquals(76, sequential.globalHp)
        assertEquals(76, batch.globalHp)
        assertEquals(2L, sequential.rawExTotal)
        assertEquals(2L, sequential.publishedExScore)
        assertEquals(2L, batch.rawExTotal)
        assertFalse(sequential.failed)
        assertFalse(batch.failed)

        // There is no per-map reset: the next event continues from the carried HP.
        sequential.onJudgment(CourseJudgment.HIT300)
        assertEquals(77, sequential.globalHp)
    }

    @Test
    fun `Pooled accuracy is one perfect plus nine misses at ten percent`() {
        val accumulator = CourseAccumulator(definition())

        accumulator.onJudgment(CourseJudgment.HIT300)
        repeat(9) {
            accumulator.onJudgment(CourseJudgment.HIT0)
        }

        assertEquals(300L, accumulator.accuracyNumerator)
        assertEquals(3000L, accumulator.accuracyDenominator)
        assertEquals(0.1, accumulator.accuracy, 0.0000001)
        assertEquals(1, accumulator.hit300Count)
        assertEquals(9, accumulator.hit0Count)
    }

    @Test
    fun `Break and tail events in one batch both apply`() {
        val accumulator = CourseAccumulator(
            definition(
                gaugeSettings = CourseGaugeSettings(
                    totalHp = 100,
                    startingHp = 100,
                    sliderbreak = -2,
                    sliderend = -4
                )
            )
        )

        accumulator.onJudgmentBatch(
            listOf(CourseJudgment.SLIDERBREAK, CourseJudgment.SLIDEREND)
        )

        assertEquals(94, accumulator.globalHp)
        assertEquals(1, accumulator.sliderBreakCount)
        assertEquals(1, accumulator.sliderEndCount)
        // Slider events do not add denominator objects.
        assertEquals(0L, accumulator.accuracyDenominator)
    }

    @Test
    fun `Batch gauge clamping happens once after the net delta`() {
        val accumulator = CourseAccumulator(
            definition(
                gaugeSettings = CourseGaugeSettings(
                    totalHp = 100,
                    startingHp = 99,
                    hit300 = 2,
                    hit0 = -3
                )
            )
        )

        accumulator.onJudgmentBatch(listOf(CourseJudgment.HIT300, CourseJudgment.HIT0))

        // Per-event clamping would produce 97 (99 -> 100 -> 97); the batch produces 98.
        assertEquals(98, accumulator.globalHp)
    }

    @Test
    fun `Terminal global failure is sticky and later events are ignored`() {
        val accumulator = CourseAccumulator(
            definition(
                gaugeSettings = CourseGaugeSettings(
                    totalHp = 100,
                    startingHp = 2,
                    hit300 = 10,
                    hit0 = -3
                )
            )
        )

        accumulator.onJudgment(CourseJudgment.HIT0)
        assertTrue(accumulator.failed)
        assertTrue(accumulator.isFailed)
        assertEquals(0, accumulator.globalHp)
        assertEquals(-1L, accumulator.rawExTotal)

        accumulator.onJudgment(CourseJudgment.HIT300)
        assertEquals(0, accumulator.globalHp)
        assertEquals(-1L, accumulator.rawExTotal)
        assertEquals(1, accumulator.hit0Count)
        assertEquals(0, accumulator.hit300Count)
        assertEquals(0L, accumulator.registerCompletedMapScore(123_456L))
        assertEquals(0, accumulator.completedMapCount)
    }

    @Test
    fun `EX keeps signed raw total and floors only the published score`() {
        val positive = CourseAccumulator(
            definition(
                exscoreSettings = CourseExscoreSettings(hit300 = 2, hit0 = -1)
            )
        )
        positive.onJudgment(CourseJudgment.HIT0)
        positive.onJudgment(CourseJudgment.HIT300)
        assertEquals(1L, positive.rawExTotal)
        assertEquals(1L, positive.publishedExScore)

        val negative = CourseAccumulator(
            definition(
                exscoreSettings = CourseExscoreSettings(hit300 = 2, hit0 = -3)
            )
        )
        negative.onJudgment(CourseJudgment.HIT0)
        negative.onJudgment(CourseJudgment.HIT300)
        assertEquals(-1L, negative.rawExTotal)
        assertEquals(0L, negative.publishedExScore)

        // The signed raw value remains displayable even after the published floor is applied.
        assertEquals(-1L, negative.rawExTotal)
    }

    @Test
    fun `None gauge never fails but still records EX and accuracy`() {
        val accumulator = CourseAccumulator(
            definition(
                lifeGauge = "none",
                gaugeSettings = CourseGaugeSettings(startingHp = 80)
            )
        )

        repeat(10) {
            accumulator.onJudgment(CourseJudgment.HIT0)
        }

        assertFalse(accumulator.failed)
        assertEquals(80, accumulator.globalHp)
        assertEquals(-10L, accumulator.rawExTotal)
        assertEquals(10, accumulator.hit0Count)
        assertEquals(0.0, accumulator.accuracy, 0.0)
    }

    @Test
    fun `Standard gauge delegates HP failure to native gameplay`() {
        val accumulator = CourseAccumulator(
            definition(
                lifeGauge = "standard",
                gaugeSettings = CourseGaugeSettings(startingHp = 80)
            )
        )

        repeat(10) {
            accumulator.onJudgment(CourseJudgment.HIT0)
        }

        assertFalse(accumulator.failed)
        assertEquals(80, accumulator.globalHp)
        assertEquals(10, accumulator.hit0Count)
    }

    @Test
    fun `Completed map native scores sum and pooled accuracy does not average maps`() {
        val accumulator = CourseAccumulator(definition())

        assertEquals(123_456L, accumulator.registerCompletedMapScore(123_456L))
        assertEquals(358_023L, accumulator.registerCompletedMapScore(234_567L))
        assertEquals(2, accumulator.completedMapCount)

        accumulator.registerCompletedMapJudgments(hit300 = 1, hit100 = 0, hit50 = 0, hit0 = 0)
        accumulator.registerCompletedMapJudgments(hit300 = 0, hit100 = 0, hit50 = 0, hit0 = 9)

        assertEquals(300L, accumulator.accuracyNumerator)
        assertEquals(3000L, accumulator.accuracyDenominator)
        assertEquals(0.1, accumulator.accuracy, 0.0000001)
    }

    @Test
    fun `Completed map judgment registration rejects negative counts`() {
        val accumulator = CourseAccumulator(definition())

        assertThrows(IllegalArgumentException::class.java) {
            accumulator.registerCompletedMapJudgments(hit300 = -1, hit100 = 0, hit50 = 0, hit0 = 0)
        }
    }
}

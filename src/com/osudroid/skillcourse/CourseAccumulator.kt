package com.osudroid.skillcourse

import java.util.EnumMap
import kotlin.math.max

/**
 * Deterministic, event-only course aggregation.
 *
 * Gauge policy:
 * - `global` owns an integer HP value initialized once at [CourseDefinition.gaugeSettings]'s
 *   starting HP. Events in a batch are summed, clamped once, and then checked for failure.
 * - `standard` is driven by the engine's native per-map HP; this accumulator does not mutate or
 *   fail the global value.
 * - `none` never fails from HP. Events still contribute to EX and accuracy.
 *
 * After a terminal global failure all later events are ignored. EX remains a signed running total;
 * only [publishedExScore] is floored at zero.
 */
class CourseAccumulator(
    private val definition: CourseDefinition
) {

    private val gauge = definition.gaugeSettings
    private val exscore = definition.exscoreSettings
    private val counts = EnumMap<CourseJudgment, Int>(CourseJudgment::class.java)

    /**
     * The global HP value. Initialized exactly once and never reset by this accumulator.
     */
    var globalHp: Int = gauge.startingHp.coerceIn(0, gauge.totalHp)
        private set

    /**
     * Signed sum of every EX event delta in the course.
     */
    var rawExTotal: Long = 0L
        private set

    /**
     * The published course EX score: `max(0, rawExTotal)`.
     */
    val publishedExScore: Long
        get() = max(0L, rawExTotal)

    /**
     * Sticky terminal failure state for the global gauge.
     */
    var failed: Boolean = false
        private set

    /**
     * Java-friendly alias for [failed].
     */
    val isFailed: Boolean
        get() = failed

    /**
     * Sum of every registered completed map's native displayed score.
     */
    var nativeScoreTotal: Long = 0L
        private set

    /**
     * Number of completed maps whose native score has been registered.
     */
    var completedMapCount: Int = 0
        private set

    val hit300Count: Int
        get() = countOf(CourseJudgment.HIT300)

    val hit100Count: Int
        get() = countOf(CourseJudgment.HIT100)

    val hit50Count: Int
        get() = countOf(CourseJudgment.HIT50)

    val hit0Count: Int
        get() = countOf(CourseJudgment.HIT0)

    val sliderBreakCount: Int
        get() = countOf(CourseJudgment.SLIDERBREAK)

    val sliderEndCount: Int
        get() = countOf(CourseJudgment.SLIDEREND)

    /**
     * Pooled accuracy numerator: `300*N300 + 100*N100 + 50*N50`.
     */
    val accuracyNumerator: Long
        get() =
            300L * hit300Count +
            100L * hit100Count +
            50L * hit50Count

    /**
     * Pooled accuracy denominator: `300 * (N300 + N100 + N50 + N0)`.
     */
    val accuracyDenominator: Long
        get() = 300L * (hit300Count + hit100Count + hit50Count + hit0Count)

    /**
     * Pooled course accuracy in the range 0..1, or 0 when no final judgment exists.
     */
    val accuracy: Double
        get() {
            val denominator = accuracyDenominator
            return if (denominator == 0L) 0.0 else accuracyNumerator.toDouble() / denominator.toDouble()
        }

    /**
     * Returns an immutable snapshot of the final judgment counts, including break/tail events.
     */
    fun judgmentCounts(): Map<CourseJudgment, Int> = counts.toMap()

    /**
     * Applies one semantic event. This is equivalent to a one-event batch.
     */
    fun onJudgment(event: CourseJudgment) {
        onJudgmentBatch(listOf(event))
    }

    /**
     * Applies one deterministic net-gauge batch.
     *
     * Every event in a batch contributes its EX delta and final-judgment count. The HP deltas are
     * summed, applied once, clamped once, and then the terminal failure check runs. This makes the
     * result independent of callback order for events at the same gameplay timestamp.
     */
    fun onJudgmentBatch(events: List<CourseJudgment>) {
        if (failed || events.isEmpty()) {
            return
        }

        var gaugeDelta = 0L
        val globalGauge = definition.lifeGauge == "global"

        for (event in events) {
            counts[event] = (counts[event] ?: 0) + 1
            rawExTotal += exDelta(event)

            if (globalGauge) {
                gaugeDelta += gaugeDeltaFor(event)
            }
        }

        if (!globalGauge) {
            return
        }

        val clamped = (globalHp.toLong() + gaugeDelta)
            .coerceIn(0L, gauge.totalHp.toLong())
            .toInt()

        globalHp = clamped
        if (clamped == 0) {
            failed = true
        }
    }

    /**
     * Registers a completed map's native displayed score once.
     *
     * @return The updated native score sum.
     */
    fun registerCompletedMapScore(nativeScore: Long): Long {
        if (failed) {
            return nativeScoreTotal
        }

        nativeScoreTotal += nativeScore
        completedMapCount++
        return nativeScoreTotal
    }

    /**
     * Registers native final-judgment counts for a completed map.
     *
     * Use this only when a completed map's native statistics are available as a whole rather than
     * as live [CourseJudgment] events; do not combine it with the equivalent events for the same
     * map or the counts will be duplicated.
     */
    fun registerCompletedMapJudgments(hit300: Int, hit100: Int, hit50: Int, hit0: Int) {
        if (failed) {
            return
        }

        require(hit300 >= 0 && hit100 >= 0 && hit50 >= 0 && hit0 >= 0) {
            "Judgment counts must not be negative."
        }

        counts[CourseJudgment.HIT300] = (counts[CourseJudgment.HIT300] ?: 0) + hit300
        counts[CourseJudgment.HIT100] = (counts[CourseJudgment.HIT100] ?: 0) + hit100
        counts[CourseJudgment.HIT50] = (counts[CourseJudgment.HIT50] ?: 0) + hit50
        counts[CourseJudgment.HIT0] = (counts[CourseJudgment.HIT0] ?: 0) + hit0
    }

    private fun countOf(event: CourseJudgment): Int = counts[event] ?: 0

    private fun gaugeDeltaFor(event: CourseJudgment): Int = when (event) {
        CourseJudgment.HIT300 -> gauge.hit300
        CourseJudgment.HIT100 -> gauge.hit100
        CourseJudgment.HIT50 -> gauge.hit50
        CourseJudgment.HIT0 -> gauge.hit0
        CourseJudgment.SLIDERBREAK -> gauge.sliderbreak
        CourseJudgment.SLIDEREND -> gauge.sliderend
    }

    private fun exDelta(event: CourseJudgment): Int = when (event) {
        CourseJudgment.HIT300 -> exscore.hit300
        CourseJudgment.HIT100 -> exscore.hit100
        CourseJudgment.HIT50 -> exscore.hit50
        CourseJudgment.HIT0 -> exscore.hit0
        CourseJudgment.SLIDERBREAK -> exscore.sliderbreak
        CourseJudgment.SLIDEREND -> exscore.sliderend
    }
}

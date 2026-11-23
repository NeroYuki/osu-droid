package com.osudroid.skillcourse.runtime

import com.osudroid.skillcourse.CourseDefinition
import kotlinx.serialization.Serializable

@Serializable
data class CourseEventRecord(
    val sequence: Int,
    val objectId: Int,
    val mapTimeMs: Long,
    val judgment: String
)

/** A native one-map statistic snapshot. Course totals never replace the native replay's statistic. */
@Serializable
data class CourseComponentResult(
    val originalIndex: Int,
    val playedIndex: Int,
    val mapHash: String,
    val score: Long,
    val maxCombo: Int,
    val hit300: Int,
    val hit100: Int,
    val hit50: Int,
    val misses: Int,
    val sliderHeadHits: Int,
    val sliderTickHits: Int,
    val sliderRepeatHits: Int,
    val sliderEndHits: Int,
    val modsJson: String,
    val rawExContribution: Long,
    val startingHp: Int?,
    val endingHp: Int?,
    val activeNanos: Long,
    val completed: Boolean,
    val replayFilename: String?,
    val replaySha256: String?,
    val eventsFilename: String?,
    val hit300k: Int = 0,
    val hit100k: Int = 0
) {
    val accuracyNumerator: Long
        get() = 300L * hit300 + 100L * hit100 + 50L * hit50

    val accuracyDenominator: Long
        get() = 300L * (hit300 + hit100 + hit50 + misses)
}

@Serializable
data class CourseResult(
    val clientAttemptId: String,
    val courseId: String,
    val courseHash: String,
    val definition: CourseDefinition,
    val playerName: String,
    val createdAt: Long,
    val outcome: String,
    val reason: String? = null,
    val rulesetVersion: Int = 1,
    val order: List<Int>,
    val components: List<CourseComponentResult>,
    val rawExTotal: Long,
    val activeNanos: Long,
    val replayDirectory: String,
    val additionalModsJson: String = "[]",
    val speeds: Map<Int, Float> = emptyMap(),
    /** Local storage status; excluded from the course identity and scoring rules. */
    val storageError: String? = null,
    /** Optional player additions keyed by authored index; old results use additionalModsJson. */
    val additionalModsByIndexJson: Map<Int, String> = emptyMap()
) {
    val nativeScore: Long
        get() = components.fold(0L) { total, component ->
            if (Long.MAX_VALUE - total < component.score) Long.MAX_VALUE else total + component.score
        }

    val publishedExScore: Long
        get() = rawExTotal.coerceAtLeast(0)

    val accuracyNumerator: Long
        get() = components.sumOf { it.accuracyNumerator }

    val accuracyDenominator: Long
        get() = components.sumOf { it.accuracyDenominator }

    val accuracy: Double
        get() = if (accuracyDenominator == 0L) 0.0 else accuracyNumerator.toDouble() / accuracyDenominator

    val largestMapCombo: Int
        get() = components.maxOfOrNull { it.maxCombo } ?: 0
}

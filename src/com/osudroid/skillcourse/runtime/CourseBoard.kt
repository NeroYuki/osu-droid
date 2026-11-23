package com.osudroid.skillcourse.runtime

import com.osudroid.data.SkillCourseAttemptEntity
import com.osudroid.skillcourse.CourseRepository
import com.osudroid.utils.updateThread
import kotlinx.serialization.json.Json
import ru.nsu.ccfit.zuev.osu.ToastLogger
import java.util.Locale

/** A ready-to-render course board row. All presentation strings are built off the update thread. */
data class CourseBoardRow(
    val rank: Int,
    val title: String,
    val detail: String,
    val mark: String,
    val attemptId: String,
    val playedIndex: Int?,
    val originalIndex: Int?,
    val result: CourseResult
)

/** Result of one guarded course board load. [message] is set when there is nothing to render. */
data class CourseBoardSnapshot(
    val mode: BoardMode,
    val rows: List<CourseBoardRow>,
    val message: String?
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

internal data class CourseAttempt(
    val entity: SkillCourseAttemptEntity,
    val result: CourseResult
)

internal data class ParsedCourseAttempts(
    val attempts: List<CourseAttempt>,
    val failed: Int
)

internal data class CourseMapComponent(
    val attempt: CourseAttempt,
    val component: CourseComponentResult
)

/**
 * Pure board construction and ordering rules.
 *
 * The course repositories intentionally return attempts for previous definitions of the same row;
 * [parseCourseAttempts] keeps only attempts whose frozen hash matches the active course hash.
 */
internal object CourseBoardModel {

    fun parseCourseAttempts(
        activeHash: String,
        attempts: List<SkillCourseAttemptEntity>,
        json: Json
    ): ParsedCourseAttempts {
        var failed = 0
        val parsed = ArrayList<CourseAttempt>(attempts.size)

        for (entity in attempts) {
            // listAttempts() includes history for the row, including older definition hashes.
            if (entity.courseHash != activeHash) {
                continue
            }
            if (entity.outcome == CourseAttemptStoreCore.UNFINISHED_OUTCOME) {
                continue
            }
            try {
                val result = json.decodeFromString<CourseResult>(entity.resultJson)
                if (result.courseHash != activeHash || result.outcome == CourseAttemptStoreCore.UNFINISHED_OUTCOME) {
                    continue
                }
                parsed += CourseAttempt(entity, result)
            } catch (_: Exception) {
                failed++
            }
        }

        return ParsedCourseAttempts(parsed, failed)
    }

    fun totalRows(definition: com.osudroid.skillcourse.CourseDefinition, attempts: List<CourseAttempt>): List<CourseBoardRow> =
        attempts.sortedWith(Comparator { first, second -> compareTotals(first, second, definition) })
            .mapIndexed { index, attempt ->
                val result = attempt.result
                totalRow(index + 1, definition, result)
            }

    fun mapRows(
        state: CourseViewState,
        selectedMapHash: String?,
        attempts: List<CourseAttempt>
    ): List<CourseBoardRow> {
        val authoredIndex = state.authoredIndexFor(selectedMapHash) ?: return emptyList()
        val components = ArrayList<CourseMapComponent>()
        for (attempt in attempts) {
            for (component in attempt.result.components) {
                if (component.originalIndex == authoredIndex &&
                    component.mapHash == state.definition.maps[authoredIndex].mapHash) {
                    components += CourseMapComponent(attempt, component)
                }
            }
        }

        return components
            .sortedWith(Comparator { first, second -> compareMapComponents(first, second, state.definition) })
            .mapIndexed { index, entry -> mapRow(index + 1, entry) }
    }

    fun compareTotals(
        first: CourseAttempt,
        second: CourseAttempt,
        definition: com.osudroid.skillcourse.CourseDefinition
    ): Int {
        val firstCompleted = first.result.outcome == "completed"
        val secondCompleted = second.result.outcome == "completed"
        if (firstCompleted != secondCompleted) {
            return if (firstCompleted) -1 else 1
        }

        val metric = second.result.totalMetric(definition.scoring)
            .compareTo(first.result.totalMetric(definition.scoring))
        if (metric != 0) {
            return metric
        }

        if (definition.isSpeedrun) {
            val time = first.result.activeNanos.compareTo(second.result.activeNanos)
            if (time != 0) {
                return time
            }
        }

        val created = second.result.createdAt.compareTo(first.result.createdAt)
        if (created != 0) {
            return created
        }
        return first.result.clientAttemptId.compareTo(second.result.clientAttemptId)
    }

    fun compareMapComponents(
        first: CourseMapComponent,
        second: CourseMapComponent,
        definition: com.osudroid.skillcourse.CourseDefinition
    ): Int {
        if (first.component.completed != second.component.completed) {
            return if (first.component.completed) -1 else 1
        }

        val metric = second.component.componentMetric(definition.scoring)
            .compareTo(first.component.componentMetric(definition.scoring))
        if (metric != 0) {
            return metric
        }

        if (definition.isSpeedrun) {
            val time = first.component.activeNanos.compareTo(second.component.activeNanos)
            if (time != 0) {
                return time
            }
        }

        val created = second.attempt.result.createdAt.compareTo(first.attempt.result.createdAt)
        if (created != 0) {
            return created
        }

        val attempt = first.attempt.result.clientAttemptId.compareTo(second.attempt.result.clientAttemptId)
        if (attempt != 0) {
            return attempt
        }
        return first.component.playedIndex.compareTo(second.component.playedIndex)
    }

    private fun totalRow(
        rank: Int,
        definition: com.osudroid.skillcourse.CourseDefinition,
        result: CourseResult
    ): CourseBoardRow {
        val mapsPlayed = result.components.size
        val mapsTotal = definition.maps.size
        val outcome = result.outcome.replace('_', ' ')
        val title = buildString {
            append('#').append(rank).append(' ').append(outcome)
            append(" · ").append(mapsPlayed).append('/').append(mapsTotal).append(" maps")
            append('\n').append(result.totalMetricText(definition.scoring))
        }
        val detail = buildString {
            append(result.playerName)
            append('\n').append(formatAccuracy(result.accuracy))
            append('\n').append(formatCompactDuration(result.activeNanos))
        }
        return CourseBoardRow(
            rank = rank,
            title = title,
            detail = detail,
            mark = result.totalGrade(),
            attemptId = result.clientAttemptId,
            playedIndex = null,
            originalIndex = null,
            result = result
        )
    }

    private fun mapRow(rank: Int, entry: CourseMapComponent): CourseBoardRow {
        val component = entry.component
        val result = entry.attempt.result
        val state = when {
            !component.completed -> "Partial"
            result.outcome == "completed" -> "Completed"
            else -> result.outcome.replace('_', ' ')
        }
        val title = buildString {
            append('#').append(rank).append(' ').append(result.playerName)
            append(" · ").append(state)
            append('\n').append(component.componentMetricText(result.definition.scoring))
        }
        val detail = buildString {
            append(component.modsOrEmpty().toDisplayModString(false))
            append('\n').append(formatAccuracy(component.componentAccuracy))
            append('\n').append("EX ").append(formatSigned(component.rawExContribution))
            append(" · ").append(formatCompactDuration(component.activeNanos))
        }
        return CourseBoardRow(
            rank = rank,
            title = title,
            detail = detail,
            mark = component.nativeGrade(),
            attemptId = result.clientAttemptId,
            playedIndex = component.playedIndex,
            originalIndex = component.originalIndex,
            result = result
        )
    }
}

//region Pure metric and formatting helpers

internal fun CourseResult.totalMetric(scoring: String): Double = when (scoring) {
    "exscore" -> publishedExScore.toDouble()
    "accuracy" -> accuracy
    else -> nativeScore.toDouble()
}

internal fun CourseResult.totalMetricText(scoring: String): String = when (scoring) {
    "exscore" -> "EX ${publishedExScore}"
    "accuracy" -> "Accuracy ${formatAccuracy(accuracy)}"
    else -> "Score ${formatScoreValue(nativeScore)}"
}

internal val CourseComponentResult.componentAccuracy: Double
    get() = if (accuracyDenominator == 0L) 0.0 else accuracyNumerator.toDouble() / accuracyDenominator

internal fun CourseComponentResult.componentMetric(scoring: String): Double = when (scoring) {
    "exscore" -> rawExContribution.toDouble()
    "accuracy" -> componentAccuracy
    else -> score.toDouble()
}

internal fun CourseComponentResult.componentMetricText(scoring: String): String = when (scoring) {
    // Component EX is a signed contribution; published total EX is clamped separately.
    "exscore" -> "EX ${formatSigned(rawExContribution)}"
    "accuracy" -> "Accuracy ${formatAccuracy(componentAccuracy)}"
    else -> "Score ${formatScoreValue(score)}"
}

/** Pooled grade for a whole attempt. Unplayed or unjudged attempts never invent an S/X grade. */
internal fun CourseResult.totalGrade(): String {
    val hit300 = components.sumOf { it.hit300.toLong() }
    val hit100 = components.sumOf { it.hit100.toLong() }
    val hit50 = components.sumOf { it.hit50.toLong() }
    val misses = components.sumOf { it.misses.toLong() }
    val total = hit300 + hit100 + hit50 + misses
    if (total <= 0L) {
        return "D"
    }

    val hidden = components.any { component ->
        val mods = component.modsOrEmpty()
        mods.values.any { it is com.osudroid.mods.ModHidden || it is com.osudroid.mods.ModFlashlight }
    }

    if (hit100 == 0L && hit50 == 0L && misses == 0L) {
        return if (hidden) "XH" else "X"
    }
    if (hit300.toDouble() / total > 0.9 && misses == 0L && hit50.toDouble() / total < 0.01) {
        return if (hidden) "SH" else "S"
    }
    if ((hit300.toDouble() / total > 0.8 && misses == 0L) || hit300.toDouble() / total > 0.9) {
        return "A"
    }
    if ((hit300.toDouble() / total > 0.7 && misses == 0L) || hit300.toDouble() / total > 0.8) {
        return "B"
    }
    if (hit300.toDouble() / total > 0.6) {
        return "C"
    }
    return "D"
}

internal fun formatScoreValue(score: Long): String =
    String.format(Locale.US, "%,d", score.coerceAtLeast(0L)).replace(',', ' ')

//endregion

/**
 * Loads and opens local course boards.
 *
 * [load] is deliberately database-only and safe to call from [ScoreBoard]'s loader executor.
 * [open] only reuses the existing read-only result screen; it never writes or sends a score.
 */
object CourseBoardLoader {

    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun load(mode: BoardMode, state: CourseViewState?, selectedMapHash: String?): CourseBoardSnapshot {
        if (!mode.isCourse) {
            return CourseBoardSnapshot(mode, emptyList(), "")
        }
        if (state == null) {
            return CourseBoardSnapshot(mode, emptyList(), BoardModePolicy.COURSE_VIEW_UNAVAILABLE)
        }

        val attempts = try {
            CourseRepository.listAttempts(state.courseId, state.courseHash)
        } catch (_: Exception) {
            return CourseBoardSnapshot(mode, emptyList(), BoardModePolicy.COURSE_ATTEMPTS_UNREADABLE)
        }

        val parsed = CourseBoardModel.parseCourseAttempts(state.courseHash, attempts, json)
        val mapSpecific = mode == BoardMode.COURSE_MAP
        val rows = when (mode) {
            BoardMode.COURSE_TOTAL -> CourseBoardModel.totalRows(state.definition, parsed.attempts)
            BoardMode.COURSE_MAP -> CourseBoardModel.mapRows(state, selectedMapHash, parsed.attempts)
            else -> emptyList()
        }

        val message = when {
            rows.isNotEmpty() -> null
            mapSpecific && state.authoredIndexFor(selectedMapHash) == null ->
                "This map is not part of the active course."
            else -> BoardModePolicy.emptyMessage(
                mode,
                mapSpecific = mapSpecific,
                unreadable = parsed.failed > 0 && parsed.attempts.isEmpty()
            )
        }

        return CourseBoardSnapshot(mode, rows, message)
    }

    @JvmStatic
    fun open(row: CourseBoardRow) {
        updateThread {
            try {
                CourseResults.show(row.result)
            } catch (e: Exception) {
                ToastLogger.showText("Unable to open this course result: ${e.message}", true)
            }
        }
    }
}

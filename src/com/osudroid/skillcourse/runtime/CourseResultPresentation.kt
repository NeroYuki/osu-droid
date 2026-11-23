package com.osudroid.skillcourse.runtime

import com.osudroid.mods.ModFlashlight
import com.osudroid.mods.ModHidden
import com.osudroid.skillcourse.CourseMap
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.deserializeMods
import org.anddev.andengine.opengl.font.Font
import ru.nsu.ccfit.zuev.osu.Config
import java.util.Locale
import kotlin.math.min

/** Aggregated native judgments shown by the course result overview. */
internal data class CourseAggregateCounts(
    val score: Long,
    val maxCombo: Int,
    val accuracy: Double,
    val hit300: Long,
    val hit100: Long,
    val hit50: Long,
    val misses: Long,
    val hit300k: Long,
    val hit100k: Long
)

/** One page of the course breakdown in realized play order. */
internal data class CourseBreakdownPage(
    val playedIndex: Int,
    val originalIndex: Int,
    val map: CourseMap?,
    val component: CourseComponentResult?
) {
    val isPlayed: Boolean get() = component != null
    val isPartial: Boolean get() = component?.completed == false
}

internal fun CourseResult.aggregateCounts() = CourseAggregateCounts(
    score = nativeScore,
    maxCombo = largestMapCombo,
    accuracy = accuracy,
    hit300 = components.sumOf { it.hit300.toLong() },
    hit100 = components.sumOf { it.hit100.toLong() },
    hit50 = components.sumOf { it.hit50.toLong() },
    misses = components.sumOf { it.misses.toLong() },
    hit300k = components.sumOf { it.hit300k.toLong() },
    hit100k = components.sumOf { it.hit100k.toLong() }
)

/**
 * Realized pages for the component panel.
 *
 * [CourseResult.order] is the authored play order (shuffled when the course requested it), so
 * played components naturally form its prefix. Every remaining entry is still a page, but it is
 * explicitly unplayed and never receives invented judgments, grades, or a replay action.
 */
internal fun CourseResult.breakdownPages(): List<CourseBreakdownPage> {
    val order = this.order.ifEmpty { definition.maps.indices.toList() }
    val byPlayedIndex = components.associateBy { it.playedIndex }
    return order.mapIndexed { playedIndex, originalIndex ->
        CourseBreakdownPage(
            playedIndex = playedIndex,
            originalIndex = originalIndex,
            map = definition.maps.getOrNull(originalIndex),
            component = byPlayedIndex[playedIndex]
        )
    }
}

internal fun CourseResult.isFailedOutcome(): Boolean = outcome != "completed"

internal fun CourseComponentResult.modsOrEmpty(): ModHashMap = try {
    deserializeMods(modsJson)
} catch (_: Exception) {
    ModHashMap()
}

/** Mirrors StatisticV2's native grade calculation without fabricating a grade for unplayed maps. */
internal fun CourseComponentResult.nativeGrade(): String {
    val total = hit300 + hit100 + hit50 + misses
    if (total <= 0) {
        return "D"
    }

    val mods = modsOrEmpty()
    val hidden = mods.values.any { it is ModHidden || it is ModFlashlight }

    if (hit100 == 0 && hit50 == 0 && misses == 0) {
        return if (hidden) "XH" else "X"
    }
    if (hit300 / total.toFloat() > 0.9f && misses == 0 && hit50 / total.toFloat() < 0.01f) {
        return if (hidden) "SH" else "S"
    }
    if ((hit300 / total.toFloat() > 0.8f && misses == 0) || hit300 / total.toFloat() > 0.9f) {
        return "A"
    }
    if ((hit300 / total.toFloat() > 0.7f && misses == 0) || hit300 / total.toFloat() > 0.8f) {
        return "B"
    }
    if (hit300 / total.toFloat() > 0.6f) {
        return "C"
    }
    return "D"
}

internal fun CourseMap.displayArtist(): String =
    if (Config.isForceRomanized()) artist else artistUnicode.ifBlank { artist }

internal fun CourseMap.displayTitle(): String =
    if (Config.isForceRomanized()) title else titleUnicode.ifBlank { title }

internal fun formatAccuracy(accuracy: Double): String =
    String.format(Locale.US, "%.2f%%", accuracy * 100)

internal fun formatSigned(value: Long): String = if (value > 0) "+$value" else value.toString()

/** Compact active-time text used by speedrun results and panel extras. */
internal fun formatCompactDuration(nanos: Long): String {
    val totalSeconds = (nanos.coerceAtLeast(0L) / 1_000_000_000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val millis = nanos.coerceAtLeast(0L) / 1_000_000L % 1000L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    } else {
        String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, millis)
    }
}

/** Shortens a line to the measured width of the supplied skin font. */
internal fun ellipsize(text: String, font: Font?, maxWidth: Float): String {
    if (text.isEmpty() || font == null || maxWidth <= 0f) {
        return text
    }
    return ellipsize(text, maxWidth) { codePoint ->
        font.getLetter(codePointText(codePoint)).mAdvance.toFloat()
    }
}

/**
 * [ellipsize] with an injectable advance measurement.
 *
 * Iteration is code-point based so a truncated line can never end with a lone UTF-16 surrogate
 * from a split supplementary-plane character (for example an emoji).
 */
internal fun ellipsize(text: String, maxWidth: Float, advanceOf: (Int) -> Float): String {
    if (text.isEmpty() || maxWidth <= 0f) {
        return text
    }

    val codePoints = text.codePointsArray()

    fun measure(points: IntArray): Float {
        var measured = 0f
        for (point in points) {
            measured += advanceOf(point)
        }
        return measured
    }

    if (measure(codePoints) <= maxWidth) {
        return text
    }

    val ellipsis = "..."
    val ellipsisCodePoints = ellipsis.codePointsArray()
    val ellipsisWidth = measure(ellipsisCodePoints)
    if (ellipsisWidth >= maxWidth) {
        return ellipsis
    }

    val result = StringBuilder()
    var width = ellipsisWidth
    for (point in codePoints) {
        val advance = advanceOf(point)
        if (width + advance > maxWidth) {
            break
        }
        result.appendCodePoint(point)
        width += advance
    }
    return result.toString().trimEnd() + ellipsis
}

private fun String.codePointsArray(): IntArray {
    val codePointCount = codePointCount(0, length)
    val codePoints = IntArray(codePointCount)
    var stringIndex = 0
    for (index in 0 until codePointCount) {
        val codePoint = codePointAt(stringIndex)
        codePoints[index] = codePoint
        stringIndex += Character.charCount(codePoint)
    }
    return codePoints
}

private fun codePointText(codePoint: Int): String =
    StringBuilder().appendCodePoint(codePoint).toString()

/** Native score textures are sized for the skin's score prefix. */
internal fun scoreDigitsText(score: Long, padTo: Int = 0): String {
    val value = score.coerceAtLeast(0L).toString()
    return if (value.length >= padTo) value else "0".repeat(padTo - value.length) + value
}

internal fun safePanelWidth(): Float = min(Config.getRES_WIDTH() * 0.88f, 920f)

internal fun safePanelHeight(): Float = min(Config.getRES_HEIGHT() * 0.82f, 396f)

package com.osudroid.skillcourse.ui

import kotlin.math.roundToInt

/**
 * Small, Android-free UI rules shared by the course manager, editor, preflight and file boundary.
 *
 * Keeping these rules here makes the parts that must stay consistent (speed steps, display-text
 * bounds and the portable export filename) directly testable without inflating a fragment.
 */
internal object CourseUiLogic {

    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.0f
    const val SPEED_STEP = 0.05f
    const val SPEED_STEP_COUNT = 51

    fun speedAt(index: Int): Float = (50 + index.coerceIn(0, SPEED_STEP_COUNT - 1) * 5) / 100f

    fun speedIndex(speed: Float): Int {
        if (!speed.isFinite()) {
            return SPEED_STEP_COUNT / 2
        }
        return ((speed - MIN_SPEED) / SPEED_STEP).roundToInt().coerceIn(0, SPEED_STEP_COUNT - 1)
    }

    fun isValidDisplayText(value: String): Boolean {
        if (value.isEmpty()) {
            return false
        }
        if (value.codePointCount(0, value.length) > 128) {
            return false
        }
        for (index in 0 until value.length) {
            if (Character.isISOControl(value[index])) {
                return false
            }
        }
        return true
    }

    fun suggestedFileName(courseName: String): String {
        val base = courseName
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trim('.')
            .take(64)
            .ifBlank { "course" }
        return base + CourseFiles.EXTENSION
    }
}

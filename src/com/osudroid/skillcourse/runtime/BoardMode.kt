package com.osudroid.skillcourse.runtime

/**
 * The explicit leaderboard mode shown by the song menu.
 *
 * Ordinary offline play only ever has [LOCAL_MAP]. When the ordinary online panel is available the
 * user may cycle local -> online score -> online PP. While a course projection is active the board
 * is a local course board and cycles between the selected map's components and the whole course.
 */
enum class BoardMode {
    LOCAL_MAP,
    ONLINE_SCORE,
    ONLINE_PP,
    COURSE_MAP,
    COURSE_TOTAL;

    val isCourse: Boolean
        get() = this == COURSE_MAP || this == COURSE_TOTAL

    val isOnline: Boolean
        get() = this == ONLINE_SCORE || this == ONLINE_PP
}

/**
 * Pure navigation rules for [BoardMode].
 *
 * The production song menu never invents an ordinary online mode while offline, never issues an
 * online request from a course view, and restores the ordinary mode after leaving a course view.
 */
object BoardModePolicy {

    const val EXIT_COURSE_VIEW_HINT = "Press Back to exit course view"

    const val ONLINE_PP_UNAVAILABLE =
        "PP leaderboard unavailable.\nThe server does not provide PP values."

    const val COURSE_VIEW_UNAVAILABLE = "The course view is no longer active."

    const val NO_COURSE_ATTEMPTS = "No local attempts for this course yet."

    const val NO_COURSE_MAP_ATTEMPTS = "No local attempts for this map yet."

    const val COURSE_ATTEMPTS_UNREADABLE = "Unable to read the local course attempts."

    /** Mode shown after login/logout, course enter/exit, or selection changes. */
    @JvmStatic
    fun normalize(mode: BoardMode, online: Boolean, courseActive: Boolean): BoardMode {
        if (courseActive) {
            return if (mode.isCourse) mode else BoardMode.COURSE_MAP
        }
        if (mode.isCourse) {
            return BoardMode.LOCAL_MAP
        }
        if (!online && mode.isOnline) {
            return BoardMode.LOCAL_MAP
        }
        return mode
    }

    /** The next mode produced by the scoring switcher. */
    @JvmStatic
    fun next(mode: BoardMode, online: Boolean, courseActive: Boolean): BoardMode {
        val current = normalize(mode, online, courseActive)
        if (courseActive) {
            return if (current == BoardMode.COURSE_TOTAL) BoardMode.COURSE_MAP else BoardMode.COURSE_TOTAL
        }
        if (!online) {
            return BoardMode.LOCAL_MAP
        }
        return when (current) {
            BoardMode.LOCAL_MAP -> BoardMode.ONLINE_SCORE
            BoardMode.ONLINE_SCORE -> BoardMode.ONLINE_PP
            else -> BoardMode.LOCAL_MAP
        }
    }

    /**
     * Ordinary offline boards have no switch. Ordinary online and every course board are cyclable;
     * an offline course view is intentionally cyclable because both course boards are local.
     */
    @JvmStatic
    fun isCycleVisible(mode: BoardMode, online: Boolean, courseActive: Boolean): Boolean =
        if (courseActive) true else online && !mode.isCourse

    @JvmStatic
    fun label(mode: BoardMode): String = when (mode) {
        BoardMode.LOCAL_MAP -> "Local map scores"
        BoardMode.ONLINE_SCORE -> "Online scores"
        BoardMode.ONLINE_PP -> "Online PP (unavailable)"
        BoardMode.COURSE_MAP -> "Course map scores"
        BoardMode.COURSE_TOTAL -> "Course total scores"
    }

    @JvmStatic
    fun emptyMessage(mode: BoardMode, mapSpecific: Boolean, unreadable: Boolean): String = when {
        mode == BoardMode.ONLINE_PP -> ONLINE_PP_UNAVAILABLE
        mode.isCourse && unreadable -> COURSE_ATTEMPTS_UNREADABLE
        mode.isCourse && mapSpecific -> NO_COURSE_MAP_ATTEMPTS
        mode.isCourse -> NO_COURSE_ATTEMPTS
        else -> ""
    }
}

/**
 * Testable state machine for preserving/restoring the ordinary mode while a course view is open.
 *
 * [SongMenu] delegates every mode transition to this object, so the same matrix is exercised by
 * the JVM tests instead of relying on Android UI state.
 */
class BoardModeSession @JvmOverloads constructor(initial: BoardMode = BoardMode.LOCAL_MAP) {

    var mode: BoardMode = initial
        private set

    var ordinaryMode: BoardMode? = null
        private set

    fun normalize(online: Boolean, courseActive: Boolean): BoardMode {
        mode = BoardModePolicy.normalize(mode, online, courseActive)
        return mode
    }

    fun toggle(online: Boolean, courseActive: Boolean): BoardMode {
        mode = BoardModePolicy.next(mode, online, courseActive)
        return mode
    }

    /** Saves the normalized ordinary mode once and shows the selected-map course board. */
    fun enterCourse(online: Boolean): BoardMode {
        if (ordinaryMode == null) {
            ordinaryMode = BoardModePolicy.normalize(mode, online, false)
        }
        mode = BoardMode.COURSE_MAP
        return mode
    }

    /** Restores the saved ordinary mode, normalizing an online mode that was lost meanwhile. */
    fun exitCourse(online: Boolean): BoardMode {
        val restored = ordinaryMode ?: BoardMode.LOCAL_MAP
        ordinaryMode = null
        mode = BoardModePolicy.normalize(restored, online, false)
        return mode
    }
}

package com.osudroid.skillcourse.ui

import com.reco1l.andengine.text.FontAwesomeIcon
import com.reco1l.andengine.theme.Icon
import com.reco1l.andengine.ui.UITextButton
import ru.nsu.ccfit.zuev.osu.ResourceManager
import ru.nsu.ccfit.zuev.osu.helper.StringTable
import ru.nsu.ccfit.zuev.osuplus.R

/**
 * Native song-menu entry points for the local course feature.
 *
 * The button is deliberately built from the same material theme primitives as the mod menu so it
 * picks up the active skin/theme instead of being an unthemed rectangle.
 */
object CourseNavigation {

    @JvmStatic
    fun createManagerButton(onClick: Runnable): UITextButton = UITextButton().apply {
        width = 214f
        height = 48f
        text = StringTable.get(R.string.skill_course_manager_title)
        font = ResourceManager.getInstance().getFont("smallFont")
        leadingIcon = FontAwesomeIcon(Icon.List).apply {
            iconSize = 18f
        }
        onActionUp = { onClick.run() }
    }

    @JvmStatic
    fun exitCourseViewHint(): String = com.osudroid.skillcourse.runtime.BoardModePolicy.EXIT_COURSE_VIEW_HINT
}

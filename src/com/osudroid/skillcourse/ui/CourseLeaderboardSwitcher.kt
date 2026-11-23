package com.osudroid.skillcourse.ui

import com.osudroid.skillcourse.runtime.BoardMode
import com.reco1l.andengine.sprite.UISprite
import org.anddev.andengine.input.touch.TouchEvent
import org.anddev.andengine.opengl.texture.region.TextureRegion
import ru.nsu.ccfit.zuev.osu.ResourceManager

/**
 * Builds the compact top-left leaderboard mode switcher.
 *
 * The switcher reuses the ranking icons that are loaded by [ResourceManager.loadSkin], so the
 * course-specific modes no longer reference a texture that does not exist. Cycling itself remains
 * owned by [com.osudroid.skillcourse.runtime.BoardModeSession] through the supplied callback.
 */
object CourseLeaderboardSwitcher {

    private const val HOME = "ranking_disabled"
    private const val SCORE = "ranking_enabled_score"
    private const val PP = "ranking_enabled_pp"

    /** The already loaded texture shown for [mode]. */
    @JvmStatic
    fun textureName(mode: BoardMode): String = when (mode) {
        BoardMode.LOCAL_MAP, BoardMode.COURSE_MAP -> HOME
        BoardMode.COURSE_TOTAL, BoardMode.ONLINE_SCORE -> SCORE
        BoardMode.ONLINE_PP -> PP
    }

    /** Creates the switcher at the icon's native size. The caller owns scene registration. */
    @JvmStatic
    fun create(onCycle: Runnable): UISprite =
        create(ResourceManager.getInstance().getTexture(HOME), onCycle)

    internal fun create(textureRegion: TextureRegion, onCycle: Runnable): UISprite =
        SwitcherSprite(textureRegion, onCycle)
}

private class SwitcherSprite(
    textureRegion: TextureRegion,
    private val onCycle: Runnable
) : UISprite(textureRegion) {

    private var pressed = false
    private var draggedOut = false

    init {
        width = textureRegion.width.toFloat()
        height = textureRegion.height.toFloat()
    }

    override fun onAreaTouched(event: TouchEvent, localX: Float, localY: Float): Boolean {
        when {
            event.isActionDown -> {
                pressed = isInside(localX, localY)
                draggedOut = !pressed
            }

            event.isActionMove -> {
                if (pressed && !isInside(localX, localY)) {
                    pressed = false
                    draggedOut = true
                }
            }

            event.isActionUp -> {
                val shouldCycle = pressed && !draggedOut && isInside(localX, localY)
                pressed = false
                draggedOut = false
                if (shouldCycle) {
                    onCycle.run()
                }
            }

            event.isActionCancel || event.isActionOutside -> {
                pressed = false
                draggedOut = false
            }
        }
        return true
    }

    private fun isInside(x: Float, y: Float): Boolean =
        x >= 0f && y >= 0f && x <= width && y <= height
}

package com.osudroid.skillcourse.runtime

import com.reco1l.andengine.Anchor
import com.reco1l.andengine.text.UIText
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.ResourceManager
import ru.nsu.ccfit.zuev.osu.game.GameScene
import java.util.Locale

/** Transient course information is not added to or saved in the user's HUD layout. */
class CourseHudOverlay(private val game: GameScene) : UIText() {
    init {
        font = ResourceManager.getInstance().getFont("smallFont")
        origin = Anchor.TopCenter
        x = Config.getRES_WIDTH() / 2f
        y = 48f
    }

    override fun onManagedUpdate(deltaTimeSec: Float) {
        val heading = CourseRuntime.progressText().take(80)
        val metric = CourseRuntime.liveMetric(game.stat)
        val duration = if (CourseRuntime.isSpeedrun()) String.format(Locale.US, "  %.3fs", CourseRuntime.activeTimeMillis() / 1000.0) else ""
        val next = "$heading\n$metric$duration"
        if (text != next) text = next
        super.onManagedUpdate(deltaTimeSec)
    }
}

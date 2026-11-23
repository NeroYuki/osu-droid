package com.osudroid.ui.v2.hud.elements

import com.osudroid.ui.v2.hud.HUDElement
import com.osudroid.ui.v2.SpriteFont
import com.osudroid.skillcourse.runtime.CourseRuntime
import com.reco1l.andengine.text.UIText
import ru.nsu.ccfit.zuev.osu.ResourceManager
import com.rian.framework.RollingLongCounter
import ru.nsu.ccfit.zuev.osu.game.GameScene
import ru.nsu.ccfit.zuev.skins.OsuSkin
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class HUDScoreCounter : HUDElement() {

    private val sprite = SpriteFont(OsuSkin.get().scorePrefix)
    private val courseSign = UIText().apply {
        font = ResourceManager.getInstance().getFont("smallFont")
        text = "-"
        isVisible = false
    }
    private val format = DecimalFormat("00000000", DecimalFormatSymbols(Locale.US))

    private var value = 0L
        set(value) {
            if (field != value) {
                field = value
                sprite.text = format.format(value)
            }
        }

    private val counter = RollingLongCounter(0L).apply { rollingDuration = 1f }

    init {
        sprite.spacing = -OsuSkin.get().scoreOverlap

        val digitRange = '0'..'9'
        val maxDigitWidth = digitRange.maxOfOrNull { sprite.characters[it]?.width?.toFloat() ?: 0f } ?: 0f

        sprite.fixedCharWidths = digitRange.associateWith { maxDigitWidth }
        sprite.text = format.format(0)

        registerUpdateHandler(counter)
        attachChild(sprite)
        attachChild(courseSign)
        onContentChanged()
    }

    override fun onGameplayUpdate(game: GameScene, secondsElapsed: Float) {
        if (CourseRuntime.scoringMode() == "exscore") {
            val raw = CourseRuntime.liveExScore()
            courseSign.isVisible = raw < 0
            courseSign.x = -courseSign.width - 4f
            value = if (raw < 0) -raw else raw
        } else {
            courseSign.isVisible = false
            counter.targetValue = game.stat.totalScoreWithMultiplier
        }
    }

    override fun onManagedUpdate(deltaTimeSec: Float) {
        if (CourseRuntime.scoringMode() != "exscore") value = counter.currentValue

        super.onManagedUpdate(deltaTimeSec)
    }
}

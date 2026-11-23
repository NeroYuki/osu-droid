package com.osudroid.skillcourse.runtime

import com.reco1l.andengine.Anchor
import com.reco1l.andengine.Axes
import com.reco1l.andengine.UIScene
import com.reco1l.andengine.container.UIContainer
import com.reco1l.andengine.component.*
import com.reco1l.andengine.text.UIText
import com.reco1l.andengine.ui.UITextButton
import com.reco1l.framework.Color4
import org.anddev.andengine.entity.primitive.Rectangle
import org.anddev.andengine.entity.scene.background.SpriteBackground
import org.anddev.andengine.entity.sprite.Sprite
import org.anddev.andengine.input.touch.TouchEvent
import org.anddev.andengine.opengl.font.Font
import org.anddev.andengine.opengl.texture.region.TextureRegion
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.ResourceManager
import ru.nsu.ccfit.zuev.osu.Utils
import ru.nsu.ccfit.zuev.osu.scoring.ScoreNumber

/** Actions owned by [CourseResults]; the presenter itself never persists or submits anything. */
internal class CourseResultActions(
    val onBack: () -> Unit,
    val onBreakdown: () -> Unit,
    val onRetryCourse: () -> Unit,
    val onRetrySaving: (() -> Unit)? = null
)

/**
 * Builds the read-only course result scene.
 *
 * The layout mirrors [ru.nsu.ccfit.zuev.osu.scoring.ScoringScene.load] so the same skin assets,
 * six judgment icons, score-number digits, accuracy and combo positions are used. The ordinary
 * score save/upload path is never invoked.
 */
internal object CourseResultPresenter {

    private const val VERDICT_HEIGHT = 48f
    private const val VERDICT_SPACING = 4f
    private const val MAPS_HEIGHT = 28f
    private const val STATUS_SHADOW_OFFSET = 1.75f

    fun render(result: CourseResult, actions: CourseResultActions): UIScene {
        val resources = ResourceManager.getInstance()
        val scene = UIScene()
        val aggregate = result.aggregateCounts()

        val backgroundTexture = resources.getTextureIfLoaded("::background")
            ?: resources.getTexture("menu-background")
        val backgroundHeight = backgroundTexture.height.toFloat() *
            Config.getRES_WIDTH() / backgroundTexture.width.toFloat()
        scene.setBackground(
            SpriteBackground(
                Sprite(
                    0f,
                    (Config.getRES_HEIGHT() - backgroundHeight) / 2f,
                    Config.getRES_WIDTH().toFloat(),
                    backgroundHeight,
                    backgroundTexture
                )
            )
        )

        val topHeader = Rectangle(
            0f,
            0f,
            Config.getRES_WIDTH().toFloat(),
            Utils.toRes(100f)
        )
        topHeader.setColor(0f, 0f, 0f, 0.8f)
        scene.attachChild(topHeader)

        val scaledContainer = UIContainer().apply {
            width = Config.getRES_WIDTH().toFloat()
            height = Config.getRES_HEIGHT().toFloat()
            x = 0f
            y = 100f
        }
        scene.attachChild(scaledContainer)

        val panelTexture = resources.getTexture("ranking-panel")
        scaledContainer.attachChild(
            Sprite(
                0f,
                0f,
                Utils.toRes(panelTexture.width * 0.9f),
                Utils.toRes(panelTexture.height * 0.9f),
                panelTexture
            )
        )

        fun judgmentIcon(textureName: String, centerX: Float, centerY: Float) {
            val texture = resources.getTexture(textureName)
            val iconWidth = Utils.toRes(texture.width.toFloat())
            val iconHeight = Utils.toRes(texture.height.toFloat())
            scaledContainer.attachChild(
                Sprite(
                    Utils.toRes(centerX - iconWidth / 2f),
                    Utils.toRes(centerY - iconHeight / 2f),
                    iconWidth,
                    iconHeight,
                    texture
                )
            )
        }

        judgmentIcon("hit300", 70f, 130f)
        judgmentIcon("hit100", 70f, 222f)
        judgmentIcon("hit50", 70f, 314f)
        judgmentIcon("hit300g", 340f, 130f)
        judgmentIcon("hit100k", 340f, 222f)
        judgmentIcon("hit0", 340f, 314f)

        fun scoreNumber(x: Float, y: Float, text: String): ScoreNumber =
            ScoreNumber(Utils.toRes(x), Utils.toRes(y), text, 1f, false).also {
                it.attachToScene(scaledContainer)
            }

        val mainMetric = when (result.definition.scoring) {
            "exscore" -> scoreDigitsText(result.publishedExScore)
            "accuracy" -> formatAccuracy(result.accuracy)
            else -> scoreDigitsText(result.nativeScore, padTo = 8)
        }

        scoreNumber(220f, 18f, mainMetric)
        scoreNumber(138f, 110f, "${aggregate.hit300}x")
        scoreNumber(138f, 195f, "${aggregate.hit100}x")
        scoreNumber(138f, 280f, "${aggregate.hit50}x")
        scoreNumber(400f, 110f, "${aggregate.hit300k}x")
        scoreNumber(400f, 195f, "${aggregate.hit100k}x")
        scoreNumber(400f, 280f, "${aggregate.misses}x")

        val maxComboLabel = Sprite(
            Utils.toRes(20f),
            Utils.toRes(332f),
            resources.getTexture("ranking-maxcombo")
        )
        scaledContainer.attachChild(maxComboLabel)
        val accuracyLabel = Sprite(
            Utils.toRes(260f),
            Utils.toRes(332f),
            resources.getTexture("ranking-accuracy")
        )
        scaledContainer.attachChild(accuracyLabel)

        scoreNumber(20f, maxComboLabel.y + 38f, "${aggregate.maxCombo}x")
        scoreNumber(260f, accuracyLabel.y + 38f, formatAccuracy(aggregate.accuracy))

        val rankingTexture = resources.getTexture("ranking-title")
        scene.attachChild(
            Sprite(
                Config.getRES_WIDTH() * 5f / 6f - rankingTexture.width / 2f,
                0f,
                rankingTexture
            )
        )

        attachMetadata(scene, result)

        if (result.storageError != null) {
            val errorWidth = (Config.getRES_WIDTH() * 0.58f).coerceAtMost(
                Config.getRES_WIDTH() - Utils.toRes(380f) - 70f
            ).coerceAtLeast(220f)
            scene.attachChild(UIText().apply {
                x = 30f
                y = Config.getRES_HEIGHT() - 78f
                width = errorWidth
                height = 48f
                font = resources.getFont("smallFont")
                text = ellipsize(
                    "Result not saved: ${result.storageError}",
                    font,
                    errorWidth
                )
                autoScrollAxes = Axes.None
                color = Color4(1f, 0.45f, 0.45f)
            })
        }

        val backButton = textureButton(resources.getTexture("ranking-back")) { actions.onBack() }
        backButton.setPosition(
            Config.getRES_WIDTH() - backButton.width - 10f,
            Config.getRES_HEIGHT() - backButton.height - 10f
        )
        scene.attachChild(backButton)
        scene.registerTouchArea(backButton)

        val retryButton = textureButton(resources.getTexture("ranking-retry")) { actions.onRetryCourse() }
        retryButton.setPosition(backButton.x, backButton.y - retryButton.height - 10f)
        scene.attachChild(retryButton)
        scene.registerTouchArea(retryButton)

        var actionY = retryButton.y - 70f
        fun actionButton(text: String, onAction: () -> Unit): UITextButton {
            val button = UITextButton().apply {
                this.text = text
                x = backButton.x
                y = actionY
                width = backButton.width
                height = 58f
                onActionUp = onAction
            }
            scene.attachChild(button)
            actionY -= 68f
            return button
        }

        val breakdownButton = actionButton("Breakdown") { actions.onBreakdown() }
        actions.onRetrySaving?.let { retry ->
            // Keep the title-to-Breakdown gap available for the verdict, including save failures.
            val retrySavingButton = actionButton("Retry saving") { retry() }
            val actionWidth = (backButton.width - 8f) / 2f
            breakdownButton.width = actionWidth
            retrySavingButton.apply {
                x = breakdownButton.x + actionWidth + 8f
                y = breakdownButton.y
                width = actionWidth
            }
        }

        // The aggregate verdict is centered between the ranking title asset and the Breakdown
        // action, both measured from the actual laid-out components rather than fixed offsets.
        val statusWidth = Config.getRES_WIDTH() * 0.30f
        val statusHeight = VERDICT_HEIGHT + VERDICT_SPACING + MAPS_HEIGHT
        val rankingBottom = rankingTexture.height.toFloat()
        val statusY = (
            (rankingBottom + breakdownButton.y - statusHeight) / 2f
        ).coerceAtLeast(rankingBottom)

        fun statusLine(
            lineText: String,
            lineFont: Font?,
            lineHeight: Float,
            lineY: Float,
            lineColor: Color4
        ): UIContainer = UIContainer().apply {
            x = 0f
            y = lineY
            width = statusWidth
            height = lineHeight

            // UIText has no built-in shadow, so each line is drawn twice with an offset dark copy.
            // The two texts share a container instead of being stacked in a linear flow, keeping
            // the measured native height at exactly these line heights.
            attachChild(UIText().apply {
                x = STATUS_SHADOW_OFFSET
                y = STATUS_SHADOW_OFFSET
                width = statusWidth
                height = lineHeight
                alignment = Anchor.Center
                font = lineFont
                text = lineText
                autoScrollAxes = Axes.None
                color = Color4(0f, 0f, 0f, 0.7f)
            })
            attachChild(UIText().apply {
                x = 0f
                y = 0f
                width = statusWidth
                height = lineHeight
                alignment = Anchor.Center
                font = lineFont
                text = lineText
                autoScrollAxes = Axes.None
                color = lineColor
            })
        }

        val statusContainer = UIContainer().apply {
            x = Config.getRES_WIDTH() * 5f / 6f - statusWidth / 2f
            y = statusY
            width = statusWidth
            height = statusHeight
        }
        statusContainer.attachChild(
            statusLine(
                lineText = if (result.isFailedOutcome()) "Failed" else "Completed",
                lineFont = resources.getFont("CaptionFont"),
                lineHeight = VERDICT_HEIGHT,
                lineY = 0f,
                lineColor = if (result.isFailedOutcome()) Color4(1f, 0.45f, 0.45f) else Color4.White
            )
        )
        statusContainer.attachChild(
            statusLine(
                lineText = "Maps played ${result.components.size}/${countedMaps(result)}",
                lineFont = resources.getFont("smallFont"),
                lineHeight = MAPS_HEIGHT,
                lineY = VERDICT_HEIGHT + VERDICT_SPACING,
                lineColor = Color4.White
            )
        )
        scene.attachChild(statusContainer)

        return scene
    }

    private fun attachMetadata(scene: UIScene, result: CourseResult) {
        val resources = ResourceManager.getInstance()
        val titleFont = resources.getFont("font")
        val smallFont = resources.getFont("smallFont")
        val maxWidth = Config.getRES_WIDTH() * 0.60f

        val title = UIText().apply {
            x = Utils.toRes(4f)
            y = Utils.toRes(2f)
            width = maxWidth
            height = titleFont.lineHeight.toFloat()
            font = titleFont
            text = ellipsize(result.definition.courseName, titleFont, maxWidth)
            autoScrollAxes = Axes.None
            color = Color4.White
        }
        scene.attachChild(title)

        val subtitleY = title.y + title.height + Utils.toRes(2f)
        val subtitle = UIText().apply {
            x = Utils.toRes(4f)
            y = subtitleY
            width = maxWidth
            height = smallFont.lineHeight.toFloat()
            font = smallFont
            text = ellipsize(
                "Course by ${result.definition.author}  ·  Played by ${result.playerName}",
                smallFont,
                maxWidth
            )
            autoScrollAxes = Axes.None
            color = Color4.White
        }
        scene.attachChild(subtitle)

        if (result.definition.isSpeedrun) {
            val active = UIText().apply {
                x = Utils.toRes(4f)
                y = subtitle.y + subtitle.height + Utils.toRes(2f)
                width = maxWidth
                height = smallFont.lineHeight.toFloat()
                font = smallFont
                text = ellipsize(
                    "Speedrun  ·  Active time ${formatCompactDuration(result.activeNanos)}",
                    smallFont,
                    maxWidth
                )
                autoScrollAxes = Axes.None
                color = Color4.White
            }
            scene.attachChild(active)
        }
    }

    private fun textureButton(texture: TextureRegion, onAction: () -> Unit): Sprite =
        object : Sprite(0f, 0f, texture) {
            override fun onAreaTouched(
                event: TouchEvent,
                localX: Float,
                localY: Float
            ): Boolean {
                if (event.isActionDown) {
                    setColor(0.7f, 0.7f, 0.7f)
                    ResourceManager.getInstance().getSound("menuback").play()
                    return true
                }
                if (event.isActionUp) {
                    onAction()
                    return true
                }
                return false
            }
        }

    private fun countedMaps(result: CourseResult): Int =
        result.order.size.takeIf { it > 0 } ?: result.definition.maps.size
}

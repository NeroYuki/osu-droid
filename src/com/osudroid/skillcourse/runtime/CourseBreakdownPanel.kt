package com.osudroid.skillcourse.runtime

import android.view.KeyEvent
import com.osudroid.ui.v2.modmenu.ModIcon
import com.osudroid.utils.calculateRate
import com.osudroid.utils.updateThread
import com.reco1l.andengine.Anchor
import com.reco1l.andengine.Axes
import com.reco1l.andengine.component.*
import com.reco1l.andengine.component.UIComponent.Companion.FillParent
import com.reco1l.andengine.container.*
import com.reco1l.andengine.shape.UIBox
import com.reco1l.andengine.sprite.ScaleType
import com.reco1l.andengine.sprite.UISprite
import com.reco1l.andengine.text.UIText
import com.reco1l.andengine.ui.UIButton
import com.reco1l.andengine.ui.UIModal
import com.reco1l.andengine.ui.UITextButton
import com.reco1l.framework.Color4
import com.reco1l.framework.math.Vec4
import ru.nsu.ccfit.zuev.osu.ResourceManager
import ru.nsu.ccfit.zuev.osu.scoring.ScoreNumber
import java.util.Locale
import kotlin.math.min

/**
 * Read-only native component browser.
 *
 * This follows the themed game overlay pattern (not an Android AlertDialog). It never persists or
 * submits anything; closing it, paging through entries, or starting a component replay leaves the
 * aggregated [CourseResult] untouched.
 */
internal class CourseBreakdownPanel(
    private val result: CourseResult,
    private val onWatchReplay: (CourseComponentResult) -> Unit
) : UIModal(card = courseBreakdownCard()) {

    private val pages = result.breakdownPages()
    private var pageIndex = 0

    private val panelWidth = card.width
    private val panelHeight = card.height
    private val panelInset = 16f
    private val footerButtonHeight = 44f
    private val footerY = panelHeight - panelInset - footerButtonHeight
    private val footerGap = 8f
    private val bodyTop = panelInset
    private val bodyHeight = (footerY - bodyTop - footerGap).coerceAtLeast(140f)
    private val bodyInset = 56f
    private val closeButtonWidth = 120f
    private val replayButtonWidth =
        min(170f, panelWidth - bodyInset * 2f - closeButtonWidth - 16f)

    private var currentReplay: CourseComponentResult? = null

    private val pageText = UIText().apply {
        anchor = Anchor.TopRight
        origin = Anchor.TopRight
        x = -panelInset
        y = panelInset
        width = 150f
        height = 26f
        alignment = Anchor.CenterRight
        font = ResourceManager.getInstance().getFont("smallFont")
        color = Color4.White
    }

    private val body = UIContainer().apply {
        x = bodyInset
        y = bodyTop
        width = panelWidth - bodyInset * 2f
        height = bodyHeight
        clipToBounds = true
    }

    private val leftChevron = chevron(left = true) { showPage(pageIndex - 1) }
    private val rightChevron = chevron(left = false) { showPage(pageIndex + 1) }

    /**
     * Shared footer replay action.
     *
     * A single instance is attached only for pages that have a replay and detached otherwise, so
     * paging never stacks duplicate buttons or callbacks. [currentReplay] carries the page-owned
     * component the button acts on.
     */
    private val replayButton = UITextButton().apply {
        text = "Watch replay"
        x = panelWidth - bodyInset - replayButtonWidth
        y = footerY
        width = replayButtonWidth
        height = footerButtonHeight
        isSelected = true
        onActionUp = {
            val component = currentReplay
            if (component != null) {
                this@CourseBreakdownPanel.hide()
                onWatchReplay(component)
            }
        }
    }

    init {
        detachOnHide = true
        staticBackdrop = false

        leftChevron.apply {
            anchor = Anchor.CenterLeft
            origin = Anchor.CenterLeft
            x = 6f
            y = 0f
        }
        rightChevron.apply {
            anchor = Anchor.CenterRight
            origin = Anchor.CenterRight
            x = -6f
            y = 0f
        }

        card.attachChild(pageText)
        card.attachChild(leftChevron)
        card.attachChild(rightChevron)
        card.attachChild(body)
        card.attachChild(
            UITextButton().apply {
                text = "Close"
                x = bodyInset
                y = footerY
                width = closeButtonWidth
                height = footerButtonHeight
                onActionUp = { hide() }
            }
        )

        showPage(0)
    }

    override fun onKeyPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!isVisible) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    updateThread { hide() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    updateThread { showPage(pageIndex - 1) }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    updateThread { showPage(pageIndex + 1) }
                    return true
                }
            }
        }
        return super.onKeyPress(keyCode, event)
    }

    private fun showPage(index: Int) {
        if (pages.isEmpty()) {
            pageIndex = 0
            pageText.text = "No maps"
            leftChevron.isEnabled = false
            rightChevron.isEnabled = false
            updateReplayButton(null)
            body.onInvalidateInputBindings()
            body.detachChildren()
            return
        }

        pageIndex = index.coerceIn(0, pages.lastIndex)
        val page = pages[pageIndex]

        pageText.text = buildString {
            append("Map ${pageIndex + 1} / ${pages.size}")
            if (page.isPartial) {
                append("  ·  Partial")
            } else if (!page.isPlayed) {
                append("  ·  Unplayed")
            }
        }

        leftChevron.isEnabled = pageIndex > 0
        rightChevron.isEnabled = pageIndex < pages.lastIndex

        updateReplayButton(page)
        renderPage(page)
    }

    /**
     * Keeps the footer replay action in sync with the visible page.
     *
     * Unplayed pages and components without a replay detach the button entirely, which also
     * removes its touch area. Reattaching reuses the same instance and callback.
     */
    private fun updateReplayButton(page: CourseBreakdownPage?) {
        val component = page?.component?.takeIf { it.replayFilename != null }
        currentReplay = component

        if (component == null) {
            if (replayButton.parent != null) {
                replayButton.detachSelf()
            }
            return
        }

        if (replayButton.parent == null) {
            card.attachChild(replayButton)
        }
    }

    private fun renderPage(page: CourseBreakdownPage) {
        body.onInvalidateInputBindings()
        body.detachChildren()

        val header = UIContainer().apply {
            x = 0f
            y = 0f
            width = body.width
            height = 62f
        }
        body.attachChild(header)
        renderHeader(header, page)

        val contentTop = 66f
        val contentHeight = (body.height - contentTop).coerceAtLeast(100f)
        val columnGap = 16f
        val leftWidth = (body.width * 0.61f).coerceAtLeast(180f)
        val rightWidth = (body.width - leftWidth - columnGap).coerceAtLeast(120f)

        val left = UILinearContainer().apply {
            orientation = Orientation.Vertical
            x = 0f
            y = contentTop
            width = leftWidth
            height = contentHeight
            spacing = 4f
        }
        val right = UIContainer().apply {
            x = leftWidth + columnGap
            y = contentTop
            width = rightWidth
            height = contentHeight
        }
        body.attachChild(left)
        body.attachChild(right)

        if (page.isPlayed) {
            renderPlayed(left, right, page)
        } else {
            renderUnplayed(left, right)
        }
    }

    private fun renderHeader(container: UIContainer, page: CourseBreakdownPage) {
        val map = page.map
        val titleFont = ResourceManager.getInstance().getFont("font")
        val bodyFont = ResourceManager.getInstance().getFont("smallFont")
        val titleWidth = body.width - 230f
        val title = if (map != null) {
            "${map.displayArtist()} - ${map.displayTitle()} [${map.difficulty}]"
        } else {
            "Course map ${page.originalIndex + 1}"
        }
        val mapper = if (map != null) "Beatmap by ${map.mapper}" else "Map metadata unavailable"

        container.attachChild(UIText().apply {
            x = 0f
            y = 0f
            width = titleWidth
            height = 34f
            font = titleFont
            text = ellipsize(title, titleFont, titleWidth)
            autoScrollAxes = Axes.None
            color = Color4.White
        })
        container.attachChild(UIText().apply {
            x = 0f
            y = 34f
            width = body.width
            height = 26f
            font = bodyFont
            text = ellipsize(mapper, bodyFont, body.width)
            autoScrollAxes = Axes.None
            color = Color4(1f, 1f, 1f, 0.65f)
        })
        container.attachChild(UIBox().apply {
            x = 0f
            y = 61f
            width = body.width
            height = 1f
            color = Color4(1f, 1f, 1f, 0.14f)
        })
    }

    private fun renderPlayed(
        left: UIContainer,
        right: UIContainer,
        page: CourseBreakdownPage
    ) {
        val component = page.component ?: return
        val resource = ResourceManager.getInstance()
        val smallFont = resource.getFont("smallFont")
        val normalFont = resource.getFont("font")

        left.attachChild(UIText().apply {
            width = left.width
            height = 20f
            font = smallFont
            text = "SCORE"
            color = Color4(1f, 1f, 1f, 0.55f)
        })

        val scoreHost = UIContainer().apply {
            width = left.width
            height = 40f
        }
        ScoreNumber(0f, 0f, scoreDigitsText(component.score), 1f, false).attachToScene(scoreHost)
        left.attachChild(scoreHost)

        // The judgment cells are capped so the two columns stay readable instead of stretching
        // across the full left column on wider panels.
        val gridWidth = min(left.width, 440f)
        val grid = UILinearContainer().apply {
            orientation = Orientation.Vertical
            width = gridWidth
            height = 110f
            spacing = 1f
        }
        val cellWidth = (gridWidth - 12f) / 2f
        fun row(leftName: String, leftFallback: String, leftCount: Int, rightName: String, rightFallback: String, rightCount: Int) {
            val row = UILinearContainer().apply {
                orientation = Orientation.Horizontal
                width = gridWidth
                height = 36f
                spacing = 12f
            }
            row.attachChild(judgmentCell(leftName, leftFallback, leftCount, cellWidth))
            row.attachChild(judgmentCell(rightName, rightFallback, rightCount, cellWidth))
            grid.attachChild(row)
        }
        row("hit300", "300", component.hit300, "hit300g", "300G", component.hit300k)
        row("hit100", "100", component.hit100, "hit100k", "100K", component.hit100k)
        row("hit50", "50", component.hit50, "hit0", "X", component.misses)
        left.attachChild(grid)

        val accuracy = if (component.accuracyDenominator == 0L) 0.0 else {
            component.accuracyNumerator.toDouble() / component.accuracyDenominator
        }
        left.attachChild(UIText().apply {
            width = left.width
            height = 30f
            font = normalFont
            text = "Accuracy ${formatAccuracy(accuracy)}"
            color = Color4.White
        })

        val extras = buildList {
            if (result.definition.scoring == "exscore") {
                add("EX ${formatSigned(component.rawExContribution)}")
            }
            if (component.startingHp != null && component.endingHp != null) {
                add("HP ${component.startingHp} → ${component.endingHp}")
            }
            val mods = component.modsOrEmpty()
            val rate = mods.values.calculateRate()
            if (result.definition.isSpeedrun || rate != 1f) {
                add(String.format(Locale.US, "Rate %.2fx", rate))
            }
            if (result.definition.isSpeedrun) {
                add("Time ${formatCompactDuration(component.activeNanos)}")
            }
        }.joinToString("   ·   ")
        if (extras.isNotEmpty()) {
            left.attachChild(UIText().apply {
                width = left.width
                height = 26f
                font = smallFont
                text = ellipsize(extras, smallFont, left.width)
                autoScrollAxes = Axes.None
                color = Color4(1f, 1f, 1f, 0.7f)
            })
        }

        renderGradeAndMods(right, component, page)
    }

    private fun renderUnplayed(left: UIContainer, right: UIContainer) {
        val smallFont = ResourceManager.getInstance().getFont("smallFont")
        val normalFont = ResourceManager.getInstance().getFont("font")

        left.attachChild(UIText().apply {
            width = left.width
            height = 46f
            font = normalFont
            text = "Not played"
            color = Color4(1f, 1f, 1f, 0.8f)
        })
        left.attachChild(UIText().apply {
            width = left.width
            height = 26f
            font = smallFont
            text = "This course entry has no result in this attempt."
            autoScrollAxes = Axes.None
            color = Color4(1f, 1f, 1f, 0.55f)
        })
        right.attachChild(UIText().apply {
            width = right.width
            height = 46f
            alignment = Anchor.Center
            font = normalFont
            text = "—"
            color = Color4(1f, 1f, 1f, 0.55f)
        })
    }

    private fun renderGradeAndMods(
        right: UIContainer,
        component: CourseComponentResult,
        page: CourseBreakdownPage
    ) {
        val resource = ResourceManager.getInstance()
        val grade = component.nativeGrade()
        val hasJudgments = component.hit300 + component.hit100 + component.hit50 + component.misses > 0
        val gradeTexture = resource.getTexture("ranking-$grade")
        val gradeWidth = min(right.width * 0.72f, 190f)
        val gradeHeight = 90f

        if ((hasJudgments || component.completed) && gradeTexture.width > 0 && gradeTexture.height > 0) {
            right.attachChild(UISprite().apply {
                textureRegion = gradeTexture
                x = (right.width - gradeWidth) / 2f
                y = 0f
                width = gradeWidth
                height = gradeHeight
                scaleType = ScaleType.Fit
            })
        } else {
            right.attachChild(UIText().apply {
                x = 0f
                y = 8f
                width = right.width
                height = 60f
                alignment = Anchor.Center
                font = resource.getFont("CaptionFont")
                text = if (hasJudgments || component.completed) grade else "—"
                color = Color4.White
            })
        }

        var rowY = gradeHeight + 4f
        if (page.isPartial) {
            right.attachChild(UIText().apply {
                x = 0f
                y = rowY
                width = right.width
                height = 22f
                alignment = Anchor.Center
                font = resource.getFont("smallFont")
                text = "Partial result"
                color = Color4(1f, 0.65f, 0.35f)
            })
            rowY += 26f
        }

        val mods = component.modsOrEmpty().values.filter { it.isUserPlayable }
        val modRow = UIContainer().apply {
            x = 0f
            y = rowY
            width = right.width
            height = 36f
        }
        if (mods.isEmpty()) {
            modRow.attachChild(UIText().apply {
                width = right.width
                height = 22f
                alignment = Anchor.Center
                font = resource.getFont("smallFont")
                text = "No mods"
                color = Color4(1f, 1f, 1f, 0.55f)
            })
        } else {
            val rows = mods.chunked((right.width / 40f).toInt().coerceAtLeast(1))
            modRow.height = rows.size * 40f
            for ((rowIndex, rowMods) in rows.withIndex()) {
                val totalWidth = rowMods.size * 34f + (rowMods.size - 1) * 6f
                val startX = ((right.width - totalWidth) / 2f).coerceAtLeast(0f)
                for ((column, mod) in rowMods.withIndex()) {
                    modRow.attachChild(ModIcon(mod).apply {
                        x = startX + column * 40f
                        y = rowIndex * 40f
                        width = 34f
                        height = 34f
                    })
                }
            }
        }
        right.attachChild(modRow)
    }

    private fun judgmentCell(
        textureName: String,
        fallback: String,
        count: Int,
        cellWidth: Float
    ): UIContainer {
        val resource = ResourceManager.getInstance()
        val texture = resource.getTexture(textureName)
        val cell = UIContainer().apply {
            this.width = cellWidth
            height = 36f
        }

        if (texture.width > 0 && texture.height > 0) {
            cell.attachChild(UISprite().apply {
                textureRegion = texture
                x = 0f
                y = 3f
                width = 30f
                height = 30f
                scaleType = ScaleType.Fit
            })
        } else {
            cell.attachChild(UIText().apply {
                x = 0f
                y = 7f
                width = 38f
                height = 24f
                font = resource.getFont("smallFont")
                text = fallback
                color = Color4.White
            })
        }

        cell.attachChild(UIText().apply {
            x = 36f
            y = 7f
            width = cellWidth - 36f
            height = 24f
            alignment = Anchor.CenterLeft
            font = resource.getFont("smallFont")
            text = count.toString()
            color = Color4.White
        })
        return cell
    }

    private fun chevron(left: Boolean, onAction: () -> Unit): UIButton {
        val button = UIButton().apply {
            width = 44f
            height = 84f
            onActionUp = onAction
        }
        button.attachChild(UIText().apply {
            width = FillParent
            height = FillParent
            alignment = Anchor.Center
            font = ResourceManager.getInstance().getFont("font")
            text = if (left) "<" else ">"
            color = Color4.White
        })
        return button
    }
}

private fun courseBreakdownCard(): UIContainer = UIContainer().apply {
    anchor = Anchor.Center
    origin = Anchor.Center
    scaleCenter = Anchor.Center
    width = safePanelWidth()
    height = safePanelHeight()
    padding = Vec4(0f)
    clipToBounds = true
}

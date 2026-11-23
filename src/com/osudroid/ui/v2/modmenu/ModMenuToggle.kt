package com.osudroid.ui.v2.modmenu

import com.osudroid.mods.*
import com.osudroid.multiplayer.*
import com.osudroid.utils.searchContiguously
import com.reco1l.andengine.*
import com.reco1l.andengine.buffered.*
import com.reco1l.andengine.component.*
import com.reco1l.andengine.container.*
import com.reco1l.andengine.shape.*
import com.reco1l.andengine.text.*
import com.reco1l.andengine.ui.*
import com.rian.andengine.modifier.ModifierType
import ru.nsu.ccfit.zuev.osu.*

class ModMenuToggle(var mod: Mod): UIButton() {

    /**
     * Whether the [Mod] represented by this [ModMenuToggle] is incompatible with one or more enabled [Mod]s.
     */
    var hasIncompatibility = false
        set(value) {
            if (field != value) {
                field = value
                applyCompatibilityState()
            }
        }

    /**
     * Whether this mod is required by the current scoped course session. Locked mods stay selected
     * but cannot be toggled or configured.
     */
    var isLocked = false
        set(value) {
            if (field != value) {
                field = value
                applyCompatibilityState()
            }
        }

    /**
     * Whether this mod is outside the current scoped course's allowlist (or is a rate mod reserved
     * for a speedrun speed control). Restricted mods remain visible but are grayed out and disabled.
     */
    var isRestricted = false
        set(value) {
            if (field != value) {
                field = value
                applyCompatibilityState()
            }
        }

    init {
        orientation = Orientation.Horizontal
        width = FillParent
        spacing = 8f
        cullingMode = CullingMode.CameraBounds

        background = UIBox().apply {
            cornerRadius = 12f
            // Sharing the same VBO across all toggles to reduce memory usage.
            buffer = sharedButtonVBO
        }

        +ModIcon(mod).apply {
            width = 38f
            height = 38f
            anchor = Anchor.CenterLeft
            origin = Anchor.CenterLeft
        }

        linearContainer {
            orientation = Orientation.Vertical
            width = FillParent
            anchor = Anchor.CenterLeft
            origin = Anchor.CenterLeft

            text {
                text = mod.name
                font = ResourceManager.getInstance().getFont("smallFont")
                buffer = sharedTextCB
            }

            text {
                width = FillParent
                font = ResourceManager.getInstance().getFont("xs")
                text = mod.description
                clipToBounds = true
                alpha = 0.75f
                buffer = sharedTextCB
            }
        }

        onActionUp = {
            if (isSelected) {
                ModMenu.removeMod(mod)
                ResourceManager.getInstance().getSound("check-off")?.play()
            } else {
                ModMenu.addMod(mod)
                ResourceManager.getInstance().getSound("check-on")?.play()
            }
        }

        updateVisibility()
    }

    @JvmOverloads
    fun updateVisibility(searchTerm: String = "") {
        // A scoped course selector must show its disallowed choices so they can be grayed out and
        // explained. Ordinary multiplayer visibility filtering would hide them entirely.
        var shouldBeVisible = if (!ModMenu.hasScopedSession() && Multiplayer.isMultiplayer && Multiplayer.room != null) {
            mod.isValidForMultiplayer && (Multiplayer.isRoomHost ||
                    (Multiplayer.room!!.gameplaySettings.isFreeMod && mod.isValidForMultiplayerAsFreeMod))
        } else {
            true
        }

        if (searchTerm.isNotBlank()) {
            shouldBeVisible = shouldBeVisible &&
                (mod.acronym.equals(searchTerm, true) ||
                    mod.name.searchContiguously(searchTerm, true))
        }

        isVisible = shouldBeVisible
    }

    fun applyCompatibilityState() {
        // Intentionally not using isEnabled alone here, otherwise the button will not be clickable.
        clearModifiers(ModifierType.Alpha)

        val targetAlpha = when {
            isRestricted -> 0.35f
            isLocked -> 1f
            hasIncompatibility -> 0.5f
            else -> if (isEnabled) 1f else 0.5f
        }
        fadeTo(targetAlpha, 0.2f)
    }

    /**
     * Applies the course-session restriction flags to this toggle.
     */
    fun applyRestriction(locked: Boolean, restricted: Boolean) {
        isLocked = locked
        isRestricted = restricted
        isEnabled = !locked && !restricted
        applyCompatibilityState()
    }

    companion object {

        private val sharedButtonVBO = UIBox.BoxVBO(12f, UICircle.approximateSegments(12f, 12f, 90f), PaintStyle.Fill)
        private val sharedTextCB = UITextCompoundBuffer(256).asSharedDynamically()

    }

}
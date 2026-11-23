package com.osudroid.ui.v2.modmenu

import com.edlplan.framework.easing.Easing
import com.osudroid.GameMode
import com.osudroid.beatmaps.BeatmapCache
import com.osudroid.data.BeatmapInfo
import com.osudroid.data.DatabaseManager
import com.reco1l.andengine.*
import com.reco1l.andengine.component.UIComponent.Companion.MatchContent
import com.reco1l.andengine.component.UIComponent.Companion.FillParent
import com.reco1l.andengine.container.*
import com.reco1l.andengine.shape.*
import com.reco1l.andengine.sprite.*
import com.reco1l.andengine.ui.*
import com.reco1l.framework.*
import com.reco1l.framework.math.*
import com.osudroid.multiplayer.api.RoomAPI.setPlayerMods
import com.osudroid.multiplayer.api.RoomAPI.setRoomMods
import com.osudroid.multiplayer.api.data.RoomMods
import com.osudroid.multiplayer.Multiplayer
import com.osudroid.ui.v2.ModsIndicator
import com.osudroid.ui.v2.StarRatingBadge
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.async
import com.osudroid.utils.updateThread
import com.reco1l.andengine.component.*
import com.reco1l.andengine.ui.UITextButton
import com.rian.framework.RollingFloatCounter
import com.osudroid.difficulty.BeatmapDifficultyCalculator.calculateDroidDifficulty
import com.osudroid.difficulty.BeatmapDifficultyCalculator.calculateStandardDifficulty
import com.osudroid.mods.*
import com.osudroid.scoring.ScoreMultiplierCalculator
import com.osudroid.utils.allModsInstances
import com.osudroid.utils.applyMods
import com.osudroid.utils.calculateRate
import java.io.IOException
import kotlinx.coroutines.*
import ru.nsu.ccfit.zuev.osu.*
import ru.nsu.ccfit.zuev.osu.DifficultyAlgorithm.*
import ru.nsu.ccfit.zuev.osu.helper.*
import java.util.LinkedList
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

/**
 * The two isolated reuse modes of the native mod selector.
 */
internal enum class ScopedCourseMode {
    PLAYER,
    ALLOWLIST
}

/**
 * A scoped course selector session.
 *
 * The ordinary selector state is snapshotted by [ModMenu] when this session starts and restored when
 * it ends. Constraints are deliberately expressed as class sets because mods are identified by their
 * concrete class in [ModHashMap].
 */
internal class ScopedCourseSession(
    val mode: ScopedCourseMode,

    /** Mods authored by the course map (plus engine-injected choices). These are selected and locked. */
    val requiredClasses: Set<Class<out Mod>> = emptySet(),

    /** Mods that may optionally be added by the player. */
    val allowedClasses: Set<Class<out Mod>> = emptySet(),

    /** User-playable mods that remain visible but cannot be selected. */
    val restrictedClasses: Set<Class<out Mod>> = emptySet(),

    /** Beatmap used only for the session's difficulty preview. The normal song-menu selection is untouched. */
    val previewBeatmap: BeatmapInfo? = null,

    /** Initial effective mods for player mode. */
    val initialMods: ModHashMap = ModHashMap(),

    /** Initial selected acronyms for allowlist-authoring mode. */
    val initialAllowedAcronyms: Set<String> = emptySet(),

    /** Invoked on the engine update thread when explicit Save is pressed. */
    val onSave: () -> Unit,

    /** Invoked when Cancel or Back is pressed. */
    val onCancel: () -> Unit
)

object ModMenu : UIScene() {


    /**
     * List of currently enabled mods.
     */
    val enabledMods = ModHashMap()

    /**
     * List of all mod sections.
     */
    val modSections = mutableListOf<ModMenuSection>()

    /**
     * List of all mod toggles.
     */
    val modToggles = mutableListOf<ModMenuToggle>()


    private val modChangeQueue = LinkedList<Mod>()
    private val modPresetsSection: ModMenuPresetsSection

    private val backButton: UITextButton
    private val saveButton: UITextButton
    private val customizeButton: UITextButton
    private val customizationMenu: ModCustomizationMenu
    private val selectedModsIndicator: ModsIndicator
    private val searchInput: ModMenuSearchInput

    private val rankedBadge: UIBadge
    private val arBadge: StatisticsBadge
    private val odBadge: StatisticsBadge
    private val csBadge: StatisticsBadge
    private val hpBadge: StatisticsBadge
    private val bpmBadge: StatisticsBadge
    private val starRatingBadge: StarRatingBadge
    private val scoreMultiplierBadge: StatisticsBadge

    private var calculationJob: Job? = null
    private var calculationGeneration = 0L

    @Volatile
    private var scopedSession: ScopedCourseSession? = null
    private val scopedAuthorSelection = LinkedHashSet<String>()

    private var normalEnabledMods = ModHashMap()
    private var normalToggleMods = emptyMap<Class<out Mod>, Mod>()
    private var normalPresetsVisible = false
    private var normalSelectedBeatmap: BeatmapInfo? = null


    init {
        isBackgroundEnabled = false

        ResourceManager.getInstance().loadHighQualityAsset("back-arrow", "back-arrow.png")
        ResourceManager.getInstance().loadHighQualityAsset("tune", "tune.png")
        ResourceManager.getInstance().loadHighQualityAsset("backspace", "backspace.png")
        ResourceManager.getInstance().loadHighQualityAsset("search-small", "search-small.png")
        ResourceManager.getInstance().loadHighQualityAsset("settings", "settings.png")

        customizationMenu = ModCustomizationMenu()

        attachChild(UIFlexContainer().apply {
            width = FillParent
            height = FillParent
            direction = FlexDirection.Column
            justifyContent = JustifyContent.SpaceBetween
            background = UIBox().apply {
                applyTheme = {
                    color = it.accentColor * 0.1f
                    alpha = 0.9f
                }
            }

            +UIContainer().apply {
                width = FillParent
                height = MatchContent
                padding = Vec4(60f, 12f)

                +UILinearContainer().apply {
                    orientation = Orientation.Horizontal
                    anchor = Anchor.CenterLeft
                    origin = Anchor.CenterLeft
                    spacing = 10f

                    backButton = UITextButton().apply {
                        text = "Back"
                        leadingIcon = UISprite().apply {
                            textureRegion = ResourceManager.getInstance().getTexture("back-arrow")
                            width = 28f
                            height = 28f
                        }
                        onActionUp = {
                            ResourceManager.getInstance().getSound("click-short-confirm")?.play()
                            back()
                        }
                        onActionCancel = { ResourceManager.getInstance().getSound("click-short")?.play() }
                    }

                    +backButton

                    saveButton = UITextButton().apply {
                        text = "Save"
                        isVisible = false
                        isSelected = true
                        onActionUp = {
                            ResourceManager.getInstance().getSound("click-short-confirm")?.play()
                            scopedSession?.onSave?.invoke()
                        }
                        onActionCancel = { ResourceManager.getInstance().getSound("click-short")?.play() }
                    }

                    +saveButton

                    customizeButton = UITextButton().apply {
                        text = "Customize"
                        isEnabled = false
                        leadingIcon = UISprite().apply {
                            textureRegion = ResourceManager.getInstance().getTexture("tune")
                            width = 28f
                            height = 28f
                        }
                        onActionUp = {
                            ResourceManager.getInstance().getSound("click-short-confirm")?.play()
                            if (customizationMenu.isVisible) {
                                customizationMenu.hide()
                            } else {
                                customizationMenu.show()
                            }
                        }
                        onActionCancel = { ResourceManager.getInstance().getSound("click-short")?.play() }
                    }

                    +customizeButton

                    +UITextButton().apply {
                        text = "Clear"
                        applyTheme = {}
                        color = Color4(0xFFFFBFBF)
                        background?.color = Color4(0xFF342121)
                        leadingIcon = UISprite().apply {
                            textureRegion = ResourceManager.getInstance().getTexture("backspace")
                            width = 28f
                            height = 28f
                        }
                        onActionUp = {
                            ResourceManager.getInstance().getSound("click-short-confirm")?.play()
                            clear()
                        }
                        onActionCancel = { ResourceManager.getInstance().getSound("click-short")?.play() }
                    }

                    +UIScrollableContainer().apply {
                        width = 340f
                        height = MatchContent
                        anchor = Anchor.CenterLeft
                        origin = Anchor.CenterLeft
                        scrollAxes = Axes.X
                        clipToBounds = true

                        selectedModsIndicator = ModsIndicator()
                        +selectedModsIndicator
                    }
                }

                +UIContainer().apply {
                    anchor = Anchor.CenterRight
                    origin = Anchor.CenterRight
                    height = FillParent

                    searchInput = ModMenuSearchInput().apply {
                        width = 400f
                        height = FillParent
                        onSearchTermUpdate = { searchTerm ->
                            modSections.forEach { it.onSearchTermUpdate(searchTerm) }

                            if (modPresetsSection.isVisible) {
                                modPresetsSection.onSearchTermUpdate(searchTerm)
                            }
                        }
                    }

                    +searchInput

                    +UISprite().apply {
                        textureRegion = ResourceManager.getInstance().getTexture("search-small")
                        size = Vec2(52f, 28f)
                        anchor = Anchor.CenterRight
                        origin = Anchor.CenterRight
                        applyTheme = { color = it.accentColor }
                    }
                }
            }

            +UIScrollableContainer().apply {
                width = FillParent
                height = FillParent
                scrollAxes = Axes.X
                flexRules { grow = 1f }

                +UILinearContainer().apply {
                    orientation = Orientation.Horizontal
                    width = MatchContent
                    height = FillParent
                    spacing = 16f
                    padding = Vec4(60f, 0f)

                    modPresetsSection = ModMenuPresetsSection()
                    +modPresetsSection

                    val mods = allModsInstances

                    ModType.entries.forEach { type ->
                        val sectionName = StringTable.get(type.stringId)
                        val sectionToggles = mods.filter { it !is IMigratableMod && it.isUserPlayable && it.type == type }.map { ModMenuToggle(it) }

                        if (sectionToggles.isEmpty()) {
                            return@forEach
                        }

                        modToggles.addAll(sectionToggles)

                        val section = ModMenuSection(sectionName, sectionToggles)

                        +section
                        modSections.add(section)
                    }
                }
            }

            +UIContainer().apply {
                width = FillParent
                height = MatchContent
                padding = Vec4(60f, 12f)

                onUpdateTick = {
                    val buttonHeight = Multiplayer.roomScene?.chat?.buttonHeight ?: 0f

                    paddingBottom = if (Multiplayer.isConnected) buttonHeight + 12f else 12f
                }

                +UILinearContainer().apply {
                    orientation = Orientation.Horizontal
                    anchor = Anchor.CenterLeft
                    origin = Anchor.CenterLeft
                    spacing = 10f

                    arBadge = StatisticsBadge().apply {
                        label = "AR"
                        value = "0.00"
                    }

                    odBadge = StatisticsBadge().apply {
                        label = "OD"
                        value = "0.00"
                    }

                    csBadge = StatisticsBadge().apply {
                        label = "CS"
                        value = "0.00"
                    }

                    hpBadge = StatisticsBadge().apply {
                        label = "HP"
                        value = "0.00"
                    }

                    bpmBadge = StatisticsBadge { it.roundToInt().toString() }.apply {
                        label = "BPM"
                        value = "0.0"
                    }

                    +arBadge
                    +odBadge
                    +csBadge
                    +hpBadge
                    +bpmBadge
                }

                +UILinearContainer().apply {
                    orientation = Orientation.Horizontal
                    anchor = Anchor.CenterRight
                    origin = Anchor.CenterRight
                    spacing = 10f

                    starRatingBadge = StarRatingBadge()
                    +starRatingBadge

                    rankedBadge = badge {
                        text = "Ranked"
                        background!!.color = Color4(0xFF83DF6B)
                        color = Color4(0xFF161622)
                        applyTheme = {}
                    }

                    scoreMultiplierBadge = StatisticsBadge { "%.2fx".format(it) }.apply {
                        label = "Score"
                        value = "1.00x"
                    }

                    +scoreMultiplierBadge
                }
            }
        })

        // Customizations menu
        attachChild(customizationMenu)

        modPresetsSection.loadPresets()
    }


    //region Calculation

    fun cancelCalculationJob() {
        calculationGeneration++
        calculationJob?.cancel(CancellationException("Difficulty calculation has been cancelled."))
        calculationJob = null
    }

    private fun parseBeatmap() {
        cancelCalculationJob()

        val session = scopedSession

        // Allowlist authoring is not an active mod combination, so it has no preview calculation.
        if (session?.mode == ScopedCourseMode.ALLOWLIST) {
            return
        }

        val selectedBeatmap = session?.previewBeatmap ?: GlobalManager.getInstance().selectedBeatmap ?: return
        val generation = calculationGeneration
        val isScopedPreview = session != null
        val previewMods = enabledMods.deepCopy()

        calculationJob = async scope@{

            val difficultyAlgorithm = Config.getDifficultyAlgorithm()
            val gameMode = if (difficultyAlgorithm == droid) GameMode.Droid else GameMode.Standard

            val beatmap = try {
                BeatmapCache.getBeatmap(selectedBeatmap, true, gameMode, this)
            } catch (e: Exception) {
                when (e) {
                    is IOException, is IllegalArgumentException -> null
                    else -> throw e
                }
            }

            val songMenu = GlobalManager.getInstance().songMenu
            ensureActive()
            if (generation != calculationGeneration || scopedSession !== session) return@scope

            if (beatmap == null) {
                if (!isScopedPreview && generation == calculationGeneration && scopedSession === session) {
                    songMenu.setStarsDisplay(0f)
                }
                return@scope
            }

            if (!isScopedPreview && selectedBeatmap.needsDifficultyCalculation) {
                val newInfo = BeatmapInfo(beatmap, selectedBeatmap.dateImported, true)
                selectedBeatmap.apply(newInfo)
                DatabaseManager.beatmapInfoTable.update(newInfo)
            }

            // This is needed to give the mod a reference to the beatmap's default difficulty values.
            previewMods.ofType<ModDifficultyAdjust>()?.setDefaultDifficulty(beatmap.difficulty)
            updateThread {
                if (generation == calculationGeneration && scopedSession === session) {
                    enabledMods.ofType<ModDifficultyAdjust>()?.setDefaultDifficulty(beatmap.difficulty)
                    customizationMenu.updateComponents()
                }
            }

            val mods = previewMods.values
            val difficulty = beatmap.difficulty.clone()
            val rate = mods.calculateRate(Double.POSITIVE_INFINITY)

            difficulty.applyMods(gameMode, mods, true, this@scope)

            ensureActive()

            updateThread {
                // A later parse, session exit, or restore may have superseded this calculation.
                if (generation != calculationGeneration || scopedSession !== session) {
                    return@updateThread
                }

                arBadge.updateValue(selectedBeatmap.approachRate, difficulty.ar)
                odBadge.updateValue(selectedBeatmap.overallDifficulty, difficulty.od)
                csBadge.updateValue(selectedBeatmap.circleSize, difficulty.difficultyCS)
                hpBadge.updateValue(selectedBeatmap.hpDrainRate, difficulty.hp)
                bpmBadge.updateValue(selectedBeatmap.mostCommonBPM, selectedBeatmap.mostCommonBPM * rate)

                scoreMultiplierBadge.updateValue(1f, ScoreMultiplierCalculator(beatmap.difficulty).calculateFor(mods).toFloat())
            }

            ensureActive()

            val attributes = when (difficultyAlgorithm) {
                droid -> calculateDroidDifficulty(beatmap, mods, this@scope)
                standard -> calculateStandardDifficulty(beatmap, mods, this@scope)
            }

            ensureActive()

            if (generation != calculationGeneration) {
                return@scope
            }

            updateThread {
                if (generation != calculationGeneration || scopedSession !== session) {
                    return@updateThread
                }

                starRatingBadge.rating = attributes.starRating

                // Course previews must not modify the ordinary song-menu dimension text, star
                // display, or music effects. Those are restored/recomputed after the scoped session
                // ends.
                if (!isScopedPreview) {
                    songMenu.changeDimensionInfo(selectedBeatmap)
                    songMenu.setStarsDisplay(attributes.starRating.toFloat())
                }
            }
        }
    }

    //endregion

    //region Visibility

    override fun show() {
        val session = scopedSession

        // Keep the session chrome consistent even if an earlier session was abandoned while the
        // activity was being destroyed.
        backButton.text = if (session != null) "Cancel" else "Back"
        saveButton.isVisible = session != null
        customizeButton.isVisible = session == null || session.mode == ScopedCourseMode.PLAYER

        GlobalManager.getInstance().engine.scene.setChildScene(
            this,
            false,
            true,
            true
        )

        // Do not show mod presets in multiplayer or in any scoped course session. Presets could
        // otherwise replace a locked authored mod or bypass the course allowlist.
        modPresetsSection.isVisible = session == null && !Multiplayer.isMultiplayer

        if (modPresetsSection.isVisible) {
            modPresetsSection.onSearchTermUpdate(searchInput.value)
        }

        // Ensure mods and customizations that can be enabled by the user are displayed and enabled.
        updateModButtonVisibility()
        updateCustomizationMenuEnabledStates()

        // Only parsing to update mod's specific settings defaults, specially those which rely on the original beatmap data.
        parseBeatmap()
    }

    override fun back() {
        val session = scopedSession

        if (session != null) {
            session.onCancel()
            return
        }

        back(true)
    }

    fun back(updatePlayerMods: Boolean) {
        val session = scopedSession

        if (session != null) {
            session.onCancel()
            return
        }

        if (Multiplayer.isConnected) {
            val gameScene = GlobalManager.getInstance().gameScene
            val isInGameplay = gameScene != null &&
                GlobalManager.getInstance().engine.scene == gameScene.scene

            if (!isInGameplay) {
                Multiplayer.roomScene?.chat?.show()
                Multiplayer.roomScene?.isWaitingForModsChange?.set(true)

                // The room mods are the same as the host mods
                if (Multiplayer.isRoomHost) {
                    setRoomMods(enabledMods.serializeMods())
                } else if (updatePlayerMods) {
                    setPlayerMods(enabledMods.serializeMods())
                } else {
                    Multiplayer.roomScene?.isWaitingForModsChange?.set(false)
                }
            }
        }

        super.back()
    }

    //endregion

    //region Mods

    internal fun hasScopedSession() = scopedSession != null

    internal fun scopedSelectedMods(): ModHashMap = enabledMods.deepCopy()

    internal fun scopedAuthorSelection(): Set<String> = scopedAuthorSelection.toSet()

    internal fun canModifySettings(mod: Mod): Boolean {
        val session = scopedSession ?: return true

        if (session.mode == ScopedCourseMode.ALLOWLIST) {
            return false
        }

        return mod::class.java !in session.requiredClasses
    }

    /**
     * Starts a scoped course selector session. The ordinary selector state is snapshotted before any
     * course-specific state is applied.
     */
    internal fun showScoped(session: ScopedCourseSession) {
        beginScopedSession(session)
        show()
    }

    private fun beginScopedSession(session: ScopedCourseSession) {
        check(scopedSession == null) { "A scoped mod menu session is already active." }

        normalEnabledMods = enabledMods.deepCopy()
        normalToggleMods = modToggles.associate { it.mod::class.java to it.mod.deepCopy() }
        normalPresetsVisible = modPresetsSection.isVisible
        normalSelectedBeatmap = GlobalManager.getInstance().selectedBeatmap

        cancelCalculationJob()
        synchronized(modChangeQueue) { modChangeQueue.clear() }

        scopedSession = session
        scopedAuthorSelection.clear()

        when (session.mode) {
            ScopedCourseMode.PLAYER -> applyScopedPlayerMods(session.initialMods)

            ScopedCourseMode.ALLOWLIST -> {
                scopedAuthorSelection.addAll(session.initialAllowedAcronyms)
                applyScopedAuthorSelection()
            }
        }

        backButton.text = "Cancel"
        saveButton.isVisible = true
        customizeButton.isVisible = session.mode == ScopedCourseMode.PLAYER

        if (session.mode == ScopedCourseMode.ALLOWLIST) {
            customizationMenu.clearModSettings()
        }

        updateModButtonVisibility()
        updateCustomizationMenuEnabledStates()

        // Refresh the initial selected-mod indicator, score multiplier, and customize button state.
        // The show() call immediately after this starts the preview calculation.
        onModsChanged()
    }

    /**
     * Ends a scoped session and restores the exact ordinary selector state. Call on the engine update
     * thread while the engine is alive.
     */
    internal fun endScopedSession() {
        if (scopedSession == null) {
            return
        }

        scopedSession = null
        scopedAuthorSelection.clear()
        backButton.text = "Back"
        saveButton.isVisible = false
        customizeButton.isVisible = true
        customizationMenu.hide()

        restoreNormalModState()

        GlobalManager.getInstance().selectedBeatmap = normalSelectedBeatmap

        val engine = GlobalManager.getInstance().engine
        val scene = engine?.scene
        if (scene?.childScene === this) {
            scene?.clearChildScene()
        }

        modPresetsSection.isVisible = normalPresetsVisible
        updateModButtonVisibility()
        updateCustomizationMenuEnabledStates()
        onModsChanged()
    }

    /**
     * Drops a scoped session without touching the scene or starting calculations. Used only when the
     * owning activity has already been destroyed and the UI can no longer be refreshed safely.
     */
    internal fun abandonScopedSession() {
        if (scopedSession == null) {
            return
        }

        scopedSession = null
        scopedAuthorSelection.clear()
        backButton.text = "Back"
        saveButton.isVisible = false
        customizeButton.isVisible = true
        customizationMenu.clearModSettings()

        restoreNormalModState()
        updateModButtonVisibility()
        updateCustomizationMenuEnabledStates()
        customizeButton.isEnabled = !customizationMenu.isEmpty()
        selectedModsIndicator.mods = enabledMods.values.toList()
        modPresetsSection.isVisible = normalPresetsVisible
    }

    private fun applyScopedPlayerMods(mods: ModHashMap) {
        enabledMods.clear()
        mods.values.forEach { enabledMods[it::class.java] = it.deepCopy() }

        modToggles.forEach { toggle ->
            val selected = enabledMods[toggle.mod::class]
            toggle.isSelected = selected != null
            toggle.mod = selected ?: toggle.mod::class.createInstance()
        }

        customizationMenu.clearModSettings()
        enabledMods.values.forEach { customizationMenu.onModAdded(it) }
        refreshCompatibility()
    }

    private fun applyScopedAuthorSelection() {
        enabledMods.clear()
        customizationMenu.clearModSettings()

        modToggles.forEach { toggle ->
            toggle.isSelected = toggle.mod.acronym in scopedAuthorSelection
            toggle.hasIncompatibility = false
            toggle.mod = toggle.mod::class.createInstance()
        }

        refreshScopedAuthorView()
    }

    private fun refreshCompatibility() {
        val selected = enabledMods.values.toList()

        modToggles.forEach {
            it.hasIncompatibility =
                !it.isSelected && selected.any { mod -> !it.mod.isCompatibleWith(mod) || !mod.isCompatibleWith(it.mod) }
        }
    }

    private fun refreshScopedAuthorView() {
        modToggles.forEach {
            it.isSelected = it.mod.acronym in scopedAuthorSelection
            it.hasIncompatibility = false
        }

        selectedModsIndicator.mods = modToggles.filter { it.isSelected }.map { it.mod }
    }

    private fun restoreNormalModState() {
        cancelCalculationJob()
        synchronized(modChangeQueue) { modChangeQueue.clear() }

        enabledMods.clear()
        normalEnabledMods.values.forEach { enabledMods[it::class.java] = it.deepCopy() }

        modToggles.forEach { toggle ->
            val snapshot = normalToggleMods[toggle.mod::class.java]
            val selected = enabledMods[toggle.mod::class]
            toggle.isSelected = selected != null
            toggle.mod = selected ?: snapshot?.deepCopy() ?: toggle.mod::class.createInstance()
            toggle.isLocked = false
            toggle.isRestricted = false
            toggle.isEnabled = true
        }

        customizationMenu.clearModSettings()
        enabledMods.values.forEach { customizationMenu.onModAdded(it) }
    }

    fun setMods(mods: RoomMods, isFreeMod: Boolean) {
        // A scoped course selection owns the mod set until it is explicitly saved or cancelled.
        if (scopedSession != null) {
            return
        }

        if (isFreeMod) {
            for ((_, mod) in enabledMods.toList()) {
                if (mod.isValidForMultiplayerAsFreeMod) {
                    continue
                }

                if (mod !in mods) {
                    removeMod(mod)
                }
            }
        } else {
            clear()
        }

        for (mod in mods.values) {
            if (!mod.isValidForMultiplayer) {
                continue
            }

            if (!isFreeMod || !mod.isValidForMultiplayerAsFreeMod) {
                addMod(mod)
            }
        }

        if (!Multiplayer.isRoomHost) {
            val doubleTime = enabledMods.ofType<ModDoubleTime>()
            val nightCore = enabledMods.ofType<ModNightCore>()

            if (Config.isUseNightcoreOnMultiplayer() && doubleTime != null) {
                removeMod(doubleTime)
                addMod(ModNightCore())
            } else if (!Config.isUseNightcoreOnMultiplayer() && nightCore != null) {
                removeMod(nightCore)
                addMod(ModDoubleTime())
            }
        }

        if (ModScoreV2::class in mods) {
            addMod(ModScoreV2())
        } else {
            removeMod(ModScoreV2())
        }

        updateModButtonVisibility()
    }

    fun updateModButtonVisibility() {
        val session = scopedSession

        modToggles.forEach {
            it.updateVisibility(searchInput.value)

            when {
                session == null -> it.applyRestriction(false, false)
                session.mode == ScopedCourseMode.ALLOWLIST -> it.applyRestriction(false, false)
                else -> {
                    val modClass = it.mod::class.java
                    it.applyRestriction(
                        locked = modClass in session.requiredClasses,
                        restricted = modClass in session.restrictedClasses || (!it.isSelected && it.hasIncompatibility)
                    )
                }
            }
        }

        // Update section visibility after toggles have been updated since it may not need to be visible anymore.
        modSections.forEach { it.updateVisibility() }
    }

    fun updateCustomizationMenuEnabledStates() {
        val session = scopedSession
        val locked = if (session == null || session.mode == ScopedCourseMode.ALLOWLIST) {
            emptySet()
        } else {
            session.requiredClasses
        }

        customizationMenu.setLockedModClasses(locked)
        customizationMenu.updateComponentEnabledStates()
    }

    fun clear() {
        val session = scopedSession

        if (session != null) {
            clearScoped(session)
            return
        }

        cancelCalculationJob()

        val room = Multiplayer.room
        val isHost = Multiplayer.isRoomHost

        enabledMods.toList().forEach {
            val mod = it.second

            if (room != null) {
                // For non-host in multiplayer, we want to keep mods that are not allowed to be selected by the player
                // since only the host can change those mods.
                if (!isHost && room.gameplaySettings.isFreeMod && !mod.isValidForMultiplayerAsFreeMod) {
                    return@forEach
                }

                // Special handling for the ScoreV2 mod, which is used by the ScoreV2 win condition.
                if (mod is ModScoreV2) {
                    return@forEach
                }
            }

            removeMod(mod::class)
        }
    }

    private fun clearScoped(session: ScopedCourseSession) {
        if (session.mode == ScopedCourseMode.ALLOWLIST) {
            scopedAuthorSelection.clear()
            refreshScopedAuthorView()
            updateModButtonVisibility()
            return
        }

        val removable = enabledMods.values
            .filter { it::class.java !in session.requiredClasses }
            .map { it::class }

        removable.forEach { removeMod(it) }
    }

    fun queueModChange(mod: Mod) = synchronized(modChangeQueue) {
        // Adding to first place in case it is already queued.
        if (modChangeQueue.isEmpty() || modChangeQueue.first() != mod) {
            if (modChangeQueue.isNotEmpty()) {
                modChangeQueue.remove(mod)
            }
            modChangeQueue.addFirst(mod)
        }
    }

    override fun onManagedUpdate(deltaTimeSec: Float) {

        if (modChangeQueue.isNotEmpty()) {
            onModsChanged()
        }

        super.onManagedUpdate(deltaTimeSec)
    }

    private fun onModsChanged() = synchronized(modChangeQueue) {
        val session = scopedSession

        if (session?.mode == ScopedCourseMode.ALLOWLIST) {
            modChangeQueue.clear()
            refreshScopedAuthorView()
            updateModButtonVisibility()
            return
        }

        val enabledMods = enabledMods.values.toList()
        val isRanked = enabledMods.isEmpty() || enabledMods.none { !it.isRanked }

        rankedBadge.apply {
            text = if (isRanked) "Ranked" else "Unranked"

            clearEntityModifiers()
            colorTo(if (isRanked) Color4(0xFF161622) else Theme.current.accentColor, 0.1f)

            background!!.clearEntityModifiers()
            background!!.colorTo(if (isRanked) Color4(0xFF83DF6B) else Theme.current.accentColor * 0.15f, 0.1f)
        }

        modToggles.forEach {
            it.hasIncompatibility =
                !it.isSelected && enabledMods.any { m -> !it.mod.isCompatibleWith(m) || !m.isCompatibleWith(it.mod) }
        }

        val displayBeatmap = session?.previewBeatmap ?: GlobalManager.getInstance().selectedBeatmap
        scoreMultiplierBadge.updateValue(1f, ScoreMultiplierCalculator(displayBeatmap?.getBeatmapDifficulty()).calculateFor(enabledMods).toFloat())

        customizeButton.isEnabled = session?.mode != ScopedCourseMode.ALLOWLIST && !customizationMenu.isEmpty()

        if (session == null && modChangeQueue.any { it is IModApplicableToTrackRate }) {
            GlobalManager.getInstance().songMenu.updateMusicEffects()
        }
        modChangeQueue.clear()

        parseBeatmap()

        selectedModsIndicator.mods = enabledMods

        if (session == null) {
            modPresetsSection.onModsChanged()
        } else {
            updateModButtonVisibility()
            updateCustomizationMenuEnabledStates()
        }
    }

    fun addMod(mod: Mod) {
        val session = scopedSession

        if (session != null) {
            when (session.mode) {
                ScopedCourseMode.ALLOWLIST -> addAllowedMod(mod)
                ScopedCourseMode.PLAYER -> addPlayerMod(session, mod)
            }
            return
        }

        if (mod in enabledMods) {
            return
        }
        enabledMods.put(mod)

        modToggles.forEach { button ->

            val wasSelected = button.isSelected
            button.isSelected = button.mod::class in enabledMods

            if (button.mod::class == mod::class) {
                button.mod = mod
            }

            // Handle incompatible mods with the selected mod.
            if (wasSelected && !button.isSelected) {
                customizationMenu.onModRemoved(button.mod)
                button.mod = button.mod::class.createInstance()
            }
        }

        customizationMenu.onModAdded(mod)
        queueModChange(mod)
    }

    private fun addAllowedMod(mod: Mod) {
        if (!mod.isUserPlayable) {
            return
        }

        val toggle = modToggles.find { it.mod::class == mod::class } ?: return

        // Allowlist authoring deliberately keeps an independent acronym set. Incompatible
        // alternatives such as HR and EZ may both be allowed here.
        scopedAuthorSelection.add(toggle.mod.acronym)
        toggle.isSelected = true
        queueModChange(toggle.mod)
    }

    private fun addPlayerMod(session: ScopedCourseSession, mod: Mod) {
        if (!mod.isUserPlayable) {
            return
        }

        val modClass = mod::class.java

        if (modClass in session.requiredClasses ||
            modClass in session.restrictedClasses ||
            modClass !in session.allowedClasses ||
            modClass in enabledMods
        ) {
            return
        }

        // Never let ModHashMap.put evict a required authored mod. Compatibility is checked in both
        // directions against every current mod before insertion, so the put is non-destructive.
        for (existing in enabledMods.values) {
            if (!mod.isCompatibleWith(existing) || !existing.isCompatibleWith(mod)) {
                return
            }
        }

        enabledMods[modClass] = mod

        val toggle = modToggles.find { it.mod::class == mod::class }

        if (toggle != null) {
            toggle.isSelected = true
            toggle.mod = mod
        }

        customizationMenu.onModAdded(mod)
        queueModChange(mod)
    }

    fun removeMod(mod: Mod) = removeMod(mod::class)

    fun removeMod(modClass: KClass<out Mod>) {
        val session = scopedSession

        if (session != null) {
            when (session.mode) {
                ScopedCourseMode.ALLOWLIST -> {
                    val toggle = modToggles.find { it.mod::class == modClass } ?: return

                    if (scopedAuthorSelection.remove(toggle.mod.acronym)) {
                        toggle.isSelected = false
                        queueModChange(toggle.mod)
                    }
                    return
                }

                ScopedCourseMode.PLAYER -> {
                    // Required mods cannot be removed. Settings are locked separately.
                    if (modClass.java in session.requiredClasses) {
                        return
                    }
                }
            }
        }

        if (modClass !in enabledMods) {
            return
        }

        val toggle = modToggles.find { it.mod::class == modClass } ?: return

        enabledMods.remove(modClass)

        toggle.isSelected = false

        customizationMenu.onModRemoved(toggle.mod)
        queueModChange(toggle.mod)

        toggle.mod = modClass.createInstance()
    }

    //endregion

    //region Components

    private class StatisticsBadge(
        private val formatter: (Float) -> String = { "%.2f".format(it) }
    ) : UILabeledBadge() {
        private val counter = RollingFloatCounter(0f).apply {
            rollingEasing = Easing.OutQuint
            rollingDuration = 0.3f
        }

        init {
            registerUpdateHandler(counter)
        }

        fun updateValue(initialValue: Float, finalValue: Float) {
            if (counter.targetValue == finalValue) {
                return
            }

            counter.targetValue = finalValue

            valueEntity.clearEntityModifiers()
            valueEntity.colorTo(Color4(when {
                initialValue < finalValue -> 0xFFF78383
                initialValue > finalValue -> 0xFF40CF5D
                else -> 0xFFFFFFFF
            }), counter.rollingDuration, counter.rollingEasing)
        }

        override fun onManagedUpdate(deltaTimeSec: Float) {
            super.onManagedUpdate(deltaTimeSec)
            // A long first frame can finish the entire roll. Render the settled value too.
            valueEntity.text = formatter(counter.currentValue)
        }
    }

    //endregion

}



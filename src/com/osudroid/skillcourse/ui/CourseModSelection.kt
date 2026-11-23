package com.osudroid.skillcourse.ui

import android.os.Handler
import android.os.Looper
import com.edlplan.ui.ActivityOverlay
import com.edlplan.ui.fragment.BaseFragment
import com.osudroid.data.BeatmapInfo
import com.osudroid.mods.IModApplicableToTrackRate
import com.osudroid.mods.Mod
import com.osudroid.mods.ModRateAdjust
import com.osudroid.mods.ModScoreV2
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseMap
import com.osudroid.skillcourse.CourseModPolicy
import com.osudroid.skillcourse.CoursePolicyException
import com.osudroid.ui.v2.modmenu.ModMenu
import com.osudroid.ui.v2.modmenu.ScopedCourseMode
import com.osudroid.ui.v2.modmenu.ScopedCourseSession
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.allModsInstances
import com.osudroid.utils.updateThread
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.LibraryManager

/**
 * Pure selection helpers for [CourseModSelection].
 *
 * These functions intentionally contain no Android or engine state so the course allowlist and
 * additional-mod extraction rules can be covered by JVM tests.
 */
internal object CourseModSelectionRules {

    /**
     * Returns the canonical acronyms of all user-playable mods in [initial], ignoring case and
     * silently dropping unknown or non-user-playable entries.
     */
    fun canonicalUserPlayableAcronyms(
        initial: Iterable<String>,
        mods: Iterable<Mod> = allModsInstances.asIterable()
    ): LinkedHashSet<String> {
        val playableByLowercase = mods
            .filter { it.isUserPlayable }
            .associateBy { it.acronym.lowercase(Locale.ROOT) }

        val result = LinkedHashSet<String>()
        for (acronym in initial) {
            playableByLowercase[acronym.lowercase(Locale.ROOT)]?.let { result.add(it.acronym) }
        }
        return result
    }

    /**
     * Maps canonical acronyms to their concrete mod classes, keeping only user-playable mods.
     */
    fun classesForAcronyms(
        acronyms: Iterable<String>,
        mods: Iterable<Mod> = allModsInstances.asIterable()
    ): Set<Class<out Mod>> {
        val canonical = canonicalUserPlayableAcronyms(acronyms, mods)
        return mods
            .filter { it.isUserPlayable && it.acronym in canonical }
            .map { it::class.java }
            .toSet()
    }

    /**
     * Builds the set of user-playable mod classes that a player must not be able to select.
     *
     * Required mods are always exempt (they are selected and locked instead). [additionallyRestricted]
     * is used for speedrun rate mods, which must remain controlled by the dedicated speed input even
     * when they appear in the course allowlist.
     */
    fun disallowedClasses(
        mods: Iterable<Mod>,
        requiredClasses: Set<Class<out Mod>>,
        allowedClasses: Set<Class<out Mod>>,
        additionallyRestricted: Set<Class<out Mod>> = emptySet()
    ): Set<Class<out Mod>> {
        val result = LinkedHashSet<Class<out Mod>>()

        for (mod in mods) {
            if (!mod.isUserPlayable) {
                continue
            }

            val modClass = mod::class.java
            if (modClass in requiredClasses) {
                continue
            }

            if (modClass !in allowedClasses || modClass in additionallyRestricted) {
                result.add(modClass)
            }
        }

        return result
    }

    /**
     * Converts the selected allowlist into a deterministic list of canonical, user-playable acronyms.
     */
    fun authorAllowedList(
        selected: Iterable<String>,
        mods: Iterable<Mod> = allModsInstances.asIterable()
    ): List<String> = canonicalUserPlayableAcronyms(selected, mods).sorted()

    /** Whitelisted mods can still conflict with the course's gauge or scoring rules. */
    fun policyRestrictedClasses(definition: CourseDefinition, map: CourseMap, speed: Float?): Set<Class<out Mod>> =
        allModsInstances.filter { candidate ->
            candidate.acronym in definition.allowedMods && map.mods.none { it.acronym == candidate.acronym } &&
                runCatching {
                    CourseModPolicy.effectiveMods(definition, map, ModHashMap(listOf(candidate.deepCopy())), speed)
                }.isFailure
        }.mapTo(linkedSetOf()) { it::class.java }

    /**
     * Extracts only the optional mods from an effective selection. Required (authored or injected)
     * mods are excluded, and every optional mod is deep-copied so later UI changes cannot mutate the
     * caller's instances.
     *
     * Incompatible optional selections are rejected rather than silently evicted by [ModHashMap.put].
     */
    fun additionalMods(
        selected: Map<Class<out Mod>, Mod>,
        requiredClasses: Set<Class<out Mod>>
    ): ModHashMap {
        val result = ModHashMap()

        for ((modClass, mod) in selected.entries.sortedBy { it.value.acronym }) {
            if (modClass in requiredClasses) {
                continue
            }

            for (existing in result.values) {
                if (!mod.isCompatibleWith(existing) || !existing.isCompatibleWith(mod)) {
                    throw CoursePolicyException(
                        "Mod ${mod.acronym} is incompatible with ${existing.acronym} (additional mods)."
                    )
                }
            }

            result.put(mod.deepCopy())
        }

        return result
    }
}

/**
 * Bridge that lets course editor/preflight fragments reuse the ordinary native mod selector in an
 * isolated, scoped session.
 *
 * Both entry points are expected to be called on the Android main thread. The `onSave` and `onError`
 * callbacks are always delivered on the Android main thread.
 */
object CourseModSelection {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var active: Active? = null

    private class Active(
        val suspension: ActivityOverlay.Suspension,
        val mode: ScopedCourseMode,
        val definition: CourseDefinition?,
        val map: CourseMap?,
        val speed: Float?,
        val requiredClasses: Set<Class<out Mod>>,
        val onPlayerSave: ((ModHashMap) -> Unit)?,
        val onAllowedSave: ((List<String>) -> Unit)?,
        val onError: (String) -> Unit
    ) {
        val finished = AtomicBoolean(false)
    }

    private sealed class SaveResult {
        class Player(val mods: ModHashMap) : SaveResult()
        class Allowed(val acronyms: List<String>) : SaveResult()
    }

    /**
     * Opens the native selector to author the course-wide acronym allowlist.
     *
     * @param initial Existing allowlist acronyms.
     * @param onSave Receives a sorted list of canonical, user-playable acronyms.
     * @param onError Receives a user-facing error message if the session cannot start or save.
     */
    @JvmStatic
    @JvmOverloads
    fun chooseAllowedMods(
        owner: BaseFragment,
        initial: Set<String>,
        onSave: (List<String>) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        onMain {
            if (!canStart(owner, onError)) {
                return@onMain
            }

            val initialAcronyms = CourseModSelectionRules.canonicalUserPlayableAcronyms(initial)
            launch(
                owner = owner,
                mode = ScopedCourseMode.ALLOWLIST,
                definition = null,
                map = null,
                speed = null,
                requiredClasses = emptySet(),
                previewBeatmap = null,
                initialMods = ModHashMap(),
                initialAllowedAcronyms = initialAcronyms,
                onPlayerSave = null,
                onAllowedSave = onSave,
                onError = onError
            )
        }
    }

    /**
     * Opens the native selector to choose one map's optional additions.
     *
     * The effective authored + additional + injected mod set is shown with required mods selected and
     * locked. The callback receives only optional additions, after [CourseModPolicy] validates them.
     *
     * @param speed The map's speedrun rate, or `null` outside speedrun courses.
     * @param onError Receives a user-facing error message if the session cannot start or save.
     */
    @JvmStatic
    @JvmOverloads
    fun choosePlayerMods(
        owner: BaseFragment,
        definition: CourseDefinition,
        map: CourseMap,
        additional: ModHashMap,
        speed: Float?,
        onSave: (ModHashMap) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        onMain {
            if (!canStart(owner, onError)) {
                return@onMain
            }

            val initialMods = try {
                CourseModPolicy.effectiveMods(definition, map, additional, speed)
            } catch (e: Exception) {
                onError(messageOf(e))
                return@onMain
            }

            val allowedAcronyms = CourseModSelectionRules.canonicalUserPlayableAcronyms(definition.allowedMods)
            val allowedClasses = CourseModSelectionRules.classesForAcronyms(allowedAcronyms)

            val requiredClasses = try {
                LinkedHashSet<Class<out Mod>>().apply {
                    addAll(CourseModPolicy.buildAuthoredMods(map).keys)

                    // Engine-injected choices are required too, even when an older saved addition
                    // happens to contain the same class.
                    if (definition.scoring == "scorev2") {
                        add(ModScoreV2::class.java)
                    }

                    if (definition.isSpeedrun) {
                        initialMods.values
                            .filterIsInstance<ModRateAdjust>()
                            .forEach { add(it::class.java) }
                    }
                }
            } catch (e: Exception) {
                onError(messageOf(e))
                return@onMain
            }

            // Speedrun rate is owned by the dedicated preflight rate control. Rate mods stay visible
            // but cannot be toggled, even when the course allowlist contains them.
            val additionallyRestricted = if (definition.isSpeedrun) {
                val rateClasses = LinkedHashSet<Class<out Mod>>()
                allModsInstances.forEach {
                    if (it is IModApplicableToTrackRate) {
                        rateClasses.add(it::class.java)
                    }
                }
                rateClasses
            } else {
                emptySet()
            }

            val restrictedClasses = CourseModSelectionRules.disallowedClasses(
                mods = allModsInstances.asIterable(),
                requiredClasses = requiredClasses,
                allowedClasses = allowedClasses,
                additionallyRestricted = additionallyRestricted + CourseModSelectionRules.policyRestrictedClasses(definition, map, speed)
            )

            launch(
                owner = owner,
                mode = ScopedCourseMode.PLAYER,
                definition = definition,
                map = map,
                speed = speed,
                requiredClasses = requiredClasses,
                previewBeatmap = findPreviewBeatmap(map),
                initialMods = initialMods,
                initialAllowedAcronyms = emptySet(),
                onPlayerSave = onSave,
                onAllowedSave = null,
                onError = onError,
                restrictedClasses = restrictedClasses,
                allowedClasses = allowedClasses
            )
        }
    }

    /**
     * Whether a native course selection session is currently open.
     */
    @JvmStatic
    fun isActive(): Boolean = active != null || ModMenu.hasScopedSession()

    private fun canStart(owner: BaseFragment, onError: (String) -> Unit): Boolean {
        if (active != null || ModMenu.hasScopedSession()) {
            onError("A course mod selection is already open.")
            return false
        }

        if (GlobalManager.getInstance().engine == null || !owner.isAdded || owner.activity == null || owner.view == null) {
            onError("The native mod selector is currently unavailable.")
            return false
        }

        return true
    }

    private fun launch(
        owner: BaseFragment,
        mode: ScopedCourseMode,
        definition: CourseDefinition?,
        map: CourseMap?,
        speed: Float?,
        requiredClasses: Set<Class<out Mod>>,
        previewBeatmap: BeatmapInfo?,
        initialMods: ModHashMap,
        initialAllowedAcronyms: Set<String>,
        onPlayerSave: ((ModHashMap) -> Unit)?,
        onAllowedSave: ((List<String>) -> Unit)?,
        onError: (String) -> Unit,
        allowedClasses: Set<Class<out Mod>> = emptySet(),
        restrictedClasses: Set<Class<out Mod>> = emptySet()
    ) {
        val suspension = ActivityOverlay.suspendOverlays(owner) { cancelActive() }

        if (suspension == null) {
            onError("Another native scene is already open over the course screen.")
            return
        }

        val selection = Active(
            suspension = suspension,
            mode = mode,
            definition = definition,
            map = map,
            speed = speed,
            requiredClasses = requiredClasses,
            onPlayerSave = onPlayerSave,
            onAllowedSave = onAllowedSave,
            onError = onError
        )

        synchronized(lock) {
            if (active != null) {
                ActivityOverlay.resumeOverlays(suspension)
                onError("A course mod selection is already open.")
                return
            }

            active = selection
        }

        val session = try {
            ScopedCourseSession(
                mode = mode,
                requiredClasses = requiredClasses,
                allowedClasses = allowedClasses,
                restrictedClasses = restrictedClasses,
                previewBeatmap = previewBeatmap,
                initialMods = initialMods,
                initialAllowedAcronyms = initialAllowedAcronyms,
                onSave = { saveActive(selection) },
                onCancel = { cancelActive() }
            )
        } catch (e: Exception) {
            release(selection)
            onError(messageOf(e))
            return
        }

        val engine = GlobalManager.getInstance().engine

        if (engine == null) {
            release(selection)
            onError("The native mod selector is currently unavailable.")
            return
        }

        try {
            updateThread {
                if (selection.finished.get()) {
                    return@updateThread
                }

                try {
                    ModMenu.showScoped(session)
                } catch (e: Exception) {
                    if (selection.finished.compareAndSet(false, true)) {
                        synchronized(lock) {
                            if (active === selection) {
                                active = null
                            }
                        }

                        try {
                            ModMenu.endScopedSession()
                        } catch (_: Exception) {
                        }

                        runOnMain {
                            ActivityOverlay.resumeOverlays(selection.suspension)
                            selection.onError(messageOf(e))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            release(selection)
            onError(messageOf(e))
        }
    }

    private fun saveActive(selection: Active) {
        if (active !== selection) {
            return
        }

        val result = try {
            when (selection.mode) {
                ScopedCourseMode.PLAYER -> {
                    val definition = selection.definition
                        ?: throw IllegalArgumentException("Missing course definition.")
                    val map = selection.map
                        ?: throw IllegalArgumentException("Missing course map.")

                    val selected = ModMenu.scopedSelectedMods()
                    val additions = CourseModSelectionRules.additionalMods(selected, selection.requiredClasses)

                    // Save must validate the same policy the runtime will use before the caller sees it.
                    CourseModPolicy.effectiveMods(definition, map, additions, selection.speed)

                    SaveResult.Player(additions)
                }

                ScopedCourseMode.ALLOWLIST -> SaveResult.Allowed(
                    CourseModSelectionRules.authorAllowedList(ModMenu.scopedAuthorSelection())
                )
            }
        } catch (e: Exception) {
            runOnMain {
                if (!selection.finished.get()) {
                    selection.onError(messageOf(e))
                }
            }
            return
        }

        if (!selection.finished.compareAndSet(false, true)) {
            return
        }

        synchronized(lock) {
            if (active === selection) {
                active = null
            }
        }

        try {
            ModMenu.endScopedSession()
        } catch (_: Exception) {
            // The overlay must still be restored even if the engine scene is already tearing down.
        }

        runOnMain {
            ActivityOverlay.resumeOverlays(selection.suspension)

            when (result) {
                is SaveResult.Player -> selection.onPlayerSave?.invoke(result.mods)
                is SaveResult.Allowed -> selection.onAllowedSave?.invoke(result.acronyms)
            }
        }
    }

    private fun cancelActive() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val selection = takeActive() ?: return

            if (!selection.finished.compareAndSet(false, true)) {
                return
            }

            val manager = GlobalManager.getInstance()
            val engine = manager.engine
            val activity = manager.mainActivity

            if (engine != null && activity != null && !activity.isDestroyed && !activity.isFinishing) {
                try {
                    updateThread {
                        try {
                            ModMenu.endScopedSession()
                        } catch (_: Exception) {
                        }

                        runOnMain {
                            ActivityOverlay.resumeOverlays(selection.suspension)
                        }
                    }
                } catch (_: Exception) {
                    try {
                        ModMenu.abandonScopedSession()
                    } catch (_: Exception) {
                    }

                    ActivityOverlay.resumeOverlays(selection.suspension)
                }
            } else {
                try {
                    ModMenu.abandonScopedSession()
                } catch (_: Exception) {
                    // The activity is already gone; the important part is that the overlay returns.
                }

                ActivityOverlay.resumeOverlays(selection.suspension)
            }
        } else {
            // ModMenu buttons and Back are handled on the engine update thread.
            val selection = takeActive() ?: return

            if (!selection.finished.compareAndSet(false, true)) {
                return
            }

            try {
                ModMenu.endScopedSession()
            } catch (_: Exception) {
            }

            runOnMain {
                ActivityOverlay.resumeOverlays(selection.suspension)
            }
        }
    }

    private fun takeActive(): Active? = synchronized(lock) {
        val selection = active
        active = null
        selection
    }

    private fun release(selection: Active) {
        if (!selection.finished.compareAndSet(false, true)) {
            return
        }

        synchronized(lock) {
            if (active === selection) {
                active = null
            }
        }

        ActivityOverlay.resumeOverlays(selection.suspension)
    }

    private fun findPreviewBeatmap(map: CourseMap): BeatmapInfo? = try {
        LibraryManager.findBeatmapByMD5(map.mapHash)
    } catch (_: Exception) {
        null
    }

    private fun messageOf(error: Throwable): String =
        error.message?.takeUnless { it.isBlank() } ?: error.javaClass.simpleName

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        onMain(block)
    }
}

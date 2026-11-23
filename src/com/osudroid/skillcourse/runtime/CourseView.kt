package com.osudroid.skillcourse.runtime

import com.osudroid.data.BeatmapInfo
import com.osudroid.data.BeatmapSetInfo
import com.osudroid.data.SkillCourseEntity
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseCodec
import com.osudroid.skillcourse.CourseMap
import com.osudroid.skillcourse.CourseModPolicy
import com.osudroid.skillcourse.CourseRepository
import com.osudroid.skillcourse.recovery.CourseAvailabilityCache
import com.osudroid.skillcourse.recovery.CourseAvailabilityListener
import com.osudroid.skillcourse.recovery.CourseAvailabilitySnapshot
import com.osudroid.skillcourse.recovery.CourseLibraryEvents
import com.osudroid.skillcourse.recovery.CourseMapStatus
import com.osudroid.ui.v2.modmenu.ModMenu
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.async
import com.osudroid.utils.updateThread
import java.util.concurrent.atomic.AtomicLong
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.LibraryManager
import ru.nsu.ccfit.zuev.osu.ToastLogger
import ru.nsu.ccfit.zuev.osu.menu.SongMenu

/** The installed maps currently projected by the course view. */
internal data class CourseViewProjection(
    val mapByInstalledHash: Map<String, CourseMap>,
    val outdated: Set<String>,
)

/**
 * An immutable snapshot of the active course view.
 *
 * The project is frozen when the view is entered and replaced only by a newer availability
 * snapshot. [generation] changes on every enter/exit so asynchronous boards can reject work that
 * belongs to a previous view.
 */
data class CourseViewState(
    val courseId: String,
    val courseHash: String,
    val definition: CourseDefinition,
    val mapByInstalledHash: Map<String, CourseMap>,
    val outdated: Set<String>,
    val generation: Long
) {
    /** Returns the authored identity for an installed map hash, if it is visible in this view. */
    fun authoredMapFor(installedHash: String?): CourseMap? =
        installedHash?.let { mapByInstalledHash[it] }

    /** Returns the authored index for an installed map hash, if it is visible in this view. */
    fun authoredIndexFor(installedHash: String?): Int? {
        val authored = authoredMapFor(installedHash) ?: return null
        return definition.maps.indexOfFirst { it.mapHash == authored.mapHash }.takeIf { it >= 0 }
    }

    fun isOutdated(installedHash: String?): Boolean =
        installedHash != null && installedHash in outdated
}

/**
 * Projects one availability snapshot into song-menu identities.
 *
 * The projection is rebuilt from scratch for every snapshot so an updated installed revision
 * replaces the previous candidate instead of leaving a stale hash selectable. Missing and invalid
 * entries deliberately contribute no identity.
 */
internal fun projectCourseView(snapshot: CourseAvailabilitySnapshot): CourseViewProjection {
    val visible = LinkedHashMap<String, CourseMap>()
    val outdated = LinkedHashSet<String>()

    for (entry in snapshot.entries) {
        if (entry.status != CourseMapStatus.READY && entry.status != CourseMapStatus.OUTDATED) {
            continue
        }
        for (candidate in entry.candidates) {
            val hash = candidate.md5
            if (hash.isBlank()) {
                continue
            }
            visible[hash] = entry.courseMap
            if (entry.status == CourseMapStatus.OUTDATED) {
                outdated += hash
            }
        }
    }

    return CourseViewProjection(visible, outdated)
}

/**
 * A temporary library projection; no missing entry is removed from the saved course.
 *
 * While active the view observes [CourseAvailabilityCache]. Opening the view never scans files.
 * Cached projections are published only for the current view token and library generation, so
 * delayed cache work cannot resurrect a view that the player has already exited.
 */
object CourseView {
    private data class Context(
        val courseId: String,
        val courseHash: String,
        val definition: CourseDefinition,
        val mapByInstalledHash: Map<String, CourseMap>,
        val outdated: Set<String>,
        val previousMods: ModHashMap
    )

    @Volatile private var context: Context? = null
    private var lastAppliedHash: String? = null

    /** Invalidates every pending enter/rescan when the view is replaced or exited. */
    private val generation = AtomicLong(0)

    /** Coalesces overlapping import-triggered rescans; only the newest one may publish. */
    private val rescanSequence = AtomicLong(0)

    @Volatile private var availabilityListener: CourseAvailabilityListener? = null

    @JvmStatic fun isActive() = context != null

    /** Immutable identity of the active course view, or null after exit. */
    @JvmStatic fun activeState(): CourseViewState? {
        val current = context ?: return null
        return CourseViewState(
            courseId = current.courseId,
            courseHash = current.courseHash,
            definition = current.definition,
            mapByInstalledHash = current.mapByInstalledHash,
            outdated = current.outdated,
            generation = generation.get()
        )
    }

    @JvmStatic fun activeCourseId(): String? = context?.courseId
    @JvmStatic fun activeCourseHash(): String? = context?.courseHash
    @JvmStatic fun activeDefinition(): CourseDefinition? = context?.definition
    @JvmStatic fun activeGeneration(): Long = generation.get()

    /** Authored map identity for the installed hash selected in the course view. */
    @JvmStatic fun activeAuthoredMap(installedHash: String?): CourseMap? =
        activeState()?.authoredMapFor(installedHash)

    @JvmStatic fun activeAuthoredIndex(installedHash: String?): Int? =
        activeState()?.authoredIndexFor(installedHash)

    @JvmStatic fun cancelPendingEnter() {
        generation.incrementAndGet()
        rescanSequence.incrementAndGet()
    }

    fun enter(entity: SkillCourseEntity) {
        val token = generation.incrementAndGet()
        rescanSequence.incrementAndGet()

        async {
            try {
                val currentEntity = checkNotNull(CourseRepository.getEntity(entity.id))
                val definition = CourseCodec.decode(currentEntity.definitionJson, allowDraft = true)
                updateThread {
                    val global = GlobalManager.getInstance()
                    if (token != generation.get() || CourseRuntime.isBusy() || global.engine.scene !== global.songMenu.scene) {
                        return@updateThread
                    }

                    val previous = context?.previousMods ?: ModMenu.enabledMods.deepCopy()
                    publish(cachedSnapshot(currentEntity.courseHash), currentEntity, definition, previous)
                    installAvailabilityListener()
                    global.songMenu.enterCourseView()
                    context?.mapByInstalledHash?.keys?.firstNotNullOfOrNull {
                        LibraryManager.findBeatmapByMD5(it)
                    }?.let { global.songMenu.selectBeatmap(it, false) }

                    // Close the gap between reading the cache and installing the observer.
                    refreshCachedProjection()
                }
            } catch (e: Exception) {
                ToastLogger.showText("Unable to view the course: ${e.message}", true)
            }
        }
    }

    @JvmStatic fun filterSets(sets: List<BeatmapSetInfo>): List<BeatmapSetInfo> {
        val current = context ?: return sets
        return sets.mapNotNull { set ->
            val visible = set.beatmaps.filter { current.mapByInstalledHash.containsKey(it.md5) }
            if (visible.isEmpty()) null else set.copy(beatmaps = visible.toMutableList())
        }
    }

    @JvmStatic fun prepareSelection(beatmap: BeatmapInfo): Boolean {
        val current = context ?: return true
        val authored = current.mapByInstalledHash[beatmap.md5] ?: return false
        if (lastAppliedHash != beatmap.md5) {
            val mods = CourseModPolicy.effectiveMods(current.definition, authored, ModHashMap())
            GlobalManager.getInstance().selectedBeatmap = beatmap
            ModMenu.clear()
            mods.values.forEach { ModMenu.addMod(it.deepCopy()) }
            lastAppliedHash = beatmap.md5
            if (beatmap.md5 in current.outdated) {
                ToastLogger.showText("Outdated map: practice only. Download the course revision for a full run.", true)
            }
        }
        return true
    }

    @JvmStatic fun exit() {
        generation.incrementAndGet()
        rescanSequence.incrementAndGet()

        val previous = context
        context = null
        lastAppliedHash = null
        removeAvailabilityListener()

        if (previous == null) {
            return
        }

        ModMenu.clear()
        previous.previousMods.values.forEach { ModMenu.addMod(it.deepCopy()) }
        GlobalManager.getInstance().songMenu.restoreCourseView()
    }

    /** Must run on the engine update thread. */
    private fun publish(
        snapshot: CourseAvailabilitySnapshot,
        entity: SkillCourseEntity,
        definition: CourseDefinition,
        previousMods: ModHashMap
    ) {
        val projection = projectCourseView(snapshot)
        context = Context(
            courseId = entity.id,
            courseHash = entity.courseHash,
            definition = definition,
            mapByInstalledHash = projection.mapByInstalledHash,
            outdated = projection.outdated,
            previousMods = previousMods
        )
        lastAppliedHash = null
    }

    @Synchronized
    private fun installAvailabilityListener() {
        if (availabilityListener != null) {
            return
        }
        val listener = CourseAvailabilityListener { hash ->
            if (hash == null || hash == context?.courseHash) refreshCachedProjection()
        }
        availabilityListener = listener
        CourseAvailabilityCache.addListener(listener)
    }

    @Synchronized
    private fun removeAvailabilityListener() {
        val listener = availabilityListener ?: return
        availabilityListener = null
        CourseAvailabilityCache.removeListener(listener)
    }

    private fun cachedSnapshot(hash: String): CourseAvailabilitySnapshot =
        CourseAvailabilityCache.get(hash).snapshot
            ?: CourseAvailabilitySnapshot(emptyList(), false, emptyList(), CourseLibraryEvents.revision)

    private fun refreshCachedProjection() {
        val token = generation.get()
        val request = rescanSequence.incrementAndGet()
        updateThread {
            if (!isCurrent(token, request)) return@updateThread
            val current = context ?: return@updateThread
            val projection = projectCourseView(cachedSnapshot(current.courseHash))
            if (projection.mapByInstalledHash == current.mapByInstalledHash && projection.outdated == current.outdated) {
                return@updateThread
            }
            context = current.copy(
                mapByInstalledHash = projection.mapByInstalledHash,
                outdated = projection.outdated
            )
            lastAppliedHash = null
            refreshCourseViewIfVisible()
        }
    }

    private fun isCurrent(token: Long, request: Long): Boolean =
        token == generation.get() && request == rescanSequence.get() && context != null

    /**
     * Reloads the song menu for the new projection without touching the pre-course state captured by
     * [SongMenu.enterCourseView]. That saved state is what [SongMenu.restoreCourseView] uses on exit.
     */
    @JvmStatic fun refreshCourseViewIfVisible() {
        if (context == null) return
        val global = GlobalManager.getInstance()
        if (CourseRuntime.isBusy() || global.engine.scene !== global.songMenu.scene) {
            // An import may finish while a practice map or course scene owns the engine. Updating the
            // projection is safe, but reloading the song menu here would switch scenes under the
            // player. The next view entry or import refresh will publish the new projection.
            return
        }
        reloadCourseView()
    }

    private fun reloadCourseView() {
        val songMenu = GlobalManager.getInstance().songMenu
        val previousSelection = songMenu.selectedBeatmap?.md5

        songMenu.reload()
        songMenu.setFilter("", SongMenu.SortOrder.Title, false, null)

        val visible = context?.mapByInstalledHash.orEmpty()
        val selected = previousSelection?.takeIf { it in visible }?.let { LibraryManager.findBeatmapByMD5(it) }
            ?: visible.keys.firstNotNullOfOrNull { LibraryManager.findBeatmapByMD5(it) }
        selected?.let { songMenu.selectBeatmap(it, false) }
        songMenu.show()
    }
}

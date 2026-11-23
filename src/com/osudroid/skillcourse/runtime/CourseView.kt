package com.osudroid.skillcourse.runtime

import android.util.Log
import com.osudroid.data.BeatmapInfo
import com.osudroid.data.BeatmapSetInfo
import com.osudroid.data.SkillCourseEntity
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseMap
import com.osudroid.skillcourse.CourseModPolicy
import com.osudroid.skillcourse.CourseRepository
import com.osudroid.skillcourse.recovery.CourseAvailabilityScanner
import com.osudroid.skillcourse.recovery.CourseAvailabilitySnapshot
import com.osudroid.skillcourse.recovery.CourseLibraryEvents
import com.osudroid.skillcourse.recovery.CourseLibraryListener
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
 * While active the view subscribes to [CourseLibraryEvents]. Availability is always rebuilt from a
 * fresh snapshot, and the view only publishes it when the snapshot still belongs to the current
 * view token and library generation. A late enter or rescan therefore cannot resurrect a view that
 * the player has already exited.
 */
object CourseView {
    private data class Context(
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

    /** [CourseLibraryEvents] keeps only weak listener references, so the view holds it strongly. */
    @Volatile private var libraryListener: CourseLibraryListener? = null

    @JvmStatic fun isActive() = context != null

    @JvmStatic fun cancelPendingEnter() {
        generation.incrementAndGet()
        rescanSequence.incrementAndGet()
    }

    fun enter(entity: SkillCourseEntity) {
        val token = generation.incrementAndGet()
        rescanSequence.incrementAndGet()

        async {
            try {
                val definition = checkNotNull(CourseRepository.getCourse(entity.id))
                val snapshot = CourseAvailabilityScanner.scan(definition)
                updateThread {
                    val global = GlobalManager.getInstance()
                    if (token != generation.get() || CourseRuntime.isBusy() || global.engine.scene !== global.songMenu.scene) {
                        return@updateThread
                    }

                    val previous = context?.previousMods ?: ModMenu.enabledMods.deepCopy()
                    publish(snapshot, definition, previous)
                    installLibraryListener()
                    global.songMenu.enterCourseView()
                    context?.mapByInstalledHash?.keys?.firstNotNullOfOrNull {
                        LibraryManager.findBeatmapByMD5(it)
                    }?.let { global.songMenu.selectBeatmap(it, false) }

                    // The import may have committed between the scan and listener installation.
                    if (snapshot.libraryRevision < CourseLibraryEvents.revision) {
                        scheduleRescan()
                    }
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
        removeLibraryListener()

        if (previous == null) {
            return
        }

        ModMenu.clear()
        previous.previousMods.values.forEach { ModMenu.addMod(it.deepCopy()) }
        GlobalManager.getInstance().songMenu.restoreCourseView()
    }

    /** Must run on the engine update thread. */
    private fun publish(snapshot: CourseAvailabilitySnapshot, definition: CourseDefinition, previousMods: ModHashMap) {
        val projection = projectCourseView(snapshot)
        context = Context(
            definition = definition,
            mapByInstalledHash = projection.mapByInstalledHash,
            outdated = projection.outdated,
            previousMods = previousMods
        )
        lastAppliedHash = null
    }

    @Synchronized
    private fun installLibraryListener() {
        if (libraryListener != null) {
            return
        }
        val listener = CourseLibraryListener { onLibraryImportCompleted() }
        libraryListener = listener
        CourseLibraryEvents.addListener(listener)
    }

    @Synchronized
    private fun removeLibraryListener() {
        val listener = libraryListener ?: return
        libraryListener = null
        CourseLibraryEvents.removeListener(listener)
    }

    private fun onLibraryImportCompleted() {
        if (context == null) {
            return
        }
        scheduleRescan()
    }

    private fun scheduleRescan() {
        val token = generation.get()
        val request = rescanSequence.incrementAndGet()

        async {
            try {
                if (!isCurrent(token, request)) {
                    return@async
                }

                val active = context ?: return@async
                val snapshot = CourseAvailabilityScanner.scan(active.definition)
                if (snapshot.libraryRevision < CourseLibraryEvents.revision) {
                    // A newer import committed while scanning. Let the newest event publish instead
                    // of applying a snapshot from an obsolete library generation.
                    if (isCurrent(token, request)) {
                        scheduleRescan()
                    }
                    return@async
                }

                updateThread {
                    if (!isCurrent(token, request)) {
                        return@updateThread
                    }
                    if (snapshot.libraryRevision < CourseLibraryEvents.revision) {
                        scheduleRescan()
                        return@updateThread
                    }

                    val current = context ?: return@updateThread
                    val projection = projectCourseView(snapshot)
                    context = current.copy(
                        mapByInstalledHash = projection.mapByInstalledHash,
                        outdated = projection.outdated
                    )
                    refreshCourseViewIfVisible()
                }
            } catch (e: Exception) {
                // A failed rescan keeps the previous projection; the next import retries it.
                Log.e("SkillCourse", "Unable to refresh the course view", e)
            }
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

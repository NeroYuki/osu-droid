package com.osudroid.skillcourse.recovery

import android.os.Handler
import android.os.Looper
import com.osudroid.skillcourse.CourseCodec
import com.osudroid.skillcourse.CourseRepository
import com.osudroid.utils.async
import ru.nsu.ccfit.zuev.osu.LibraryManager

/**
 * Process-wide, event-driven cache of local course availability.
 *
 * The cache is keyed by the immutable course hash and the committed beatmap-library revision. It is
 * warmed asynchronously after startup, after every committed library update and after a course
 * create/import/save/toggle-map transaction. The manager reads [get] snapshots instead of scanning
 * on every modal open.
 *
 * A snapshot is only served while its library revision is still current. Actual course starts must
 * continue to run a fresh exact-file integrity check; this cache is a UI acceleration only.
 */
object CourseAvailabilityCache {

    @Volatile
    private var core: CourseAvailabilityCacheCore? = null

    /** [CourseLibraryEvents] keeps only weak listener references, so the cache holds this strongly. */
    @Volatile
    private var libraryListener: CourseLibraryListener? = null

    /**
     * Installs the process-wide cache. Safe to call more than once; later calls are no-ops.
     *
     * [CourseClient][com.osudroid.skillcourse.runtime.CourseClient] calls this during startup.
     */
    @JvmStatic
    @Synchronized
    fun initialize() {
        if (core != null) {
            return
        }

        val cache = CourseAvailabilityCacheCore(
            schedule = { task -> async(Runnable { task.run() }) },
            scan = { definition, candidates, reader, revision ->
                CourseAvailabilityScanner.scan(definition, candidates, reader, revision)
            },
            definitions = ::loadDefinitions,
            librarySnapshot = { LibraryManager.getLibrary().flatMap { it.beatmaps } },
            readerFactory = {
                MemoizingBeatmapFileIdentityReader(
                    BeatmapFileIdentityReader { file -> readBeatmapFileIdentity(file) }
                )
            },
            revision = { CourseLibraryEvents.revision },
            dispatch = ::postMain,
        )
        core = cache

        val listener = CourseLibraryListener { cache.requestAll() }
        libraryListener = listener
        CourseLibraryEvents.addListener(listener)

        // The library may already be loaded when the cache is installed. This also covers a
        // startup where no library event has been published yet.
        cache.requestAll()
    }

    /** Current cache state. Missing/stale entries are checking placeholders, not synchronous scans. */
    @JvmStatic
    fun get(courseHash: String): CourseAvailabilityState =
        core?.get(courseHash) ?: CourseAvailabilityState(
            courseHash = courseHash,
            libraryRevision = CourseLibraryEvents.revision,
            isChecking = true,
            snapshot = null,
            failure = null,
        )

    /** A cached result only when it belongs to the current library revision. */
    @JvmStatic
    fun peek(courseHash: String): CourseAvailabilityState? = core?.peek(courseHash)

    @JvmStatic
    fun addListener(listener: CourseAvailabilityListener) {
        core?.addListener(listener)
    }

    @JvmStatic
    fun removeListener(listener: CourseAvailabilityListener) {
        core?.removeListener(listener)
    }

    /** Schedules one coalesced warm generation. Used after a committed library update. */
    @JvmStatic
    fun refreshAll() {
        coreOrInitialize()?.requestAll()
    }

    /** Schedules a scan for one committed course hash. */
    @JvmStatic
    fun requestCourse(courseHash: String) {
        coreOrInitialize()?.requestCourse(courseHash)
    }

    /** Evicts a deleted course so an in-flight scan cannot publish its obsolete result. */
    @JvmStatic
    fun evictCourse(courseHash: String) {
        core?.evictCourse(courseHash)
    }

    /**
     * Applies a committed course edit. The old hash is evicted when content changed; the new hash is
     * warmed. Lock-only edits must not call this method.
     */
    @JvmStatic
    fun onCourseChanged(previousHash: String?, newHash: String) {
        coreOrInitialize()?.onCourseChanged(previousHash, newHash)
    }

    private fun coreOrInitialize(): CourseAvailabilityCacheCore? {
        core?.let { return it }
        initialize()
        return core
    }

    private fun loadDefinitions(): List<CourseAvailabilityDefinition> =
        CourseRepository.listCourses().mapNotNull { entity ->
            try {
                CourseAvailabilityDefinition(
                    courseHash = entity.courseHash,
                    definition = CourseCodec.decode(entity.definitionJson, allowDraft = true),
                )
            } catch (_: Exception) {
                // Invalid rows are reported by the manager and editor; they have no availability.
                null
            }
        }

    private fun postMain(block: () -> Unit) {
        try {
            Handler(Looper.getMainLooper()).post(block)
        } catch (_: Exception) {
            // Unit-test JVMs may not have a prepared main looper. Running inline still preserves
            // listener ordering in that environment.
            block()
        }
    }
}

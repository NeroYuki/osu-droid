package com.osudroid.skillcourse.recovery

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Published after beatmap files and the Room library have been committed. */
data class CourseLibraryImportEvent(
    /** Monotonically increasing library generation. */
    @JvmField val revision: Long,

    /** Beatmap set directories touched by the import, relative to the beatmap path. */
    @JvmField val affectedDirectories: List<String>,
)

/**
 * Listener for successful beatmap-library commits.
 *
 * Listeners are held weakly so a forgotten activity or fragment cannot be leaked by this global
 * object. Callers must keep a strong reference to the listener for as long as they want events.
 */
fun interface CourseLibraryListener {
    fun onImportCompleted(event: CourseLibraryImportEvent)
}

/**
 * Process-wide completion signal for beatmap imports.
 *
 * Every caller that has successfully committed `.osu` files, reconciled Room rows and reloaded the
 * in-memory library should call [importCompleted]. Course availability must be refreshed from this
 * signal, not from a downloader callback alone.
 */
object CourseLibraryEvents {

    private val revisionCounter = AtomicLong(0)

    private val listeners = CopyOnWriteArrayList<WeakReference<CourseLibraryListener>>()

    /**
     * The current library generation. A scan captures this before reading the library and can be
     * discarded if a later commit has already advanced the revision.
     */
    @JvmStatic
    val revision: Long
        get() = revisionCounter.get()

    @JvmStatic
    fun addListener(listener: CourseLibraryListener) {
        pruneDeadListeners()
        if (listeners.none { it.get() === listener }) {
            listeners.add(WeakReference(listener))
        }
    }

    @JvmStatic
    fun removeListener(listener: CourseLibraryListener) {
        listeners.removeAll { it.get() == null || it.get() === listener }
    }

    /**
     * Advances the library revision and notifies listeners with the affected set directories.
     *
     * Returns the new revision. Listener exceptions are isolated so one bad observer cannot make a
     * successful import look failed.
     */
    @JvmStatic
    fun importCompleted(affectedDirectories: Collection<String>): Long {
        val directoryList = affectedDirectories
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val revision = revisionCounter.incrementAndGet()
        val event = CourseLibraryImportEvent(revision, directoryList)

        pruneDeadListeners()
        for (reference in listeners) {
            val listener = reference.get() ?: continue
            try {
                listener.onImportCompleted(event)
            } catch (_: Exception) {
                // A listener must not be able to turn a committed import into a failed import.
            }
        }

        return revision
    }

    internal fun listenerCount(): Int {
        pruneDeadListeners()
        return listeners.count { it.get() != null }
    }

    internal fun clearForTesting() {
        listeners.clear()
    }

    private fun pruneDeadListeners() {
        listeners.removeAll { it.get() == null }
    }
}

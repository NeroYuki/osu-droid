package com.osudroid.skillcourse.recovery

import com.osudroid.data.BeatmapInfo
import com.osudroid.skillcourse.CourseDefinition
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The availability state exposed for one course hash.
 *
 * The state is derived local data. A snapshot is only exposed while it belongs to the current
 * [CourseLibraryEvents] revision; otherwise [isChecking] is true and manager rows display a
 * checking placeholder instead of a stale result.
 */
data class CourseAvailabilityState(
    @JvmField val courseHash: String,
    @JvmField val libraryRevision: Long,
    @JvmField val isChecking: Boolean,
    @JvmField val snapshot: CourseAvailabilitySnapshot?,
    @JvmField val failure: String?,
)

/**
 * Receives cache state transitions. [courseHash] is `null` when every course was invalidated.
 *
 * Callbacks are dispatched through the cache's dispatcher (the main thread in production), so a
 * listener may update views directly.
 */
fun interface CourseAvailabilityListener {
    fun onCourseAvailabilityChanged(courseHash: String?)
}

/** A course definition as the cache sees it. Production uses the committed entity hash. */
internal data class CourseAvailabilityDefinition(
    val courseHash: String,
    val definition: CourseDefinition,
)

/**
 * Memoizes file identities for one scan generation.
 *
 * The cache creates one reader per drain generation and shares it across every course in that
 * generation, so duplicate candidate files are parsed at most once. Unreadable files are cached as
 * `null` for the same generation. The reader is deliberately not shared with gameplay integrity
 * checks; those must keep reading the exact file at start time.
 */
internal class MemoizingBeatmapFileIdentityReader(
    private val delegate: BeatmapFileIdentityReader,
) : BeatmapFileIdentityReader {

    private val identities = HashMap<String, BeatmapFileIdentity?>()

    override fun read(file: File): BeatmapFileIdentity? {
        val key = file.absolutePath
        if (identities.containsKey(key)) {
            return identities[key]
        }
        return delegate.read(file).also { identities[key] = it }
    }
}

/**
 * Pure, testable cache core.
 *
 * All dependencies are injectable so scheduling, scanning and revision changes can be driven by a
 * test. The core never touches the filesystem under [lock]. A drain task reads one library snapshot
 * and creates one memoizing reader per generation, then scans the requested definitions. Results
 * are published only when their request serial and library revision are still current.
 */
internal class CourseAvailabilityCacheCore(
    private val schedule: (Runnable) -> Unit,
    private val scan: (
        definition: CourseDefinition,
        installedCandidates: Collection<BeatmapInfo>,
        reader: BeatmapFileIdentityReader,
        libraryRevision: Long,
    ) -> CourseAvailabilitySnapshot,
    private val definitions: () -> List<CourseAvailabilityDefinition>,
    private val librarySnapshot: () -> Collection<BeatmapInfo>,
    private val readerFactory: () -> BeatmapFileIdentityReader,
    private val revision: () -> Long,
    private val dispatch: (() -> Unit) -> Unit = { it() },
) {

    private data class Entry(
        val revision: Long,
        val isChecking: Boolean,
        val snapshot: CourseAvailabilitySnapshot?,
        val failure: String?,
    )

    private data class Batch(
        val scanAll: Boolean,
        val requestedHashes: List<String>?,
        val revision: Long,
    )

    private val lock = Any()

    private val entries = HashMap<String, Entry>()

    /** Per-course request serials reject a scan that was superseded while it was running. */
    private val requestSerials = HashMap<String, Long>()

    private val pendingHashes = LinkedHashSet<String>()

    /**
     * Deleted hashes remain tombstoned until that definition is committed again. A deletion that
     * races a repository/library snapshot must not let the older snapshot resurrect a scan result.
     */
    private val evictedHashes = HashSet<String>()

    private var pendingAll = false

    private var draining = false

    private var serialCounter = 0L

    private val listeners = CopyOnWriteArrayList<CourseAvailabilityListener>()

    fun addListener(listener: CourseAvailabilityListener) {
        if (listeners.none { it === listener }) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: CourseAvailabilityListener) {
        listeners.remove(listener)
    }

    /**
     * Returns the current state. A missing or stale entry is reported as checking; it is never
     * repaired synchronously by this call.
     */
    fun get(courseHash: String): CourseAvailabilityState {
        val currentRevision = revision()
        return synchronized(lock) {
            val entry = entries[courseHash]
            if (entry == null || entry.revision != currentRevision) {
                CourseAvailabilityState(
                    courseHash = courseHash,
                    libraryRevision = currentRevision,
                    isChecking = true,
                    snapshot = null,
                    failure = null,
                )
            } else {
                CourseAvailabilityState(
                    courseHash = courseHash,
                    libraryRevision = currentRevision,
                    isChecking = entry.isChecking,
                    snapshot = entry.snapshot,
                    failure = entry.failure,
                )
            }
        }
    }

    /** Returns a cached result only when it belongs to the current library revision. */
    fun peek(courseHash: String): CourseAvailabilityState? {
        val currentRevision = revision()
        return synchronized(lock) {
            val entry = entries[courseHash]
            if (entry == null || entry.revision != currentRevision) {
                null
            } else {
                CourseAvailabilityState(
                    courseHash = courseHash,
                    libraryRevision = currentRevision,
                    isChecking = entry.isChecking,
                    snapshot = entry.snapshot,
                    failure = entry.failure,
                )
            }
        }
    }

    /**
     * Invalidates every cached result for a committed library change and schedules one coalesced
     * warm generation. Called after the library list and database rows have been committed.
     */
    fun requestAll() {
        val currentRevision = revision()
        synchronized(lock) {
            val serial = ++serialCounter
            // In-flight scans may not have an entry yet. Invalidate their serials too so a second
            // library event cannot let an older scan publish after it finishes.
            val hashes = entries.keys + requestSerials.keys
            for (hash in hashes) {
                requestSerials[hash] = serial
                if (entries.containsKey(hash)) {
                    entries[hash] = Entry(
                        revision = currentRevision,
                        isChecking = true,
                        snapshot = null,
                        failure = null,
                    )
                }
            }
            pendingAll = true
            pendingHashes.clear()
            scheduleDrainLocked()
        }
        notifyChanged(null)
    }

    /**
     * Invalidates one course hash and schedules a background scan.
     *
     * This is called by the repository facade after a create/import/save/toggle transaction has
     * committed. Lock-only edits deliberately do not call it.
     */
    fun requestCourse(courseHash: String) {
        if (courseHash.isBlank()) {
            return
        }

        val currentRevision = revision()
        synchronized(lock) {
            evictedHashes.remove(courseHash)
            requestSerials[courseHash] = ++serialCounter
            entries[courseHash] = Entry(
                revision = currentRevision,
                isChecking = true,
                snapshot = null,
                failure = null,
            )
            pendingHashes.add(courseHash)
            scheduleDrainLocked()
        }
        notifyChanged(courseHash)
    }

    /**
     * Evicts a deleted course. Any in-flight scan for it can no longer publish because its request
     * serial was removed.
     */
    fun evictCourse(courseHash: String) {
        if (courseHash.isBlank()) {
            return
        }

        synchronized(lock) {
            evictedHashes += courseHash
            pendingHashes.remove(courseHash)
            entries.remove(courseHash)
            requestSerials.remove(courseHash)
        }
        notifyChanged(courseHash)
    }

    /**
     * Applies an edit that may change the immutable course hash. The old hash is evicted and the
     * new definition is warmed.
     */
    fun onCourseChanged(previousHash: String?, newHash: String) {
        if (previousHash != null && previousHash != newHash) {
            evictCourse(previousHash)
        }
        requestCourse(newHash)
    }

    private fun scheduleDrainLocked() {
        if (draining) {
            return
        }
        draining = true
        schedule(Runnable { drain() })
    }

    private fun drain() {
        while (true) {
            val batch = synchronized(lock) {
                if (!pendingAll && pendingHashes.isEmpty()) {
                    draining = false
                    return
                }

                val batch = Batch(
                    scanAll = pendingAll,
                    requestedHashes = if (pendingAll) null else pendingHashes.toList(),
                    revision = revision(),
                )
                pendingAll = false
                pendingHashes.clear()
                batch
            }

            val loadedDefinitions = try {
                definitions()
            } catch (e: Exception) {
                failPending(batch, "Unable to list courses: ${messageOf(e)}")
                continue
            }

            val byHash = loadedDefinitions.associateBy { it.courseHash }

            val hashes = (batch.requestedHashes ?: byHash.keys.toList()).filter { it in byHash }
            if (hashes.isEmpty()) {
                continue
            }

            val installedCandidates = try {
                librarySnapshot()
            } catch (e: Exception) {
                failPending(
                    batch = batch,
                    message = "Unable to read the beatmap library: ${messageOf(e)}",
                    knownHashes = byHash.keys,
                )
                continue
            }
            val reader = readerFactory()

            for (hash in hashes) {
                // A newer library commit must never be scanned with this generation's snapshot.
                if (revision() != batch.revision) {
                    synchronized(lock) { pendingAll = true }
                    break
                }

                val definition = byHash[hash] ?: continue
                val serial = synchronized(lock) {
                    if (hash in evictedHashes) {
                        null
                    } else if (batch.scanAll) {
                        requestSerials.getOrPut(hash) { ++serialCounter }
                    } else {
                        requestSerials[hash]
                    }
                } ?: continue

                val snapshot = try {
                    scan(definition.definition, installedCandidates, reader, batch.revision)
                } catch (e: Exception) {
                    publish(hash, serial, batch.revision, null, messageOf(e))
                    continue
                }
                publish(hash, serial, batch.revision, snapshot, null)
            }
        }
    }

    private fun publish(
        courseHash: String,
        serial: Long,
        expectedRevision: Long,
        snapshot: CourseAvailabilitySnapshot?,
        failure: String?,
    ) {
        synchronized(lock) {
            // An import committed while this scan was running. Its own event will rescan.
            if (revision() != expectedRevision) {
                return
            }
            // A newer request (edit, lock-safe refresh, delete) superseded this scan.
            if (requestSerials[courseHash] != serial || courseHash in evictedHashes) {
                return
            }
            if (snapshot != null && snapshot.libraryRevision != expectedRevision) {
                return
            }

            entries[courseHash] = Entry(
                revision = expectedRevision,
                isChecking = false,
                snapshot = snapshot,
                failure = failure,
            )
        }
        notifyChanged(courseHash)
    }

    private fun failPending(
        batch: Batch,
        message: String,
        knownHashes: Collection<String>? = null,
    ) {
        val changed = ArrayList<String>()
        synchronized(lock) {
            if (revision() != batch.revision) {
                return
            }

            val targets = batch.requestedHashes ?: knownHashes ?: entries.keys.toList()
            for (hash in targets) {
                if (hash in evictedHashes) {
                    continue
                }

                val entry = entries[hash]
                if (entry != null && entry.revision != batch.revision) {
                    continue
                }
                if (entry != null && !entry.isChecking) {
                    continue
                }

                entries[hash] = Entry(
                    revision = batch.revision,
                    isChecking = false,
                    snapshot = null,
                    failure = message,
                )
                changed += hash
            }
        }
        for (hash in changed) {
            notifyChanged(hash)
        }
    }

    private fun notifyChanged(courseHash: String?) {
        if (listeners.isEmpty()) {
            return
        }
        dispatch {
            for (listener in listeners) {
                try {
                    listener.onCourseAvailabilityChanged(courseHash)
                } catch (_: Exception) {
                    // A closing fragment must not break the cache or other listeners.
                }
            }
        }
    }

    private fun messageOf(error: Throwable): String =
        error.message?.takeUnless { it.isBlank() } ?: error.javaClass.simpleName
}

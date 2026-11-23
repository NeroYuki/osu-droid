package com.osudroid.skillcourse.recovery

import android.os.Handler
import android.os.Looper
import com.osudroid.beatmaplisting.BeatmapDownloadResult
import com.osudroid.beatmaplisting.BeatmapDownloadCallback
import com.osudroid.beatmaplisting.BeatmapDownloadStart
import com.osudroid.beatmaplisting.BeatmapDownloadStatus
import com.osudroid.beatmaplisting.BeatmapDownloadToken
import com.osudroid.beatmaplisting.BeatmapDownloader
import com.osudroid.beatmaplisting.BeatmapListing
import com.osudroid.beatmaplisting.BeatmapSetModel
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import ru.nsu.ccfit.zuev.osu.Config

/** The outcome of one sequential recovery batch. */
data class CourseRecoveryResult(
    /**
     * Every authored entry that is not [CourseMapStatus.READY] after the final rescan. When the
     * final scan fails this is the last known snapshot and [allReady] remains false.
     */
    @JvmField val unresolved: List<CourseMapAvailability>,

    /** Per-entry and per-set messages, including manual-import guidance. */
    @JvmField val errors: List<String>,

    @JvmField val canceled: Boolean,

    /** True only when the final rescan ran without throwing. */
    @JvmField val finalScanSucceeded: Boolean = false,

    /**
     * True when the final rescan succeeded, returned a non-empty snapshot and covered every
     * authored map. Unknown or failed final scans are never treated as complete.
     */
    @JvmField val finalScanComplete: Boolean = finalScanSucceeded,
) {
    /**
     * True only when a complete final scan observed every authored map as ready. An empty, failed,
     * or unknown scan must not claim readiness just because no unresolved entries were listed.
     */
    val allReady: Boolean
        get() = finalScanComplete && unresolved.isEmpty()
}

/** Handle for a running recovery batch. */
interface CourseRecoveryHandle {
    val isActive: Boolean
    val isCanceled: Boolean

    /**
     * Stops scheduling new sets and cancels only the transfer reservation this batch owns at a safe
     * point. An unrelated listing or multiplayer download is never canceled.
     */
    fun cancel()
}

/**
 * Sequential course-map repair through the existing beatmap mirror.
 *
 * Only one batch may run at a time. Downloads are deduplicated by parent set and processed one set
 * at a time. A set is only considered repaired after the archive importer has validated the exact
 * revisions, committed files and Room rows, reloaded the library and published a library event. The
 * course is rescanned between sets. This object never starts a course automatically.
 */
object CourseMapRecovery {

    private val lock = Any()

    private var activeBatch: CourseRecoveryBatch? = null

    @JvmStatic
    val hasActiveBatch: Boolean
        get() = synchronized(lock) { activeBatch != null }

    /**
     * Starts a recovery batch. Callbacks are delivered on the main thread. The method returns
     * immediately; all scanning, mirror lookup and downloading happens on a background thread.
     */
    @JvmStatic
    fun start(
        definition: CourseDefinition,
        onProgress: (String) -> Unit,
        onComplete: (CourseRecoveryResult) -> Unit,
    ): CourseRecoveryHandle {
        val batch = synchronized(lock) {
            if (activeBatch != null) {
                null
            } else {
                createRealBatch(definition, onProgress, onComplete).also { activeBatch = it }
            }
        }

        if (batch == null) {
            val rejected = RejectedRecoveryHandle()
            Thread({
                val unresolved = try {
                    CourseAvailabilityScanner.scan(definition).entries
                        .filter { it.status != CourseMapStatus.READY }
                } catch (_: Exception) {
                    emptyList()
                }
                val result = CourseRecoveryResult(
                    unresolved = unresolved,
                    errors = listOf(
                        "Another course map recovery is already running. " +
                            "Wait for it to finish or cancel it before retrying."
                    ),
                    canceled = false,
                )
                postMain { onComplete(result) }
            }, "skill-course-recovery-rejected").start()
            return rejected
        }

        batch.startAsync()
        return batch.handle
    }

    private fun createRealBatch(
        definition: CourseDefinition,
        onProgress: (String) -> Unit,
        onComplete: (CourseRecoveryResult) -> Unit,
    ): CourseRecoveryBatch {
        val mirror = BeatmapListing.mirror

        return CourseRecoveryBatch(
            definition = definition,
            scan = { CourseAvailabilityScanner.scan(definition) },
            resolveSet = { beatmapId -> mirror.handleBeatmapLink(beatmapId) },
            setUrl = { set ->
                mirror.download.request(set.id, !Config.isPreferNoVideoDownloads()).toString()
            },
            setFilename = { set -> "${set.id} ${set.artist} - ${set.title}" },
            startDownload = { url, filename, expectedMaps, callback, token ->
                BeatmapDownloader.downloadWithResult(
                    url,
                    filename,
                    expectedMaps,
                    BeatmapDownloadCallback { callback(it) },
                    token,
                )
            },
            cancelDownload = { token -> BeatmapDownloader.cancel(token) },
            dispatch = ::postMain,
            sleep = { milliseconds -> Thread.sleep(milliseconds) },
            onProgress = onProgress,
            onComplete = onComplete,
        ).also { batch ->
            batch.onFinished = {
                synchronized(lock) {
                    if (activeBatch === batch) {
                        activeBatch = null
                    }
                }
            }
        }
    }

    /** Test-only access to the one-active-batch guard. */
    internal fun startForTesting(batch: CourseRecoveryBatch): CourseRecoveryHandle {
        synchronized(lock) {
            if (activeBatch != null) {
                return RejectedRecoveryHandle()
            }
            activeBatch = batch
            batch.onFinished = {
                synchronized(lock) {
                    if (activeBatch === batch) {
                        activeBatch = null
                    }
                }
            }
            batch.startAsync()
            return batch.handle
        }
    }

    /** Clears the singleton between isolated tests. */
    internal fun clearActiveForTesting() {
        synchronized(lock) { activeBatch = null }
    }

    private fun postMain(block: () -> Unit) {
        try {
            Handler(Looper.getMainLooper()).post(block)
        } catch (_: Exception) {
            // Unit-test JVMs may not have a main looper. Running inline preserves ordering there.
            block()
        }
    }
}

private class RejectedRecoveryHandle : CourseRecoveryHandle {
    override val isActive: Boolean = false
    override val isCanceled: Boolean = false
    override fun cancel() = Unit
}

/**
 * Implementation of one sequential batch. Dependency parameters are injectable so queue behavior
 * can be tested without a live mirror or UI.
 */
internal class CourseRecoveryBatch(
    private val definition: CourseDefinition,
    private val scan: () -> CourseAvailabilitySnapshot,
    private val resolveSet: (Long) -> BeatmapSetModel?,
    private val setUrl: (BeatmapSetModel) -> String,
    private val setFilename: (BeatmapSetModel) -> String,
    private val startDownload: (
        url: String,
        filename: String,
        expectedMaps: List<CourseMap>,
        callback: (BeatmapDownloadResult) -> Unit,
        token: BeatmapDownloadToken,
    ) -> BeatmapDownloadStart,
    private val cancelDownload: (BeatmapDownloadToken) -> Unit,
    private val dispatch: (() -> Unit) -> Unit,
    private val sleep: (Long) -> Unit,
    private val onProgress: (String) -> Unit,
    private val onComplete: (CourseRecoveryResult) -> Unit,
) {

    internal var onFinished: (() -> Unit)? = null

    private val canceled = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    /** Guards the token of the reservation this batch actually owns, not one it merely queued for. */
    private val downloadStateLock = Any()
    private var activeDownloadToken: BeatmapDownloadToken? = null

    val handle: CourseRecoveryHandle = object : CourseRecoveryHandle {
        override val isActive: Boolean
            get() = started.get() && !finished.get()

        override val isCanceled: Boolean
            get() = canceled.get()

        override fun cancel() {
            if (finished.get()) {
                return
            }
            canceled.set(true)

            // Only cancel the reservation this batch has already started. While scanning, waiting
            // BUSY, or between sets there is no owned token, so another listing/multiplayer
            // download must be left alone.
            val token = synchronized(downloadStateLock) { activeDownloadToken }
            if (token != null) {
                try {
                    cancelDownload(token)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun startAsync() {
        started.set(true)
        Thread(::run, "skill-course-recovery").start()
    }

    fun run() {
        started.set(true)

        val errors = ArrayList<String>()
        var wasCanceled = false
        var mayScheduleMore = true
        var snapshot: CourseAvailabilitySnapshot? = null

        try {
            emitProgress("Checking course maps...")
            val initial = scan()
            snapshot = initial

            if (!canceled.get() && initial.entries.any { it.status != CourseMapStatus.READY }) {
                val jobs = buildJobs(initial.entries, errors)

                for ((index, job) in jobs.withIndex()) {
                    if (canceled.get()) {
                        wasCanceled = true
                        break
                    }

                    // Recheck before every set. An earlier import or a manual import may already
                    // have satisfied some or all of this set's authored entries.
                    val current = scan()
                    snapshot = current
                    val stillNeeded = job.expected.filter { expected ->
                        current.entryAt(expected.originalIndex)?.status != CourseMapStatus.READY
                    }

                    if (stillNeeded.isEmpty()) {
                        emitProgress("Maps from ${job.filename} are already available.")
                        continue
                    }

                    emitProgress("Downloading set ${index + 1} of ${jobs.size}: ${job.filename}")
                    val download = downloadSet(job, stillNeeded.map { it.map })

                    if (download.canceled || canceled.get()) {
                        wasCanceled = true
                        break
                    }

                    val result = download.result
                    if (result == null) {
                        errors += "Set ${job.filename}: download did not report a result."
                        continue
                    }

                    when (result.status) {
                        BeatmapDownloadStatus.CANCELED -> {
                            wasCanceled = true
                            mayScheduleMore = false
                        }

                        BeatmapDownloadStatus.FAILED -> {
                            errors += "Set ${job.filename}: ${result.error ?: "download failed"}."
                        }

                        BeatmapDownloadStatus.COMPLETED,
                        BeatmapDownloadStatus.IMPORT_FAILED -> {
                            val importResult = result.importResult
                            if (importResult == null) {
                                errors += "Set ${job.filename}: import did not report a result."
                            } else {
                                if (!importResult.success) {
                                    if (importResult.errors.isEmpty()) {
                                        errors += "Set ${job.filename}: required course revision " +
                                            "was not imported."
                                    } else {
                                        errors += importResult.errors.map {
                                            "Set ${job.filename}: $it"
                                        }
                                    }
                                }

                                if (importResult.failure ==
                                    com.osudroid.beatmaplisting.BeatmapImportFailure.RECOVERY_REQUIRED
                                ) {
                                    mayScheduleMore = false
                                }
                            }

                            // The importer only returns after the library event. Always rescan
                            // before advancing to another set.
                            val after = scan()
                            snapshot = after
                            for (expected in stillNeeded) {
                                val entry = after.entryAt(expected.originalIndex) ?: continue
                                if (entry.status != CourseMapStatus.READY) {
                                    errors += "Map ${expected.originalIndex + 1} " +
                                        "(${entry.courseMap.artist} - ${entry.courseMap.title} " +
                                        "[${entry.courseMap.difficulty}]): still ${entry.status}. " +
                                        "Required MD5 ${entry.courseMap.mapHash}."
                                }
                            }
                        }
                    }

                    if (!mayScheduleMore) {
                        break
                    }
                }
            }
        } catch (e: InterruptedException) {
            wasCanceled = true
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            errors += "Course recovery failed: ${e.message ?: e.javaClass.simpleName}."
        } finally {
            var finalScanSucceeded = false
            val finalSnapshot = try {
                scan().also {
                    snapshot = it
                    finalScanSucceeded = true
                }
            } catch (e: Exception) {
                errors += "Final course rescan failed: ${e.message ?: e.javaClass.simpleName}."
                snapshot
            }

            val unresolved = finalSnapshot?.entries
                ?.filter { it.status != CourseMapStatus.READY }
                ?: emptyList()
            val finalScanComplete = finalScanSucceeded &&
                (finalSnapshot?.entries?.let { entries ->
                    entries.isNotEmpty() && entries.size == definition.maps.size
                } == true)

            val result = CourseRecoveryResult(
                unresolved = unresolved,
                errors = errors.distinct(),
                canceled = wasCanceled || canceled.get(),
                finalScanSucceeded = finalScanSucceeded,
                finalScanComplete = finalScanComplete,
            )

            finished.set(true)
            try {
                onFinished?.invoke()
            } catch (_: Exception) {
            }

            dispatch { onComplete(result) }
        }
    }

    private fun buildJobs(
        entries: List<CourseMapAvailability>,
        errors: MutableList<String>,
    ): List<RecoveryJob> {
        val jobs = LinkedHashMap<Long, RecoveryJob>()

        for (entry in entries) {
            if (entry.status == CourseMapStatus.READY) {
                continue
            }

            val beatmapId = entry.courseMap.beatmapid
            if (beatmapId == null || beatmapId <= 0L) {
                errors += mapLabel(entry) + ": no online beatmap ID is available. " +
                    "Import the required revision manually (MD5 ${entry.courseMap.mapHash})."
                continue
            }

            val set = try {
                resolveSet(beatmapId)
            } catch (e: Exception) {
                errors += mapLabel(entry) + ": beatmap lookup failed (${e.message ?: "unknown " +
                    "error"}). Import the required revision manually " +
                    "(MD5 ${entry.courseMap.mapHash})."
                continue
            }

            if (set == null) {
                errors += mapLabel(entry) + ": the selected mirror did not return a beatmap set. " +
                    "Import the required revision manually (MD5 ${entry.courseMap.mapHash})."
                continue
            }

            val job = jobs.getOrPut(set.id) {
                RecoveryJob(
                    setId = set.id,
                    url = setUrl(set),
                    filename = setFilename(set),
                    expected = ArrayList(),
                )
            }

            if (job.expected.none {
                    it.originalIndex == entry.originalIndex ||
                        it.map.mapHash.equals(entry.courseMap.mapHash, ignoreCase = true)
                }
            ) {
                job.expected += ExpectedEntry(entry.originalIndex, entry.courseMap)
            }
        }

        return jobs.values.toList()
    }

    private fun downloadSet(
        job: RecoveryJob,
        expectedMaps: List<CourseMap>,
    ): DownloadWait {
        val latch = CountDownLatch(1)
        val result = AtomicReference<BeatmapDownloadResult?>(null)
        val callback: (BeatmapDownloadResult) -> Unit = {
            result.set(it)
            latch.countDown()
        }
        val token = BeatmapDownloadToken()

        var start = try {
            startDownload(job.url, job.filename, expectedMaps, callback, token)
        } catch (e: Exception) {
            return DownloadWait(
                result = BeatmapDownloadResult(
                    status = BeatmapDownloadStatus.FAILED,
                    suggestedFilename = job.filename,
                    importResult = null,
                    error = e.message ?: e.javaClass.simpleName,
                ),
                canceled = false,
            )
        }

        // A shared downloader reservation is respected. Queue behind an external download instead
        // of dropping the request or polling UI visibility. No token is owned while waiting, so a
        // batch cancellation must simply stop retrying rather than cancel the external download.
        while (start == BeatmapDownloadStart.BUSY && !canceled.get()) {
            emitProgress("Waiting for the current beatmap download to finish...")
            sleep(BUSY_RETRY_MS)
            if (canceled.get()) {
                break
            }
            start = try {
                startDownload(job.url, job.filename, expectedMaps, callback, token)
            } catch (e: Exception) {
                BeatmapDownloadStart.FAILED
            }
        }

        if (canceled.get() && start != BeatmapDownloadStart.STARTED) {
            return DownloadWait(null, true)
        }

        if (start != BeatmapDownloadStart.STARTED) {
            return DownloadWait(
                BeatmapDownloadResult(
                    status = BeatmapDownloadStatus.FAILED,
                    suggestedFilename = job.filename,
                    importResult = null,
                    error = if (start == BeatmapDownloadStart.BUSY) {
                        "the beatmap downloader stayed busy"
                    } else {
                        "the download could not be started"
                    },
                ),
                canceled = false,
            )
        }

        synchronized(downloadStateLock) {
            activeDownloadToken = token
        }

        try {
            emitProgress("Importing ${job.filename}...")

            var waited = 0L
            var cancelSent = false
            while (true) {
                if (latch.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                    return DownloadWait(result.get(), false)
                }

                waited += POLL_MS

                if (canceled.get()) {
                    if (!cancelSent) {
                        cancelSent = true
                        try {
                            cancelDownload(token)
                        } catch (_: Exception) {
                        }
                        emitProgress(
                            "Waiting for the current download or import to finish before stopping..."
                        )
                    } else if (waited % CANCEL_WAIT_PROGRESS_MS == 0L) {
                        emitProgress(
                            "Waiting for the current download or import to finish before stopping..."
                        )
                    }

                    // The downloader keeps the reservation until its actual callback, including
                    // after an import has started. Do not return early: the importer may still be
                    // committing/rolling back files, and the next set must not race it.
                }
            }
        } catch (e: InterruptedException) {
            // Thread cancellation still releases this batch's own transfer (an import commit is
            // allowed to finish) instead of orphaning the global downloader.
            try {
                cancelDownload(token)
            } catch (_: Exception) {
            }
            throw e
        } finally {
            synchronized(downloadStateLock) {
                if (activeDownloadToken === token) {
                    activeDownloadToken = null
                }
            }
        }
    }

    private fun emitProgress(message: String) {
        dispatch { onProgress(message) }
    }

    private fun mapLabel(entry: CourseMapAvailability): String =
        "Map ${entry.originalIndex + 1} " +
            "(${entry.courseMap.artist} - ${entry.courseMap.title} " +
            "[${entry.courseMap.difficulty}])"

    private data class ExpectedEntry(val originalIndex: Int, val map: CourseMap)

    private data class RecoveryJob(
        val setId: Long,
        val url: String,
        val filename: String,
        val expected: MutableList<ExpectedEntry>,
    )

    private data class DownloadWait(
        val result: BeatmapDownloadResult?,
        val canceled: Boolean,
    )

    private companion object {
        const val BUSY_RETRY_MS = 500L
        const val POLL_MS = 200L
        const val CANCEL_WAIT_PROGRESS_MS = 2_000L
    }
}

package com.osudroid.beatmaplisting

import android.os.Environment.DIRECTORY_DOWNLOADS
import android.view.View
import com.osudroid.resources.R.*
import com.reco1l.framework.net.FileRequest
import com.reco1l.framework.net.IFileRequestObserver
import com.osudroid.utils.mainThread
import com.osudroid.multiplayer.Multiplayer
import com.osudroid.skillcourse.CourseMap
import com.osudroid.ui.v1.DownloadFragment
import com.osudroid.utils.async
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.ToastLogger
import ru.nsu.ccfit.zuev.osu.helper.StringTable
import java.io.File

/** Synchronous reservation result for a download request. */
enum class BeatmapDownloadStart {
    STARTED,
    BUSY,
    FAILED,
}

/** Completion state reported after download, validated import and library commit. */
enum class BeatmapDownloadStatus {
    COMPLETED,
    IMPORT_FAILED,
    CANCELED,
    FAILED,
}

/**
 * The final result of one downloader reservation.
 *
 * [importResult] is only null for transfer failures/cancellation. A completed network transfer with
 * a failed import reports [BeatmapDownloadStatus.IMPORT_FAILED] and never pretends success.
 */
data class BeatmapDownloadResult(
    @JvmField val status: BeatmapDownloadStatus,
    @JvmField val suggestedFilename: String?,
    @JvmField val importResult: BeatmapImportResult?,
    @JvmField val error: String?,
)

fun interface BeatmapDownloadCallback {
    fun onCompleted(result: BeatmapDownloadResult)
}

/**
 * Opaque, caller-owned identity for one download subscription.
 *
 * A token is bound to the shared reservation only after [BeatmapDownloader.downloadWithResult]
 * returns [BeatmapDownloadStart.STARTED]. Scoped cancellation compares this identity under the
 * downloader lock, so a stale or queued caller can never cancel a newer reservation.
 */
class BeatmapDownloadToken

/**
 * Singleton beatmap downloader.
 *
 * The existing two-argument [download] remains source-compatible and silently declines when busy.
 * Course recovery uses [downloadWithResult] to receive a typed reservation and a completion result
 * after the shared archive importer has committed the files, Room rows and library event.
 */
object BeatmapDownloader : IFileRequestObserver {

    private val lock = Any()

    private var active: ActiveDownload? = null

    @JvmStatic
    val isBusy: Boolean
        get() = synchronized(lock) { active != null }

    @JvmStatic
    fun download(url: String, suggestedFilename: String) {
        downloadWithResult(url, suggestedFilename, emptyList(), null)
    }

    /**
     * Reserves the single global downloader for [url].
     *
     * This is the same reservation used by beatmap listing and multiplayer. A [BUSY] result does not
     * drop or queue the request internally; sequential callers (such as course recovery) wait and
     * retry the reservation.
     */
    @JvmStatic
    fun downloadWithResult(
        url: String,
        suggestedFilename: String,
        expectedMaps: List<CourseMap>,
        callback: BeatmapDownloadCallback?,
    ): BeatmapDownloadStart =
        downloadWithResult(url, suggestedFilename, expectedMaps, callback, null)

    /**
     * Reserves the single global downloader for [url] and binds [token] to it. Pass the same token
     * to [cancel] to cancel only this reservation, never a newer or unrelated one.
     */
    @JvmStatic
    fun downloadWithResult(
        url: String,
        suggestedFilename: String,
        expectedMaps: List<CourseMap>,
        callback: BeatmapDownloadCallback?,
        token: BeatmapDownloadToken?,
    ): BeatmapDownloadStart {
        val job: ActiveDownload

        synchronized(lock) {
            if (active != null || com.osudroid.skillcourse.runtime.CourseRuntime.isBusy()) {
                return BeatmapDownloadStart.BUSY
            }

            // Remove illegal characters in FAT32 file system.
            val safeFilename = suggestedFilename.replace("[\"*/:<>?\\\\|]".toRegex(), "_")
            val downloadsDirectory = try {
                GlobalManager.getInstance().mainActivity
                    .getExternalFilesDir(DIRECTORY_DOWNLOADS)
            } catch (_: Exception) {
                null
            } ?: return BeatmapDownloadStart.FAILED

            val file = downloadsDirectory.resolve("$safeFilename.osz")
            val request = try {
                FileRequest(file, url)
            } catch (_: Exception) {
                return BeatmapDownloadStart.FAILED
            }

            job = ActiveDownload(
                request = request,
                filename = safeFilename,
                expectedMaps = expectedMaps.toList(),
                callback = callback,
                token = token,
            )
            active = job
        }

        val fragment = DownloadFragment()
        job.fragment = fragment

        mainThread {
            if (synchronized(lock) { active !== job }) {
                return@mainThread
            }

            try {
                fragment.setDownloader(job.request) {
                    if (synchronized(lock) { active !== job }) {
                        return@setDownloader
                    }

                    job.phase = Phase.TRANSFER
                    fragment.text.visibility = View.VISIBLE
                    fragment.text.text = job.context.getString(string.beatmap_downloader_connecting)

                    fragment.button.visibility = View.VISIBLE
                    fragment.button.text = job.context.getString(string.beatmap_downloader_cancel)

                    job.request.observer = this@BeatmapDownloader

                    async {
                        job.request.execute()

                        mainThread {
                            if (synchronized(lock) { active === job }) {
                                fragment.text.text = StringTable.format(
                                    string.beatmap_downloader_downloading,
                                    job.filename,
                                )
                            }
                        }
                    }

                    fragment.button.setOnClickListener {
                        job.request.cancel()
                    }
                }
                fragment.show()
            } catch (e: Exception) {
                finishJob(
                    job,
                    BeatmapDownloadResult(
                        status = BeatmapDownloadStatus.FAILED,
                        suggestedFilename = job.filename,
                        importResult = null,
                        error = e.message ?: e.javaClass.simpleName,
                    ),
                )
            }
        }

        return BeatmapDownloadStart.STARTED
    }

    /**
     * Cancels the current transfer at a safe point. During import commit this only marks intent;
     * the import is allowed to complete or roll back before the reservation is released.
     */
    @JvmStatic
    fun cancel(): Boolean {
        val job = synchronized(lock) { active } ?: return false
        return cancelJob(job)
    }

    /**
     * Cancels the current transfer only when [token] still owns the reservation.
     *
     * The ownership check and the reservation lookup happen under the same lock as [finishJob], so
     * the stale-callback/new-owner window cannot cancel an unrelated download. A token that was
     * merely queued behind another owner or already released simply returns `false`.
     */
    @JvmStatic
    fun cancel(token: BeatmapDownloadToken?): Boolean {
        if (token == null) {
            return false
        }

        val job = synchronized(lock) {
            val current = active ?: return false
            if (!ownsReservation(token, current.token)) {
                return false
            }
            current
        }

        return cancelJob(job)
    }

    private fun cancelJob(job: ActiveDownload): Boolean = when (job.phase) {
        Phase.QUEUED -> {
            finishJob(
                job,
                BeatmapDownloadResult(
                    status = BeatmapDownloadStatus.CANCELED,
                    suggestedFilename = job.filename,
                    importResult = null,
                    error = null,
                ),
            )
            true
        }

        Phase.TRANSFER -> {
            job.request.cancel()
            true
        }

        Phase.IMPORTING -> true
        Phase.DONE -> false
    }

    override fun onDownloadEnd(downloader: FileRequest) {
        val job = activeJob(downloader) ?: return
        job.phase = Phase.IMPORTING

        mainThread {
            if (synchronized(lock) { active !== job }) {
                return@mainThread
            }

            val fragment = job.fragment ?: return@mainThread
            fragment.progressBar.visibility = View.GONE
            fragment.progressBar.isIndeterminate = true
            fragment.progressBar.visibility = View.VISIBLE

            fragment.text.text = StringTable.format(
                string.beatmap_downloader_importing,
                job.filename,
            )
            fragment.button.visibility = View.GONE
        }

        val importResult = try {
            BeatmapArchiveImporter.importArchive(downloader.file, job.expectedMaps)
        } catch (e: Exception) {
            BeatmapImportResult(
                success = false,
                archiveName = downloader.file.name,
                affectedDirectories = emptyList(),
                importedBeatmaps = emptyList(),
                identities = emptyList(),
                expectedMaps = emptyList(),
                errors = listOf(e.message ?: e.javaClass.simpleName),
                failure = BeatmapImportFailure.FILE_SYSTEM,
            )
        }

        if (!importResult.success) {
            mainThread {
                ToastLogger.showText(
                    "Import failed: " + importResult.errorSummary,
                    true,
                )
            }
        }

        finishJob(
            job,
            BeatmapDownloadResult(
                status = if (importResult.success) {
                    BeatmapDownloadStatus.COMPLETED
                } else {
                    BeatmapDownloadStatus.IMPORT_FAILED
                },
                suggestedFilename = job.filename,
                importResult = importResult,
                error = if (importResult.success) null else importResult.errorSummary,
            ),
        )
    }

    override fun onDownloadCancel(downloader: FileRequest) {
        val job = activeJob(downloader) ?: return
        finishJob(
            job,
            BeatmapDownloadResult(
                status = BeatmapDownloadStatus.CANCELED,
                suggestedFilename = job.filename,
                importResult = null,
                error = null,
            ),
        )
    }

    override fun onDownloadUpdate(downloader: FileRequest) {
        val job = activeJob(downloader) ?: return
        if (job.phase != Phase.TRANSFER) {
            return
        }

        val info = "\n%.3f mb/s (%d%%)".format(
            downloader.speedKbps / 1024,
            downloader.progress.toInt(),
        )

        mainThread {
            if (synchronized(lock) { active !== job }) {
                return@mainThread
            }
            val fragment = job.fragment ?: return@mainThread

            fragment.text.text = job.context.getString(string.beatmap_downloader_downloading)
                .format(job.filename) + info
            fragment.progressBar.isIndeterminate = false
            fragment.progressBar.progress = downloader.progress.toInt()
        }
    }

    override fun onDownloadFail(downloader: FileRequest, exception: Exception) {
        val job = activeJob(downloader) ?: return
        finishJob(
            job,
            BeatmapDownloadResult(
                status = BeatmapDownloadStatus.FAILED,
                suggestedFilename = job.filename,
                importResult = null,
                error = exception.message ?: exception.javaClass.simpleName,
            ),
        )
    }

    private fun activeJob(request: FileRequest): ActiveDownload? =
        synchronized(lock) { active?.takeIf { it.request === request } }

    /**
     * Releases the reservation exactly once, then posts UI dismissal and the completion callback to
     * the main thread. Releasing before the callback lets a sequential caller start the next set.
     */
    private fun finishJob(job: ActiveDownload, result: BeatmapDownloadResult) {
        val released = synchronized(lock) {
            if (active !== job) {
                false
            } else {
                active = null
                true
            }
        }

        if (!released) {
            return
        }

        job.phase = Phase.DONE

        mainThread {
            try {
                job.fragment?.dismiss()
            } catch (_: Exception) {
            }

            if (result.status == BeatmapDownloadStatus.COMPLETED && Multiplayer.isConnected) {
                try {
                    Multiplayer.roomScene?.onRoomBeatmapChange(Multiplayer.room!!.beatmap)
                } catch (_: Exception) {
                }
            }

            try {
                job.callback?.onCompleted(result)
            } catch (_: Exception) {
            }
        }
    }

    private enum class Phase {
        QUEUED,
        TRANSFER,
        IMPORTING,
        DONE,
    }

    private class ActiveDownload(
        val request: FileRequest,
        val filename: String,
        val expectedMaps: List<CourseMap>,
        val callback: BeatmapDownloadCallback?,
        val token: BeatmapDownloadToken?,
    ) {
        @Volatile
        var phase: Phase = Phase.QUEUED

        var fragment: DownloadFragment? = null

        val context
            get() = GlobalManager.getInstance().mainActivity
    }
}

/**
 * Pure ownership rule for scoped cancellation. Evaluated under the downloader lock so a reservation
 * released between the caller's request and the cancellation cannot match a newer token.
 */
internal fun ownsReservation(
    requestToken: BeatmapDownloadToken?,
    activeToken: BeatmapDownloadToken?,
): Boolean = requestToken != null && activeToken === requestToken

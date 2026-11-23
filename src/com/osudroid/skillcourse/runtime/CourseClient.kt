package com.osudroid.skillcourse.runtime

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.osudroid.skillcourse.ui.CourseUi
import com.osudroid.skillcourse.CourseRepository
import com.osudroid.utils.async
import com.osudroid.utils.updateThread
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.ToastLogger
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** File types whose intents are captured while a course owns the map library. */
private val DEFERRED_COURSE_EXTENSIONS = setOf("odc", "osz", "osk", "odr", "edr")

/**
 * Chooses the staged file extension without trusting a provider display name as proof of content.
 * The actual payload is still bounded and validated by its normal importer.
 */
internal fun deferredCourseFileExtension(displayName: String, mimeType: String?): String {
    val extension = displayName.substringAfterLast('.', "").lowercase()
    return extension.takeIf { it in DEFERRED_COURSE_EXTENSIONS }
        ?: if (mimeType == "application/json" || mimeType == "application/vnd.osudroid.course+json") "odc" else "osz"
}

/** Limits the staging copy before any importer sees the untrusted document. */
internal fun deferredCourseFileLimit(extension: String): Long =
    if (extension == "odc") 1024L * 1024 else 1024L * 1024 * 1024

/** Android/engine glue; the C5 path deliberately has no course-server dependencies. */
object CourseClient {
    private val incomingFiles = ConcurrentLinkedQueue<File>()
    private val queueLock = Any()
    private val importing = AtomicBoolean(false)
    @Volatile private var flushRequested = false
    @Volatile private var ready = false
    @Volatile private var holdingImports = false
    private val initialized = AtomicBoolean(false)

    @JvmStatic fun isReady() = ready

    fun holdDeferredImports() { holdingImports = true }

    @JvmStatic fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        CourseUi.onView = { CourseView.enter(it) }
        CourseUi.onPlay = { request ->
            updateThread { CourseRuntime.start(request.entity, request.definition, request.additionalMods, request.speeds, request.additionalModsByIndex) }
        }
        CourseUi.onHistory = { CourseResults.history(it) }
        CourseRuntime.onResult = { CourseResults.show(it) }
        async {
            try {
                val report = CourseAttemptStore.recover()
                if (!report.succeeded) {
                    report.errors.forEach { Log.e("SkillCourse", "Attempt recovery: ${it.attemptId}: ${it.message}") }
                    ToastLogger.showText("Some local course results could not be recovered. Their files were kept.", true)
                }
            } finally {
                ready = true
            }
        }
    }

    /** Copies a newly granted document while access is valid, but never updates a live run's maps. */
    @JvmStatic fun deferIncoming(activity: Activity, intent: Intent): Boolean {
        if (!isCourseBusy() || intent.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (uri.scheme != "file" && uri.scheme != "content") return false
        intent.action = Intent.ACTION_MAIN
        ToastLogger.showText("The file will be imported after leaving the course results.", true)
        async {
            try {
                val name = displayName(activity, uri)
                val mime = activity.contentResolver.getType(uri)
                val extension = deferredCourseFileExtension(name, mime)
                val file = File(activity.cacheDir, "course-pending-${UUID.randomUUID()}.$extension")
                val stream = if (uri.scheme == "file") File(checkNotNull(uri.path)).inputStream()
                    else checkNotNull(activity.contentResolver.openInputStream(uri)) { "The document could not be opened." }
                try {
                    stream.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytes = 0L
                            val limit = deferredCourseFileLimit(extension)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                check(bytes <= limit) { "The import exceeds its supported size." }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    synchronized(queueLock) {
                        incomingFiles += file
                        flushRequested = true
                    }

                    // The run may have ended while the document was being copied. In that case the
                    // newly staged file must still be drained even if the results screen already
                    // performed its flush request against an empty queue.
                    drainDeferredImports()
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
            } catch (e: Exception) {
                ToastLogger.showText("Could not queue the import: ${e.message}", true)
            }
        }
        return true
    }

    @JvmStatic fun flushDeferredImports() {
        synchronized(queueLock) {
            holdingImports = false
            flushRequested = true
        }
        drainDeferredImports()
    }

    private fun drainDeferredImports() {
        if (!flushRequested) return
        if (isCourseBusy()) {
            // Nothing to remember if the queue is already empty; a later staging completion
            // requests its own drain.
            synchronized(queueLock) {
                if (incomingFiles.isEmpty()) {
                    flushRequested = false
                }
            }
            return
        }
        if (!importing.compareAndSet(false, true)) return
        async {
            try {
                while (!isCourseBusy()) {
                    val file = incomingFiles.poll() ?: break

                    // Close the remaining window between the loop check and the import call. A run
                    // that has started owns the frozen maps; requeue and wait for its results.
                    if (isCourseBusy()) {
                        synchronized(queueLock) {
                            incomingFiles += file
                            flushRequested = true
                        }
                        break
                    }

                    if (!file.isFile) {
                        Log.w("SkillCourse", "Dropping a deferred course file that no longer exists")
                        continue
                    }
                    try {
                        GlobalManager.getInstance().mainActivity.importDeferredCourseFile(file)
                    } catch (e: Exception) {
                        // One bad document must not prevent later deferred files from being imported.
                        Log.e("SkillCourse", "Unable to import a deferred course file", e)
                        ToastLogger.showText("Could not import a deferred file: ${e.message}", true)
                    }
                }
            } finally {
                importing.set(false)
                val drainAgain = synchronized(queueLock) {
                    when {
                        incomingFiles.isEmpty() -> {
                            // The queue is drained. A later deferred staging completion requests
                            // its own drain, so this flag must not stay armed across runs.
                            flushRequested = false
                            false
                        }
                        isCourseBusy() -> {
                            // Keep the request pending; CourseResults.back() flushes again.
                            false
                        }
                        else -> {
                            flushRequested = true
                            true
                        }
                    }
                }
                if (drainAgain) {
                    drainDeferredImports()
                }
            }
        }
    }

    /** Library mutations also wait while preflight freezes the exact installed maps. */
    private fun isCourseBusy(): Boolean = CourseRuntime.isBusy() || holdingImports

    private fun displayName(activity: Activity, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).name
        return try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0).orEmpty() else ""
            }.orEmpty()
        } catch (_: Exception) { "" }
    }
}

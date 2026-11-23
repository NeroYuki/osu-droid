package com.osudroid.skillcourse.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.osudroid.skillcourse.CourseCodec
import com.osudroid.skillcourse.CourseRepository
import com.osudroid.utils.async
import com.osudroid.utils.mainThread
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import ru.nsu.ccfit.zuev.osuplus.R

/**
 * Android document-provider boundary for importing and exporting portable `.odc` courses.
 *
 * The host activity must forward its `onActivityResult` to [onActivityResult]. No arbitrary content
 * URI is treated as a filesystem path, and every read is bounded to one mebibyte.
 */
object CourseFiles {

    const val MIME_TYPE = "application/vnd.osudroid.course+json"
    const val EXTENSION = ".odc"

    /** Fixed request codes; distinct from existing replay/skin/beatmap import codes. */
    const val REQUEST_CODE_IMPORT = 0x5C01
    const val REQUEST_CODE_EXPORT = 0x5C02

    private const val MAX_BYTES = 1024 * 1024

    private var pendingExportCourseId: String? = null

    /** Each delivered course owns its URI, avoiding the legacy shared pending beatmap-file slot. */
    @JvmStatic
    fun consumeIncoming(activity: Activity, intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (uri.scheme != "file" && uri.scheme != "content") return false
        val mime = intent.type ?: runCatching { activity.contentResolver.getType(uri) }.getOrNull()
        val name = if (uri.scheme == "file") uri.lastPathSegment else displayName(activity, uri)
        if (mime != MIME_TYPE && mime != "application/json" && name?.endsWith(EXTENSION, true) != true) return false
        intent.action = Intent.ACTION_MAIN
        importUri(activity, uri)
        return true
    }

    @JvmStatic
    fun launchImport(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Providers may assign an unregistered MIME type to .odc, including files
            // created by ACTION_CREATE_DOCUMENT. Validate bounded UTF-8 JSON after selection.
        }

        try {
            activity.startActivityForResult(intent, REQUEST_CODE_IMPORT)
        } catch (_: ActivityNotFoundException) {
            CourseUi.showMessage(activity, activity.getString(R.string.skill_course_file_picker_unavailable))
        }
    }

    @JvmStatic
    fun launchExport(activity: Activity, courseId: String) {
        val entity = CourseRepository.getEntity(courseId)
        if (entity == null) {
            CourseUi.showMessage(activity, activity.getString(R.string.skill_course_export_unavailable))
            return
        }

        try {
            val definition = CourseCodec.decode(entity.definitionJson, allowDraft = true)
            CourseCodec.validate(definition, allowDraft = false)
        } catch (e: Exception) {
            CourseUi.showMessage(
                activity,
                activity.getString(R.string.skill_course_export_failed, messageOf(e))
            )
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, CourseUiLogic.suggestedFileName(entity.courseName))
        }

        try {
            pendingExportCourseId = courseId
            activity.startActivityForResult(intent, REQUEST_CODE_EXPORT)
        } catch (_: ActivityNotFoundException) {
            pendingExportCourseId = null
            CourseUi.showMessage(activity, activity.getString(R.string.skill_course_file_picker_unavailable))
        }
    }

    /**
     * Handles the document picker results routed by the host activity.
     *
     * @return `true` when the request code belonged to this component, including cancellation.
     */
    @JvmStatic
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            REQUEST_CODE_IMPORT -> {
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    importUri(activity, uri)
                }
                return true
            }

            REQUEST_CODE_EXPORT -> {
                val courseId = pendingExportCourseId
                pendingExportCourseId = null
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null && courseId != null) {
                    exportUri(activity, courseId, uri)
                }
                return true
            }
        }

        return false
    }

    @JvmStatic
    fun importUri(activity: Activity, uri: Uri) {
        CourseUi.showMessage(activity, activity.getString(R.string.skill_course_importing))
        async {
            try {
                val stream = activity.contentResolver.openInputStream(uri)
                if (stream == null) {
                    toast(activity, R.string.skill_course_import_no_stream)
                    return@async
                }

                val text = stream.use { readBoundedText(it) }
                importText(activity, text)
            } catch (e: FileTooLargeException) {
                toast(activity, R.string.skill_course_import_too_large)
            } catch (_: CharacterCodingException) {
                toast(activity, R.string.skill_course_import_invalid_encoding)
            } catch (e: SecurityException) {
                toast(activity, activity.getString(R.string.skill_course_import_failed, messageOf(e)))
            } catch (e: Exception) {
                toast(activity, activity.getString(R.string.skill_course_import_failed, messageOf(e)))
            }
        }
    }

    @JvmStatic
    fun importFile(activity: Activity, file: File) {
        CourseUi.showMessage(activity, activity.getString(R.string.skill_course_importing))
        async {
            try {
                if (!file.isFile) {
                    toast(activity, R.string.skill_course_import_no_stream)
                    return@async
                }
                if (file.length() > MAX_BYTES) {
                    toast(activity, R.string.skill_course_import_too_large)
                    return@async
                }

                val text = file.inputStream().buffered().use { readBoundedText(it) }
                importText(activity, text)
            } catch (e: FileTooLargeException) {
                toast(activity, R.string.skill_course_import_too_large)
            } catch (_: CharacterCodingException) {
                toast(activity, R.string.skill_course_import_invalid_encoding)
            } catch (e: Exception) {
                toast(activity, activity.getString(R.string.skill_course_import_failed, messageOf(e)))
            }
        }
    }

    /**
     * Best-effort display name for an opaque document URI. The extension is never trusted as proof
     * of the content; the bounded UTF-8 payload is always validated by the repository.
     */
    @JvmStatic
    fun displayName(activity: Activity, uri: Uri): String? {
        return try {
            activity.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount > 0) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun importText(activity: Activity, text: String) {
        try {
            CourseRepository.importCourse(text)
            mainThread {
                CourseUi.notifyCoursesChanged()
                CourseUi.showMessage(activity, activity.getString(R.string.skill_course_import_success))
            }
        } catch (e: Exception) {
            toast(activity, activity.getString(R.string.skill_course_import_failed, messageOf(e)))
        }
    }

    private fun exportUri(activity: Activity, courseId: String, uri: Uri) {
        async {
            try {
                val entity = CourseRepository.getEntity(courseId)
                if (entity == null) {
                    toast(activity, R.string.skill_course_export_unavailable)
                    return@async
                }

                val definition = CourseCodec.decode(entity.definitionJson, allowDraft = true)
                // Empty drafts and invalid data must never be exported as a portable course.
                CourseCodec.validate(definition, allowDraft = false)
                val json = CourseCodec.encode(definition)

                val output = activity.contentResolver.openOutputStream(uri, "wt")
                if (output == null) {
                    toast(activity, activity.getString(R.string.skill_course_export_failed, "no output stream"))
                    return@async
                }

                output.bufferedWriter(StandardCharsets.UTF_8).use { it.write(json) }
                toast(activity, R.string.skill_course_export_success)
            } catch (e: Exception) {
                toast(activity, activity.getString(R.string.skill_course_export_failed, messageOf(e)))
            }
        }
    }

    private fun readBoundedText(input: InputStream): String {
        val output = ByteArrayOutputStream(minOf(MAX_BYTES, 16 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0

        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            total += read
            if (total > MAX_BYTES) {
                throw FileTooLargeException()
            }
            output.write(buffer, 0, read)
        }

        val bytes = output.toByteArray()
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun toast(activity: Activity, messageRes: Int) {
        mainThread { CourseUi.showMessage(activity, activity.getString(messageRes)) }
    }

    private fun toast(activity: Activity, message: String) {
        mainThread { CourseUi.showMessage(activity, message) }
    }

    private fun messageOf(error: Throwable): String =
        error.message?.takeUnless { it.isBlank() } ?: error.javaClass.simpleName

    private class FileTooLargeException : Exception()
}

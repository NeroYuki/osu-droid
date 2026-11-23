package com.osudroid.skillcourse.runtime

import com.osudroid.data.SkillCourseAttemptEntity
import com.osudroid.skillcourse.CourseCodec
import com.osudroid.skillcourse.CourseRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.nsu.ccfit.zuev.osu.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** One journal record that could not be reconciled. Other records still proceed. */
data class CourseAttemptRecoveryError(
    val attemptId: String?,
    val message: String
)

/**
 * Outcome of one [CourseAttemptStore.recover] sweep.
 *
 * [errors] is not empty when at least one journal or unfinished database record could not be
 * repaired. Callers must not present a sweep with errors as a clean recovery.
 */
data class CourseAttemptRecoveryReport(
    val journalsScanned: Int = 0,
    val journalsReconciled: Int = 0,
    val unfinishedAttemptsInterrupted: Int = 0,
    val errors: List<CourseAttemptRecoveryError> = emptyList()
) {
    val succeeded: Boolean get() = errors.isEmpty()
}

/**
 * Minimal database surface used by [CourseAttemptStoreCore].
 *
 * Keeping the store independent of Android initialization lets the recovery logic be exercised
 * against an explicit root and an in-memory/fake repository.
 */
internal interface CourseAttemptSink {
    fun getAttempt(id: String): SkillCourseAttemptEntity?
    fun saveAttempt(attempt: SkillCourseAttemptEntity): SkillCourseAttemptEntity
    fun listUnfinishedAttempts(): List<SkillCourseAttemptEntity>
}

/**
 * Durable local attempt journal plus startup reconciliation.
 *
 * Every attempt owns exactly one directory below [attemptsRoot]. [persist] atomically replaces the
 * typed `result.json` snapshot in that directory before writing Room, so a database failure always
 * leaves the last complete snapshot available for the next [recover] sweep. Native `.odr` and
 * events sidecars are never read, rewritten, or removed here.
 *
 * [recover] is intended for a startup background thread. It never resumes a run: an `in_progress`
 * journal is reconciled as `interrupted`, while terminal outcomes are preserved. The journal file
 * is left untouched as evidence. Database reconciliation is independent of course existence so
 * history survives course deletion.
 */
internal class CourseAttemptStoreCore(
    attemptsRoot: File,
    private val sink: CourseAttemptSink,
    private val json: Json = Json { encodeDefaults = true }
) {
    private val root = attemptsRoot.absoluteFile

    /**
     * Writes the durable snapshot first, then the Room attempt row.
     *
     * The journal is deliberately not deleted after a successful upsert: it is the repair evidence
     * used when a later database write or process termination leaves Room behind.
     */
    fun persist(result: CourseResult): SkillCourseAttemptEntity {
        val directory = directoryFor(result.clientAttemptId)
        if (declaredDirectory(result.replayDirectory) != directory) {
            throw IllegalArgumentException(
                "Attempt replay directory does not match its attempt id."
            )
        }

        val resultJson = encodeResult(result)
        val entity = entityFor(result, resultJson)

        ensureDirectory(directory)
        atomicWrite(File(directory, RESULT_FILE), resultJson.toByteArray(StandardCharsets.UTF_8))
        sink.saveAttempt(entity)
        return entity
    }

    /**
     * Repairs missing or stale database rows from journals, then interrupts every remaining
     * unfinished database attempt. A damaged record is reported and skipped without blocking the
     * remaining records.
     */
    fun recover(): CourseAttemptRecoveryReport {
        val errors = ArrayList<CourseAttemptRecoveryError>()
        var journalsScanned = 0
        var journalsReconciled = 0
        var interrupted = 0

        val canonicalRoot = try {
            canonical(root)
        } catch (e: Exception) {
            errors += CourseAttemptRecoveryError(null, "Invalid attempt root: ${message(e)}")
            null
        }

        if (canonicalRoot != null) {
            var rootExists = false
            var rootIsDirectory = false
            try {
                rootExists = root.exists()
                rootIsDirectory = rootExists && root.isDirectory
            } catch (e: Exception) {
                errors += CourseAttemptRecoveryError(
                    null,
                    "Unable to inspect the attempt journal directory: ${message(e)}"
                )
                rootExists = false
            }

            if (rootExists) {
                if (!rootIsDirectory) {
                    errors += CourseAttemptRecoveryError(null, "Attempt root is not a directory.")
                } else {
                    var listingFailed = false
                    val children = try {
                        root.listFiles()
                    } catch (e: Exception) {
                        errors += CourseAttemptRecoveryError(
                            null,
                            "Unable to list the attempt journal directory: ${message(e)}"
                        )
                        listingFailed = true
                        null
                    }
                    if (children == null) {
                        if (!listingFailed) {
                            errors += CourseAttemptRecoveryError(
                                null,
                                "Unable to list the attempt journal directory."
                            )
                        }
                    } else {
                        for (directory in children.sortedBy { it.name }) {
                            val journal = File(directory, RESULT_FILE)
                            if (!journal.exists()) {
                                // A staging directory without a committed snapshot has no attempt yet.
                                continue
                            }
                            journalsScanned++
                            try {
                                val result = readJournal(directory, canonicalRoot)
                                val recovered = result.withInterruptedOutcome()
                                val resultJson = encodeResult(recovered)
                                val entity = entityFor(recovered, resultJson)
                                if (sink.getAttempt(recovered.clientAttemptId) != entity) {
                                    sink.saveAttempt(entity)
                                    journalsReconciled++
                                    if (result.outcome == UNFINISHED_OUTCOME) {
                                        interrupted++
                                    }
                                }
                            } catch (e: Exception) {
                                errors += CourseAttemptRecoveryError(directory.name, message(e))
                            }
                        }
                    }
                }
            }
        }

        try {
            for (attempt in sink.listUnfinishedAttempts()) {
                try {
                    if (attempt.outcome != UNFINISHED_OUTCOME) {
                        continue
                    }
                    val result = json.decodeFromString<CourseResult>(attempt.resultJson)
                    if (result.clientAttemptId != attempt.id) {
                        throw IOException("Attempt result id does not match its stored row.")
                    }
                    val recovered = result.copy(
                        outcome = "interrupted",
                        reason = INTERRUPTED_REASON
                    )
                    sink.saveAttempt(
                        attempt.copy(
                            outcome = "interrupted",
                            resultJson = json.encodeToString(recovered)
                        )
                    )
                    interrupted++
                } catch (e: Exception) {
                    errors += CourseAttemptRecoveryError(attempt.id, message(e))
                }
            }
        } catch (e: Exception) {
            errors += CourseAttemptRecoveryError(
                null,
                "Unable to read unfinished attempts: ${message(e)}"
            )
        }

        return CourseAttemptRecoveryReport(
            journalsScanned = journalsScanned,
            journalsReconciled = journalsReconciled,
            unfinishedAttemptsInterrupted = interrupted,
            errors = errors.toList()
        )
    }

    private fun readJournal(directory: File, canonicalRoot: File): CourseResult {
        if (canonical(directory) != directoryFor(directory.name, canonicalRoot)) {
            throw IOException("Attempt directory escapes the attempt root.")
        }

        val journal = File(directory, RESULT_FILE)
        if (canonical(journal).parentFile != canonical(directory)) {
            throw IOException("Attempt snapshot escapes its attempt directory.")
        }

        val result = readSnapshot(journal)
        validateAttemptId(result.clientAttemptId)

        if (result.clientAttemptId != directory.name) {
            throw IOException("Attempt id does not match its directory.")
        }
        if (declaredDirectory(result.replayDirectory) !=
            directoryFor(result.clientAttemptId, canonicalRoot)
        ) {
            throw IOException("Attempt replay directory does not match its attempt directory.")
        }
        return result
    }

    private fun readSnapshot(file: File): CourseResult {
        if (!file.isFile) {
            throw IOException("Attempt snapshot is missing.")
        }

        val length = file.length()
        if (length <= 0L || length > MAX_RESULT_BYTES) {
            throw IOException("Attempt snapshot size is outside the supported range.")
        }

        val bytes = ByteArray(length.toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) {
                    throw IOException("Attempt snapshot is truncated.")
                }
                offset += read
            }
            if (input.read() != -1) {
                throw IOException("Attempt snapshot changed while reading.")
            }
        }

        val text = decodeUtf8(bytes)
        return try {
            json.decodeFromString<CourseResult>(text)
        } catch (e: Exception) {
            throw IOException("Attempt snapshot is not a valid course result.", e)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IOException("Attempt snapshot is not valid UTF-8.", e)
        }
    }

    private fun encodeResult(result: CourseResult): String {
        val text = json.encodeToString(result)
        if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_RESULT_BYTES) {
            throw IOException("Attempt snapshot exceeds the supported size.")
        }
        return text
    }

    private fun entityFor(
        result: CourseResult,
        resultJson: String
    ): SkillCourseAttemptEntity = SkillCourseAttemptEntity(
        id = result.clientAttemptId,
        courseId = result.courseId,
        courseHash = result.courseHash,
        definitionJson = CourseCodec.encode(result.definition),
        rulesetVersion = result.rulesetVersion,
        outcome = result.outcome,
        createdAt = result.createdAt,
        resultJson = resultJson,
        replayDirectory = result.replayDirectory
    )

    private fun CourseResult.withInterruptedOutcome(): CourseResult =
        if (outcome == UNFINISHED_OUTCOME) {
            copy(outcome = "interrupted", reason = INTERRUPTED_REASON)
        } else {
            this
        }

    private fun directoryFor(id: String): File = directoryFor(id, canonical(root))

    private fun directoryFor(id: String, canonicalRoot: File): File {
        validateAttemptId(id)
        return File(canonicalRoot, id)
    }

    private fun ensureDirectory(directory: File) {
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw IOException("Attempt path is not a directory.")
            }
            if (canonical(directory) != directory) {
                throw IllegalArgumentException("Attempt directory escapes the attempt root.")
            }
            return
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create the attempt directory.")
        }
        if (canonical(directory) != directory) {
            throw IllegalArgumentException("Attempt directory escapes the attempt root.")
        }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: throw IOException("Attempt snapshot has no directory.")
        if (file.isDirectory) {
            throw IOException("Attempt snapshot path is not a file.")
        }
        val temporary = File(parent, "${file.name}.tmp")

        if (temporary.isDirectory) {
            throw IOException("Cannot write the attempt snapshot temporary file.")
        }
        if (temporary.exists() && !temporary.delete()) {
            throw IOException("Cannot replace the attempt snapshot temporary file.")
        }

        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }

        if (temporary.renameTo(file)) {
            return
        }

        // POSIX rename replaces atomically. Some test/host filesystems refuse to replace an
        // existing file, so keep the old complete snapshot until the replacement is committed.
        if (!file.exists()) {
            throw IOException("Cannot commit the attempt snapshot.")
        }
        val backup = File(parent, "${file.name}.bak")
        if (backup.exists() && !backup.delete()) {
            throw IOException("Cannot prepare the attempt snapshot replacement.")
        }
        if (!file.renameTo(backup)) {
            throw IOException("Cannot commit the attempt snapshot.")
        }
        if (!temporary.renameTo(file)) {
            backup.renameTo(file)
            throw IOException("Cannot commit the attempt snapshot.")
        }
        backup.delete()
    }

    private fun validateAttemptId(id: String) {
        if (!SAFE_ATTEMPT_ID.matches(id)) {
            throw IllegalArgumentException("Attempt id is not a safe path segment.")
        }
    }

    private fun declaredDirectory(value: String): File {
        val file = File(value)
        if (!file.isAbsolute) {
            throw IllegalArgumentException("Attempt replay directory must be absolute.")
        }
        return canonical(file)
    }

    private fun canonical(file: File): File = try {
        file.canonicalFile
    } catch (e: IOException) {
        throw IllegalArgumentException("Attempt path is invalid.", e)
    }

    private fun message(error: Throwable): String =
        error.message ?: error.javaClass.simpleName

    companion object {
        const val RESULT_FILE = "result.json"
        const val MAX_RESULT_BYTES = 8 * 1024 * 1024
        const val UNFINISHED_OUTCOME = "in_progress"

        /** Stable reason recorded when process death leaves an `in_progress` snapshot behind. */
        const val INTERRUPTED_REASON = "The app stopped before the course finished."

        private val SAFE_ATTEMPT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

/**
 * Public entry point for local attempt durability.
 *
 * [persist] may run on a background IO thread before Room is written. [recover] must only run on a
 * startup background thread and never while a course attempt is active.
 */
object CourseAttemptStore {
    private val json = Json { encodeDefaults = true }

    private fun core(): CourseAttemptStoreCore = CourseAttemptStoreCore(
        File(corePath(), "SkillCourses/Attempts"),
        RoomCourseAttemptSink,
        json
    )

    private fun corePath(): String = Config.getCorePath()?.takeIf { it.isNotBlank() }
        ?: error("The app storage path is not initialized.")

    @JvmStatic
    fun persist(result: CourseResult): SkillCourseAttemptEntity = core().persist(result)

    @JvmStatic
    fun recover(): CourseAttemptRecoveryReport = core().recover()

    private object RoomCourseAttemptSink : CourseAttemptSink {
        override fun getAttempt(id: String): SkillCourseAttemptEntity? =
            CourseRepository.getAttempt(id)

        override fun saveAttempt(attempt: SkillCourseAttemptEntity): SkillCourseAttemptEntity =
            CourseRepository.saveAttempt(attempt)

        override fun listUnfinishedAttempts(): List<SkillCourseAttemptEntity> =
            CourseRepository.listUnfinishedAttempts()
    }
}

package com.osudroid.beatmaplisting

import android.util.Log
import com.osudroid.beatmaps.BeatmapCache
import com.osudroid.beatmaps.parser.BeatmapParser
import com.osudroid.beatmaps.parser.UnsupportedBeatmapModeException
import com.osudroid.data.BeatmapInfo
import com.osudroid.data.BeatmapKey
import com.osudroid.data.DatabaseManager
import com.osudroid.data.IBeatmapInfoDAO
import com.osudroid.skillcourse.CourseMap
import com.osudroid.skillcourse.CourseLibraryGuard
import com.osudroid.skillcourse.recovery.BeatmapFileIdentity
import com.osudroid.skillcourse.recovery.readBeatmapFileIdentity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.LibraryManager
import ru.nsu.ccfit.zuev.osu.helper.FileUtils

/** The highest-level reason an archive import did not fully succeed. */
enum class BeatmapImportFailure {
    NONE,
    INVALID_ARCHIVE,
    EXPECTED_REVISION_UNAVAILABLE,
    TARGET_AMBIGUOUS,
    FILE_SYSTEM,
    DATABASE,
    RECOVERY_REQUIRED,
    LIBRARY_BUSY,
}

/** One `.osu` file that is installed after a successful import (or already existed). */
data class BeatmapImportIdentity(
    @JvmField val md5: String,
    @JvmField val setDirectory: String,
    @JvmField val filename: String,
    @JvmField val artist: String,
    @JvmField val title: String,
    @JvmField val creator: String,
    @JvmField val version: String,
    @JvmField val circleCount: Int,
    @JvmField val sliderCount: Int,
    @JvmField val spinnerCount: Int,
    @JvmField val setId: Int?,
    @JvmField val beatmapId: Long?,
)

/** The result of checking one authored course revision against the staged archive. */
data class ExpectedMapImportResult(
    @JvmField val mapHash: String,
    @JvmField val matched: Boolean,
    @JvmField val installedDirectory: String?,
    @JvmField val message: String?,
)

/**
 * The complete result of a shared archive import.
 *
 * [success] is only true when all requested course revisions were installed or already present and
 * no file/database step failed. A partial course repair is reported as unsuccessful while still
 * listing the maps that were imported.
 */
data class BeatmapImportResult(
    @JvmField val success: Boolean,
    @JvmField val archiveName: String?,
    @JvmField val affectedDirectories: List<String>,
    @JvmField val importedBeatmaps: List<BeatmapInfo>,
    @JvmField val identities: List<BeatmapImportIdentity>,
    @JvmField val expectedMaps: List<ExpectedMapImportResult>,
    @JvmField val errors: List<String>,
    @JvmField val failure: BeatmapImportFailure,
) {
    /**
     * A short, single-line summary of the first diagnostic suitable for a toast.
     *
     * The full [errors] list is intentionally left untouched for logging and diagnostics.
     */
    val errorSummary: String
        get() {
            val first = errors.asSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: ERROR_SUMMARY_FALLBACK
            val singleLine = first.replace(WHITESPACE, " ").trim()
            if (singleLine.isEmpty()) {
                return ERROR_SUMMARY_FALLBACK
            }
            return if (singleLine.length <= MAX_ERROR_SUMMARY_LENGTH) {
                singleLine
            } else {
                singleLine.take(MAX_ERROR_SUMMARY_LENGTH - 1).trimEnd() + "\u2026"
            }
        }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private const val MAX_ERROR_SUMMARY_LENGTH = 180
        private const val ERROR_SUMMARY_FALLBACK = "Beatmap import failed."
    }

    fun expectedMatched(mapHash: String): Boolean =
        expectedMaps.any { it.mapHash.equals(mapHash, ignoreCase = true) && it.matched }
}

/**
 * Shared, staged beatmap archive importer used by ordinary `.osz` imports and course repair.
 *
 * Untrusted archives are never extracted into an installed set folder. The importer validates and
 * parses a private copy first, then applies only the required files using a durable journal and
 * per-file backups. Database changes are a targeted transaction over the import's declared keys.
 */
object BeatmapArchiveImporter {

    private val importLock = ReentrantLock()

    private const val JOURNAL_VERSION = 1
    private const val STAGING_DIRECTORY = ".beatmap-import"
    private const val JOURNAL_DIRECTORY = "journal"

    private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 4096

    /**
     * Imports [archive]. When [expectedMaps] is non-empty, only the exact authored revisions are
     * applied. A differently versioned archive never replaces an installed candidate: if the
     * required revision is already installed it is a successful no-op, otherwise the expected-map
     * result fails.
     */
    @JvmStatic
    @JvmOverloads
    fun importArchive(
        archive: File,
        expectedMaps: List<CourseMap> = emptyList(),
    ): BeatmapImportResult {
        val lease = CourseLibraryGuard.tryImport() ?: return failedResult(
            archive, BeatmapImportFailure.LIBRARY_BUSY,
            listOf("Finish the active course before importing beatmaps."), expectedMaps
        )
        return lease.use {
            importLock.withLock {
                recoverPendingImportsLocked()
                val result = importArchiveLocked(archive, expectedMaps)
                deleteSuccessfulSourceIfRequested(archive, result)
                result
            }
        }
    }

    /**
     * Removes the source `.osz` only after a fully completed import and only when the user asked for
     * downloaded archives to be deleted. Failures, partial course repairs, rollbacks and recovery
     * states always keep the source so the import can be retried.
     */
    private fun deleteSuccessfulSourceIfRequested(archive: File, result: BeatmapImportResult) {
        if (!result.success || result.failure != BeatmapImportFailure.NONE) {
            return
        }
        if (!Config.isDELETE_OSZ()) {
            return
        }
        if (!archive.name.endsWith(".osz", ignoreCase = true)) {
            return
        }

        try {
            if (archive.exists() && !archive.delete()) {
                Log.w(
                    "BeatmapArchiveImporter",
                    "Unable to delete imported source archive: ${archive.path}",
                )
            }
        } catch (e: Exception) {
            Log.w(
                "BeatmapArchiveImporter",
                "Unable to delete imported source archive: ${archive.path}",
                e,
            )
        }
    }

    /**
     * Finishes or rolls back imports interrupted by process death. Returns recovery errors suitable
     * for logging; an empty list means every journal reached a consistent state.
     */
    @JvmStatic
    fun recoverPendingImports(): List<String> {
        val lease = CourseLibraryGuard.tryImport() ?: return listOf("The active course has pinned the beatmap library.")
        return lease.use { importLock.withLock { recoverPendingImportsLocked() } }
    }

    private fun importArchiveLocked(
        archive: File,
        expectedMaps: List<CourseMap>,
    ): BeatmapImportResult {
        val jobId = "job-" + UUID.randomUUID().toString().replace("-", "")
        val jobDir = File(stagingRoot(), jobId)
        var journal: ImportJournal? = null
        var databaseApplied = false

        try {
            if (!archive.isFile) {
                return failedResult(
                    archive = archive,
                    failure = BeatmapImportFailure.INVALID_ARCHIVE,
                    errors = listOf("Archive does not exist: ${archive.path}"),
                    expectedMaps = expectedMaps,
                )
            }

            if (!jobDir.mkdirs() && !jobDir.isDirectory) {
                return failedResult(
                    archive = archive,
                    failure = BeatmapImportFailure.FILE_SYSTEM,
                    errors = listOf("Unable to create import staging directory."),
                    expectedMaps = expectedMaps,
                )
            }

            val layout = stageArchive(archive, jobDir)
            val dao = BeatmapArchiveImporterHooks.daoProvider()
            val installed = dao.getAll()
            val identityCache = HashMap<String, BeatmapFileIdentity?>()
            val selection = selectMaps(layout, expectedMaps, installed, identityCache)

            if (selection.selected.isEmpty()) {
                // Every requested revision may already be installed even though this archive does
                // not carry it. That is a successful no-op, not an unavailable revision.
                if (expectedMaps.isNotEmpty() &&
                    selection.expectedResults.isNotEmpty() &&
                    selection.expectedResults.all { it.matched }
                ) {
                    val result = successResult(
                        archive = archive,
                        plan = ImportPlan(
                            mutations = emptyList(),
                            deleteKeys = emptyList(),
                            finalFiles = emptyList(),
                            affectedDirectories = emptyList(),
                            oldMd5s = emptySet(),
                            newMd5s = emptySet(),
                            skippedIdentities = emptyList(),
                        ),
                        selection = selection,
                        rows = emptyList(),
                    )
                    // This path never wrote a journal or any file; do not leave the staged archive.
                    deleteRecursively(jobDir)
                    return result
                }

                val errors = selection.errors.ifEmpty {
                    listOf("The archive contained no importable osu!standard difficulties.")
                }
                return failedResult(
                    archive = archive,
                    failure = if (expectedMaps.isEmpty()) {
                        BeatmapImportFailure.INVALID_ARCHIVE
                    } else {
                        BeatmapImportFailure.EXPECTED_REVISION_UNAVAILABLE
                    },
                    errors = errors,
                    expectedMaps = expectedMaps,
                    expectedResults = selection.expectedResults,
                )
            }

            val plan = buildPlan(layout, selection.selected, installed, identityCache)

            // Back up every file that may be replaced or removed before journaling or mutating.
            val backups = createBackups(jobDir, plan)
            journal = ImportJournal(
                version = JOURNAL_VERSION,
                jobId = jobId,
                phase = ImportPhase.PREPARED,
                affectedDirectories = plan.affectedDirectories,
                mutations = plan.mutations.map { mutation ->
                    JournalMutation(
                        kind = mutation.kind,
                        targetRelative = relativeToBeatmapPath(mutation.target),
                        backupRelative = backups[mutation.target],
                    )
                },
                deleteKeys = plan.deleteKeys,
                finalFiles = plan.finalFiles.map(::relativeToBeatmapPath),
            )
            writeJournal(journal)
            BeatmapArchiveImporterHooks.fault(ImportCommitPoint.AFTER_BACKUP)

            applyMutations(plan)
            journal.phase = ImportPhase.FILES_APPLIED
            writeJournal(journal)
            BeatmapArchiveImporterHooks.fault(ImportCommitPoint.AFTER_FILES_APPLIED)

            BeatmapArchiveImporterHooks.fault(ImportCommitPoint.BEFORE_DATABASE)
            val rows = parseFinalFiles(plan.finalFiles)
            dao.applyImportChanges(rows, plan.deleteKeys)
            databaseApplied = true

            journal.phase = ImportPhase.DB_APPLIED
            writeJournal(journal)
            BeatmapArchiveImporterHooks.fault(ImportCommitPoint.AFTER_DATABASE)

            BeatmapArchiveImporterHooks.fault(ImportCommitPoint.BEFORE_FINALIZE)
            finalizeImport(plan)

            journal.phase = ImportPhase.DONE
            try {
                writeJournal(journal)
            } catch (e: Exception) {
                // Files, Room rows and the library event are already committed. A stale journal
                // marker must not turn a completed, published import into a false failure.
                Log.e("BeatmapArchiveImporter", "Unable to mark import journal done.", e)
            }
            cleanupJournal(journal, jobDir)

            return successResult(archive, plan, selection, rows)
        } catch (death: SimulatedProcessDeath) {
            // Deliberately leave the journal and files for startup recovery tests / process death.
            throw death
        } catch (e: ImportValidationException) {
            journal?.let { cleanupJournal(it, jobDir) } ?: deleteRecursively(jobDir)
            return failedResult(
                archive = archive,
                failure = BeatmapImportFailure.INVALID_ARCHIVE,
                errors = listOf(e.message ?: "Invalid beatmap archive."),
                expectedMaps = expectedMaps,
            )
        } catch (e: Exception) {
            if (databaseApplied) {
                // The database commit happened. Do not roll files back underneath committed rows;
                // leave the journal at DB_APPLIED for the startup recovery path to finish.
                Log.e("BeatmapArchiveImporter", "Import committed but finalization failed.", e)
                return failedResult(
                    archive = archive,
                    failure = BeatmapImportFailure.RECOVERY_REQUIRED,
                    errors = listOf(
                        "Import was committed but finalization failed: " +
                            (e.message ?: e.javaClass.simpleName) +
                            ". It will be completed on the next startup or import."
                    ),
                    expectedMaps = expectedMaps,
                )
            }

            val rollbackError = journal?.let { rollbackJournal(it, jobDir) }
            if (rollbackError == null) {
                journal?.let { cleanupJournal(it, jobDir) } ?: deleteRecursively(jobDir)
            }

            return failedResult(
                archive = archive,
                failure = if (rollbackError == null) {
                    BeatmapImportFailure.FILE_SYSTEM
                } else {
                    BeatmapImportFailure.RECOVERY_REQUIRED
                },
                errors = buildList {
                    add(e.message ?: e.javaClass.simpleName)
                    if (rollbackError != null) {
                        add("Rollback was incomplete: $rollbackError")
                    }
                },
                expectedMaps = expectedMaps,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Staging
    // ---------------------------------------------------------------------------------------------

    private fun stageArchive(archive: File, jobDir: File): ArchiveLayout {
        if (archive.length() > MAX_ARCHIVE_BYTES) {
            throw ImportValidationException("Archive is too large.")
        }

        val validZip = try {
            ZipFile(archive).use { it.isValidZipFile }
        } catch (_: Exception) {
            false
        }
        if (!validZip) {
            throw ImportValidationException("Invalid ZIP archive.")
        }

        val payload = File(jobDir, "payload")
        if (!payload.mkdirs() && !payload.isDirectory) {
            throw ImportValidationException("Unable to create staging payload directory.")
        }

        val payloadRoot = payload.canonicalFile
        val payloadPrefix = payloadRoot.path + File.separator
        val seenNames = HashSet<String>()
        var totalBytes = 0L

        ZipFile(archive).use { zip ->
            if (!zip.isValidZipFile) {
                throw ImportValidationException("Invalid ZIP archive.")
            }

            val headers = zip.fileHeaders
            if (headers.isEmpty() || headers.size > MAX_ENTRIES) {
                throw ImportValidationException("Archive entry count is invalid.")
            }

            for (header in headers) {
                validateEntry(header)

                if (header.isDirectory) {
                    continue
                }

                val entryName = normalizeEntryName(header.fileName)
                val duplicateKey = entryName.lowercase(Locale.ROOT)
                if (!seenNames.add(duplicateKey)) {
                    throw ImportValidationException("Archive contains duplicate entry: $entryName")
                }

                val uncompressed = header.uncompressedSize
                if (uncompressed < 0 || uncompressed > MAX_ENTRY_BYTES) {
                    throw ImportValidationException("Archive entry is too large: $entryName")
                }

                totalBytes += uncompressed
                if (totalBytes > MAX_TOTAL_UNCOMPRESSED) {
                    throw ImportValidationException("Archive expands beyond the size limit.")
                }

                val target = File(payloadRoot, entryName.replace('/', File.separatorChar))
                    .canonicalFile
                if (target != payloadRoot &&
                    !target.path.startsWith(payloadPrefix, ignoreCase = true)
                ) {
                    throw ImportValidationException("Archive entry escapes the staging folder.")
                }

                target.parentFile?.mkdirs()

                val copied = zip.getInputStream(header).use { input ->
                    target.outputStream().use { output ->
                        copyBounded(input, output, MAX_ENTRY_BYTES)
                    }
                }

                if (copied > MAX_ENTRY_BYTES) {
                    throw ImportValidationException("Archive entry is too large: $entryName")
                }
            }
        }

        return readStagedLayout(archive, payloadRoot)
    }

    private fun validateEntry(header: FileHeader) {
        if (header.isEncrypted) {
            throw ImportValidationException("Encrypted archives are not supported.")
        }
        if (isSymlink(header)) {
            throw ImportValidationException("Symbolic links are not allowed in beatmap archives.")
        }
        if (header.fileName.isBlank()) {
            throw ImportValidationException("Archive contains an empty entry name.")
        }
        normalizeEntryName(header.fileName)
    }

    private fun normalizeEntryName(raw: String): String {
        if (raw.indexOf('\u0000') >= 0) {
            throw ImportValidationException("Archive entry name contains a NUL character.")
        }

        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.matches(Regex("^[A-Za-z]:"))) {
            throw ImportValidationException("Archive contains an absolute path: $raw")
        }

        val segments = normalized.split('/')
        if (segments.any { it == ".." }) {
            throw ImportValidationException("Archive contains a path traversal entry: $raw")
        }

        val cleaned = segments.filter { it.isNotEmpty() && it != "." }
        if (cleaned.isEmpty()) {
            throw ImportValidationException("Archive contains an empty path: $raw")
        }

        return cleaned.joinToString("/")
    }

    /**
     * ZIP does not have a portable symlink field; Unix mode bits are stored in the external
     * attributes when present. Check both byte orders to avoid trusting a crafted archive.
     */
    private fun isSymlink(header: FileHeader): Boolean {
        val bytes = header.externalFileAttributes ?: return false
        if (bytes.size < 4) {
            return false
        }

        val littleEndian = ((bytes[0].toLong() and 0xFFL)) or
            ((bytes[1].toLong() and 0xFFL) shl 8) or
            ((bytes[2].toLong() and 0xFFL) shl 16) or
            ((bytes[3].toLong() and 0xFFL) shl 24)
        val bigEndian = ((bytes[3].toLong() and 0xFFL)) or
            ((bytes[2].toLong() and 0xFFL) shl 8) or
            ((bytes[1].toLong() and 0xFFL) shl 16) or
            ((bytes[0].toLong() and 0xFFL) shl 24)

        for (attributes in longArrayOf(littleEndian, bigEndian)) {
            val mode = ((attributes ushr 16) and 0xFFFFL).toInt()
            if ((mode and 0xF000) == 0xA000) {
                return true
            }
        }

        return false
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L

        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                return total
            }
            total += read
            if (total > limit) {
                throw ImportValidationException("Archive entry expands beyond the size limit.")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun readStagedLayout(archive: File, payloadRoot: File): ArchiveLayout {
        val files = payloadRoot.walkTopDown()
            .filter { it.isFile }
            .toList()

        if (files.isEmpty()) {
            throw ImportValidationException("Archive contains no files.")
        }

        val relativePaths = files.associateWith {
            it.relativeTo(payloadRoot).path.replace(File.separatorChar, '/')
        }

        val firstSegments = relativePaths.values.map { it.substringBefore('/') }.distinct()
        val contentRoot = if (firstSegments.size == 1 &&
            relativePaths.values.all { it.contains('/') }
        ) {
            File(payloadRoot, firstSegments.single())
        } else {
            payloadRoot
        }

        val defaultSetName = if (contentRoot != payloadRoot) {
            contentRoot.name
        } else {
            archive.name
                .substringBeforeLast('.')
                .replace(Regex("(?i) \\[no video]$"), "")
                .ifBlank { "Imported Beatmap" }
        }

        val maps = ArrayList<StagedMap>()
        val assets = ArrayList<StagedAsset>()
        val parseErrors = ArrayList<String>()
        var unsupportedModeCount = 0

        for (file in files) {
            val relative = file.relativeTo(contentRoot).path.replace(File.separatorChar, '/')

            if (file.name.endsWith(".osu", ignoreCase = true)) {
                try {
                    val beatmap = BeatmapParser(file).parse(true)
                    maps += StagedMap(
                        file = file,
                        relativePath = relative,
                        filename = file.name,
                        md5 = beatmap.md5,
                        circleCount = beatmap.hitObjects.circleCount,
                        sliderCount = beatmap.hitObjects.sliderCount,
                        spinnerCount = beatmap.hitObjects.spinnerCount,
                        artist = beatmap.metadata.artist,
                        title = beatmap.metadata.title,
                        creator = beatmap.metadata.creator,
                        version = beatmap.metadata.version,
                        beatmapId = beatmap.metadata.beatmapId.toLong().takeIf { it > 0 },
                        setId = beatmap.metadata.beatmapSetId,
                        audioFilename = beatmap.general.audioFilename,
                        backgroundFilename = beatmap.events.backgroundFilename,
                    )
                } catch (_: UnsupportedBeatmapModeException) {
                    // Non-standard modes are not importable, but a mixed-mode archive is still a
                    // valid osu!standard import. Skip the file quietly instead of reporting it as
                    // corrupt or adding it to the installed assets.
                    unsupportedModeCount++
                } catch (e: Exception) {
                    parseErrors += "Skipped unreadable osu file ${file.name}: " +
                        (e.message ?: e.javaClass.simpleName)
                }
            } else {
                assets += StagedAsset(file = file, relativePath = relative)
            }
        }

        if (maps.isEmpty()) {
            val reason = when {
                parseErrors.isNotEmpty() ->
                    "Archive contains no parseable osu!standard difficulties. " +
                        parseErrors.joinToString(" ")

                unsupportedModeCount > 0 ->
                    "Archive contains no supported osu!standard difficulties."

                else -> "Archive contains no parseable osu!standard difficulties."
            }
            throw ImportValidationException(reason)
        }

        return ArchiveLayout(
            archive = archive,
            payloadRoot = payloadRoot,
            contentRoot = contentRoot,
            defaultSetName = defaultSetName,
            maps = maps,
            assets = assets,
            parseErrors = parseErrors,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Selection and target resolution
    // ---------------------------------------------------------------------------------------------

    private fun selectMaps(
        layout: ArchiveLayout,
        expectedMaps: List<CourseMap>,
        installed: List<BeatmapInfo>,
        identityCache: MutableMap<String, BeatmapFileIdentity?>,
    ): Selection {
        if (expectedMaps.isEmpty()) {
            return Selection(
                selected = layout.maps.map { SelectedMap(it, null) },
                expectedResults = emptyList(),
                errors = layout.parseErrors.toMutableList(),
            )
        }

        val selected = ArrayList<SelectedMap>()
        val expectedResults = ArrayList<ExpectedMapImportResult>()
        val errors = ArrayList<String>()
        val seen = HashSet<String>()

        for (expected in expectedMaps) {
            val hash = expected.mapHash.trim().lowercase(Locale.ROOT)
            if (hash.isEmpty() || !seen.add(hash)) {
                continue
            }

            val candidates = layout.maps.filter { it.md5.equals(hash, ignoreCase = true) }
            val matchingCounts = candidates.filter { countsMatch(expected, it) }

            when {
                matchingCounts.isNotEmpty() -> {
                    selected += SelectedMap(matchingCounts.first(), expected)
                    expectedResults += ExpectedMapImportResult(
                        mapHash = expected.mapHash,
                        matched = true,
                        installedDirectory = null,
                        message = null,
                    )
                }

                candidates.isNotEmpty() -> {
                    expectedResults += ExpectedMapImportResult(
                        mapHash = expected.mapHash,
                        matched = false,
                        installedDirectory = null,
                        message = "The archive contains MD5 $hash but its object counts do not " +
                            "match the authored counts ${expected.objectCounts}.",
                    )
                    errors += "Required revision for ${expected.artist} - ${expected.title} " +
                        "[${expected.difficulty}] has object counts ${candidates.map { listOf(it.circleCount, it.sliderCount, it.spinnerCount) }}, expected ${expected.objectCounts}."
                }

                installedExact(installed, expected, hash, identityCache) != null -> {
                    val installedRow = installedExact(installed, expected, hash, identityCache)!!
                    expectedResults += ExpectedMapImportResult(
                        mapHash = expected.mapHash,
                        matched = true,
                        installedDirectory = installedRow.setDirectory,
                        message = "The exact revision is already installed.",
                    )
                }

                else -> {
                    expectedResults += ExpectedMapImportResult(
                        mapHash = expected.mapHash,
                        matched = false,
                        installedDirectory = null,
                        message = "Required version unavailable: the archive does not contain " +
                            "MD5 $hash. Import the correct revision manually.",
                    )
                    errors += "Required version unavailable for ${expected.artist} - " +
                        "${expected.title} [${expected.difficulty}] (MD5 $hash)."
                }
            }
        }

        errors.addAll(layout.parseErrors)
        return Selection(selected, expectedResults, errors)
    }

    private fun buildPlan(
        layout: ArchiveLayout,
        selected: List<SelectedMap>,
        installed: List<BeatmapInfo>,
        identityCache: MutableMap<String, BeatmapFileIdentity?>,
    ): ImportPlan {
        val beatmapRoot = File(Config.getBeatmapPath()).canonicalFile
        val defaultDirectory = sanitizeDirectoryName(layout.defaultSetName)
        val distinctDirectory = uniqueManagedDirectory(defaultDirectory, installed)

        val initial = ArrayList<Decision>()
        val skippedIdentities = ArrayList<BeatmapImportIdentity>()
        val skippedRows = ArrayList<BeatmapInfo>()

        for (staged in selected) {
            // A matching database hash/count alone is not proof that the file still contains that
            // revision: the file may have been replaced or truncated outside the app. Confirm the
            // actual on-disk identity before treating the map as already installed.
            val alreadyInstalled = installed.firstOrNull { row ->
                row.md5.equals(staged.staged.md5, ignoreCase = true) &&
                    diskIdentityMatches(
                        row = row,
                        md5 = staged.staged.md5,
                        circleCount = staged.staged.circleCount,
                        sliderCount = staged.staged.sliderCount,
                        spinnerCount = staged.staged.spinnerCount,
                        identityCache = identityCache,
                    )
            }
            if (alreadyInstalled != null) {
                skippedIdentities += identityOf(alreadyInstalled)
                skippedRows += alreadyInstalled
                continue
            }

            val candidates = installed.filter { row ->
                metadataMatchesRow(staged, row)
            }.distinctBy { it.setDirectory to it.filename }

            if (candidates.size == 1) {
                initial += Decision(
                    selected = staged,
                    directory = candidates.single().setDirectory,
                    existingTarget = candidates.single(),
                )
            } else {
                initial += Decision(
                    selected = staged,
                    directory = if (candidates.size > 1) distinctDirectory else defaultDirectory,
                    existingTarget = null,
                )
            }
        }

        // Any decision that cannot safely share a destination is moved to the archive's distinct
        // managed directory. Ambiguous metadata targets land there by construction.
        reassignUnsafeGroups(layout, initial, installed, distinctDirectory)

        val writes = LinkedHashMap<String, PlannedWrite>()
        val deletes = LinkedHashMap<String, PlannedDelete>()
        val deleteKeys = LinkedHashMap<String, BeatmapKey>()
        val finalFiles = ArrayList<File>()
        val affectedDirectories = LinkedHashSet<String>()
        val oldMd5s = HashSet<String>()
        val newMd5s = HashSet<String>()

        for (decision in initial) {
            val directory = decision.existingTarget?.setDirectory
                ?: sanitizeDirectoryName(decision.directory)
            val destinationDir = resolveContained(beatmapRoot, directory)
            val targetFile = resolveContained(destinationDir, decision.selected.staged.filename)

            affectedDirectories += directory
            newMd5s += decision.selected.staged.md5
            finalFiles += targetFile

            val old = decision.existingTarget
            if (old != null) {
                oldMd5s += old.md5
                if (old.filename != decision.selected.staged.filename ||
                    old.setDirectory != directory
                ) {
                    val oldFile = resolveContained(beatmapRoot, old.setDirectory, old.filename)
                    deletes[oldFile.path] = PlannedDelete(oldFile)
                    deleteKeys[keyOf(old.setDirectory, old.filename)] =
                        BeatmapKey(old.filename, old.setDirectory)
                    affectedDirectories += old.setDirectory
                }
            }

            val previousWrite = writes[targetFile.path]
            if (previousWrite != null &&
                previousWrite.source.canonicalPath != decision.selected.staged.file.canonicalPath
            ) {
                throw ImportValidationException(
                    "Multiple archive difficulties map to the same destination: ${targetFile.path}"
                )
            }
            writes[targetFile.path] = PlannedWrite(targetFile, decision.selected.staged.file)

            for (asset in layout.assets) {
                val assetTarget = resolveContained(destinationDir, asset.relativePath)
                if (assetTarget.isFile && sameContent(asset.file, assetTarget)) {
                    continue
                }
                val previousAsset = writes[assetTarget.path]
                if (previousAsset != null &&
                    previousAsset.source.canonicalPath != asset.file.canonicalPath
                ) {
                    throw ImportValidationException(
                        "Conflicting archive assets map to ${assetTarget.path}"
                    )
                }
                writes[assetTarget.path] = PlannedWrite(assetTarget, asset.file)
            }
        }

        // An exact installed map is not rewritten, but its archive assets still may be missing or
        // stale (most commonly audio/background after a partial repair). Reconcile those assets
        // independently of map writes. A missing asset is always safe to add; a differing asset is
        // only replaced when the set directory has no unrelated installed sibling that could share
        // the same file, mirroring the unsafe-group handling above.
        val relatedKeys = HashSet<String>()
        for (row in skippedRows) {
            relatedKeys += keyOf(row.setDirectory, row.filename)
        }
        for (decision in initial) {
            decision.existingTarget?.let { relatedKeys += keyOf(it.setDirectory, it.filename) }
        }

        for (row in skippedRows.distinctBy { it.setDirectory to it.filename }) {
            val directory = row.setDirectory
            val destinationDir = resolveContained(beatmapRoot, directory)
            val hasUnrelatedSibling = installed.any { other ->
                other.setDirectory == directory &&
                    keyOf(other.setDirectory, other.filename) !in relatedKeys
            }

            for (asset in layout.assets) {
                val assetTarget = resolveContained(destinationDir, asset.relativePath)
                if (assetTarget.isFile && sameContent(asset.file, assetTarget)) {
                    continue
                }
                if (assetTarget.isFile && hasUnrelatedSibling) {
                    continue
                }

                val previousAsset = writes[assetTarget.path]
                if (previousAsset != null &&
                    previousAsset.source.canonicalPath != asset.file.canonicalPath
                ) {
                    throw ImportValidationException(
                        "Conflicting archive assets map to ${assetTarget.path}"
                    )
                }
                writes[assetTarget.path] = PlannedWrite(assetTarget, asset.file)
                affectedDirectories += directory
            }
        }

        // A destination cannot be both deleted and written.
        for (path in writes.keys.toList()) {
            deletes.remove(path)
        }

        return ImportPlan(
            mutations = writes.values.map {
                PlannedMutation(MutationKind.WRITE, it.target, it.source)
            } + deletes.values.map {
                PlannedMutation(MutationKind.DELETE, it.target, null)
            },
            deleteKeys = deleteKeys.values.toList(),
            finalFiles = finalFiles.distinct(),
            affectedDirectories = affectedDirectories.toList(),
            oldMd5s = oldMd5s,
            newMd5s = newMd5s,
            skippedIdentities = skippedIdentities,
        )
    }

    /**
     * Chooses the distinct managed directory and moves every group whose in-place replacement could
     * damage an unrelated sibling through a differing shared asset or filename collision.
     */
    private fun reassignUnsafeGroups(
        layout: ArchiveLayout,
        decisions: MutableList<Decision>,
        installed: List<BeatmapInfo>,
        distinctDirectory: String,
    ) {
        val byDirectory = decisions
            .filter { it.existingTarget != null }
            .groupBy { it.directory }

        for ((directory, group) in byDirectory) {
            val groupOldKeys = group.mapNotNull { decision ->
                decision.existingTarget?.let { keyOf(it.setDirectory, it.filename) }
            }.toSet()

            val unrelated = installed.filter {
                it.setDirectory == directory && keyOf(it.setDirectory, it.filename) !in groupOldKeys
            }

            val filenameCollision = group.any { decision ->
                installed.any { row ->
                    row.setDirectory == directory &&
                        row.filename == decision.selected.staged.filename &&
                        keyOf(row.setDirectory, row.filename) !in groupOldKeys
                }
            }

            val assetCollision = unrelated.isNotEmpty() && layout.assets.any { asset ->
                val existing = File(File(Config.getBeatmapPath(), directory), asset.relativePath)
                existing.isFile && !sameContent(asset.file, existing)
            }

            val sameFileTwice = group.groupBy { it.selected.staged.filename }
                .any { (_, values) -> values.size > 1 }

            val sameExistingTargetTwice = group
                .groupBy { decision ->
                    decision.existingTarget?.let { keyOf(it.setDirectory, it.filename) }
                }
                .any { (key, values) -> key != null && values.size > 1 }

            if (filenameCollision || assetCollision || sameFileTwice ||
                sameExistingTargetTwice
            ) {
                for (decision in group) {
                    decision.directory = distinctDirectory
                    decision.existingTarget = null
                }
            }
        }

        // Default-directory decisions also need a distinct home when their file name collides with
        // an existing row or another staged file.
        val defaultGroups = decisions
            .filter { it.existingTarget == null }
            .groupBy { it.directory }

        for ((directory, group) in defaultGroups) {
            val hasInstalledRows = installed.any { it.setDirectory == directory }
            val filenameCollision = group.any { decision ->
                installed.any {
                    it.setDirectory == directory &&
                        it.filename == decision.selected.staged.filename
                } || group.count { it.selected.staged.filename == decision.selected.staged.filename } > 1
            }

            val assetCollision = hasInstalledRows && layout.assets.any { asset ->
                val existing = File(File(Config.getBeatmapPath(), directory), asset.relativePath)
                existing.isFile && !sameContent(asset.file, existing)
            }

            if (directory == distinctDirectory || filenameCollision || assetCollision) {
                if (directory != distinctDirectory) {
                    for (decision in group) {
                        decision.directory = distinctDirectory
                    }
                }
            }
        }
    }

    private fun metadataMatchesRow(selected: SelectedMap, row: BeatmapInfo): Boolean {
        val expected = selected.expected
        val staged = selected.staged
        val artist = expected?.artist ?: staged.artist
        val title = expected?.title ?: staged.title
        val mapper = expected?.mapper ?: staged.creator
        val difficulty = expected?.difficulty ?: staged.version

        val metadata = normalize(artist) == normalize(row.artist) &&
            normalize(title) == normalize(row.title) &&
            normalize(mapper) == normalize(row.creator) &&
            normalize(difficulty) == normalize(row.version)
        if (!metadata) {
            return false
        }

        val expectedId = expected?.beatmapid ?: staged.beatmapId
        return expectedId == null || row.id == null || row.id == expectedId
    }

    private fun installedExact(
        installed: List<BeatmapInfo>,
        expected: CourseMap,
        hash: String,
        identityCache: MutableMap<String, BeatmapFileIdentity?>,
    ): BeatmapInfo? = installed.firstOrNull { row ->
        row.md5.equals(hash, ignoreCase = true) &&
            diskIdentityMatches(
                row = row,
                md5 = hash,
                circleCount = expected.objectCounts.getOrNull(0) ?: -1,
                sliderCount = expected.objectCounts.getOrNull(1) ?: -1,
                spinnerCount = expected.objectCounts.getOrNull(2) ?: -1,
                identityCache = identityCache,
            )
    }

    private fun countsMatch(expected: CourseMap, staged: StagedMap): Boolean {
        val counts = expected.objectCounts
        return counts.size >= 3 &&
            counts[0] == staged.circleCount &&
            counts[1] == staged.sliderCount &&
            counts[2] == staged.spinnerCount
    }

    /**
     * Confirms that the row's file still contains the expected revision and object counts. The
     * result is cached for this import so a row is parsed at most once even when the same server
     * revision is checked from selection and planning.
     */
    private fun diskIdentityMatches(
        row: BeatmapInfo,
        md5: String,
        circleCount: Int,
        sliderCount: Int,
        spinnerCount: Int,
        identityCache: MutableMap<String, BeatmapFileIdentity?>,
    ): Boolean {
        val file = File(row.path)
        if (!file.isFile) {
            return false
        }

        val path = file.absolutePath
        val identity = if (identityCache.containsKey(path)) {
            identityCache[path]
        } else {
            BeatmapArchiveImporterHooks.installedIdentityReader(file)
                .also { identityCache[path] = it }
        }
        if (identity == null) {
            return false
        }

        return identity.md5.equals(md5, ignoreCase = true) &&
            identity.circleCount == circleCount &&
            identity.sliderCount == sliderCount &&
            identity.spinnerCount == spinnerCount
    }

    private fun uniqueManagedDirectory(base: String, installed: List<BeatmapInfo>): String {
        val used = installed.map { it.setDirectory.lowercase(Locale.ROOT) }.toHashSet()
        var candidate = "$base (imported)"
        var index = 2

        while (used.contains(candidate.lowercase(Locale.ROOT)) ||
            File(Config.getBeatmapPath(), candidate).exists()
        ) {
            candidate = "$base (imported $index)"
            index++
        }

        return candidate
    }

    private fun sanitizeDirectoryName(value: String): String {
        val sanitized = value
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .trimEnd('.')
        return sanitized.ifBlank { "Imported Beatmap" }
    }

    private fun resolveContained(root: File, vararg segments: String): File {
        var target = root
        for (segment in segments) {
            val normalized = segment.replace(File.separatorChar, '/')
            for (part in normalized.split('/')) {
                if (part.isEmpty() || part == ".") {
                    continue
                }
                if (part == "..") {
                    throw ImportValidationException("Refusing to write outside the beatmap path.")
                }
                target = File(target, part)
            }
        }

        val canonical = target.canonicalFile
        val rootPath = root.canonicalFile.path + File.separator
        if (canonical != root && !canonical.path.startsWith(rootPath, ignoreCase = true)) {
            throw ImportValidationException("Refusing to write outside the beatmap path.")
        }
        return canonical
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile) {
            return false
        }
        if (a.length() != b.length()) {
            return false
        }
        return FileUtils.getMD5Checksum(a) == FileUtils.getMD5Checksum(b)
    }

    // ---------------------------------------------------------------------------------------------
    // Journal, mutation and recovery
    // ---------------------------------------------------------------------------------------------

    private fun createBackups(jobDir: File, plan: ImportPlan): Map<File, String> {
        val backupDir = File(jobDir, "backup")
        val backups = HashMap<File, String>()
        var index = 0

        for (mutation in plan.mutations) {
            if (!mutation.target.isFile) {
                continue
            }

            if (!backupDir.exists() && !backupDir.mkdirs() && !backupDir.isDirectory) {
                throw IOException("Unable to create import backup directory.")
            }

            val backupName = "%04d_%s".format(index++, mutation.target.name)
            val backup = File(backupDir, backupName)
            FileUtils.copy(mutation.target, backup)
            FileOutputStream(backup, true).use { it.fd.sync() }
            backups[mutation.target] = relativeToJobDir(jobDir, backup)
        }

        return backups
    }

    private fun applyMutations(plan: ImportPlan) {
        for (mutation in plan.mutations) {
            when (mutation.kind) {
                MutationKind.WRITE -> {
                    val source = mutation.source
                        ?: throw IOException("Missing staged source for ${mutation.target.path}")
                    mutation.target.parentFile?.mkdirs()
                    val temp = File(
                        mutation.target.parentFile,
                        ".${mutation.target.name}.import-${UUID.randomUUID()}.tmp",
                    )
                    try {
                        source.copyTo(temp, overwrite = true)
                        FileOutputStream(temp, true).use { it.fd.sync() }
                        if (!temp.renameTo(mutation.target)) {
                            if (mutation.target.exists() && !mutation.target.delete()) {
                                throw IOException("Unable to replace ${mutation.target.path}")
                            }
                            if (!temp.renameTo(mutation.target)) {
                                throw IOException("Unable to promote ${mutation.target.path}")
                            }
                        }
                    } finally {
                        if (temp.exists()) {
                            temp.delete()
                        }
                    }
                }

                MutationKind.DELETE -> {
                    if (mutation.target.exists() && !mutation.target.delete()) {
                        throw IOException("Unable to remove ${mutation.target.path}")
                    }
                }
            }
        }
    }

    private fun parseFinalFiles(files: List<File>): List<BeatmapInfo> {
        val root = File(Config.getBeatmapPath()).canonicalFile
        return files.map { file ->
            val data = BeatmapParser(file).parse(true)
            val row = BeatmapInfo(data, file.parentFile?.lastModified() ?: 0L, false)
            val relativeDirectory = file.parentFile.canonicalFile.relativeTo(root)
                .path.replace(File.separatorChar, '/')
            row.copy(filename = file.name, setDirectory = relativeDirectory)
        }
    }

    private fun finalizeImport(plan: ImportPlan) {
        for (md5 in plan.oldMd5s + plan.newMd5s) {
            BeatmapArchiveImporterHooks.cacheInvalidator(md5)
        }

        // Reloading the in-memory library and publishing the generation are done only after the
        // database transaction has committed.
        BeatmapArchiveImporterHooks.libraryReload(plan.affectedDirectories)
    }

    private fun rollbackJournal(journal: ImportJournal, jobDir: File): String? {
        val errors = ArrayList<String>()
        val beatmapRoot = File(Config.getBeatmapPath()).canonicalFile

        for (mutation in journal.mutations.asReversed()) {
            val target = resolveJournalBeatmapPath(beatmapRoot, mutation.targetRelative)
            val backup = mutation.backupRelative?.let { resolveJournalJobPath(jobDir, it) }

            try {
                if (backup != null && backup.isFile) {
                    target.parentFile?.mkdirs()
                    val temp = File(
                        target.parentFile,
                        ".${target.name}.rollback-${UUID.randomUUID()}.tmp",
                    )
                    try {
                        backup.copyTo(temp, overwrite = true)
                        if (!temp.renameTo(target)) {
                            if (target.exists() && !target.delete()) {
                                throw IOException("Unable to restore ${target.path}")
                            }
                            if (!temp.renameTo(target)) {
                                throw IOException("Unable to restore ${target.path}")
                            }
                        }
                    } finally {
                        if (temp.exists()) {
                            temp.delete()
                        }
                    }
                } else if (mutation.kind == MutationKind.WRITE) {
                    // No backup means the target did not exist before this import.
                    if (target.exists() && !target.delete()) {
                        throw IOException("Unable to remove ${target.path}")
                    }
                }
            } catch (e: Exception) {
                errors += e.message ?: e.javaClass.simpleName
            }
        }

        // Database work is transactional, so a failed import has left old rows intact. If a crash
        // interrupted the database transaction, Room rolls it back on next open.

        for (directory in journal.affectedDirectories) {
            try {
                File(Config.getBeatmapPath(), directory).listFiles { file ->
                    file.name.endsWith(".osu", ignoreCase = true)
                }?.forEach { BeatmapCache.invalidate(FileUtils.getMD5Checksum(it)) }
            } catch (_: Exception) {
            }
        }

        return errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun recoverPendingImportsLocked(): List<String> {
        val journalRoot = File(stagingRoot(), JOURNAL_DIRECTORY)
        val errors = ArrayList<String>()

        val journals = journalRoot.listFiles { file -> file.extension == "journal" }
            ?: return errors

        for (journalFile in journals.sortedBy { it.name }) {
            try {
                val journal = readJournal(journalFile)
                val jobDir = File(stagingRoot(), journal.jobId)

                when (journal.phase) {
                    ImportPhase.PREPARED,
                    ImportPhase.FILES_APPLIED -> {
                        // PREPARED may include a process death during file application. Prefer
                        // finishing only when every planned final file is valid; otherwise restore
                        // backups. This is safe for both partial files and an uncommitted DB.
                        val files = journal.finalFiles.map {
                            resolveJournalBeatmapPath(
                                File(Config.getBeatmapPath()).canonicalFile,
                                it,
                            )
                        }

                        if (files.isNotEmpty() && files.all { it.isFile } &&
                            journal.phase == ImportPhase.FILES_APPLIED
                        ) {
                            val rows = parseFinalFiles(files)
                            BeatmapArchiveImporterHooks.daoProvider()
                                .applyImportChanges(rows, journal.deleteKeys)
                            journal.phase = ImportPhase.DB_APPLIED
                            writeJournal(journal)
                        } else {
                            val rollbackError = rollbackJournal(journal, jobDir)
                            if (rollbackError != null) {
                                errors += "${journalFile.name}: rollback incomplete: " +
                                    rollbackError
                                continue
                            }
                            journal.phase = ImportPhase.DONE
                            writeJournal(journal)
                        }
                    }

                    ImportPhase.DB_APPLIED -> Unit
                    ImportPhase.DONE -> Unit
                }

                if (journal.phase == ImportPhase.DB_APPLIED) {
                    val files = journal.finalFiles.map {
                        resolveJournalBeatmapPath(
                            File(Config.getBeatmapPath()).canonicalFile,
                            it,
                        )
                    }
                    val rows = parseFinalFiles(files)
                    finalizeImport(
                        plan = ImportPlan(
                            mutations = emptyList(),
                            deleteKeys = emptyList(),
                            finalFiles = files,
                            affectedDirectories = journal.affectedDirectories,
                            oldMd5s = emptySet(),
                            newMd5s = rows.map { it.md5 }.toSet(),
                            skippedIdentities = emptyList(),
                        ),
                    )
                    journal.phase = ImportPhase.DONE
                    writeJournal(journal)
                }

                cleanupJournal(journal, jobDir)
            } catch (e: Exception) {
                errors += "${journalFile.name}: ${e.message ?: e.javaClass.simpleName}"
                Log.e("BeatmapArchiveImporter", "Failed to recover ${journalFile.name}", e)
            }
        }

        return errors
    }

    private fun cleanupJournal(journal: ImportJournal, jobDir: File) {
        val journalFile = File(File(stagingRoot(), JOURNAL_DIRECTORY), "${journal.jobId}.journal")
        if (journalFile.exists()) {
            journalFile.delete()
        }
        deleteRecursively(jobDir)
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) {
            return
        }
        try {
            file.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    private fun writeJournal(journal: ImportJournal) {
        val journalRoot = File(stagingRoot(), JOURNAL_DIRECTORY)
        if (!journalRoot.exists() && !journalRoot.mkdirs() && !journalRoot.isDirectory) {
            throw IOException("Unable to create import journal directory.")
        }

        val target = File(journalRoot, "${journal.jobId}.journal")
        val temp = File(journalRoot, "${journal.jobId}.journal.tmp")

        val text = buildString {
            appendLine("version=$JOURNAL_VERSION")
            appendLine("phase=${journal.phase.name}")
            appendLine("job=${journal.jobId}")
            appendLine("affected=${journal.affectedDirectories.joinToString(",") { it.encodeBase64() }}")
            for (mutation in journal.mutations) {
                append("mutation=")
                append(mutation.kind.name)
                append('|')
                append(mutation.targetRelative.encodeBase64())
                append('|')
                append(mutation.backupRelative?.encodeBase64().orEmpty())
                appendLine()
            }
            for (key in journal.deleteKeys) {
                append("delete=")
                append(key.setDirectory.encodeBase64())
                append('|')
                append(key.filename.encodeBase64())
                appendLine()
            }
            for (final in journal.finalFiles) {
                appendLine("final=${final.encodeBase64()}")
            }
        }

        FileOutputStream(temp).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }

        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                throw IOException("Unable to write import journal.")
            }
        }
    }

    private fun readJournal(file: File): ImportJournal {
        val values = file.readLines(Charsets.UTF_8)
        var version = 0
        var phase = ImportPhase.PREPARED
        var job = ""
        var affected = emptyList<String>()
        val mutations = ArrayList<JournalMutation>()
        val deleteKeys = ArrayList<BeatmapKey>()
        val finalFiles = ArrayList<String>()

        for (line in values) {
            val separator = line.indexOf('=')
            if (separator < 0) {
                continue
            }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)

            when (key) {
                "version" -> version = value.toIntOrNull() ?: 0
                "phase" -> phase = ImportPhase.valueOf(value)
                "job" -> job = value
                "affected" -> affected = value.split(',')
                    .filter { it.isNotBlank() }
                    .map { it.decodeBase64String() }
                "mutation" -> {
                    val parts = value.split('|')
                    if (parts.size == 3) {
                        mutations += JournalMutation(
                            kind = MutationKind.valueOf(parts[0]),
                            targetRelative = parts[1].decodeBase64String(),
                            backupRelative = parts[2].takeIf { it.isNotEmpty() }
                                ?.decodeBase64String(),
                        )
                    }
                }
                "delete" -> {
                    val parts = value.split('|')
                    if (parts.size == 2) {
                        deleteKeys += BeatmapKey(
                            filename = parts[1].decodeBase64String(),
                            setDirectory = parts[0].decodeBase64String(),
                        )
                    }
                }
                "final" -> finalFiles += value.decodeBase64String()
            }
        }

        if (version != JOURNAL_VERSION || job.isBlank()) {
            throw IOException("Unsupported import journal.")
        }

        return ImportJournal(
            version = version,
            jobId = job,
            phase = phase,
            affectedDirectories = affected,
            mutations = mutations,
            deleteKeys = deleteKeys,
            finalFiles = finalFiles,
        )
    }

    private fun relativeToBeatmapPath(file: File): String =
        file.relativeTo(File(Config.getBeatmapPath()).canonicalFile)
            .path.replace(File.separatorChar, '/')

    private fun relativeToJobDir(jobDir: File, file: File): String =
        file.relativeTo(jobDir).path.replace(File.separatorChar, '/')

    private fun resolveJournalBeatmapPath(root: File, relative: String): File =
        resolveContained(root, relative)

    private fun resolveJournalJobPath(jobDir: File, relative: String): File {
        val target = File(jobDir, relative.replace('/', File.separatorChar)).canonicalFile
        val root = jobDir.canonicalFile.path + File.separator
        if (!target.path.startsWith(root, ignoreCase = true)) {
            throw ImportValidationException("Journal backup escapes its job directory.")
        }
        return target
    }

    private fun String.encodeBase64(): String =
        encodeUtf8().base64().replace('\n', ' ').trim()

    private fun String.decodeBase64String(): String =
        decodeBase64()?.utf8() ?: throw IOException("Invalid import journal encoding.")

    // ---------------------------------------------------------------------------------------------
    // Result construction
    // ---------------------------------------------------------------------------------------------

    private fun successResult(
        archive: File,
        plan: ImportPlan,
        selection: Selection,
        rows: List<BeatmapInfo>,
    ): BeatmapImportResult {
        val identities = ArrayList<BeatmapImportIdentity>()
        identities += plan.skippedIdentities
        identities += rows.map(::identityOf)

        val adjusted = selection.expectedResults.map { result ->
            if (result.matched && result.installedDirectory == null) {
                val row = rows.firstOrNull {
                    it.md5.equals(result.mapHash, ignoreCase = true)
                }
                val installed = plan.skippedIdentities.firstOrNull {
                    it.md5.equals(result.mapHash, ignoreCase = true)
                }
                result.copy(installedDirectory = row?.setDirectory ?: installed?.setDirectory)
            } else {
                result
            }
        }

        val allExpectedMatched = adjusted.all { it.matched }
        val success = allExpectedMatched &&
            (adjusted.isNotEmpty() || selection.errors.isEmpty())

        return BeatmapImportResult(
            success = success,
            archiveName = archive.name,
            affectedDirectories = plan.affectedDirectories.distinct(),
            importedBeatmaps = rows,
            identities = identities.distinctBy { it.setDirectory to it.filename },
            expectedMaps = adjusted,
            errors = selection.errors.distinct() +
                if (allExpectedMatched) emptyList() else {
                    listOf("One or more required course revisions were not available.")
                },
            failure = when {
                success -> BeatmapImportFailure.NONE
                selection.expectedResults.any { !it.matched } ->
                    BeatmapImportFailure.EXPECTED_REVISION_UNAVAILABLE
                else -> BeatmapImportFailure.FILE_SYSTEM
            },
        )
    }

    private fun failedResult(
        archive: File,
        failure: BeatmapImportFailure,
        errors: List<String>,
        expectedMaps: List<CourseMap>,
        expectedResults: List<ExpectedMapImportResult> = emptyList(),
    ): BeatmapImportResult {
        val results = expectedResults.ifEmpty {
            expectedMaps.map {
                ExpectedMapImportResult(
                    mapHash = it.mapHash,
                    matched = false,
                    installedDirectory = null,
                    message = errors.firstOrNull(),
                )
            }
        }

        return BeatmapImportResult(
            success = false,
            archiveName = archive.name,
            affectedDirectories = emptyList(),
            importedBeatmaps = emptyList(),
            identities = emptyList(),
            expectedMaps = results,
            errors = errors.distinct(),
            failure = failure,
        )
    }

    private fun identityOf(row: BeatmapInfo): BeatmapImportIdentity = BeatmapImportIdentity(
        md5 = row.md5,
        setDirectory = row.setDirectory,
        filename = row.filename,
        artist = row.artist,
        title = row.title,
        creator = row.creator,
        version = row.version,
        circleCount = row.hitCircleCount,
        sliderCount = row.sliderCount,
        spinnerCount = row.spinnerCount,
        setId = row.setId,
        beatmapId = row.id,
    )

    private fun keyOf(setDirectory: String, filename: String) = "$setDirectory\u0000$filename"

    private fun normalize(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
            .trim()
            .lowercase(Locale.ROOT)

    private fun stagingRoot(): File {
        val beatmap = File(Config.getBeatmapPath()).absoluteFile
        val parent = beatmap.parentFile ?: File(Config.getCorePath())
        return File(parent, STAGING_DIRECTORY)
    }

    // ---------------------------------------------------------------------------------------------
    // Internal data
    // ---------------------------------------------------------------------------------------------

    private data class ArchiveLayout(
        val archive: File,
        val payloadRoot: File,
        val contentRoot: File,
        val defaultSetName: String,
        val maps: List<StagedMap>,
        val assets: List<StagedAsset>,
        val parseErrors: List<String>,
    )

    private data class StagedMap(
        val file: File,
        val relativePath: String,
        val filename: String,
        val md5: String,
        val circleCount: Int,
        val sliderCount: Int,
        val spinnerCount: Int,
        val artist: String,
        val title: String,
        val creator: String,
        val version: String,
        val beatmapId: Long?,
        val setId: Int?,
        val audioFilename: String,
        val backgroundFilename: String?,
    )

    private data class StagedAsset(val file: File, val relativePath: String)

    private data class SelectedMap(val staged: StagedMap, val expected: CourseMap?)

    private data class Selection(
        val selected: List<SelectedMap>,
        val expectedResults: List<ExpectedMapImportResult>,
        val errors: List<String>,
    )

    private data class Decision(
        val selected: SelectedMap,
        var directory: String,
        var existingTarget: BeatmapInfo?,
    )

    private data class PlannedWrite(val target: File, val source: File)

    private data class PlannedDelete(val target: File)

    private data class PlannedMutation(
        val kind: MutationKind,
        val target: File,
        val source: File?,
    )

    private data class ImportPlan(
        val mutations: List<PlannedMutation>,
        val deleteKeys: List<BeatmapKey>,
        val finalFiles: List<File>,
        val affectedDirectories: List<String>,
        val oldMd5s: Set<String>,
        val newMd5s: Set<String>,
        val skippedIdentities: List<BeatmapImportIdentity>,
    )

    private data class JournalMutation(
        val kind: MutationKind,
        val targetRelative: String,
        val backupRelative: String?,
    )

    private data class ImportJournal(
        val version: Int,
        val jobId: String,
        var phase: ImportPhase,
        val affectedDirectories: List<String>,
        val mutations: List<JournalMutation>,
        val deleteKeys: List<BeatmapKey>,
        val finalFiles: List<String>,
    )

    private enum class MutationKind { WRITE, DELETE }

    private enum class ImportPhase { PREPARED, FILES_APPLIED, DB_APPLIED, DONE }

    private class ImportValidationException(message: String) : Exception(message)
}

/**
 * Fault-injection and dependency seam for importer tests. Production code uses the defaults.
 */
internal enum class ImportCommitPoint {
    AFTER_BACKUP,
    AFTER_FILES_APPLIED,
    BEFORE_DATABASE,
    AFTER_DATABASE,
    BEFORE_FINALIZE,
}

internal class SimulatedProcessDeath : Error()

internal object BeatmapArchiveImporterHooks {

    var daoProvider: () -> IBeatmapInfoDAO = { DatabaseManager.beatmapInfoTable }

    var libraryReload: (Collection<String>) -> Unit = { directories -> LibraryManager.loadLibrary(directories) }

    var cacheInvalidator: (String) -> Unit = { md5 -> BeatmapCache.invalidate(md5) }

    /**
     * Reads the actual on-disk identity for an installed row. Tests may replace this to simulate a
     * parser failure, but the default always parses the file and never trusts database metadata.
     */
    var installedIdentityReader: (File) -> BeatmapFileIdentity? = ::readBeatmapFileIdentity

    var fault: (ImportCommitPoint) -> Unit = {}

    fun resetForTesting() {
        daoProvider = { DatabaseManager.beatmapInfoTable }
        libraryReload = { directories -> LibraryManager.loadLibrary(directories) }
        cacheInvalidator = { md5 -> BeatmapCache.invalidate(md5) }
        installedIdentityReader = ::readBeatmapFileIdentity
        fault = {}
    }
}

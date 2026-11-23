package com.osudroid.skillcourse.recovery

import com.osudroid.beatmaps.parser.BeatmapParser
import com.osudroid.data.BeatmapInfo
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseMap
import java.io.File
import java.text.Normalizer
import java.util.Locale
import ru.nsu.ccfit.zuev.osu.LibraryManager

/**
 * The local availability of one authored course map.
 *
 * This is derived local state. It is never part of the portable course definition or its hash.
 */
enum class CourseMapStatus {
    /** The exact authored MD5 is installed and its object counts match. */
    READY,

    /** The authored MD5 is not installed, but the four-field metadata tuple matches a local map. */
    OUTDATED,

    /** Neither the authored MD5 nor the four-field metadata tuple matches a local map. */
    MISSING,

    /** An exact candidate exists but cannot be read, or has inconsistent object counts. */
    INVALID,
}

/**
 * The result of checking one authored map against the installed beatmap library.
 */
data class CourseMapAvailability(
    /** The authored position in [CourseDefinition.maps]. */
    @JvmField val originalIndex: Int,

    @JvmField val courseMap: CourseMap,

    @JvmField val status: CourseMapStatus,

    /** Exact candidates for [CourseMapStatus.READY], otherwise metadata candidates. */
    @JvmField val candidates: List<BeatmapInfo>,

    /**
     * The exact installed map for [CourseMapStatus.READY], or the single practice candidate for
     * [CourseMapStatus.OUTDATED]. This is `null` when multiple candidates make the choice ambiguous.
     */
    @JvmField val resolvedBeatmap: BeatmapInfo?,

    /** Human-readable diagnostic suitable for the course manager's issue list. */
    @JvmField val reason: String?,
) {
    val isReady: Boolean
        get() = status == CourseMapStatus.READY
}

/** A non-ready entry described in a form convenient for UI issue lists. */
data class CourseAvailabilityIssue(
    @JvmField val originalIndex: Int,
    @JvmField val courseMap: CourseMap,
    @JvmField val status: CourseMapStatus,
    @JvmField val reason: String?,
    @JvmField val candidates: List<BeatmapInfo>,
)

/**
 * An immutable snapshot of every authored map's availability.
 *
 * [libraryRevision] is the [CourseLibraryEvents] revision captured before the scan. Callers should
 * discard a snapshot whose revision is older than the current one instead of publishing it.
 */
data class CourseAvailabilitySnapshot(
    @JvmField val entries: List<CourseMapAvailability>,
    @JvmField val allReady: Boolean,
    @JvmField val issues: List<CourseAvailabilityIssue>,
    @JvmField val libraryRevision: Long,
) {
    /** Counts by state, including zero-valued states. */
    val statusCounts: Map<CourseMapStatus, Int>
        get() = CourseMapStatus.values().associateWith { status ->
            entries.count { it.status == status }
        }

    fun entryAt(originalIndex: Int): CourseMapAvailability? =
        entries.firstOrNull { it.originalIndex == originalIndex }

    /** All resolved exact maps, in authored order. */
    val resolvedMaps: List<BeatmapInfo>
        get() = entries.mapNotNull { it.resolvedBeatmap }
}

/**
 * A conservative identity read directly from one installed `.osu` file.
 */
data class BeatmapFileIdentity(
    @JvmField val md5: String,
    @JvmField val circleCount: Int,
    @JvmField val sliderCount: Int,
    @JvmField val spinnerCount: Int,
    @JvmField val artist: String,
    @JvmField val title: String,
    @JvmField val creator: String,
    @JvmField val version: String,
)

/** Reads an installed map's actual on-disk identity. Returning `null` means unreadable. */
fun interface BeatmapFileIdentityReader {
    fun read(file: File): BeatmapFileIdentity?
}

/**
 * Scans course maps against a library snapshot.
 *
 * [scan] may block while it revalidates the small set of files that could be an exact or metadata
 * candidate and must therefore be called from a background thread. The library snapshot is taken
 * once. When stored metadata matches nothing, a fallback pass may parse installed candidates to
 * find files whose database row is stale; each file is parsed at most once per scan.
 */
object CourseAvailabilityScanner {

    @JvmStatic
    fun scan(definition: CourseDefinition): CourseAvailabilitySnapshot {
        val revision = CourseLibraryEvents.revision
        val candidates = LibraryManager.getLibrary().flatMap { it.beatmaps }
        return scan(definition.maps, candidates, ParserFileIdentityReader, revision)
    }

    /**
     * Scans [definition] against an explicitly supplied snapshot. The reader is injectable so
     * classification can be tested without touching the filesystem.
     */
    @JvmStatic
    @JvmOverloads
    fun scan(
        definition: CourseDefinition,
        installedCandidates: Collection<BeatmapInfo>,
        reader: BeatmapFileIdentityReader = ParserFileIdentityReader,
        libraryRevision: Long = CourseLibraryEvents.revision,
    ): CourseAvailabilitySnapshot = scan(definition.maps, installedCandidates, reader, libraryRevision)

    /**
     * Pure classification entry point used by tests. The library candidates are authoritative rows;
     * their stored hashes are verified against [reader] before any entry may become ready.
     */
    internal fun scan(
        maps: List<CourseMap>,
        installedCandidates: Collection<BeatmapInfo>,
        reader: BeatmapFileIdentityReader,
        libraryRevision: Long,
    ): CourseAvailabilitySnapshot {
        val identities = HashMap<String, BeatmapFileIdentity?>()
        val entries = maps.mapIndexed { index, map ->
            classify(index, map, installedCandidates, reader, identities)
        }
        val issues = entries
            .filter { it.status != CourseMapStatus.READY }
            .map {
                CourseAvailabilityIssue(
                    originalIndex = it.originalIndex,
                    courseMap = it.courseMap,
                    status = it.status,
                    reason = it.reason,
                    candidates = it.candidates,
                )
            }

        return CourseAvailabilitySnapshot(
            entries = entries,
            // A draft with no maps is never considered ready to play.
            allReady = entries.isNotEmpty() && entries.all { it.status == CourseMapStatus.READY },
            issues = issues,
            libraryRevision = libraryRevision,
        )
    }

    private fun classify(
        index: Int,
        map: CourseMap,
        installedCandidates: Collection<BeatmapInfo>,
        reader: BeatmapFileIdentityReader,
        identityCache: MutableMap<String, BeatmapFileIdentity?>,
    ): CourseMapAvailability {
        val authoredHash = map.mapHash.trim().lowercase(Locale.ROOT)

        val exactRows = installedCandidates.filter { it.md5.equals(authoredHash, ignoreCase = true) }
        val metadataRows = installedCandidates.filter { rowMetadataMatches(map, it) }
        val inspectedRows = exactRows + metadataRows

        val validExact = LinkedHashMap<String, BeatmapInfo>()
        val outdated = LinkedHashMap<String, BeatmapInfo>()
        val invalidReasons = ArrayList<String>()

        for (row in inspectedRows.distinctBy { it.setDirectory to it.filename }) {
            val file = File(row.path)
            if (!file.isFile) {
                // A stale database row must never make an entry ready or outdated.
                continue
            }

            val identity = readIdentity(file, reader, identityCache)

            if (identity == null) {
                if (row in exactRows) {
                    invalidReasons += "Exact map candidate is unreadable: ${row.path}."
                }
                continue
            }

            if (identity.md5.equals(authoredHash, ignoreCase = true)) {
                if (countsMatch(map, identity)) {
                    validExact[keyOf(row)] = reconciled(row, identity)
                } else {
                    invalidReasons += "Installed exact map has object counts " +
                        "${countsOf(identity)}, expected ${map.objectCounts} (${row.path})."
                }
                continue
            }

            // The exact row's stored hash disagreed with the file. Treat the row as stale, but the
            // file may still be a legitimate metadata-only (outdated) candidate.
            if (metadataMatches(map, identity)) {
                outdated[keyOf(row)] = reconciled(row, identity)
            }
        }

        // The stored metadata is only a prefilter. A stale database row can otherwise hide a real
        // metadata match (or even the exact hash) that is present on disk. Parse every installed
        // candidate once per scan, but only when the prefilter found nothing usable. Identities are
        // cached for the whole scan, so this adds at most one bounded library pass and never
        // repeats I/O for the same file. Scan callers are already required to be off the main
        // thread because of the candidate reads above.
        if (validExact.isEmpty() && invalidReasons.isEmpty() && outdated.isEmpty()) {
            for (row in installedCandidates.distinctBy { it.setDirectory to it.filename }) {
                val file = File(row.path)
                if (!file.isFile) {
                    continue
                }

                val identity = readIdentity(file, reader, identityCache) ?: continue

                if (identity.md5.equals(authoredHash, ignoreCase = true)) {
                    if (countsMatch(map, identity)) {
                        validExact[keyOf(row)] = reconciled(row, identity)
                    } else {
                        invalidReasons += "Installed exact map has object counts " +
                            "${countsOf(identity)}, expected ${map.objectCounts} (${row.path})."
                    }
                } else if (metadataMatches(map, identity)) {
                    outdated[keyOf(row)] = reconciled(row, identity)
                }
            }
        }

        if (validExact.isNotEmpty()) {
            val candidates = validExact.values.sortedWith(CANDIDATE_ORDER)
            val resolved = candidates.first()
            return CourseMapAvailability(
                originalIndex = index,
                courseMap = map,
                status = CourseMapStatus.READY,
                candidates = candidates,
                resolvedBeatmap = resolved,
                reason = "Exact map installed at ${resolved.setDirectory}/${resolved.filename}.",
            )
        }

        if (invalidReasons.isNotEmpty()) {
            val candidates = exactRows.sortedWith(CANDIDATE_ORDER)
            return CourseMapAvailability(
                originalIndex = index,
                courseMap = map,
                status = CourseMapStatus.INVALID,
                candidates = candidates,
                resolvedBeatmap = null,
                reason = invalidReasons.joinToString(" "),
            )
        }

        if (outdated.isNotEmpty()) {
            val candidates = outdated.values.sortedWith(CANDIDATE_ORDER)
            val resolved = candidates.singleOrNull()
            val reason = if (resolved == null) {
                "Map is outdated: ${candidates.size} installed maps match the authored metadata " +
                    "but none has the required MD5 $authoredHash."
            } else {
                "Map is outdated: installed ${resolved.md5} at ${resolved.setDirectory}/" +
                    "${resolved.filename}, required $authoredHash."
            }
            return CourseMapAvailability(
                originalIndex = index,
                courseMap = map,
                status = CourseMapStatus.OUTDATED,
                candidates = candidates,
                resolvedBeatmap = resolved,
                reason = reason,
            )
        }

        return CourseMapAvailability(
            originalIndex = index,
            courseMap = map,
            status = CourseMapStatus.MISSING,
            candidates = emptyList(),
            resolvedBeatmap = null,
            reason = "Map is missing: no installed MD5 or metadata match for " +
                "${map.artist} - ${map.title} [${map.difficulty}].",
        )
    }

    /**
     * Caches parser results, including `null` for unreadable files, so a scan never retries the
     * same path for multiple authored maps.
     */
    private fun readIdentity(
        file: File,
        reader: BeatmapFileIdentityReader,
        identityCache: MutableMap<String, BeatmapFileIdentity?>,
    ): BeatmapFileIdentity? {
        val path = file.absolutePath
        if (identityCache.containsKey(path)) {
            return identityCache[path]
        }

        return reader.read(file).also { identityCache[path] = it }
    }

    private fun countsMatch(map: CourseMap, identity: BeatmapFileIdentity): Boolean {
        val expected = map.objectCounts
        return expected.size >= 3 &&
            expected[0] == identity.circleCount &&
            expected[1] == identity.sliderCount &&
            expected[2] == identity.spinnerCount
    }

    private fun countsOf(identity: BeatmapFileIdentity) =
        listOf(identity.circleCount, identity.sliderCount, identity.spinnerCount)

    private fun keyOf(row: BeatmapInfo) = "${row.setDirectory}\u0000${row.filename}"

    /**
     * Returns a row whose identity reflects the actual on-disk file. Stale database hashes/counts
     * must not leak into UI filters or practice selection.
     */
    private fun reconciled(row: BeatmapInfo, identity: BeatmapFileIdentity) = row.copy(
        md5 = identity.md5,
        artist = identity.artist,
        title = identity.title,
        creator = identity.creator,
        version = identity.version,
        hitCircleCount = identity.circleCount,
        sliderCount = identity.sliderCount,
        spinnerCount = identity.spinnerCount,
    )

    private val CANDIDATE_ORDER = compareBy<BeatmapInfo>(
        { it.setDirectory },
        { it.filename },
        { it.md5 },
    )

    private object ParserFileIdentityReader : BeatmapFileIdentityReader {
        override fun read(file: File): BeatmapFileIdentity? = readBeatmapFileIdentity(file)
    }
}

/**
 * Reads one installed map's actual on-disk identity. Returning `null` means the file is missing or
 * could not be parsed; callers must not fall back to stored database values.
 *
 * This is shared by the availability scanner and the archive importer so both compare against the
 * same parser-derived identity instead of trusting a potentially stale Row.
 */
internal fun readBeatmapFileIdentity(file: File): BeatmapFileIdentity? {
    if (!file.isFile) {
        return null
    }

    return try {
        val beatmap = BeatmapParser(file).parse(true)
        BeatmapFileIdentity(
            md5 = beatmap.md5,
            circleCount = beatmap.hitObjects.circleCount,
            sliderCount = beatmap.hitObjects.sliderCount,
            spinnerCount = beatmap.hitObjects.spinnerCount,
            artist = beatmap.metadata.artist,
            title = beatmap.metadata.title,
            creator = beatmap.metadata.creator,
            version = beatmap.metadata.version,
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * Conservative four-field identity normalization: Unicode NFC, surrounding whitespace removal and
 * locale-independent lower casing. Punctuation is preserved and no fuzzy matching is performed.
 */
internal fun normalizeCourseIdentity(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).trim().lowercase(Locale.ROOT)

/** Matches an authored map against the installed romanized metadata fields. */
internal fun metadataMatches(map: CourseMap, identity: BeatmapFileIdentity): Boolean =
    normalizeCourseIdentity(map.artist) == normalizeCourseIdentity(identity.artist) &&
        normalizeCourseIdentity(map.title) == normalizeCourseIdentity(identity.title) &&
        normalizeCourseIdentity(map.mapper) == normalizeCourseIdentity(identity.creator) &&
        normalizeCourseIdentity(map.difficulty) == normalizeCourseIdentity(identity.version)

/**
 * Prefilters rows whose stored romanized metadata could describe the authored map. The row is only
 * used to narrow file reads; classification rechecks the fields from [BeatmapFileIdentity].
 */
private fun rowMetadataMatches(map: CourseMap, row: BeatmapInfo): Boolean =
    normalizeCourseIdentity(map.artist) == normalizeCourseIdentity(row.artist) &&
        normalizeCourseIdentity(map.title) == normalizeCourseIdentity(row.title) &&
        normalizeCourseIdentity(map.mapper) == normalizeCourseIdentity(row.creator) &&
        normalizeCourseIdentity(map.difficulty) == normalizeCourseIdentity(row.version)

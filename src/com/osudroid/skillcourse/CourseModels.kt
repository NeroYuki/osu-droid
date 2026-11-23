package com.osudroid.skillcourse

import com.osudroid.data.BeatmapInfo
import com.osudroid.mods.APIMod
import com.osudroid.utils.ModHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The portable Skill Course definition, version 1.
 *
 * The codec validates required fields on decode even where a constructor has a default.
 * Map IDs may be null; authored mods are represented by an explicit array.
 */
@Serializable
data class CourseDefinition @JvmOverloads constructor(
    @SerialName("course_name")
    val courseName: String,

    @SerialName("author")
    val author: String,

    @SerialName("format")
    val format: String = FORMAT,

    @SerialName("course_version")
    val courseVersion: Int = 1,

    @SerialName("life_gauge")
    val lifeGauge: String = "standard",

    @SerialName("gauge_settings")
    val gaugeSettings: CourseGaugeSettings = CourseGaugeSettings(),

    @SerialName("scoring")
    val scoring: String = "scorev1",

    @SerialName("exscore_settings")
    val exscoreSettings: CourseExscoreSettings = CourseExscoreSettings(),

    @SerialName("allow_pause")
    val allowPause: Boolean = true,

    @SerialName("map_transition_ms")
    val mapTransitionMs: Long = 0L,

    @SerialName("auto_skip")
    val autoSkip: Boolean = false,

    @SerialName("is_speedrun")
    val isSpeedrun: Boolean = false,

    @SerialName("random_order")
    val randomOrder: Boolean = false,

    @SerialName("allowed_mods")
    val allowedMods: List<String> = emptyList(),

    @SerialName("maps")
    val maps: List<CourseMap> = emptyList()
) {
    companion object {
        const val FORMAT = "osu-droid-skill-course"
        const val VERSION = 1

        @JvmStatic
        fun emptyDraft(courseName: String = "New Course", author: String = "Player") = CourseDefinition(
            courseName = courseName,
            author = author
        )
    }
}

/**
 * Global gauge settings. All values are integer health units.
 */
@Serializable
data class CourseGaugeSettings @JvmOverloads constructor(
    @SerialName("total_hp")
    val totalHp: Int = 100,

    @SerialName("starting_hp")
    val startingHp: Int = 80,

    @SerialName("hit300")
    val hit300: Int = 1,

    @SerialName("hit100")
    val hit100: Int = 0,

    @SerialName("hit50")
    val hit50: Int = -1,

    @SerialName("hit0")
    val hit0: Int = -3,

    @SerialName("sliderbreak")
    val sliderbreak: Int = -1,

    @SerialName("sliderend")
    val sliderend: Int = 0
)

/**
 * Signed EX score deltas.
 */
@Serializable
data class CourseExscoreSettings @JvmOverloads constructor(
    @SerialName("hit300")
    val hit300: Int = 2,

    @SerialName("hit100")
    val hit100: Int = 1,

    @SerialName("hit50")
    val hit50: Int = 0,

    @SerialName("hit0")
    val hit0: Int = -1,

    @SerialName("sliderbreak")
    val sliderbreak: Int = 0,

    @SerialName("sliderend")
    val sliderend: Int = 0
)

/**
 * A single snapshot of a map in a course.
 *
 * This DTO deliberately contains only portable display and identity information. It never contains
 * an installed path, a set directory, local lock state, or any other local metadata.
 */
@Serializable
data class CourseMap @JvmOverloads constructor(
    @SerialName("map_hash")
    val mapHash: String,

    @SerialName("object_counts")
    val objectCounts: List<Int>,

    @SerialName("artist")
    val artist: String,

    @SerialName("artist_unicode")
    val artistUnicode: String,

    @SerialName("title")
    val title: String,

    @SerialName("title_unicode")
    val titleUnicode: String,

    @SerialName("mapper")
    val mapper: String,

    @SerialName("difficulty")
    val difficulty: String,

    @SerialName("beatmapid")
    val beatmapid: Long? = null,

    @SerialName("mods")
    val mods: List<APIMod> = emptyList()
) {
    companion object {
        /**
         * Creates an immutable snapshot from an installed beatmap and the user's current mods.
         *
         * Only user-playable mods are snapshotted. The list is sorted by acronym so that snapshots
         * are deterministic; the authored list order is then preserved by the serialized definition.
         */
        @JvmStatic
        fun fromBeatmap(beatmap: BeatmapInfo, mods: ModHashMap): CourseMap = CourseMap(
            mapHash = beatmap.md5,
            objectCounts = listOf(beatmap.hitCircleCount, beatmap.sliderCount, beatmap.spinnerCount),
            artist = beatmap.artist,
            artistUnicode = beatmap.artistUnicode,
            title = beatmap.title,
            titleUnicode = beatmap.titleUnicode,
            mapper = beatmap.creator,
            difficulty = beatmap.version,
            beatmapid = beatmap.id?.takeIf { it > 0L },
            mods = mods.values
                .asSequence()
                .filter { it.isUserPlayable }
                .sortedBy { it.acronym }
                .map { it.toAPIMod() }
                .toList()
        )
    }
}

/**
 * The six semantic judgment/policy events understood by the course rules.
 */
enum class CourseJudgment {
    HIT300,
    HIT100,
    HIT50,
    HIT0,
    SLIDERBREAK,
    SLIDEREND
}

/**
 * Thrown when a portable definition violates the shared v1 contract.
 */
open class CourseValidationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Thrown when a course mod combination or run policy violates the shared v1 contract.
 *
 * This is also a [CourseValidationException] so callers can treat every codec/import validation
 * failure uniformly while still catching policy-specific errors if they need to.
 */
class CoursePolicyException(message: String, cause: Throwable? = null) : CourseValidationException(message, cause)

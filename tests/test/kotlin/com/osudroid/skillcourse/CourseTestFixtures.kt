package com.osudroid.skillcourse

import com.osudroid.mods.APIMod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal object CourseTestFixtures {

    const val HASH_A = "00000000000000000000000000000001"
    const val HASH_B = "00000000000000000000000000000002"
    const val HASH_C = "00000000000000000000000000000003"

    fun map(
        hash: String = HASH_A,
        mods: List<APIMod> = emptyList(),
        objectCounts: List<Int> = listOf(10, 5, 1),
        artist: String = "Example Artist",
        artistUnicode: String = "",
        title: String = "Example Song",
        titleUnicode: String = "",
        mapper: String = "Example Mapper",
        difficulty: String = "Course Difficulty",
        beatmapid: Long? = null
    ) = CourseMap(
        mapHash = hash,
        objectCounts = objectCounts,
        artist = artist,
        artistUnicode = artistUnicode,
        title = title,
        titleUnicode = titleUnicode,
        mapper = mapper,
        difficulty = difficulty,
        beatmapid = beatmapid,
        mods = mods
    )

    fun definition(
        maps: List<CourseMap> = listOf(map()),
        courseName: String = "Starter Course",
        author: String = "Course Author",
        lifeGauge: String = "global",
        gaugeSettings: CourseGaugeSettings = CourseGaugeSettings(),
        scoring: String = "scorev1",
        exscoreSettings: CourseExscoreSettings = CourseExscoreSettings(),
        allowPause: Boolean = true,
        mapTransitionMs: Long = 0L,
        autoSkip: Boolean = false,
        isSpeedrun: Boolean = false,
        randomOrder: Boolean = false,
        allowedMods: List<String> = emptyList()
    ) = CourseDefinition(
        courseName = courseName,
        author = author,
        lifeGauge = lifeGauge,
        gaugeSettings = gaugeSettings,
        scoring = scoring,
        exscoreSettings = exscoreSettings,
        allowPause = allowPause,
        mapTransitionMs = mapTransitionMs,
        autoSkip = autoSkip,
        isSpeedrun = isSpeedrun,
        randomOrder = randomOrder,
        allowedMods = allowedMods,
        maps = maps
    )

    fun apiMod(acronym: String, settings: JsonObject? = null) = APIMod(acronym, settings)

    fun apiModWith(acronym: String, vararg settings: Pair<String, Any>) = APIMod(
        acronym,
        buildJsonObject {
            settings.forEach { (key, value) ->
                when (value) {
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Float -> put(key, value)
                    is Double -> put(key, value)
                    is Boolean -> put(key, value)
                    is String -> put(key, value)
                }
            }
        }
    )

    fun json(text: String): JsonElement =
        Json.decodeFromString(JsonElement.serializer(), text)

    fun rootObject(text: String): JsonObject = json(text).jsonObject

    /** Reverses every object's member order and turns arrays into new arrays. */
    fun reverseObjectOrder(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            linkedMapOf<String, JsonElement>().apply {
                element.entries.reversed().forEach { (key, value) ->
                    put(key, reverseObjectOrder(value))
                }
            }
        )

        is JsonArray -> JsonArray(element.map(::reverseObjectOrder))
        else -> element
    }

    fun encodeNoValidation(element: JsonElement): String =
        Json.encodeToString(JsonElement.serializer(), element)

    fun bool(value: Boolean) = JsonPrimitive(value)

    fun int(value: Int) = JsonPrimitive(value)

    fun string(value: String) = JsonPrimitive(value)
}

internal fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(
    linkedMapOf<String, JsonElement>().apply {
        this@without.forEach { (key, value) ->
            if (key !in keys) {
                put(key, value)
            }
        }
    }
)

internal fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(
    linkedMapOf<String, JsonElement>().apply {
        this@with.forEach { (existingKey, existingValue) ->
            put(existingKey, existingValue)
        }
        put(key, value)
    }
)

internal fun JsonObject.replace(key: String, value: JsonElement): JsonObject = JsonObject(
    linkedMapOf<String, JsonElement>().apply {
        this@replace.forEach { (existingKey, existingValue) ->
            put(existingKey, if (existingKey == key) value else existingValue)
        }
    }
)

internal fun JsonObject.getObject(key: String): JsonObject = this[key]!!.jsonObject

internal fun JsonObject.getArray(key: String): JsonArray = this[key] as JsonArray

internal fun JsonObject.mapObjects(): List<JsonObject> = getArray("maps").map { it.jsonObject }

internal fun JsonObject.putObject(key: String, value: JsonObject): JsonObject = replace(key, value)

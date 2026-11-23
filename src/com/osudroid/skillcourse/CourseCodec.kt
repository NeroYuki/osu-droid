package com.osudroid.skillcourse

import com.osudroid.mods.APIMod
import com.osudroid.utils.allModsClassesByAcronym
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.reflect.full.createInstance
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/**
 * Strict, bounded encoder/decoder and RFC 8785 hasher for portable Skill Course definitions.
 */
object CourseCodec {

    const val MAX_DEFINITION_BYTES = 1 shl 20
    const val MAX_MAPS = 256
    const val MAX_NAME_SCALARS = 128
    const val MAX_MAP_TEXT_SCALARS = 512
    const val MAX_DEPTH = 64

    private const val MIN_DELTA = -1_000_000
    private const val MAX_DELTA = 1_000_000
    private const val MAX_TOTAL_HP = 1_000_000
    private const val MAX_OBJECT_COUNT = 1_000_000L
    private const val MAX_TRANSITION_MS = 60_000L
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    private val validLifeGauges = setOf("none", "standard", "global")
    private val validScoringModes = setOf("scorev1", "scorev2", "exscore", "accuracy")

    private val rootFields = setOf(
        "format",
        "course_name",
        "course_version",
        "author",
        "life_gauge",
        "gauge_settings",
        "scoring",
        "exscore_settings",
        "allow_pause",
        "map_transition_ms",
        "auto_skip",
        "is_speedrun",
        "random_order",
        "allowed_mods",
        "maps"
    )

    private val gaugeFields = setOf(
        "total_hp", "starting_hp", "hit300", "hit100", "hit50", "hit0", "sliderbreak", "sliderend"
    )

    private val exscoreFields = setOf(
        "hit300", "hit100", "hit50", "hit0", "sliderbreak", "sliderend"
    )

    private val mapFields = setOf(
        "map_hash",
        "object_counts",
        "artist",
        "artist_unicode",
        "title",
        "title_unicode",
        "mapper",
        "difficulty",
        "beatmapid",
        "mods"
    )

    private val modFields = setOf("acronym", "settings")

    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        explicitNulls = true
    }

    private val lowercaseMd5 = Regex("^[0-9a-f]{32}$")

    /**
     * Decodes and fully validates a course definition.
     *
     * @param allowDraft Whether an empty map list is allowed. Empty lists are the only draft
     * difference; a non-empty course is validated exactly like a playable course.
     */
    @JvmStatic
    @JvmOverloads
    fun decode(text: String, allowDraft: Boolean = false): CourseDefinition {
        ensureWellFormedUtf16(text, "course definition")

        val byteCount = text.toByteArray(StandardCharsets.UTF_8).size
        if (byteCount > MAX_DEFINITION_BYTES) {
            throw CourseValidationException(
                "Course definition is $byteCount bytes; the limit is $MAX_DEFINITION_BYTES."
            )
        }

        val element = StrictJsonParser(text, MAX_DEPTH).parse()
        val root = element as? JsonObject
            ?: throw CourseValidationException("Course definition must be a JSON object.")

        validateRootShape(root)

        val definition = try {
            strictJson.decodeFromJsonElement(CourseDefinition.serializer(), root)
        } catch (e: SerializationException) {
            throw CourseValidationException("Invalid course definition: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw CourseValidationException("Invalid course definition: ${e.message}", e)
        }

        validate(definition, allowDraft)
        return definition
    }

    /**
     * Decodes a bounded UTF-8 byte stream, rejecting malformed UTF-8 instead of replacing it.
     */
    @JvmStatic
    @JvmOverloads
    fun decode(bytes: ByteArray, allowDraft: Boolean = false): CourseDefinition {
        if (bytes.size > MAX_DEFINITION_BYTES) {
            throw CourseValidationException(
                "Course definition is ${bytes.size} bytes; the limit is $MAX_DEFINITION_BYTES."
            )
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw CourseValidationException("Course definition is not valid UTF-8.", e)
        }

        return decode(text, allowDraft)
    }

    /**
     * Reads and decodes at most [MAX_DEFINITION_BYTES] from a stream. The stream is read
     * incrementally, so an oversized input is rejected without first allocating all of it.
     */
    @JvmStatic
    @JvmOverloads
    fun decode(stream: InputStream, allowDraft: Boolean = false): CourseDefinition {
        val buffer = ByteArray(8 * 1024)
        val output = java.io.ByteArrayOutputStream()

        while (true) {
            val read = stream.read(buffer)
            if (read < 0) {
                break
            }
            if (output.size() + read > MAX_DEFINITION_BYTES) {
                throw CourseValidationException(
                    "Course definition exceeds the $MAX_DEFINITION_BYTES byte limit."
                )
            }
            output.write(buffer, 0, read)
        }

        return decode(output.toByteArray(), allowDraft)
    }

    /**
     * Encodes every root and map field, including inactive setting groups.
     */
    @JvmStatic
    @JvmOverloads
    fun encode(definition: CourseDefinition, allowDraft: Boolean = false): String {
        validate(definition, allowDraft)

        val text = strictJson.encodeToString(JsonElement.serializer(), definition.toJsonObject())
        val byteCount = text.toByteArray(StandardCharsets.UTF_8).size
        if (byteCount > MAX_DEFINITION_BYTES) {
            throw CourseValidationException(
                "Encoded course definition is $byteCount bytes; the limit is $MAX_DEFINITION_BYTES."
            )
        }

        return text
    }

    /**
     * Returns the lowercase SHA-256 hex digest of the RFC 8785 canonical definition.
     */
    @JvmStatic
    @JvmOverloads
    fun hash(definition: CourseDefinition, allowDraft: Boolean = false): String {
        validate(definition, allowDraft)

        val canonical = JsonCanonicalizer.canonicalize(definition.toJsonObject())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))

        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
    }

    /**
     * Validates an in-memory definition against the shared v1 contract.
     */
    @JvmStatic
    @JvmOverloads
    fun validate(definition: CourseDefinition, allowDraft: Boolean = false) {
        if (definition.format != CourseDefinition.FORMAT) {
            throw CourseValidationException("Unsupported course format '${definition.format}'.")
        }
        if (definition.courseVersion != CourseDefinition.VERSION) {
            throw CourseValidationException(
                "Unsupported course version ${definition.courseVersion}; expected ${CourseDefinition.VERSION}."
            )
        }
        if (definition.lifeGauge !in validLifeGauges) {
            throw CourseValidationException("Unknown life gauge '${definition.lifeGauge}'.")
        }
        if (definition.scoring !in validScoringModes) {
            throw CourseValidationException("Unknown scoring mode '${definition.scoring}'.")
        }

        validateText(definition.courseName, "course_name", MAX_NAME_SCALARS, allowEmpty = false)
        validateText(definition.author, "author", MAX_NAME_SCALARS, allowEmpty = false)

        validateGauge(definition.gaugeSettings)
        validateExscore(definition.exscoreSettings)

        if (definition.mapTransitionMs !in 0L..MAX_TRANSITION_MS) {
            throw CourseValidationException(
                "map_transition_ms must be between 0 and $MAX_TRANSITION_MS."
            )
        }

        validateAllowedMods(definition.allowedMods)

        if (!allowDraft && definition.maps.isEmpty()) {
            throw CourseValidationException("A playable course must contain at least one map.")
        }
        if (definition.maps.size > MAX_MAPS) {
            throw CourseValidationException("A course may contain at most $MAX_MAPS maps.")
        }

        val seenHashes = HashSet<String>(definition.maps.size)
        for (map in definition.maps) {
            if (!seenHashes.add(map.mapHash)) {
                throw CourseValidationException("Duplicate map hash in course: ${map.mapHash}")
            }

            validateMap(definition, map)
        }
    }

    private fun validateRootShape(root: JsonObject) {
        fun stringField(value: JsonObject, key: String) {
            val primitive = value[key] as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                throw CourseValidationException("$key must be a JSON string.")
            }
        }
        fun integerField(value: JsonObject, key: String) {
            val primitive = value[key] as? JsonPrimitive
            if (primitive == null || primitive.isString || primitive.longOrNull == null) {
                throw CourseValidationException("$key must be a JSON integer.")
            }
        }
        for (key in listOf("format", "course_name", "author", "life_gauge", "scoring")) stringField(root, key)
        for (key in listOf("course_version", "map_transition_ms")) integerField(root, key)
        for (key in listOf("allow_pause", "auto_skip", "is_speedrun", "random_order")) {
            val primitive = root[key] as? JsonPrimitive
            if (primitive == null || primitive.isString || primitive.booleanOrNull == null) {
                throw CourseValidationException("$key must be a JSON boolean.")
            }
        }
        val allowed = root["allowed_mods"] as? JsonArray
            ?: throw CourseValidationException("allowed_mods must be a JSON array.")
        if (allowed.any { it !is JsonPrimitive || !it.isString }) {
            throw CourseValidationException("allowed_mods must contain JSON strings.")
        }
        val missing = rootFields - root.keys
        if (missing.isNotEmpty()) {
            throw CourseValidationException("Missing required course field(s): ${missing.sorted().joinToString()}.")
        }

        val unknown = root.keys - rootFields
        if (unknown.isNotEmpty()) {
            throw CourseValidationException("Unknown course field(s): ${unknown.sorted().joinToString()}.")
        }

        val gauge = root["gauge_settings"] as? JsonObject
            ?: throw CourseValidationException("gauge_settings must be a JSON object.")
        validateFixedObject(gauge, gaugeFields, "gauge_settings")
        gaugeFields.forEach { integerField(gauge, it) }

        val exscore = root["exscore_settings"] as? JsonObject
            ?: throw CourseValidationException("exscore_settings must be a JSON object.")
        validateFixedObject(exscore, exscoreFields, "exscore_settings")
        exscoreFields.forEach { integerField(exscore, it) }

        val maps = root["maps"] as? JsonArray
            ?: throw CourseValidationException("maps must be a JSON array.")

        maps.forEachIndexed { index, element ->
            val map = element as? JsonObject
                ?: throw CourseValidationException("maps[$index] must be a JSON object.")

            validateFixedObject(map, mapFields, "maps[$index]")
            for (key in listOf("map_hash", "artist", "artist_unicode", "title", "title_unicode", "mapper", "difficulty")) stringField(map, key)
            if (map["beatmapid"] != JsonNull) integerField(map, "beatmapid")
            val counts = map["object_counts"] as? JsonArray
                ?: throw CourseValidationException("object_counts must be a JSON array.")
            if (counts.any { it !is JsonPrimitive || it.isString || it.longOrNull == null }) {
                throw CourseValidationException("object_counts must contain JSON integers.")
            }

            val mods = map["mods"] as? JsonArray
                ?: throw CourseValidationException("maps[$index].mods must be a JSON array.")

            mods.forEachIndexed { modIndex, modElement ->
                val mod = modElement as? JsonObject
                    ?: throw CourseValidationException("maps[$index].mods[$modIndex] must be an object.")

                val unknownModFields = mod.keys - modFields
                if (unknownModFields.isNotEmpty()) {
                    throw CourseValidationException(
                        "Unknown mod field(s) at maps[$index].mods[$modIndex]: " +
                            unknownModFields.sorted().joinToString()
                    )
                }
                if ("acronym" !in mod) {
                    throw CourseValidationException(
                        "Missing acronym at maps[$index].mods[$modIndex]."
                    )
                }
                stringField(mod, "acronym")

                val settings = mod["settings"]
                if (settings != null && settings !is JsonObject) {
                    throw CourseValidationException(
                        "Mod settings at maps[$index].mods[$modIndex] must be a JSON object."
                    )
                }
            }
        }
    }

    private fun validateFixedObject(value: JsonObject, fields: Set<String>, path: String) {
        val missing = fields - value.keys
        if (missing.isNotEmpty()) {
            throw CourseValidationException("Missing required field(s) in $path: ${missing.sorted().joinToString()}.")
        }

        val unknown = value.keys - fields
        if (unknown.isNotEmpty()) {
            throw CourseValidationException("Unknown field(s) in $path: ${unknown.sorted().joinToString()}.")
        }
    }

    private fun validateGauge(settings: CourseGaugeSettings) {
        if (settings.totalHp !in 1..MAX_TOTAL_HP) {
            throw CourseValidationException("gauge_settings.total_hp must be between 1 and $MAX_TOTAL_HP.")
        }
        if (settings.startingHp !in 1..settings.totalHp) {
            throw CourseValidationException("gauge_settings.starting_hp must be between 1 and total_hp.")
        }

        validateDelta(settings.hit300, "gauge_settings.hit300")
        validateDelta(settings.hit100, "gauge_settings.hit100")
        validateDelta(settings.hit50, "gauge_settings.hit50")
        validateDelta(settings.hit0, "gauge_settings.hit0")
        validateDelta(settings.sliderbreak, "gauge_settings.sliderbreak")
        validateDelta(settings.sliderend, "gauge_settings.sliderend")
    }

    private fun validateExscore(settings: CourseExscoreSettings) {
        validateDelta(settings.hit300, "exscore_settings.hit300")
        validateDelta(settings.hit100, "exscore_settings.hit100")
        validateDelta(settings.hit50, "exscore_settings.hit50")
        validateDelta(settings.hit0, "exscore_settings.hit0")
        validateDelta(settings.sliderbreak, "exscore_settings.sliderbreak")
        validateDelta(settings.sliderend, "exscore_settings.sliderend")
    }

    private fun validateDelta(value: Int, field: String) {
        if (value !in MIN_DELTA..MAX_DELTA) {
            throw CourseValidationException("$field must be between $MIN_DELTA and $MAX_DELTA.")
        }
    }

    private fun validateAllowedMods(allowedMods: List<String>) {
        val seen = HashSet<String>(allowedMods.size)

        for (acronym in allowedMods) {
            if (!seen.add(acronym)) {
                throw CourseValidationException("allowed_mods contains duplicate acronym '$acronym'.")
            }

            val modClass = allModsClassesByAcronym[acronym]
                ?: throw CourseValidationException("allowed_mods contains unknown acronym '$acronym'.")

            val mod = try {
                modClass.createInstance()
            } catch (e: Exception) {
                throw CourseValidationException("Could not inspect allowed mod '$acronym'.", e)
            }

            if (!mod.isUserPlayable) {
                throw CourseValidationException("allowed_mods contains non-user-playable acronym '$acronym'.")
            }
        }
    }

    private fun validateMap(definition: CourseDefinition, map: CourseMap) {
        if (!lowercaseMd5.matches(map.mapHash)) {
            throw CourseValidationException(
                "Map hash '${map.mapHash}' must be exactly 32 lowercase hexadecimal characters."
            )
        }

        if (map.objectCounts.size != 3) {
            throw CourseValidationException(
                "Map ${map.mapHash} object_counts must contain [circles, sliders, spinners]."
            )
        }

        var totalObjects = 0L
        for ((index, count) in map.objectCounts.withIndex()) {
            if (count < 0) {
                throw CourseValidationException(
                    "Map ${map.mapHash} object_counts[$index] must not be negative."
                )
            }
            totalObjects += count
        }
        if (totalObjects !in 1L..MAX_OBJECT_COUNT) {
            throw CourseValidationException(
                "Map ${map.mapHash} must contain between 1 and $MAX_OBJECT_COUNT objects."
            )
        }

        validateText(map.artist, "artist", MAX_MAP_TEXT_SCALARS, allowEmpty = false)
        validateText(map.artistUnicode, "artist_unicode", MAX_MAP_TEXT_SCALARS, allowEmpty = true)
        validateText(map.title, "title", MAX_MAP_TEXT_SCALARS, allowEmpty = false)
        validateText(map.titleUnicode, "title_unicode", MAX_MAP_TEXT_SCALARS, allowEmpty = true)
        validateText(map.mapper, "mapper", MAX_MAP_TEXT_SCALARS, allowEmpty = false)
        validateText(map.difficulty, "difficulty", MAX_MAP_TEXT_SCALARS, allowEmpty = false)

        val beatmapId = map.beatmapid
        if (beatmapId != null && (beatmapId <= 0L || beatmapId > MAX_SAFE_INTEGER)) {
            throw CourseValidationException(
                "Map ${map.mapHash} beatmapid must be a positive safe integer or null."
            )
        }

        CourseModPolicy.validateAuthoredMods(definition, map)
    }

    private fun validateText(value: String, field: String, maxScalars: Int, allowEmpty: Boolean) {
        ensureWellFormedUtf16(value, field)

        var scalars = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)

            if (Character.isISOControl(codePoint)) {
                throw CourseValidationException("$field contains a control character.")
            }

            scalars++
            index += Character.charCount(codePoint)
        }

        if (!allowEmpty && scalars == 0) {
            throw CourseValidationException("$field must not be empty.")
        }
        if (scalars > maxScalars) {
            throw CourseValidationException("$field must contain at most $maxScalars Unicode scalar values.")
        }
    }

    private fun ensureWellFormedUtf16(value: String, description: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        throw CourseValidationException("$description contains an unpaired surrogate.")
                    }
                    index += 2
                }

                char.isLowSurrogate() -> {
                    throw CourseValidationException("$description contains an unpaired surrogate.")
                }

                else -> index++
            }
        }
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    internal fun CourseDefinition.toJsonObject(): JsonObject = buildJsonObject {
        put("format", format)
        put("course_name", courseName)
        put("course_version", courseVersion)
        put("author", author)
        put("life_gauge", lifeGauge)
        put("gauge_settings", gaugeSettings.toJsonObject())
        put("scoring", scoring)
        put("exscore_settings", exscoreSettings.toJsonObject())
        put("allow_pause", allowPause)
        put("map_transition_ms", mapTransitionMs)
        put("auto_skip", autoSkip)
        put("is_speedrun", isSpeedrun)
        put("random_order", randomOrder)
        putJsonArray("allowed_mods") {
            allowedMods.forEach { add(it) }
        }
        putJsonArray("maps") {
            maps.forEach { add(it.toJsonObject()) }
        }
    }

    private fun CourseGaugeSettings.toJsonObject(): JsonObject = buildJsonObject {
        put("total_hp", totalHp)
        put("starting_hp", startingHp)
        put("hit300", hit300)
        put("hit100", hit100)
        put("hit50", hit50)
        put("hit0", hit0)
        put("sliderbreak", sliderbreak)
        put("sliderend", sliderend)
    }

    private fun CourseExscoreSettings.toJsonObject(): JsonObject = buildJsonObject {
        put("hit300", hit300)
        put("hit100", hit100)
        put("hit50", hit50)
        put("hit0", hit0)
        put("sliderbreak", sliderbreak)
        put("sliderend", sliderend)
    }

    private fun CourseMap.toJsonObject(): JsonObject = buildJsonObject {
        put("map_hash", mapHash)
        putJsonArray("object_counts") {
            objectCounts.forEach { add(it) }
        }
        put("artist", artist)
        put("artist_unicode", artistUnicode)
        put("title", title)
        put("title_unicode", titleUnicode)
        put("mapper", mapper)
        put("difficulty", difficulty)
        if (beatmapid == null) {
            put("beatmapid", JsonNull)
        } else {
            put("beatmapid", beatmapid)
        }
        putJsonArray("mods") {
            mods.forEach { add(it.toJsonObject()) }
        }
    }

    private fun APIMod.toJsonObject(): JsonObject = buildJsonObject {
        put("acronym", acronym)
        val settingsObject = settings
        if (settingsObject != null) {
            put("settings", settingsObject)
        }
    }
}

/**
 * Minimal strict JSON reader that preserves the information kotlinx.serialization would otherwise
 * discard: duplicate object keys, invalid UTF-16, and excessive nesting.
 */
private class StrictJsonParser(
    private val text: String,
    private val maxDepth: Int
) {
    private var position = 0

    fun parse(): JsonElement {
        skipWhitespace()
        val value = readValue(1)
        skipWhitespace()

        if (position != text.length) {
            fail("unexpected trailing content")
        }

        return value
    }

    private fun readValue(depth: Int): JsonElement {
        if (depth > maxDepth) {
            fail("JSON nesting exceeds $maxDepth levels")
        }
        if (position >= text.length) {
            fail("unexpected end of input")
        }

        return when (text[position]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> JsonPrimitive(readString())
            't' -> {
                readLiteral("true")
                JsonPrimitive(true)
            }
            'f' -> {
                readLiteral("false")
                JsonPrimitive(false)
            }
            'n' -> {
                readLiteral("null")
                JsonNull
            }
            else -> {
                if (text[position] == '-' || text[position].isDigit()) {
                    readNumber()
                } else {
                    fail("unexpected character '${text[position]}'")
                }
            }
        }
    }

    private fun readObject(depth: Int): JsonObject {
        expect('{')
        skipWhitespace()

        val values = LinkedHashMap<String, JsonElement>()

        if (peekOrNull() == '}') {
            position++
            return JsonObject(values)
        }

        while (true) {
            skipWhitespace()
            if (peekOrNull() != '"') {
                fail("expected an object key")
            }

            val key = readString()
            skipWhitespace()
            expect(':')
            skipWhitespace()

            val value = readValue(depth + 1)

            if (key in values) {
                fail("duplicate object key '$key'")
            }
            values[key] = value

            skipWhitespace()
            when (peekOrNull()) {
                ',' -> {
                    position++
                    continue
                }

                '}' -> {
                    position++
                    return JsonObject(values)
                }

                else -> fail("expected ',' or '}' in object")
            }
        }
    }

    private fun readArray(depth: Int): JsonArray {
        expect('[')
        skipWhitespace()

        val values = ArrayList<JsonElement>()

        if (peekOrNull() == ']') {
            position++
            return JsonArray(values)
        }

        while (true) {
            skipWhitespace()
            values += readValue(depth + 1)
            skipWhitespace()

            when (peekOrNull()) {
                ',' -> {
                    position++
                    continue
                }

                ']' -> {
                    position++
                    return JsonArray(values)
                }

                else -> fail("expected ',' or ']' in array")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val builder = StringBuilder()

        while (position < text.length) {
            val char = text[position++]

            when {
                char == '"' -> {
                    val value = builder.toString()
                    checkWellFormed(value)
                    return value
                }

                char == '\\' -> {
                    if (position >= text.length) {
                        fail("unterminated string escape")
                    }

                    when (val escaped = text[position++]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (position + 4 > text.length) {
                                fail("unterminated unicode escape")
                            }
                            var code = 0
                            repeat(4) {
                                val digit = Character.digit(text[position++], 16)
                                if (digit < 0) {
                                    fail("invalid unicode escape")
                                }
                                code = code * 16 + digit
                            }
                            builder.append(code.toChar())
                        }

                        else -> fail("invalid string escape '\\$escaped'")
                    }
                }

                char < ' ' -> fail("unescaped control character in string")

                else -> builder.append(char)
            }
        }

        fail("unterminated string")
    }

    private fun readNumber(): JsonElement {
        val start = position

        if (text[position] == '-') {
            position++
        }

        if (position >= text.length) {
            fail("unterminated number")
        }

        if (text[position] == '0') {
            position++
            if (position < text.length && text[position].isDigit()) {
                fail("numbers must not have leading zeroes")
            }
        } else if (text[position] in '1'..'9') {
            while (position < text.length && text[position].isDigit()) {
                position++
            }
        } else {
            fail("invalid number")
        }

        var hasFractionOrExponent = false

        if (position < text.length && text[position] == '.') {
            hasFractionOrExponent = true
            position++
            if (position >= text.length || !text[position].isDigit()) {
                fail("invalid fraction")
            }
            while (position < text.length && text[position].isDigit()) {
                position++
            }
        }

        if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
            hasFractionOrExponent = true
            position++
            if (position < text.length && (text[position] == '+' || text[position] == '-')) {
                position++
            }
            if (position >= text.length || !text[position].isDigit()) {
                fail("invalid exponent")
            }
            while (position < text.length && text[position].isDigit()) {
                position++
            }
        }

        val raw = text.substring(start, position)

        if (hasFractionOrExponent) {
            val value = raw.toDoubleOrNull() ?: fail("invalid number '$raw'")
            if (!value.isFinite()) {
                fail("number '$raw' is not finite")
            }
            return JsonPrimitive(value)
        }

        val value = raw.toLongOrNull() ?: fail("integer '$raw' is out of range")
        return JsonPrimitive(value)
    }

    private fun readLiteral(literal: String) {
        if (!text.regionMatches(position, literal, 0, literal.length)) {
            fail("expected '$literal'")
        }
        position += literal.length
    }

    private fun checkWellFormed(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        fail("string contains an unpaired surrogate")
                    }
                    index += 2
                }

                char.isLowSurrogate() -> fail("string contains an unpaired surrogate")

                else -> index++
            }
        }
    }

    private fun skipWhitespace() {
        while (position < text.length) {
            when (text[position]) {
                ' ', '\t', '\r', '\n' -> position++
                else -> return
            }
        }
    }

    private fun expect(expected: Char) {
        if (position >= text.length || text[position] != expected) {
            fail("expected '$expected'")
        }
        position++
    }

    private fun peekOrNull(): Char? = if (position < text.length) text[position] else null

    private fun fail(message: String): Nothing {
        throw CourseValidationException(
            "Invalid course JSON at offset $position: $message."
        )
    }
}

/**
 * RFC 8785 JSON Canonicalization Scheme writer for the bounded element types used by courses.
 *
 * Object members are sorted by UTF-16 code units and numbers use ECMAScript's shortest
 * round-tripping serialization. Integers outside the exact IEEE-754 safe range are rejected rather
 * than silently rounded.
 */
internal object JsonCanonicalizer {

    private const val MAX_DEPTH = CourseCodec.MAX_DEPTH
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun canonicalize(element: JsonElement): String = buildString {
        write(element, this, 0)
    }

    private fun write(element: JsonElement, builder: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) {
            throw CourseValidationException("JSON nesting exceeds $MAX_DEPTH levels.")
        }

        when (element) {
            is JsonNull -> builder.append("null")
            is JsonObject -> {
                builder.append('{')
                val entries = element.entries.sortedBy { it.key }
                entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        builder.append(',')
                    }
                    writeString(entry.key, builder)
                    builder.append(':')
                    write(entry.value, builder, depth + 1)
                }
                builder.append('}')
            }

            is JsonArray -> {
                builder.append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) {
                        builder.append(',')
                    }
                    write(value, builder, depth + 1)
                }
                builder.append(']')
            }

            is JsonPrimitive -> writePrimitive(element, builder)
        }
    }

    private fun writePrimitive(primitive: JsonPrimitive, builder: StringBuilder) {
        if (primitive.isString) {
            writeString(primitive.content, builder)
            return
        }

        when (primitive.content) {
            "true", "false" -> builder.append(primitive.content)
            else -> builder.append(formatNumber(primitive.content))
        }
    }

    private fun formatNumber(content: String): String {
        val isInteger = content.isNotEmpty() && content.all { it.isDigit() || it == '-' }

        if (isInteger) {
            val value = content.toLongOrNull()
                ?: throw CourseValidationException("JSON integer '$content' is out of range.")
            if (value > MAX_SAFE_INTEGER || value < -MAX_SAFE_INTEGER) {
                throw CourseValidationException(
                    "JSON integer '$content' is outside the supported exact integer range."
                )
            }
            return value.toString()
        }

        val value = content.toDoubleOrNull()
            ?: throw CourseValidationException("JSON number '$content' is invalid.")
        if (!value.isFinite()) {
            throw CourseValidationException("JSON number '$content' is not finite.")
        }

        return EcmaScriptNumber.format(value)
    }

    private fun writeString(value: String, builder: StringBuilder) {
        builder.append('"')

        var index = 0
        while (index < value.length) {
            val char = value[index]

            when {
                char == '"' -> builder.append("\\\"")
                char == '\\' -> builder.append("\\\\")
                char == '\b' -> builder.append("\\b")
                char == '\u000C' -> builder.append("\\f")
                char == '\n' -> builder.append("\\n")
                char == '\r' -> builder.append("\\r")
                char == '\t' -> builder.append("\\t")
                char < ' ' -> {
                    builder.append("\\u")
                    builder.append(HEX_DIGITS[(char.code ushr 12) and 0xF])
                    builder.append(HEX_DIGITS[(char.code ushr 8) and 0xF])
                    builder.append(HEX_DIGITS[(char.code ushr 4) and 0xF])
                    builder.append(HEX_DIGITS[char.code and 0xF])
                }

                char.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        throw CourseValidationException("JSON string contains an unpaired surrogate.")
                    }
                    builder.append(char)
                    builder.append(value[index + 1])
                    index++
                }

                char.isLowSurrogate() -> throw CourseValidationException(
                    "JSON string contains an unpaired surrogate."
                )

                else -> builder.append(char)
            }

            index++
        }

        builder.append('"')
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}

/**
 * ECMAScript Number::toString for finite doubles, as required by RFC 8785.
 */
internal object EcmaScriptNumber {

    fun format(value: Double): String {
        if (!value.isFinite()) {
            throw CourseValidationException("Cannot canonicalize a non-finite number.")
        }
        if (value == 0.0) {
            return "0"
        }

        val negative = value < 0.0
        val absolute = kotlin.math.abs(value)
        val exact = BigDecimal(absolute)
        val baseExponent = exact.precision() - exact.scale()

        var chosen: BigInteger? = null
        var chosenExponent = 0
        var chosenDifference: BigDecimal? = null

        for (digits in 1..17) {
            for (candidateExponent in intArrayOf(baseExponent, baseExponent + 1)) {
                val scaled = exact.movePointRight(digits - candidateExponent)
                val nearest = scaled.setScale(0, RoundingMode.HALF_EVEN).toBigIntegerExact()
                val lower = BigInteger.TEN.pow(digits - 1)
                val upper = BigInteger.TEN.pow(digits).subtract(BigInteger.ONE)

                var best: BigInteger? = null
                var bestDifference: BigDecimal? = null

                for (offset in -2..2) {
                    val candidate = nearest + BigInteger.valueOf(offset.toLong())
                    if (candidate < lower || candidate > upper) {
                        continue
                    }

                    val decimal = BigDecimal(candidate).scaleByPowerOfTen(candidateExponent - digits)
                    if (decimal.toDouble() != absolute) {
                        continue
                    }

                    val difference = decimal.subtract(exact).abs()
                    val comparison = bestDifference?.let { difference.compareTo(it) }
                    val better = comparison == null ||
                        comparison < 0 ||
                        (comparison == 0 && !candidate.testBit(0) && best!!.testBit(0))

                    if (better) {
                        best = candidate
                        bestDifference = difference
                    }
                }

                if (best != null) {
                    if (chosen == null) {
                        chosen = best
                        chosenExponent = candidateExponent
                        chosenDifference = bestDifference
                    } else {
                        val comparison = bestDifference!!.compareTo(chosenDifference!!)
                        val chosenValue = chosen!!
                        if (comparison < 0 ||
                            (comparison == 0 && !best.testBit(0) && chosenValue.testBit(0))
                        ) {
                            chosen = best
                            chosenExponent = candidateExponent
                            chosenDifference = bestDifference
                        }
                    }
                }
            }

            if (chosen != null) {
                break
            }
        }

        val number = chosen
            ?: throw CourseValidationException("Could not produce a canonical decimal for $value.")

        val digits = number.toString()
        val length = digits.length
        val exponent = chosenExponent
        val builder = StringBuilder()

        if (negative) {
            builder.append('-')
        }

        when {
            length <= exponent && exponent <= 21 -> {
                builder.append(digits)
                repeat(exponent - length) { builder.append('0') }
            }

            exponent > 0 && exponent <= 21 -> {
                builder.append(digits, 0, exponent)
                builder.append('.')
                builder.append(digits, exponent, digits.length)
            }

            exponent > -6 && exponent <= 0 -> {
                builder.append("0.")
                repeat(-exponent) { builder.append('0') }
                builder.append(digits)
            }

            else -> {
                builder.append(digits[0])
                if (length > 1) {
                    builder.append('.')
                    builder.append(digits, 1, digits.length)
                }
                builder.append('e')
                val power = exponent - 1
                if (power >= 0) {
                    builder.append('+')
                }
                builder.append(power)
            }
        }

        return builder.toString()
    }
}

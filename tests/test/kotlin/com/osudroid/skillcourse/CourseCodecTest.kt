package com.osudroid.skillcourse

import com.osudroid.mods.APIMod
import com.osudroid.skillcourse.CourseTestFixtures.apiMod
import com.osudroid.skillcourse.CourseTestFixtures.apiModWith
import com.osudroid.skillcourse.CourseTestFixtures.definition
import com.osudroid.skillcourse.CourseTestFixtures.encodeNoValidation
import com.osudroid.skillcourse.CourseTestFixtures.int
import com.osudroid.skillcourse.CourseTestFixtures.json
import com.osudroid.skillcourse.CourseTestFixtures.map
import com.osudroid.skillcourse.CourseTestFixtures.reverseObjectOrder
import com.osudroid.skillcourse.CourseTestFixtures.rootObject
import com.osudroid.skillcourse.CourseTestFixtures.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseCodecTest {

    private fun playable() = definition(
        maps = listOf(
            map(mods = listOf(apiMod("HD"), apiModWith("DA", "ar" to 9, "od" to 8))),
            map(hash = CourseTestFixtures.HASH_B)
        )
    )

    private fun assertInvalid(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    private fun removeMapField(text: String, field: String): String {
        val root = rootObject(text)
        val maps = root.getArray("maps").map { it.jsonObject }.toMutableList()
        maps[0] = maps[0].without(field)
        return encodeNoValidation(root.replace("maps", JsonArray(maps)))
    }

    @Test
    fun `Valid definition round trips with equal hash`() {
        val original = playable()
        val encoded = CourseCodec.encode(original)
        val decoded = CourseCodec.decode(encoded)

        assertEquals(original, decoded)
        assertEquals(CourseCodec.hash(original), CourseCodec.hash(decoded))
        assertEquals(CourseCodec.hash(original), CourseCodec.hash(CourseCodec.decode(encoded)))

        // The hash ignores member order and whitespace.
        val reordered = encodeNoValidation(reverseObjectOrder(json(encoded)))
        val pretty = Json { prettyPrint = true }
            .encodeToString(JsonElement.serializer(), json(reordered))

        assertEquals(CourseCodec.hash(original), CourseCodec.hash(CourseCodec.decode(pretty)))
    }

    @Test
    fun `Exported mods are an array and preserve settings`() {
        val encoded = CourseCodec.encode(playable())

        assertTrue(encoded.contains("\"mods\":["))
        assertFalse(encoded.contains("\"mods\":\"["))

        val decoded = CourseCodec.decode(encoded)
        val mods = decoded.maps[0].mods

        assertEquals(2, mods.size)
        assertEquals("HD", mods[0].acronym)
        assertNull(mods[0].settings)

        val difficultyAdjust = mods[1]
        assertEquals("DA", difficultyAdjust.acronym)
        assertEquals(9, difficultyAdjust.settings!!["ar"]!!.jsonPrimitive.int)
        assertEquals(8, difficultyAdjust.settings!!["od"]!!.jsonPrimitive.int)
    }

    @Test
    fun `All numeric bounds are inclusive`() {
        val bounded = definition(
            gaugeSettings = CourseGaugeSettings(
                totalHp = 1_000_000,
                startingHp = 1_000_000,
                hit300 = 1_000_000,
                hit100 = -1_000_000,
                hit50 = 1_000_000,
                hit0 = -1_000_000,
                sliderbreak = 1_000_000,
                sliderend = -1_000_000
            ),
            exscoreSettings = CourseExscoreSettings(
                hit300 = -1_000_000,
                hit100 = 1_000_000,
                hit50 = -1_000_000,
                hit0 = 1_000_000,
                sliderbreak = -1_000_000,
                sliderend = 1_000_000
            ),
            mapTransitionMs = 60_000
        )

        CourseCodec.validate(bounded)
        assertEquals(bounded, CourseCodec.decode(CourseCodec.encode(bounded)))

        assertInvalid {
            CourseCodec.validate(
                definition(gaugeSettings = CourseGaugeSettings(totalHp = 0, startingHp = 1))
            )
        }
        assertInvalid {
            CourseCodec.validate(
                definition(gaugeSettings = CourseGaugeSettings(totalHp = 1_000_001, startingHp = 1))
            )
        }
        assertInvalid {
            CourseCodec.validate(
                definition(gaugeSettings = CourseGaugeSettings(totalHp = 100, startingHp = 0))
            )
        }
        assertInvalid {
            CourseCodec.validate(
                definition(gaugeSettings = CourseGaugeSettings(totalHp = 100, startingHp = 101))
            )
        }
        assertInvalid {
            CourseCodec.validate(
                definition(gaugeSettings = CourseGaugeSettings(hit300 = 1_000_001))
            )
        }
        assertInvalid {
            CourseCodec.validate(
                definition(gaugeSettings = CourseGaugeSettings(hit0 = -1_000_001))
            )
        }
        assertInvalid {
            CourseCodec.validate(
                definition(exscoreSettings = CourseExscoreSettings(sliderend = 1_000_001))
            )
        }
        assertInvalid { CourseCodec.validate(definition(mapTransitionMs = -1)) }
        assertInvalid { CourseCodec.validate(definition(mapTransitionMs = 60_001)) }
    }

    @Test
    fun `Format version gauge and scoring selectors are validated`() {
        assertInvalid { CourseCodec.validate(definition().copy(format = "other-format")) }
        assertInvalid { CourseCodec.validate(definition().copy(courseVersion = 2)) }
        assertInvalid { CourseCodec.validate(definition().copy(lifeGauge = "other")) }
        assertInvalid { CourseCodec.validate(definition().copy(scoring = "other")) }

        CourseCodec.validate(definition().copy(lifeGauge = "none"))
        CourseCodec.validate(definition().copy(lifeGauge = "standard"))
        CourseCodec.validate(definition().copy(lifeGauge = "global"))
        CourseCodec.validate(definition().copy(scoring = "scorev1"))
        CourseCodec.validate(definition().copy(scoring = "scorev2"))
        CourseCodec.validate(definition().copy(scoring = "exscore"))
        CourseCodec.validate(definition().copy(scoring = "accuracy"))
    }

    @Test
    fun `Text bounds count Unicode scalar values`() {
        val emoji128 = "\uD83D\uDE00".repeat(128)
        CourseCodec.validate(definition(courseName = emoji128))

        assertInvalid { CourseCodec.validate(definition(courseName = "")) }
        assertInvalid { CourseCodec.validate(definition(author = "")) }
        assertInvalid { CourseCodec.validate(definition(courseName = "a".repeat(129))) }
        assertInvalid { CourseCodec.validate(definition(courseName = "A\u0001B")) }
        assertInvalid { CourseCodec.validate(definition(courseName = emoji128 + "\uD83D\uDE00")) }
        assertInvalid { CourseCodec.validate(definition(maps = listOf(map(artist = "a".repeat(513))))) }
        assertInvalid { CourseCodec.validate(definition(maps = listOf(map(title = "A\nB")))) }
    }

    @Test
    fun `Map identity and object count bounds are enforced`() {
        assertInvalid { CourseCodec.validate(definition(maps = listOf(map(hash = "A".repeat(32))))) }
        assertInvalid { CourseCodec.validate(definition(maps = listOf(map(hash = "a".repeat(31))))) }
        assertInvalid { CourseCodec.validate(definition(maps = listOf(map(hash = "g".repeat(32))))) }
        assertInvalid { CourseCodec.validate(definition(maps = listOf(map(objectCounts = listOf(0, 0, 0))))) }
        assertInvalid { CourseCodec.validate(definition(maps = listOf(map(objectCounts = listOf(-1, 1, 1))))) }
        assertInvalid { CourseCodec.validate(definition(maps = listOf(map(objectCounts = listOf(1, 1))))) }
        assertInvalid { CourseCodec.validate(definition(maps = listOf(map(objectCounts = listOf(1, 1, 1, 1))))) }
        assertInvalid {
            CourseCodec.validate(definition(maps = listOf(map(objectCounts = listOf(1_000_000, 1, 1)))))
        }
        assertInvalid { CourseCodec.validate(definition(maps = listOf(map(beatmapid = 0)))) }
        assertInvalid { CourseCodec.validate(definition(maps = listOf(map(beatmapid = -1)))) }
        assertInvalid {
            CourseCodec.validate(definition(maps = listOf(map(beatmapid = 9_007_199_254_740_992L))))
        }

        CourseCodec.validate(definition(maps = listOf(map(beatmapid = 9_007_199_254_740_991L))))

        val duplicateHash = definition(maps = listOf(map(), map()))
        assertInvalid { CourseCodec.validate(duplicateHash) }

        val tooMany = definition(maps = (0 until 257).map { map(hash = "%032x".format(it + 1)) })
        assertInvalid { CourseCodec.validate(tooMany) }

        val maximum = definition(maps = (0 until 256).map { map(hash = "%032x".format(it + 1)) })
        CourseCodec.validate(maximum)
    }

    @Test
    fun `Allowed mods require unique known user-playable acronyms`() {
        CourseCodec.validate(definition(allowedMods = listOf("HD", "DT")))

        assertInvalid { CourseCodec.validate(definition(allowedMods = listOf("HD", "HD"))) }
        assertInvalid { CourseCodec.validate(definition(allowedMods = listOf("ZZ"))) }
        assertInvalid { CourseCodec.validate(definition(allowedMods = listOf("RV6"))) }
    }

    @Test
    fun `Draft allows only an empty map list`() {
        val draft = definition(maps = emptyList())

        CourseCodec.validate(draft, allowDraft = true)
        val decoded = CourseCodec.decode(CourseCodec.encode(draft, allowDraft = true), allowDraft = true)
        assertEquals(draft, decoded)
        assertNotNull(CourseCodec.hash(draft, allowDraft = true))

        assertInvalid { CourseCodec.validate(draft, allowDraft = false) }
        assertInvalid { CourseCodec.decode(CourseCodec.encode(draft, allowDraft = true)) }
        assertInvalid { CourseCodec.encode(draft, allowDraft = false) }
        assertInvalid { CourseCodec.hash(draft, allowDraft = false) }
    }

    @Test
    fun `Unknown and missing fields are rejected`() {
        val encoded = CourseCodec.encode(playable())
        val root = rootObject(encoded)

        assertInvalid { CourseCodec.decode(encodeNoValidation(root.with("unknown", int(1)))) }
        assertInvalid { CourseCodec.decode(encodeNoValidation(root.without("course_name"))) }
        assertInvalid { CourseCodec.decode(encodeNoValidation(root.replace("allow_pause", string("true")))) }
        assertInvalid { CourseCodec.decode(removeMapField(encoded, "map_hash")) }
        assertInvalid { CourseCodec.decode(removeMapField(encoded, "beatmapid")) }
        assertInvalid { CourseCodec.decode(removeMapField(encoded, "mods")) }

        val gauge = root.getObject("gauge_settings")
        assertInvalid {
            CourseCodec.decode(
                encodeNoValidation(root.replace("gauge_settings", gauge.with("unknown", int(1))))
            )
        }
        assertInvalid {
            CourseCodec.decode(
                encodeNoValidation(root.replace("gauge_settings", gauge.without("total_hp")))
            )
        }
    }

    @Test
    fun `Duplicate keys are rejected before the decoder can discard them`() {
        val encoded = CourseCodec.encode(playable())

        assertTrue(encoded.contains("\"course_name\":\"Starter Course\""))
        val duplicateRoot = encoded.replaceFirst(
            "\"course_name\":\"Starter Course\"",
            "\"course_name\":\"Starter Course\",\"course_name\":\"Other\""
        )
        assertInvalid { CourseCodec.decode(duplicateRoot) }

        assertTrue(encoded.contains("\"total_hp\":100"))
        val duplicateGauge = encoded.replaceFirst("\"total_hp\":100", "\"total_hp\":100,\"total_hp\":100")
        assertInvalid { CourseCodec.decode(duplicateGauge) }

        val withSettings = CourseCodec.encode(
            definition(maps = listOf(map(mods = listOf(apiModWith("DA", "ar" to 9, "od" to 8)))))
        )
        assertTrue(withSettings.contains("\"ar\":9"))
        val duplicateSetting = withSettings.replaceFirst("\"ar\":9", "\"ar\":9,\"ar\":9")
        assertInvalid { CourseCodec.decode(duplicateSetting) }
    }

    @Test
    fun `Unknown mod fields and settings are rejected`() {
        val encoded = CourseCodec.encode(playable())

        val unknownModField = encoded.replaceFirst(
            "{\"acronym\":\"HD\"}",
            "{\"acronym\":\"HD\",\"extra\":1}"
        )
        assertInvalid { CourseCodec.decode(unknownModField) }

        val withSettings = CourseCodec.encode(
            definition(maps = listOf(map(mods = listOf(apiModWith("DA", "ar" to 9, "od" to 8)))))
        )
        val unknownSetting = withSettings.replaceFirst("\"od\":8", "\"od\":8,\"bogus\":1")
        assertInvalid { CourseCodec.decode(unknownSetting) }
    }

    @Test
    fun `Definition size nesting and UTF-8 limits are enforced`() {
        assertInvalid { CourseCodec.decode("x".repeat(CourseCodec.MAX_DEFINITION_BYTES + 1)) }
        assertInvalid { CourseCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertInvalid { CourseCodec.decode("[".repeat(80) + "]".repeat(80)) }

        val encoded = CourseCodec.encode(playable())
        assertInvalid { CourseCodec.decode(encoded.replace("Starter Course", "\uD800")) }
        assertInvalid { CourseCodec.decode(encoded.replace("Starter Course", "\\uD800")) }
    }

    @Test
    fun `Hash changes for every identity-bearing field`() {
        val base = definition(
            maps = listOf(
                map(mods = listOf(apiMod("HD"))),
                map(hash = CourseTestFixtures.HASH_B)
            )
        )
        val baseHash = CourseCodec.hash(base)

        val variants = listOf(
            base.copy(courseName = "Other Course"),
            base.copy(author = "Other Author"),
            base.copy(lifeGauge = "standard"),
            base.copy(gaugeSettings = base.gaugeSettings.copy(hit300 = 2)),
            base.copy(scoring = "exscore"),
            base.copy(exscoreSettings = base.exscoreSettings.copy(hit300 = 3)),
            base.copy(allowPause = false),
            base.copy(mapTransitionMs = 1),
            base.copy(autoSkip = true),
            base.copy(isSpeedrun = true),
            base.copy(randomOrder = true),
            base.copy(allowedMods = listOf("HD")),
            base.copy(maps = base.maps.reversed()),
            base.copy(
                maps = listOf(
                    base.maps[0].copy(title = "Different Title"),
                    base.maps[1]
                )
            ),
            base.copy(
                maps = listOf(
                    base.maps[0].copy(mods = listOf(apiModWith("DA", "ar" to 9))),
                    base.maps[1]
                )
            )
        )

        val hashes = variants.map { CourseCodec.hash(it) }
        assertEquals(variants.size, hashes.toSet().size)
        assertFalse(baseHash in hashes)
    }

    @Test
    fun `Hash canonicalizes numeric spelling`() {
        val integerSetting = definition(
            maps = listOf(map(mods = listOf(apiModWith("DA", "ar" to 9))))
        )
        val decimalSetting = definition(
            maps = listOf(map(mods = listOf(apiModWith("DA", "ar" to 9.0))))
        )
        val exponentSetting = definition(
            maps = listOf(map(mods = listOf(apiModWith("DA", "ar" to 9e0))))
        )

        assertEquals(CourseCodec.hash(integerSetting), CourseCodec.hash(decimalSetting))
        assertEquals(CourseCodec.hash(integerSetting), CourseCodec.hash(exponentSetting))
    }

    @Test
    fun `Hash ignores object member order but not map order`() {
        val course = definition(
            maps = listOf(
                map(mods = listOf(apiModWith("DA", "ar" to 9, "od" to 8))),
                map(hash = CourseTestFixtures.HASH_B)
            )
        )
        val encoded = CourseCodec.encode(course)
        val reordered = encodeNoValidation(reverseObjectOrder(json(encoded)))

        assertEquals(CourseCodec.hash(course), CourseCodec.hash(CourseCodec.decode(reordered)))

        val reversedMaps = course.copy(maps = course.maps.reversed())
        assertFalse(CourseCodec.hash(course) == CourseCodec.hash(reversedMaps))
    }
}

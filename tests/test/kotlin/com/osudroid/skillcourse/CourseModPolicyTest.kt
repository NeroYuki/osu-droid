package com.osudroid.skillcourse

import com.osudroid.mods.APIMod
import com.osudroid.mods.ModCustomSpeed
import com.osudroid.mods.ModDifficultyAdjust
import com.osudroid.mods.ModDoubleTime
import com.osudroid.mods.ModHidden
import com.osudroid.mods.ModNoFail
import com.osudroid.mods.ModPerfect
import com.osudroid.mods.ModScoreV2
import com.osudroid.mods.ModSuddenDeath
import com.osudroid.mods.ModWindUp
import com.osudroid.skillcourse.CourseTestFixtures.apiMod
import com.osudroid.skillcourse.CourseTestFixtures.apiModWith
import com.osudroid.skillcourse.CourseTestFixtures.definition
import com.osudroid.skillcourse.CourseTestFixtures.map
import com.osudroid.utils.ModHashMap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseModPolicyTest {

    private fun assertRejected(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    private fun mods(vararg mods: com.osudroid.mods.Mod) = ModHashMap(mods.toList())

    @Test
    fun `Authored mods preserve required settings and order-independent identity`() {
        val authored = listOf(apiMod("HD"), apiModWith("DA", "ar" to 9, "od" to 8))
        val course = definition(maps = listOf(map(mods = authored)), allowedMods = listOf("HD"))
        val effective = CourseModPolicy.effectiveMods(
            course,
            course.maps[0],
            ModHashMap(),
            null
        )

        assertTrue(ModHidden::class in effective)

        val difficultyAdjust = effective.ofType<ModDifficultyAdjust>()
        assertNotNull(difficultyAdjust)
        assertEquals(9f, difficultyAdjust!!.ar!!, 0f)
        assertEquals(8f, difficultyAdjust.od!!, 0f)
        assertNull(difficultyAdjust.cs)
        assertNull(difficultyAdjust.hp)
    }

    @Test
    fun `Whitelisted additions keep their settings and non-whitelisted additions are rejected`() {
        val course = definition(maps = listOf(map()), allowedMods = listOf("HD"))
        val hidden = ModHidden().apply { onlyFadeApproachCircles = true }
        val effective = CourseModPolicy.effectiveMods(
            course,
            course.maps[0],
            mods(hidden),
            null
        )

        val effectiveHidden = effective.ofType<ModHidden>()
        assertNotNull(effectiveHidden)
        assertTrue(effectiveHidden!!.onlyFadeApproachCircles)

        // The policy copies the addition rather than mutating the caller's instance.
        effectiveHidden.onlyFadeApproachCircles = false
        assertTrue(hidden.onlyFadeApproachCircles)

        assertRejected {
            CourseModPolicy.effectiveMods(
                course,
                course.maps[0],
                mods(ModDoubleTime()),
                null
            )
        }
    }

    @Test
    fun `Additions cannot replace or evict authored mods`() {
        val authoredHidden = definition(
            maps = listOf(map(mods = listOf(apiMod("HD")))),
            allowedMods = listOf("HD")
        )
        assertRejected {
            CourseModPolicy.effectiveMods(
                authoredHidden,
                authoredHidden.maps[0],
                mods(ModHidden()),
                null
            )
        }

        val authoredHardRock = definition(
            maps = listOf(map(mods = listOf(apiMod("HR")))),
            allowedMods = listOf("EZ")
        )
        assertRejected {
            CourseModPolicy.effectiveMods(
                authoredHardRock,
                authoredHardRock.maps[0],
                mods(com.osudroid.mods.ModEasy()),
                null
            )
        }
    }

    @Test
    fun `Speedrun adds a single effective rate control`() {
        val course = definition(isSpeedrun = true, maps = listOf(map()))

        val effective = CourseModPolicy.effectiveMods(
            course,
            course.maps[0],
            ModHashMap(),
            1.15f
        )
        val customSpeed = effective.ofType<ModCustomSpeed>()
        assertNotNull(customSpeed)
        assertEquals(1.15f, customSpeed!!.trackRateMultiplier, 0f)
        assertEquals(1, effective.values.count { it is ModCustomSpeed })
    }

    @Test
    fun `Speedrun replaces an authored static rate instead of stacking`() {
        val course = definition(
            isSpeedrun = true,
            maps = listOf(map(mods = listOf(apiMod("DT"))))
        )

        val effective = CourseModPolicy.effectiveMods(
            course,
            course.maps[0],
            ModHashMap(),
            1.15f
        )

        val doubleTime = effective.ofType<ModDoubleTime>()
        assertNotNull(doubleTime)
        assertEquals(1.15f, doubleTime!!.trackRateMultiplier, 0f)
        assertNull(effective.ofType<ModCustomSpeed>())
        assertEquals(1, effective.values.count { it is com.osudroid.mods.ModRateAdjust })
    }

    @Test
    fun `Speedrun rejects ambiguous or variable rates`() {
        val ambiguous = definition(
            isSpeedrun = true,
            maps = listOf(map(mods = listOf(apiMod("DT"), apiMod("CS"))))
        )
        assertRejected {
            CourseModPolicy.effectiveMods(ambiguous, ambiguous.maps[0], ModHashMap(), 1.15f)
        }

        val variable = definition(
            isSpeedrun = true,
            maps = listOf(map(mods = listOf(apiMod("WU"))))
        )
        assertRejected { CourseCodec.validate(variable) }
        assertRejected { CourseModPolicy.effectiveMods(variable, variable.maps[0], ModHashMap(), 1.15f) }

        val additionalVariable = definition(
            isSpeedrun = true,
            maps = listOf(map()),
            allowedMods = listOf("WU")
        )
        assertRejected {
            CourseModPolicy.effectiveMods(
                additionalVariable,
                additionalVariable.maps[0],
                mods(ModWindUp()),
                1.15f
            )
        }
    }

    @Test
    fun `Codec rejects multiple authored rate mods in a speedrun before runtime`() {
        val ambiguous = definition(
            isSpeedrun = true,
            maps = listOf(map(mods = listOf(apiMod("DT"), apiMod("CS"))))
        )
        assertRejected { CourseCodec.validate(ambiguous) }
        assertRejected { CourseCodec.encode(ambiguous) }

        val single = CourseCodec.encode(
            definition(
                isSpeedrun = true,
                maps = listOf(map(mods = listOf(apiMod("DT"))))
            )
        )
        assertTrue(single.contains("\"mods\":[{\"acronym\":\"DT\"}]"))
        val decoded = single.replace(
            "\"mods\":[{\"acronym\":\"DT\"}]",
            "\"mods\":[{\"acronym\":\"DT\"},{\"acronym\":\"CS\"}]"
        )
        assertRejected { CourseCodec.decode(decoded) }
    }

    @Test
    fun `Speedrun rate bounds and steps are enforced`() {
        val course = definition(isSpeedrun = true, maps = listOf(map()))

        for (speed in listOf(0.45f, 3.05f, 1.13f, Float.NaN)) {
            assertRejected {
                CourseModPolicy.effectiveMods(course, course.maps[0], ModHashMap(), speed)
            }
        }

        CourseModPolicy.effectiveMods(course, course.maps[0], ModHashMap(), 0.5f)
        CourseModPolicy.effectiveMods(course, course.maps[0], ModHashMap(), 3.0f)

        val ordinary = definition(maps = listOf(map()))
        assertRejected {
            CourseModPolicy.effectiveMods(ordinary, ordinary.maps[0], ModHashMap(), 1.5f)
        }
    }

    @Test
    fun `Scoring policy injects ScoreV2 only for scorev2`() {
        val scoreV2 = definition(scoring = "scorev2", maps = listOf(map()))
        val effective = CourseModPolicy.effectiveMods(scoreV2, scoreV2.maps[0], ModHashMap(), null)
        assertEquals(1, effective.values.count { it is ModScoreV2 })

        val scoreV1 = definition(
            scoring = "scorev1",
            maps = listOf(map(mods = listOf(apiMod("V2"))))
        )
        assertRejected { CourseCodec.validate(scoreV1) }
        assertRejected { CourseModPolicy.effectiveMods(scoreV1, scoreV1.maps[0], ModHashMap(), null) }

        val additionalV2 = definition(
            scoring = "scorev2",
            maps = listOf(map()),
            allowedMods = listOf("V2")
        )
        val withAdditional = CourseModPolicy.effectiveMods(
            additionalV2,
            additionalV2.maps[0],
            mods(ModScoreV2()),
            null
        )
        assertEquals(1, withAdditional.values.count { it is ModScoreV2 })
    }

    @Test
    fun `Fail-controller conflicts are rejected`() {
        val failingGauge = definition(
            lifeGauge = "global",
            maps = listOf(map(mods = listOf(apiMod("NF"))))
        )
        assertRejected { CourseCodec.validate(failingGauge) }

        val noneGaugePerfect = definition(
            lifeGauge = "none",
            maps = listOf(map(mods = listOf(apiMod("PF"))))
        )
        assertRejected { CourseCodec.validate(noneGaugePerfect) }

        val noneGaugeSuddenDeath = definition(
            lifeGauge = "none",
            maps = listOf(map(mods = listOf(apiMod("SD"))))
        )
        assertRejected { CourseCodec.validate(noneGaugeSuddenDeath) }

        val noneGaugeNoFail = definition(
            lifeGauge = "none",
            maps = listOf(map(mods = listOf(apiMod("NF"))))
        )
        CourseCodec.validate(noneGaugeNoFail)

        val failingGaugePerfect = definition(
            lifeGauge = "standard",
            maps = listOf(map(mods = listOf(apiMod("PF"))))
        )
        CourseCodec.validate(failingGaugePerfect)

        val additionalNoFail = definition(
            lifeGauge = "global",
            maps = listOf(map()),
            allowedMods = listOf("NF")
        )
        assertRejected {
            CourseModPolicy.effectiveMods(
                additionalNoFail,
                additionalNoFail.maps[0],
                mods(ModNoFail()),
                null
            )
        }
    }

    @Test
    fun `Additional mods are validated against every map`() {
        val course = definition(
            isSpeedrun = true,
            maps = listOf(map(), map(hash = CourseTestFixtures.HASH_B)),
            allowedMods = listOf("HD")
        )

        CourseModPolicy.validateAdditionalMods(
            course,
            course.maps,
            mods(ModHidden()),
            listOf(1.0f, 1.1f)
        )

        assertRejected {
            CourseModPolicy.validateAdditionalMods(
                course,
                course.maps,
                mods(ModHidden()),
                listOf(1.0f)
            )
        }

        assertRejected {
            CourseModPolicy.validateAdditionalMods(
                course.copy(isSpeedrun = false),
                course.maps,
                mods(ModHidden()),
                listOf(1.0f, 1.1f)
            )
        }

        val conflictingMap = definition(
            isSpeedrun = false,
            maps = listOf(
                map(mods = listOf(apiMod("HR"))),
                map(hash = CourseTestFixtures.HASH_B)
            ),
            allowedMods = listOf("EZ")
        )
        assertRejected {
            CourseModPolicy.validateAdditionalMods(conflictingMap, conflictingMap.maps, mods(com.osudroid.mods.ModEasy()), null)
        }
    }

    @Test
    fun `Strict setting validation rejects unknown clamped or mistyped values`() {
        CourseCodec.validate(
            definition(maps = listOf(map(mods = listOf(apiModWith("DA", "ar" to 9, "od" to 8)))))
        )

        assertRejected {
            CourseCodec.validate(definition(maps = listOf(map(mods = listOf(apiModWith("DA", "bogus" to 1))))))
        }
        assertRejected {
            CourseCodec.validate(definition(maps = listOf(map(mods = listOf(apiModWith("DA", "ar" to 12.6))))))
        }
        assertRejected {
            CourseCodec.validate(definition(maps = listOf(map(mods = listOf(apiModWith("DA", "ar" to 9.05))))))
        }
        assertRejected {
            CourseCodec.validate(definition(maps = listOf(map(mods = listOf(apiModWith("AD", "scale" to 1.55))))))
        }
        assertRejected {
            CourseCodec.validate(
                definition(
                    maps = listOf(
                        map(
                            mods = listOf(
                                APIMod(
                                    "DA",
                                    buildJsonObject { put("ar", "9") }
                                )
                            )
                        )
                    )
                )
            )
        }
        assertRejected {
            CourseCodec.validate(definition(maps = listOf(map(mods = listOf(apiModWith("AD", "style" to 99))))))
        }
        CourseCodec.validate(definition(maps = listOf(map(mods = listOf(apiModWith("AD", "style" to 1))))))
        assertRejected {
            CourseCodec.validate(
                definition(maps = listOf(map(mods = listOf(apiModWith("HD", "onlyFadeApproachCircles" to "true")))))
            )
        }
        CourseCodec.validate(
            definition(maps = listOf(map(mods = listOf(apiModWith("HD", "onlyFadeApproachCircles" to true)))))
        )
        assertRejected {
            CourseCodec.validate(definition(maps = listOf(map(mods = listOf(apiMod("ZZ"))))))
        }
        assertRejected {
            CourseCodec.validate(definition(maps = listOf(map(mods = listOf(apiMod("RV6"))))))
        }
        assertRejected {
            CourseCodec.validate(definition(maps = listOf(map(mods = listOf(apiMod("HD"), apiMod("HD"))))))
        }
        assertRejected {
            CourseCodec.validate(definition(maps = listOf(map(mods = listOf(apiMod("HR"), apiMod("EZ"))))))
        }
    }

    @Test
    fun `Nullable and interdependent settings reject silent clamping`() {
        CourseCodec.validate(
            definition(maps = listOf(map(mods = listOf(apiModWith("DA", "ar" to 9)))))
        )
        CourseCodec.validate(
            definition(
                maps = listOf(
                    map(
                        mods = listOf(
                            APIMod(
                                "DA",
                                buildJsonObject { put("ar", kotlinx.serialization.json.JsonNull) }
                            )
                        )
                    )
                )
            )
        )

        assertRejected {
            CourseCodec.validate(
                definition(
                    maps = listOf(
                        map(
                            mods = listOf(
                                APIMod(
                                    "MU",
                                    buildJsonObject { put("muteComboCount", kotlinx.serialization.json.JsonNull) }
                                )
                            )
                        )
                    )
                )
            )
        }

        // With inverse muting, the applied mute combo minimum becomes 1. A raw 0 would be clamped.
        assertRejected {
            CourseCodec.validate(
                definition(
                    maps = listOf(
                        map(
                            mods = listOf(
                                apiModWith("MU", "inverseMuting" to true, "muteComboCount" to 0)
                            )
                        )
                    )
                )
            )
        }

        CourseCodec.validate(
            definition(
                maps = listOf(
                    map(
                        mods = listOf(
                            apiModWith("MU", "inverseMuting" to true, "muteComboCount" to 1)
                        )
                    )
                )
            )
        )
    }
}

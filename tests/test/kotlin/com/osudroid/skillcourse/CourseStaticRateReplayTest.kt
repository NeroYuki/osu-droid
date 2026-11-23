package com.osudroid.skillcourse

import com.osudroid.mods.ModCustomSpeed
import com.osudroid.mods.ModDoubleTime
import com.osudroid.mods.ModHalfTime
import com.osudroid.mods.ModNightCore
import com.osudroid.mods.ModRateAdjust
import com.osudroid.skillcourse.CourseTestFixtures.apiMod
import com.osudroid.skillcourse.CourseTestFixtures.definition
import com.osudroid.skillcourse.CourseTestFixtures.map
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.deserializeMods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseStaticRateReplayTest {
    @Test
    fun `Authored static rate and class survive native serialization and deep copy`() {
        for (acronym in listOf("DT", "HT", "NC", "CS")) {
            val course = definition(isSpeedrun = true, maps = listOf(map(mods = listOf(apiMod(acronym)))))
            for (speed in listOf(0.5f, 1f, 1.25f, 3f)) {
                val effective = CourseModPolicy.effectiveMods(course, course.maps.single(), ModHashMap(), speed)
                val selected = effective.values.filterIsInstance<ModRateAdjust>().single()
                val fromReplay = deserializeMods(effective.serializeMods(includeIrrelevantMods = true))
                    .values.filterIsInstance<ModRateAdjust>().single()
                val copied = effective.deepCopy().values.filterIsInstance<ModRateAdjust>().single()
                assertEquals(acronym, fromReplay.acronym)
                assertEquals(selected.javaClass, fromReplay.javaClass)
                assertEquals("$acronym replay", speed, fromReplay.trackRateMultiplier, 0f)
                assertEquals("$acronym copy", speed, copied.trackRateMultiplier, 0f)
                CourseModPolicy.validateAPIMod(fromReplay.toAPIMod())
            }
        }
    }

    @Test
    fun `Ordinary rate defaults retain their legacy compact payloads`() {
        for ((mod, expected) in listOf(
            ModDoubleTime() to 1.5f,
            ModNightCore() to 1.5f,
            ModHalfTime() to 0.75f,
            ModCustomSpeed() to 1f
        )) {
            assertEquals(expected, mod.trackRateMultiplier, 0f)
            assertNull(mod.toAPIMod().settings)
            val restored = mod.toAPIMod().toMod() as ModRateAdjust
            assertEquals(expected, restored.trackRateMultiplier, 0f)
        }
    }
}

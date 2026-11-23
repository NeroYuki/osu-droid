package com.osudroid.skillcourse.ui

import com.osudroid.mods.ModDoubleTime
import com.osudroid.mods.ModEasy
import com.osudroid.mods.ModHidden
import com.osudroid.skillcourse.CourseModPolicy
import com.osudroid.skillcourse.CourseTestFixtures.apiMod
import com.osudroid.skillcourse.CourseTestFixtures.definition
import com.osudroid.skillcourse.CourseTestFixtures.map
import com.osudroid.utils.ModHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoursePreflightSelectionTest {

    private fun mods(vararg mods: com.osudroid.mods.Mod) = ModHashMap(mods.toList())

    private fun acronyms(mods: ModHashMap) = mods.values.map { it.acronym }.sorted()

    @Test
    fun `retains the compatible saved subset and never removes authored mods`() {
        val authored = listOf(apiMod("HD"))
        val course = definition(
            maps = listOf(map(mods = authored)),
            allowedMods = listOf("HD", "DT", "EZ")
        )
        val map = course.maps[0]

        val result = CoursePreflightSelection.reconcile(
            course,
            map,
            mods(ModDoubleTime(), ModEasy(), ModHidden()),
            null
        )

        assertNull(result.baseError)
        assertEquals(listOf("DT", "EZ"), acronyms(result.mods))
        assertTrue(result.dropped.any { it.acronym == "HD" })

        val effective = CourseModPolicy.effectiveMods(course, map, result.mods, null)
        assertTrue(ModHidden::class in effective)
        assertTrue(ModDoubleTime::class in effective)
        assertNotNull(effective.ofType<ModEasy>())
    }

    @Test
    fun `drops an incompatible addition but keeps the independent valid choice`() {
        val authored = listOf(apiMod("HR"))
        val course = definition(
            maps = listOf(map(mods = authored)),
            allowedMods = listOf("HD", "EZ")
        )
        val map = course.maps[0]

        val result = CoursePreflightSelection.reconcile(
            course,
            map,
            mods(ModEasy(), ModHidden()),
            null
        )

        assertNull(result.baseError)
        assertEquals(listOf("HD"), acronyms(result.mods))
        assertEquals(listOf("EZ"), result.dropped.map { it.acronym })
        assertTrue(ModHidden::class in CourseModPolicy.effectiveMods(course, map, result.mods, null))
    }

    @Test
    fun `drops a disallowed saved mod instead of resetting every choice`() {
        val course = definition(
            maps = listOf(map()),
            allowedMods = listOf("DT")
        )
        val map = course.maps[0]

        val result = CoursePreflightSelection.reconcile(
            course,
            map,
            mods(ModDoubleTime(), ModHidden()),
            null
        )

        assertNull(result.baseError)
        assertEquals(listOf("DT"), acronyms(result.mods))
        assertEquals(listOf("HD"), result.dropped.map { it.acronym })
    }

    @Test
    fun `reports a broken authored map as a policy problem instead of dropping choices`() {
        val course = definition(
            isSpeedrun = true,
            maps = listOf(map(mods = listOf(apiMod("WU"))))
        )
        val map = course.maps[0]

        val result = CoursePreflightSelection.reconcile(
            course,
            map,
            mods(ModDoubleTime()),
            1f
        )

        assertNotNull(result.baseError)
        assertTrue(result.mods.isEmpty())
        assertTrue(result.dropped.isEmpty())
    }
}

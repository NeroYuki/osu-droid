package com.osudroid.skillcourse.ui

import com.osudroid.mods.Mod
import com.osudroid.mods.ModDoubleTime
import com.osudroid.mods.ModEasy
import com.osudroid.mods.ModHardRock
import com.osudroid.mods.ModHidden
import com.osudroid.mods.ModReplayV6
import com.osudroid.mods.ModNoFail
import com.osudroid.mods.ModScoreV2
import com.osudroid.skillcourse.CourseTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseModSelectionRulesTest {

    @Test
    fun `Gauge and scoring conflicts are disabled even when whitelisted`() {
        val map = CourseTestFixtures.map()
        val course = CourseTestFixtures.definition(maps = listOf(map), allowedMods = listOf("NF", "V2", "HD"))
        val restricted = CourseModSelectionRules.policyRestrictedClasses(course, map, null)
        assertTrue(ModNoFail::class.java in restricted)
        assertTrue(ModScoreV2::class.java in restricted)
        assertFalse(ModHidden::class.java in restricted)
    }

    @Test
    fun `Author allowlist keeps incompatible alternatives and filters non-playable mods`() {
        val result = CourseModSelectionRules.authorAllowedList(
            selected = listOf("HR", "EZ", "RV6", "UNKNOWN"),
            mods = listOf(ModHardRock(), ModEasy(), ModReplayV6())
        )

        // Both incompatible difficulty alternatives remain independently selectable, while the
        // non-user-playable replay mod and unknown acronym are removed.
        assertEquals(listOf("EZ", "HR"), result)
    }

    @Test
    fun `Canonical acronyms are case-insensitive and user-playable only`() {
        val result = CourseModSelectionRules.canonicalUserPlayableAcronyms(
            initial = listOf("hr", "Hr", "ez", "RV6", "unknown"),
            mods = listOf(ModHardRock(), ModEasy(), ModReplayV6())
        )

        assertEquals(linkedSetOf("HR", "EZ"), result)
    }

    @Test
    fun `Disallowed classes exclude required and allowed mods but include reserved rate mods`() {
        val required = setOf<Class<out Mod>>(ModHardRock::class.java)
        val allowed = setOf<Class<out Mod>>(ModEasy::class.java, ModHidden::class.java)
        val reserved = setOf<Class<out Mod>>(ModDoubleTime::class.java)

        val result = CourseModSelectionRules.disallowedClasses(
            mods = listOf(ModHardRock(), ModEasy(), ModHidden(), ModDoubleTime(), ModReplayV6()),
            requiredClasses = required,
            allowedClasses = allowed,
            additionallyRestricted = reserved
        )

        assertTrue(ModDoubleTime::class.java in result)
        assertFalse(ModHardRock::class.java in result)
        assertFalse(ModEasy::class.java in result)
        assertFalse(ModHidden::class.java in result)
        // Non-user-playable mods have no toggle at all.
        assertFalse(ModReplayV6::class.java in result)
    }

    @Test
    fun `Additional mods contain only optional mods and are deep-copied`() {
        val required = setOf<Class<out Mod>>(ModHardRock::class.java)
        val hidden = ModHidden().apply { onlyFadeApproachCircles = true }
        val selected = linkedMapOf<Class<out Mod>, Mod>(
            ModHardRock::class.java to ModHardRock(),
            ModHidden::class.java to hidden
        )

        val result = CourseModSelectionRules.additionalMods(selected, required)

        assertEquals(1, result.size)
        assertFalse(ModHardRock::class.java in result)
        assertTrue(ModHidden::class.java in result)
        assertTrue(result.ofType<ModHidden>()!!.onlyFadeApproachCircles)

        // Mutating the UI instance must not change the value that will be returned to the course.
        hidden.onlyFadeApproachCircles = false
        assertTrue(result.ofType<ModHidden>()!!.onlyFadeApproachCircles)
    }

    @Test
    fun `Additional mods reject incompatible optional combinations instead of evicting them`() {
        val selected = linkedMapOf<Class<out Mod>, Mod>(
            ModEasy::class.java to ModEasy(),
            ModHardRock::class.java to ModHardRock()
        )

        assertThrows(IllegalArgumentException::class.java) {
            CourseModSelectionRules.additionalMods(selected, emptySet<Class<out Mod>>())
        }
    }
}

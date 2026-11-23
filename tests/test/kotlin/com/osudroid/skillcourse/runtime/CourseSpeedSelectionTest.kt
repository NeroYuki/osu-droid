package com.osudroid.skillcourse.runtime

import com.osudroid.mods.ModRateAdjust
import com.osudroid.skillcourse.CourseModPolicy
import com.osudroid.skillcourse.CourseTestFixtures
import com.osudroid.skillcourse.ui.CourseUiLogic
import com.osudroid.utils.ModHashMap
import org.junit.Assert.assertEquals
import org.junit.Test

class CourseSpeedSelectionTest {
    @Test
    fun `every speed offered by preflight satisfies the course mod policy`() {
        val definition = CourseTestFixtures.definition().copy(isSpeedrun = true)
        for (index in 0 until CourseUiLogic.SPEED_STEP_COUNT) {
            val rate = CourseUiLogic.speedAt(index)
            val effective = CourseModPolicy.effectiveMods(definition, definition.maps.first(), ModHashMap(), rate)
            val applied = effective.values.filterIsInstance<ModRateAdjust>().singleOrNull()?.trackRateMultiplier ?: 1f
            assertEquals("Picker index $index", rate, applied, 0f)
        }
    }
}

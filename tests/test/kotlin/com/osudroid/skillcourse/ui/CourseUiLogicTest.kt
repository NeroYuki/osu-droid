package com.osudroid.skillcourse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseUiLogicTest {

    @Test
    fun speedStepsCoverTheContractRangeInclusively() {
        assertEquals(0.50f, CourseUiLogic.speedAt(0), 0.0001f)
        assertEquals(1.00f, CourseUiLogic.speedAt(10), 0.0001f)
        assertEquals(3.00f, CourseUiLogic.speedAt(CourseUiLogic.SPEED_STEP_COUNT - 1), 0.0001f)
        assertEquals(0.50f, CourseUiLogic.speedAt(-10), 0.0001f)
        assertEquals(3.00f, CourseUiLogic.speedAt(999), 0.0001f)
    }

    @Test
    fun speedIndexSnapsToTheNearestStepAndFallsBackForNonFiniteValues() {
        assertEquals(0, CourseUiLogic.speedIndex(0.50f))
        assertEquals(10, CourseUiLogic.speedIndex(1.00f))
        assertEquals(30, CourseUiLogic.speedIndex(2.00f))
        assertEquals(50, CourseUiLogic.speedIndex(3.00f))
        assertEquals(12, CourseUiLogic.speedIndex(1.10f))
        assertEquals(14, CourseUiLogic.speedIndex(1.20f))
        assertEquals(CourseUiLogic.SPEED_STEP_COUNT / 2, CourseUiLogic.speedIndex(Float.NaN))
    }

    @Test
    fun displayTextRejectsEmptyControlAndOverlongValues() {
        assertTrue(CourseUiLogic.isValidDisplayText("Starter Course"))
        assertTrue(CourseUiLogic.isValidDisplayText("コース"))
        assertFalse(CourseUiLogic.isValidDisplayText(""))
        assertFalse(CourseUiLogic.isValidDisplayText("bad\u0000name"))
        assertFalse(CourseUiLogic.isValidDisplayText("a".repeat(129)))
        assertTrue(CourseUiLogic.isValidDisplayText("a".repeat(128)))
    }

    @Test
    fun suggestedFileNameSanitizesButKeepsThePortableExtension() {
        assertEquals("Starter Course.odc", CourseUiLogic.suggestedFileName("Starter Course"))
        assertEquals("a_b_c__.odc", CourseUiLogic.suggestedFileName("a/b:c*?"))
        assertEquals("course.odc", CourseUiLogic.suggestedFileName("   "))
        assertTrue(CourseUiLogic.suggestedFileName("名".repeat(200)).endsWith(CourseFiles.EXTENSION))
    }
}

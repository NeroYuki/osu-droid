package com.osudroid.skillcourse.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CourseEditorSelectionTest {

    @Test
    fun `allowlist selection preserves authored order and appends new acronyms`() {
        val merged = CourseEditorSelection.mergeAllowedSelection(
            current = listOf("HD", "HR"),
            returned = listOf("HR", "DT", "HD", "DT"),
            knownUserPlayable = setOf("HD", "HR", "DT")
        )

        assertEquals(listOf("HD", "HR", "DT"), merged)
    }

    @Test
    fun `allowlist selection removes deselected and unknown acronyms`() {
        val merged = CourseEditorSelection.mergeAllowedSelection(
            current = listOf("HD", "HR", "EZ"),
            returned = listOf("ez", " HD ", "UNKNOWN", ""),
            knownUserPlayable = setOf("HD", "EZ")
        )

        assertEquals(listOf("HD"), merged)
    }

    @Test
    fun `empty selection clears the allowlist without a fallback mod`() {
        val merged = CourseEditorSelection.mergeAllowedSelection(
            current = listOf("DT"),
            returned = emptyList(),
            knownUserPlayable = setOf("DT")
        )

        assertEquals(emptyList<String>(), merged)
    }
}

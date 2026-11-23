package com.osudroid.skillcourse.recovery

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseLibraryEventsTest {

    @After
    fun tearDown() {
        CourseLibraryEvents.clearForTesting()
    }

    @Test
    fun `Import completion advances the atomic revision and publishes directories`() {
        val events = ArrayList<CourseLibraryImportEvent>()
        val listener = CourseLibraryListener { events += it }
        CourseLibraryEvents.addListener(listener)

        val before = CourseLibraryEvents.revision
        val after = CourseLibraryEvents.importCompleted(listOf(" set-a ", "set-b", "set-a", ""))

        assertEquals(before + 1, after)
        assertEquals(after, CourseLibraryEvents.revision)
        assertEquals(1, events.size)
        assertEquals(after, events.single().revision)
        assertEquals(listOf("set-a", "set-b"), events.single().affectedDirectories)
    }

    @Test
    fun `Removed listener no longer receives events`() {
        var count = 0
        val listener = CourseLibraryListener { count++ }
        CourseLibraryEvents.addListener(listener)
        CourseLibraryEvents.removeListener(listener)

        CourseLibraryEvents.importCompleted(listOf("set-a"))

        assertEquals(0, count)
    }

    @Test
    fun `A failing listener does not prevent other listeners`() {
        var count = 0
        val failing = CourseLibraryListener { error("listener failure") }
        val healthy = CourseLibraryListener { count++ }
        CourseLibraryEvents.addListener(failing)
        CourseLibraryEvents.addListener(healthy)

        CourseLibraryEvents.importCompleted(listOf("set-a"))

        assertTrue(count == 1)
    }

    @Test
    fun `Duplicate adds do not duplicate listener delivery`() {
        var count = 0
        val listener = CourseLibraryListener { count++ }
        CourseLibraryEvents.addListener(listener)
        CourseLibraryEvents.addListener(listener)

        CourseLibraryEvents.importCompleted(emptyList())

        assertEquals(1, count)
    }
}

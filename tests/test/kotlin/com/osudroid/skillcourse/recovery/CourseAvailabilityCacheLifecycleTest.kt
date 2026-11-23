package com.osudroid.skillcourse.recovery

import com.osudroid.skillcourse.CourseDefinition
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Races between a committed edit/delete and the background cache's repository snapshot. */
class CourseAvailabilityCacheLifecycleTest {
    private val tasks = ArrayDeque<Runnable>()
    private val definition = CourseAvailabilityDefinition("a", CourseDefinition(courseName = "A", author = "Test"))
    private var duringDefinitionRead: (() -> Unit)? = null
    private var duringLibraryRead: (() -> Unit)? = null
    private var libraryFailure = false
    private var scans = 0
    private val cache = CourseAvailabilityCacheCore(
        schedule = tasks::addLast,
        scan = { _, _, _, revision ->
            scans++
            CourseAvailabilitySnapshot(emptyList(), false, emptyList(), revision)
        },
        definitions = {
            val captured = listOf(definition)
            duringDefinitionRead?.invoke()
            captured
        },
        librarySnapshot = {
            duringLibraryRead?.invoke()
            check(!libraryFailure) { "Library temporarily unavailable" }
            emptyList()
        },
        readerFactory = { BeatmapFileIdentityReader { null } },
        revision = { 0L },
    )

    @Test fun `deletion during definition loading cannot resurrect a cached course`() {
        duringDefinitionRead = { cache.evictCourse("a") }
        cache.requestAll()
        tasks.removeFirst().run()
        assertNull(cache.peek("a"))
        assertEquals(0, scans)
    }

    @Test fun `deletion during library loading cannot resurrect a cached course`() {
        duringLibraryRead = { cache.evictCourse("a") }
        cache.requestAll()
        tasks.removeFirst().run()
        assertNull(cache.peek("a"))
        assertEquals(0, scans)
    }

    @Test fun `initial library read failure leaves a terminal error instead of checking forever`() {
        libraryFailure = true
        cache.requestAll()
        tasks.removeFirst().run()
        val state = cache.get("a")
        assertFalse(state.isChecking)
        assertNotNull(state.failure)

        libraryFailure = false
        cache.requestAll()
        tasks.removeFirst().run()
        assertNotNull(cache.get("a").snapshot)
    }
}

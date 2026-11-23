package com.osudroid.skillcourse

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class CourseLibraryGateTest {
    @Test fun `course pins maps until its lease is released on another thread`() {
        val gate = CourseLibraryGate()
        val course = checkNotNull(gate.tryCourse())
        assertNull(gate.tryImport())
        assertNull(gate.tryCourse())
        val closed = CountDownLatch(1)
        Thread { course.close(); closed.countDown() }.start()
        assertTrue(closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        checkNotNull(gate.tryImport()).close()
    }

    @Test fun `all queued mutations must release before the course starts`() {
        val gate = CourseLibraryGate()
        val first = checkNotNull(gate.tryImport())
        val second = checkNotNull(gate.tryImport())
        assertNull(gate.tryCourse())
        first.close()
        first.close()
        assertNull(gate.tryCourse())
        second.close()
        val course = checkNotNull(gate.tryCourse())
        first.close()
        assertNull(gate.tryImport())
        course.close()
        course.close()
        checkNotNull(gate.tryCourse()).close()
    }
}

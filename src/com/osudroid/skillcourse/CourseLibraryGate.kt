package com.osudroid.skillcourse

import java.io.Closeable

/** A course pins the library across thread handoffs; imports hold a short mutation lease. */
internal class CourseLibraryGate {
    private var course = false
    private var imports = 0

    inner class Lease internal constructor(private val forCourse: Boolean) : Closeable {
        private var closed = false

        override fun close() = synchronized(this@CourseLibraryGate) {
            if (!closed) {
                closed = true
                if (forCourse) course = false else imports--
            }
        }
    }

    @Synchronized fun tryCourse(): Lease? {
        if (course || imports != 0) return null
        course = true
        return Lease(true)
    }

    @Synchronized fun tryImport(): Lease? {
        if (course) return null
        imports++
        return Lease(false)
    }
}

internal val CourseLibraryGuard = CourseLibraryGate()

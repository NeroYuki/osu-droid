package com.osudroid.skillcourse.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class CourseClockTest {
    @Test
    fun `only active gameplay contributes across maps and pauses`() {
        var nanos = 0L
        val clock = CourseClock { nanos }
        nanos += 1_500_000_000L // Loading.
        clock.startComponent()
        nanos += 2_000_000_000L
        clock.pause()
        nanos += 700_000_000L
        clock.pause() // Duplicate lifecycle notification must not count the pause.
        clock.resume()
        nanos += 3_000_000_000L // Includes an authored break and any active stall.
        assertEquals(5_000_000_000L, clock.finishComponent())
        nanos += 2_000_000_000L // Transition and next load.
        clock.startComponent()
        nanos += 3_000_000_000L
        assertEquals(3_000_000_000L, clock.finishComponent())
        nanos += 5_000_000_000L // Results lead-out must remain excluded.
        assertEquals(8_000L, clock.totalMillis)
    }

    @Test
    fun `submillisecond durations are rounded once after accumulation`() {
        var nanos = 0L
        val clock = CourseClock { nanos }
        repeat(3) {
            clock.startComponent()
            nanos += 600_000L
            clock.finishComponent()
            clock.resume() // Cannot restart a finished component.
            nanos += 9_000_000L
        }
        assertEquals(1_800_000L, clock.totalNanos)
        assertEquals(1L, clock.totalMillis)
    }
}

package com.osudroid.skillcourse

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CourseSpeedTest {

    @Test
    fun `normalize rounds to the nearest supported step`() {
        assertEquals(100, CourseSpeed.normalize("1.00"))
        assertEquals(100, CourseSpeed.normalize("1"))
        assertEquals(100, CourseSpeed.normalize("1.01"))
        assertEquals(120, CourseSpeed.normalize("1.22"))
        assertEquals(125, CourseSpeed.normalize("1.225"))
        assertEquals(130, CourseSpeed.normalize("1.275"))
        assertEquals(300, CourseSpeed.normalize("2.999"))
    }

    @Test
    fun `normalize clamps and handles invalid input`() {
        assertEquals(50, CourseSpeed.normalize(".001"))
        assertEquals(50, CourseSpeed.normalize("0.499"))
        assertEquals(50, CourseSpeed.normalize("-2"))
        assertEquals(300, CourseSpeed.normalize("9"))
        assertEquals(300, CourseSpeed.normalize("3.01"))
        assertEquals(300, CourseSpeed.normalize("1e100000000"))
        assertEquals(50, CourseSpeed.normalize("1e-100000000"))

        assertNull(CourseSpeed.normalize(""))
        assertNull(CourseSpeed.normalize("   "))
        assertNull(CourseSpeed.normalize("NaN"))
        assertNull(CourseSpeed.normalize("Infinity"))
        assertNull(CourseSpeed.normalize("-Infinity"))
        assertNull(CourseSpeed.normalize("speed"))
    }

    @Test
    fun `rate validates the persisted invariant`() {
        assertEquals(0.5f, CourseSpeed.rate(CourseSpeed.MIN_HUNDREDTHS), 0f)
        assertEquals(1f, CourseSpeed.rate(100), 0f)
        assertEquals(1.25f, CourseSpeed.rate(125), 0f)
        assertEquals(3f, CourseSpeed.rate(CourseSpeed.MAX_HUNDREDTHS), 0f)

        assertThrows(IllegalArgumentException::class.java) { CourseSpeed.rate(45) }
        assertThrows(IllegalArgumentException::class.java) { CourseSpeed.rate(53) }
        assertThrows(IllegalArgumentException::class.java) { CourseSpeed.rate(305) }
        assertThrows(IllegalArgumentException::class.java) { CourseSpeed.rate(0) }
    }

    @Test
    fun `format is locale independent two decimals`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("0.50", CourseSpeed.format(50))
            assertEquals("1.00", CourseSpeed.format(100))
            assertEquals("1.05", CourseSpeed.format(105))
            assertEquals("1.25", CourseSpeed.format(125))
            assertEquals("3.00", CourseSpeed.format(300))
        } finally {
            Locale.setDefault(original)
        }
    }
}

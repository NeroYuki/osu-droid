package com.osudroid.skillcourse.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class CourseClientDeferredFileTest {

    @Test
    fun `known provider display suffixes are preserved`() {
        assertEquals("odc", deferredCourseFileExtension("Course.ODC", null))
        assertEquals("osz", deferredCourseFileExtension("set.osz", "application/octet-stream"))
        assertEquals("osk", deferredCourseFileExtension("skin.osk", null))
        assertEquals("odr", deferredCourseFileExtension("replay.odr", null))
        assertEquals("edr", deferredCourseFileExtension("evidence.edr", null))
    }

    @Test
    fun `opaque documents fall back to their declared MIME type`() {
        assertEquals(
            "odc",
            deferredCourseFileExtension("document", "application/vnd.osudroid.course+json")
        )
        assertEquals("odc", deferredCourseFileExtension("document", "application/json"))
        assertEquals("osz", deferredCourseFileExtension("document", "application/zip"))
        assertEquals("osz", deferredCourseFileExtension("document.unknown", null))
    }

    @Test
    fun `definition staging is bounded more tightly than beatmap archives`() {
        assertEquals(1024L * 1024, deferredCourseFileLimit("odc"))
        assertEquals(1024L * 1024 * 1024, deferredCourseFileLimit("osz"))
    }
}

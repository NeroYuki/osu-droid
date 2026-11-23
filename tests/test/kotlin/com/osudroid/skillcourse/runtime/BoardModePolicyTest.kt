package com.osudroid.skillcourse.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardModePolicyTest {

    @Test
    fun `ordinary offline only exposes the local map board`() {
        for (mode in BoardMode.values().filterNot { it.isCourse }) {
            assertEquals(BoardMode.LOCAL_MAP, BoardModePolicy.normalize(mode, online = false, courseActive = false))
        }

        assertEquals(BoardMode.LOCAL_MAP, BoardModePolicy.next(BoardMode.LOCAL_MAP, online = false, courseActive = false))
        assertEquals(BoardMode.LOCAL_MAP, BoardModePolicy.next(BoardMode.ONLINE_SCORE, online = false, courseActive = false))
        assertFalse(BoardModePolicy.isCycleVisible(BoardMode.LOCAL_MAP, online = false, courseActive = false))
    }

    @Test
    fun `ordinary online cycles local score pp and back`() {
        assertEquals(BoardMode.LOCAL_MAP, BoardModePolicy.normalize(BoardMode.LOCAL_MAP, online = true, courseActive = false))
        assertEquals(BoardMode.ONLINE_SCORE, BoardModePolicy.next(BoardMode.LOCAL_MAP, online = true, courseActive = false))
        assertEquals(BoardMode.ONLINE_PP, BoardModePolicy.next(BoardMode.ONLINE_SCORE, online = true, courseActive = false))
        assertEquals(BoardMode.LOCAL_MAP, BoardModePolicy.next(BoardMode.ONLINE_PP, online = true, courseActive = false))
        assertTrue(BoardModePolicy.isCycleVisible(BoardMode.LOCAL_MAP, online = true, courseActive = false))
    }

    @Test
    fun `course view always cycles map and local total without online requests`() {
        for (online in listOf(false, true)) {
            var mode = BoardModePolicy.normalize(BoardMode.ONLINE_SCORE, online = online, courseActive = true)
            assertEquals(BoardMode.COURSE_MAP, mode)
            assertTrue(BoardModePolicy.isCycleVisible(mode, online = online, courseActive = true))

            mode = BoardModePolicy.next(mode, online = online, courseActive = true)
            assertEquals(BoardMode.COURSE_TOTAL, mode)

            mode = BoardModePolicy.next(mode, online = online, courseActive = true)
            assertEquals(BoardMode.COURSE_MAP, mode)
        }
    }

    @Test
    fun `leaving course and losing login normalize to a safe ordinary mode`() {
        assertEquals(BoardMode.LOCAL_MAP, BoardModePolicy.normalize(BoardMode.COURSE_TOTAL, online = true, courseActive = false))
        assertEquals(BoardMode.LOCAL_MAP, BoardModePolicy.normalize(BoardMode.ONLINE_PP, online = false, courseActive = false))
        assertEquals(BoardMode.ONLINE_SCORE, BoardModePolicy.normalize(BoardMode.ONLINE_SCORE, online = true, courseActive = false))
    }

    @Test
    fun `pp mode is explicitly unavailable and never presented as a score`() {
        assertEquals(
            BoardModePolicy.ONLINE_PP_UNAVAILABLE,
            BoardModePolicy.emptyMessage(BoardMode.ONLINE_PP, mapSpecific = false, unreadable = false)
        )
        assertTrue(BoardModePolicy.label(BoardMode.ONLINE_PP).contains("unavailable"))
    }

    @Test
    fun `session preserves and restores the ordinary online mode`() {
        val session = BoardModeSession(BoardMode.ONLINE_SCORE)

        assertEquals(BoardMode.COURSE_MAP, session.enterCourse(online = true))
        assertEquals(BoardMode.COURSE_TOTAL, session.toggle(online = true, courseActive = true))
        assertEquals(BoardMode.ONLINE_SCORE, session.exitCourse(online = true))
        assertEquals(BoardMode.ONLINE_SCORE, session.mode)
        assertNull(session.ordinaryMode)
    }

    @Test
    fun `session restores a safe local mode when login was lost during the course`() {
        val session = BoardModeSession(BoardMode.ONLINE_PP)

        assertEquals(BoardMode.COURSE_MAP, session.enterCourse(online = true))
        assertEquals(BoardMode.LOCAL_MAP, session.exitCourse(online = false))
        assertEquals(BoardMode.LOCAL_MAP, session.mode)
    }

    @Test
    fun `session entering offline never resurrects an obsolete online mode`() {
        val session = BoardModeSession(BoardMode.ONLINE_SCORE)

        assertEquals(BoardMode.COURSE_MAP, session.enterCourse(online = false))
        assertEquals(BoardMode.LOCAL_MAP, session.exitCourse(online = false))
    }
}

package ru.nsu.ccfit.zuev.osu.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnlineManagerScoreResponseTest {
    @Test
    fun `tournament four-field response accepts wide scores`() {
        val manager = OnlineManager()

        assertTrue(manager.applyRecordResponse(arrayListOf("SUCCESS", "123 5000000000 0.987 456.7")))

        assertEquals(123, manager.rank)
        assertEquals(5_000_000_000L, manager.score)
        assertEquals(0.987f, manager.accuracy, 0f)
        assertEquals(456.7f, manager.pp, 0f)
    }

    @Test
    fun `additional response fields remain compatible`() {
        val manager = OnlineManager()

        assertTrue(manager.applyRecordResponse(arrayListOf("SUCCESS", "123 5000000000 0.987 456.7 42")))
        assertEquals(123, manager.rank)
    }

    @Test
    fun `incomplete response is rejected without updating the score`() {
        val manager = OnlineManager()

        assertFalse(manager.applyRecordResponse(arrayListOf("SUCCESS", "123 5000000000 0.987")))
        assertEquals("Invalid server response", manager.failMessage)
        assertEquals(0L, manager.score)
    }
}

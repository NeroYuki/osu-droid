package com.osudroid.beatmaplisting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatmapDownloadTokenTest {

    @Test
    fun `Stale cancellation cannot cancel a newer reservation`() {
        val waitingToken = BeatmapDownloadToken()
        val releasedToken = BeatmapDownloadToken()
        val newOwnerToken = BeatmapDownloadToken()

        // A caller waiting BUSY has not bound its token to the reservation.
        assertFalse(ownsReservation(waitingToken, newOwnerToken))

        // A released request's late cancellation must not affect the reservation that replaced it.
        assertFalse(ownsReservation(releasedToken, newOwnerToken))

        // Only the current owner may cancel itself.
        assertTrue(ownsReservation(newOwnerToken, newOwnerToken))
        assertFalse(ownsReservation(newOwnerToken, null))
        assertFalse(ownsReservation(null, newOwnerToken))
    }
}

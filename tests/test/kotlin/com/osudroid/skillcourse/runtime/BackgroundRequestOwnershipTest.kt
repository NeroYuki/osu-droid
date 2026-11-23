package com.osudroid.skillcourse.runtime

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.nsu.ccfit.zuev.osu.ResourceManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BackgroundRequestOwnershipTest {
    @Test
    fun `canceling a late selection cannot cancel the gameplay background`() {
        val resources = ResourceManager.getInstance()
        val selection = resources.beginBackgroundRequest()
        val gameplay = resources.beginBackgroundRequest()

        resources.cancelBackgroundRequest(selection)

        assertFalse(resources.isBackgroundRequestCurrent(selection))
        assertTrue(resources.isBackgroundRequestCurrent(gameplay))
        resources.cancelBackgroundRequest(gameplay)
        assertFalse(resources.isBackgroundRequestCurrent(gameplay))
    }

    @Test
    fun `obsolete image work returns before reading files or touching the texture manager`() {
        val resources = ResourceManager.getInstance()
        val preview = resources.beginBackgroundRequest()
        val gameplay = resources.beginBackgroundRequest()
        val before = resources.getTextureIfLoaded("::background")

        assertNull(resources.loadBackground("not-an-image-that-should-be-read.png", null, preview))
        assertSame(before, resources.getTextureIfLoaded("::background"))
        assertTrue(resources.isBackgroundRequestCurrent(gameplay))
    }
}

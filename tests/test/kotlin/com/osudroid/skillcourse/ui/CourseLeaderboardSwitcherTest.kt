package com.osudroid.skillcourse.ui

import android.view.MotionEvent
import com.osudroid.skillcourse.runtime.BoardMode
import com.osudroid.skillcourse.runtime.BoardModeSession
import com.reco1l.andengine.sprite.UISprite
import org.anddev.andengine.input.touch.TouchEvent
import org.anddev.andengine.opengl.buffer.BufferObjectManager
import org.anddev.andengine.opengl.texture.atlas.bitmap.BitmapTextureAtlas
import org.anddev.andengine.opengl.texture.region.TextureRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CourseLeaderboardSwitcherTest {

    private var previousBufferManager: BufferObjectManager? = null
    private val bufferManager = BufferObjectManager()

    @Before
    fun initializeTextureBuffers() {
        previousBufferManager = BufferObjectManager.getActiveInstance()
        BufferObjectManager.setActiveInstance(bufferManager)
    }

    @After
    fun releaseTextureBuffers() {
        bufferManager.clear()
        BufferObjectManager.setActiveInstance(previousBufferManager)
    }

    @Test
    fun `tap cycles the offline course board exactly once per tap`() {
        val session = BoardModeSession(BoardMode.COURSE_MAP)
        var cycles = 0
        val switcher = switcher {
            cycles++
            session.toggle(online = false, courseActive = true)
        }

        assertEquals(BoardMode.COURSE_MAP, session.mode)

        switcher.dispatch(TouchEvent.ACTION_DOWN, 10f, 10f)
        assertEquals(0, cycles)
        switcher.dispatch(TouchEvent.ACTION_UP, 10f, 10f)
        assertEquals(1, cycles)
        assertEquals(BoardMode.COURSE_TOTAL, session.mode)

        switcher.tap(10f, 10f)
        assertEquals(2, cycles)
        assertEquals(BoardMode.COURSE_MAP, session.mode)
    }

    @Test
    fun `drags and cancellation do not cycle the board`() {
        var cycles = 0
        val switcher = switcher { cycles++ }

        switcher.dispatch(TouchEvent.ACTION_DOWN, 10f, 10f)
        switcher.dispatch(TouchEvent.ACTION_MOVE, 90f, 10f)
        switcher.dispatch(TouchEvent.ACTION_UP, 10f, 10f)

        switcher.dispatch(TouchEvent.ACTION_DOWN, 10f, 10f)
        switcher.dispatch(TouchEvent.ACTION_CANCEL, 10f, 10f)
        switcher.dispatch(TouchEvent.ACTION_UP, 10f, 10f)

        switcher.dispatch(TouchEvent.ACTION_DOWN, 10f, 10f)
        switcher.dispatch(TouchEvent.ACTION_OUTSIDE, 10f, 10f)
        switcher.dispatch(TouchEvent.ACTION_UP, 10f, 10f)

        assertEquals(0, cycles)
    }

    @Test
    fun `mode textures use the compact ranking assets that are actually loaded`() {
        assertEquals("ranking_disabled", CourseLeaderboardSwitcher.textureName(BoardMode.LOCAL_MAP))
        assertEquals("ranking_disabled", CourseLeaderboardSwitcher.textureName(BoardMode.COURSE_MAP))
        assertEquals("ranking_enabled_score", CourseLeaderboardSwitcher.textureName(BoardMode.COURSE_TOTAL))
        assertEquals("ranking_enabled_score", CourseLeaderboardSwitcher.textureName(BoardMode.ONLINE_SCORE))
        assertEquals("ranking_enabled_pp", CourseLeaderboardSwitcher.textureName(BoardMode.ONLINE_PP))

        val assets = assetsDirectory()
        val loadedNames = setOf("ranking_disabled", "ranking_enabled_score", "ranking_enabled_pp")
        for (mode in BoardMode.values()) {
            val name = CourseLeaderboardSwitcher.textureName(mode)
            assertNotEquals("ranking_enabled", name)
            assertTrue("$name is not loaded by ResourceManager.loadSkin", name in loadedNames)
            assertEquals("$name must remain the original 50x50 icon", 50 to 50, pngSize(File(assets, "$name.png")))
        }
    }

    private fun switcher(onCycle: () -> Unit): UISprite {
        val atlas = BitmapTextureAtlas(64, 64)
        val textureRegion = TextureRegion(atlas, 0, 0, 50, 50)
        return CourseLeaderboardSwitcher.create(textureRegion, Runnable { onCycle() })
    }

    private fun UISprite.tap(x: Float, y: Float) {
        dispatch(TouchEvent.ACTION_DOWN, x, y)
        dispatch(TouchEvent.ACTION_UP, x, y)
    }

    private fun UISprite.dispatch(action: Int, x: Float, y: Float) {
        val motionEvent = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        val touchEvent = TouchEvent.obtain(x, y, action, 0, motionEvent)
        try {
            onAreaTouched(touchEvent, x, y)
        } finally {
            touchEvent.recycle()
            motionEvent.recycle()
        }
    }

    private fun assetsDirectory(): File {
        var directory: File? = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val assets = File(directory, "assets")
            if (assets.isDirectory) {
                return assets
            }
            directory = directory.parentFile
        }
        throw AssertionError("Unable to locate the osu!droid assets directory")
    }

    private fun pngSize(file: File): Pair<Int, Int> {
        assertTrue("Missing asset $file", file.isFile)
        val header = ByteArray(24)
        file.inputStream().use { it.read(header) }
        fun intAt(offset: Int): Int =
            ((header[offset].toInt() and 0xFF) shl 24) or
                ((header[offset + 1].toInt() and 0xFF) shl 16) or
                ((header[offset + 2].toInt() and 0xFF) shl 8) or
                (header[offset + 3].toInt() and 0xFF)
        return intAt(16) to intAt(20)
    }
}

package com.osudroid.skillcourse

import androidx.room.Room
import com.osudroid.data.DroidDatabase
import com.osudroid.mods.Mod
import com.osudroid.mods.ModCustomSpeed
import com.osudroid.mods.ModFlashlight
import com.osudroid.mods.ModHidden
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.deserializeMods
import java.io.File
import java.util.concurrent.Callable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CoursePlayPreferencesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: DroidDatabase
    private lateinit var core: CoursePlayPreferencesCore
    private var now = 1_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DroidDatabase::class.java
        ).allowMainThreadQueries().build()

        core = CoursePlayPreferencesCore(
            dao = database.getCoursePlayPreferenceTable(),
            clock = { now++ },
            transactionRunner = transactionRunner(database)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `Preferences are independent per course and map and survive reordering`() {
        core.saveSpeed("course-a", HASH_A, 150)
        core.saveSpeed("course-b", HASH_A, 250)
        core.saveSpeed("course-a", HASH_B, 200)

        core.saveMods("course-a", HASH_A, mods(ModHidden()))

        assertEquals(150, core.get("course-a", HASH_A)!!.speedHundredths)
        assertEquals(250, core.get("course-b", HASH_A)!!.speedHundredths)
        assertEquals(200, core.get("course-a", HASH_B)!!.speedHundredths)

        assertTrue(deserializeMods(core.get("course-a", HASH_A)!!.additionalModsJson).contains(ModHidden::class))
        assertEquals("[]", core.get("course-b", HASH_A)!!.additionalModsJson)
        assertEquals("[]", core.get("course-a", HASH_B)!!.additionalModsJson)

        // Reordering maps only changes their positions; the same MD5 still resolves the same row.
        core.saveSpeed("course-a", HASH_B, 155)
        assertEquals(155, core.get("course-a", HASH_B)!!.speedHundredths)
        assertEquals(150, core.get("course-a", HASH_A)!!.speedHundredths)
    }

    @Test
    fun `Saving speed preserves mods and saving mods preserves speed`() {
        val storedMods = mods(
            ModHidden(),
            ModFlashlight().apply { sizeMultiplier = 1.2f }
        )

        core.saveMods("course", HASH_A, storedMods)
        val afterMods = core.get("course", HASH_A)!!
        assertEquals(100, afterMods.speedHundredths)
        assertEquals(storedMods.serializeMods(includeIrrelevantMods = true), afterMods.additionalModsJson)

        core.saveSpeed("course", HASH_A, 175)
        val afterSpeed = core.get("course", HASH_A)!!
        assertEquals(175, afterSpeed.speedHundredths)
        assertEquals(afterMods.additionalModsJson, afterSpeed.additionalModsJson)
        assertEquals(1.2f, deserializeMods(afterSpeed.additionalModsJson).ofType<ModFlashlight>()!!.sizeMultiplier, 0f)

        core.saveSpeed("course", HASH_A, 180)
        val afterSecondSpeed = core.get("course", HASH_A)!!
        assertEquals(180, afterSecondSpeed.speedHundredths)
        assertEquals(afterMods.additionalModsJson, afterSecondSpeed.additionalModsJson)

        val replacement = mods(ModHidden())
        core.saveMods("course", HASH_A, replacement)
        val afterSecondMods = core.get("course", HASH_A)!!
        assertEquals(180, afterSecondMods.speedHundredths)
        assertEquals(replacement.serializeMods(includeIrrelevantMods = true), afterSecondMods.additionalModsJson)
    }

    @Test
    fun `First optional mod save retains the authored speed as one choice`() {
        core.saveMods("course", HASH_A, mods(ModHidden()), speedHundredths = 150)
        val stored = core.get("course", HASH_A)!!
        assertEquals(150, stored.speedHundredths)
        assertTrue(deserializeMods(stored.additionalModsJson).contains(ModHidden::class))
        core.saveMods("course", HASH_A, ModHashMap())
        assertEquals(150, core.get("course", HASH_A)!!.speedHundredths)
        assertThrows(IllegalArgumentException::class.java) {
            core.saveMods("course", HASH_A, mods(ModHidden()), speedHundredths = 305)
        }
        assertEquals("[]", core.get("course", HASH_A)!!.additionalModsJson)
    }

    @Test
    fun `Saving mods includes irrelevant mods so default settings can be remembered`() {
        val withIrrelevant = mods(ModCustomSpeed(1f))
        core.saveMods("course", HASH_A, withIrrelevant)

        val stored = core.get("course", HASH_A)!!.additionalModsJson
        assertEquals(withIrrelevant.serializeMods(includeIrrelevantMods = true), stored)
        assertNotEquals(withIrrelevant.serializeMods(), stored)
        assertTrue(deserializeMods(stored).contains(ModCustomSpeed::class))
    }

    @Test
    fun `Validation rejects invalid keys and rates without writing`() {
        assertThrows(IllegalArgumentException::class.java) { core.get("", HASH_A) }
        assertThrows(IllegalArgumentException::class.java) { core.get("   ", HASH_A) }
        assertThrows(IllegalArgumentException::class.java) { core.get("course", "not-a-map-hash") }
        assertThrows(IllegalArgumentException::class.java) {
            core.get("course", "z".repeat(32))
        }

        assertThrows(IllegalArgumentException::class.java) { core.saveSpeed("course", HASH_A, 45) }
        assertThrows(IllegalArgumentException::class.java) { core.saveSpeed("course", HASH_A, 53) }
        assertThrows(IllegalArgumentException::class.java) { core.saveSpeed("course", HASH_A, 305) }
        assertNull(core.get("course", HASH_A))

        core.saveSpeed("course", HASH_A, CourseSpeed.MIN_HUNDREDTHS)
        core.saveSpeed("course", HASH_B, CourseSpeed.MAX_HUNDREDTHS)
        assertEquals(50, core.get("course", HASH_A)!!.speedHundredths)
        assertEquals(300, core.get("course", HASH_B)!!.speedHundredths)
    }

    @Test
    fun `Map hashes are validated case insensitively and stored canonically`() {
        core.saveSpeed("course", HASH_A.uppercase(), 150)

        assertEquals(150, core.get("course", HASH_A)!!.speedHundredths)
        assertEquals(150, core.get("course", HASH_A.uppercase())!!.speedHundredths)
    }

    @Test
    fun `Preferences survive closing and reopening the database`() {
        val path = File(temp.newFolder("database"), "preferences.db").absolutePath
        val savedMods = mods(ModHidden())

        val first = openFileDatabase(path)
        try {
            val firstCore = CoursePlayPreferencesCore(
                dao = first.getCoursePlayPreferenceTable(),
                clock = { 10L },
                transactionRunner = transactionRunner(first)
            )
            firstCore.saveSpeed("course", HASH_A, 165)
            firstCore.saveMods("course", HASH_A, savedMods)
        } finally {
            first.close()
        }

        val reopened = openFileDatabase(path)
        try {
            val reopenedCore = CoursePlayPreferencesCore(
                dao = reopened.getCoursePlayPreferenceTable(),
                clock = { 20L },
                transactionRunner = transactionRunner(reopened)
            )

            val preference = reopenedCore.get("course", HASH_A)!!
            assertEquals(165, preference.speedHundredths)
            assertEquals(savedMods.serializeMods(includeIrrelevantMods = true), preference.additionalModsJson)
            assertEquals(10L, preference.updatedAt)
        } finally {
            reopened.close()
        }
    }

    private fun openFileDatabase(path: String): DroidDatabase =
        Room.databaseBuilder(
            RuntimeEnvironment.getApplication(),
            DroidDatabase::class.java,
            path
        ).allowMainThreadQueries().build()

    private fun transactionRunner(database: DroidDatabase): (() -> Unit) -> Unit =
        { block -> database.runInTransaction(Callable { block() }) }

    private fun mods(vararg selected: Mod): ModHashMap =
        ModHashMap().apply {
            selected.forEach { put(it) }
        }

    private companion object {
        const val HASH_A = "00000000000000000000000000000001"
        const val HASH_B = "00000000000000000000000000000002"
    }
}

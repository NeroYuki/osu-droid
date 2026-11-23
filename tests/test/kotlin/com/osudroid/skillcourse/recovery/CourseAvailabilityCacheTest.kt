package com.osudroid.skillcourse.recovery

import com.osudroid.data.BeatmapInfo
import com.osudroid.skillcourse.CourseDefinition
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CourseAvailabilityCacheTest {

    private class ManualScheduler {
        private val tasks = ArrayDeque<Runnable>()

        val size: Int
            get() = tasks.size

        fun schedule(task: Runnable) {
            tasks.addLast(task)
        }

        fun take(): Runnable = tasks.removeFirst()

        fun runAll() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
            }
        }
    }

    private data class ScanCall(
        val definition: CourseDefinition,
        val candidates: Collection<BeatmapInfo>,
        val reader: BeatmapFileIdentityReader,
        val revision: Long,
    )

    private lateinit var scheduler: ManualScheduler
    private lateinit var core: CourseAvailabilityCacheCore

    private var revision = 0L
    private var definitions = listOf<CourseAvailabilityDefinition>()
    private var scanFailure: Throwable? = null
    private var scanBehavior: ((callIndex: Int, revision: Long) -> Unit)? = null

    private val scanLock = Any()
    private var scanCount = 0
    private val scanCalls = ArrayList<ScanCall>()
    private val installedCandidates = ArrayList<BeatmapInfo>()

    @Before
    fun setUp() {
        scheduler = ManualScheduler()
        core = CourseAvailabilityCacheCore(
            schedule = scheduler::schedule,
            scan = { definition, candidates, reader, libraryRevision ->
                val callIndex = synchronized(scanLock) {
                    val index = scanCount++
                    scanCalls += ScanCall(definition, candidates, reader, libraryRevision)
                    index
                }
                scanBehavior?.invoke(callIndex, libraryRevision)
                scanFailure?.let { throw it }
                snapshot(libraryRevision)
            },
            definitions = { definitions },
            librarySnapshot = { installedCandidates },
            readerFactory = {
                MemoizingBeatmapFileIdentityReader(BeatmapFileIdentityReader { null })
            },
            revision = { revision },
        )
    }

    @Test
    fun `Repeated manager reads do not schedule scans`() {
        definitions = listOf(definition("A"), definition("B"))

        core.requestAll()
        assertEquals(0, scanCalls.size)

        // A read before warming completes is a checking placeholder.
        val checking = core.get("hash-a")
        assertTrue(checking.isChecking)
        assertNull(checking.snapshot)

        scheduler.runAll()
        assertEquals(2, scanCalls.size)

        repeat(20) {
            core.get("hash-a")
            core.get("hash-b")
            core.peek("hash-a")
        }
        scheduler.runAll()

        assertEquals(2, scanCalls.size)
        assertEquals(0, scheduler.size)
        assertFalse(core.get("hash-a").isChecking)
        assertNotNull(core.get("hash-a").snapshot)
    }

    @Test
    fun `Course edit evicts the old hash and warms the new definition`() {
        definitions = listOf(definition("A"))
        core.requestAll()
        scheduler.runAll()
        assertNotNull(core.peek("hash-a"))

        definitions = listOf(definition("B"))
        core.onCourseChanged("hash-a", "hash-b")

        val old = core.get("hash-a")
        assertTrue(old.isChecking)
        assertNull(old.snapshot)
        assertNull(core.peek("hash-a"))
        assertTrue(core.get("hash-b").isChecking)

        scheduler.runAll()

        assertEquals(1, scanCalls.count { it.definition.courseName == "B" })
        assertNotNull(core.get("hash-b").snapshot)
        assertNull(core.peek("hash-a"))
    }

    @Test
    fun `Library event invalidates all cached snapshots and warms one generation`() {
        definitions = listOf(definition("A"), definition("B"))
        core.requestAll()
        scheduler.runAll()
        val firstReaders = scanCalls.map { it.reader }

        revision = 1L
        core.requestAll()

        assertTrue(core.get("hash-a").isChecking)
        assertNull(core.get("hash-a").snapshot)

        scheduler.runAll()

        assertEquals(4, scanCalls.size)
        val lastTwo = scanCalls.takeLast(2)
        assertSame(lastTwo[0].candidates, lastTwo[1].candidates)
        assertSame(lastTwo[0].reader, lastTwo[1].reader)
        assertTrue(lastTwo.none { it.reader in firstReaders })
        assertTrue(lastTwo.all { it.revision == 1L })
        assertEquals(1L, core.get("hash-a").snapshot!!.libraryRevision)
        assertEquals(1L, core.get("hash-b").snapshot!!.libraryRevision)
    }

    @Test
    fun `Burst events coalesce and scan each definition once per generation`() {
        definitions = listOf(definition("A"), definition("B"))

        repeat(5) { core.requestAll() }
        repeat(3) { core.requestCourse("hash-a") }

        assertEquals(1, scheduler.size)
        scheduler.runAll()

        assertEquals(2, scanCalls.size)
        assertEquals(0, scheduler.size)

        repeat(4) { core.requestCourse("hash-a") }
        assertEquals(1, scheduler.size)
        scheduler.runAll()

        assertEquals(3, scanCalls.size)
        assertEquals(2, scanCalls.count { it.definition.courseName == "A" })
    }

    @Test
    fun `A late scan from an obsolete library revision cannot publish a snapshot`() {
        definitions = listOf(definition("A"))
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)

        scanBehavior = { callIndex, _ ->
            when (callIndex) {
                0 -> {
                    firstStarted.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }

                1 -> {
                    secondStarted.countDown()
                    releaseSecond.await(5, TimeUnit.SECONDS)
                }
            }
        }

        core.requestAll()
        val drain = Thread(scheduler.take(), "cache-test-drain")
        drain.start()

        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        revision = 1L
        core.requestAll()
        releaseFirst.countDown()

        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
        val during = core.get("hash-a")
        assertTrue(during.isChecking)
        assertNull(during.snapshot)

        releaseSecond.countDown()
        drain.join(5_000)
        assertFalse(drain.isAlive)

        val final = core.get("hash-a")
        assertFalse(final.isChecking)
        assertEquals(1L, final.snapshot!!.libraryRevision)
    }

    @Test
    fun `A failed scan is exposed until the next event retries it`() {
        definitions = listOf(definition("A"))
        scanFailure = IllegalStateException("parser exploded")

        core.requestAll()
        scheduler.runAll()

        val failed = core.get("hash-a")
        assertFalse(failed.isChecking)
        assertNull(failed.snapshot)
        assertTrue(failed.failure!!.contains("parser exploded"))

        scanFailure = null
        core.requestCourse("hash-a")
        assertTrue(core.get("hash-a").isChecking)
        assertNull(core.get("hash-a").failure)

        scheduler.runAll()
        assertNotNull(core.get("hash-a").snapshot)
    }

    @Test
    fun `Completion notifications are delivered for the affected hash`() {
        definitions = listOf(definition("A"))
        val events = ArrayList<String?>()
        core.addListener(CourseAvailabilityListener { events += it })

        core.requestCourse("hash-a")
        scheduler.runAll()

        assertTrue(events.contains("hash-a"))
        assertFalse(core.get("hash-a").isChecking)
    }

    @Test
    fun `Memoizing reader caches unreadable files for one generation`() {
        val file = File("unreadable.osu")
        var reads = 0
        val reader = MemoizingBeatmapFileIdentityReader(
            BeatmapFileIdentityReader {
                reads++
                null
            }
        )

        assertNull(reader.read(file))
        assertNull(reader.read(file))
        assertNull(reader.read(file))

        assertEquals(1, reads)
    }

    private fun definition(name: String) = CourseAvailabilityDefinition(
        courseHash = "hash-" + name.lowercase(),
        definition = CourseDefinition(courseName = name, author = "Tester"),
    )

    private fun snapshot(revision: Long) = CourseAvailabilitySnapshot(
        entries = emptyList(),
        allReady = false,
        issues = emptyList(),
        libraryRevision = revision,
    )
}

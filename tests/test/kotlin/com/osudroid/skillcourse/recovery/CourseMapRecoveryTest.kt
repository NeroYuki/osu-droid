package com.osudroid.skillcourse.recovery

import com.osudroid.beatmaplisting.BeatmapDownloadResult
import com.osudroid.beatmaplisting.BeatmapDownloadStart
import com.osudroid.beatmaplisting.BeatmapDownloadStatus
import com.osudroid.beatmaplisting.BeatmapDownloadToken
import com.osudroid.beatmaplisting.BeatmapImportFailure
import com.osudroid.beatmaplisting.BeatmapImportResult
import com.osudroid.beatmaplisting.BeatmapSetModel
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.nsu.ccfit.zuev.osu.RankedStatus

class CourseMapRecoveryTest {

    @After
    fun tearDown() {
        CourseMapRecovery.clearActiveForTesting()
    }

    @Test
    fun `Shared parent set is resolved and downloaded once`() {
        val maps = listOf(courseMap(HASH_A, 11L), courseMap(HASH_B, 12L))
        val definition = definition(maps)
        var ready = false
        val downloads = ArrayList<List<CourseMap>>()
        var completed: CourseRecoveryResult? = null

        val batch = batch(
            definition = definition,
            scan = { snapshot(maps, if (ready) READY else MISSING) },
            resolveSet = { setModel(100L) },
            startDownload = { _, _, expected, callback, _ ->
                downloads += expected
                ready = true
                callback(downloadResult(successImport()))
                BeatmapDownloadStart.STARTED
            },
            onComplete = { completed = it },
        )

        batch.run()

        assertEquals(1, downloads.size)
        assertEquals(2, downloads.single().size)
        assertTrue(completed!!.allReady)
        assertFalse(completed!!.canceled)
    }

    @Test
    fun `Only one active batch can run`() {
        val maps = listOf(courseMap(HASH_A, 11L))
        val definition = definition(maps)
        val scanStarted = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)

        val batch = batch(
            definition = definition,
            scan = {
                scanStarted.countDown()
                releaseScan.await(2, TimeUnit.SECONDS)
                snapshot(maps, READY)
            },
            resolveSet = { setModel(100L) },
            startDownload = { _, _, _, _, _ -> BeatmapDownloadStart.BUSY },
            onComplete = {},
        )

        val first = CourseMapRecovery.startForTesting(batch)
        assertTrue(scanStarted.await(2, TimeUnit.SECONDS))
        assertTrue(CourseMapRecovery.hasActiveBatch)

        val second = CourseMapRecovery.startForTesting(batch)
        assertFalse(second.isActive)

        releaseScan.countDown()
        val deadline = System.currentTimeMillis() + 3000
        while (CourseMapRecovery.hasActiveBatch && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertFalse(CourseMapRecovery.hasActiveBatch)
        assertFalse(first.isActive)
    }

    @Test
    fun `Busy downloader is waited for instead of dropping the request`() {
        val maps = listOf(courseMap(HASH_A, 11L))
        val definition = definition(maps)
        val attempts = AtomicInteger(0)
        val sleeps = AtomicInteger(0)
        var ready = false

        val batch = batch(
            definition = definition,
            scan = { snapshot(maps, if (ready) READY else MISSING) },
            resolveSet = { setModel(100L) },
            startDownload = { _, _, _, callback, _ ->
                if (attempts.incrementAndGet() < 3) {
                    BeatmapDownloadStart.BUSY
                } else {
                    ready = true
                    callback(downloadResult(successImport()))
                    BeatmapDownloadStart.STARTED
                }
            },
            sleep = { sleeps.incrementAndGet() },
            onComplete = {},
        )

        batch.run()

        assertEquals(3, attempts.get())
        assertEquals(2, sleeps.get())
    }

    @Test
    fun `Cancellation stops the transfer and reports canceled without restoring play`() {
        val maps = listOf(courseMap(HASH_A, 11L))
        val definition = definition(maps)
        val started = CountDownLatch(1)
        val cancelCalls = AtomicInteger(0)
        val issuedToken = AtomicReference<BeatmapDownloadToken?>(null)
        var completed: CourseRecoveryResult? = null

        val batch = batch(
            definition = definition,
            scan = { snapshot(maps, MISSING) },
            resolveSet = { setModel(100L) },
            startDownload = { _, _, _, callback, token ->
                issuedToken.set(token)
                started.countDown()
                // Simulate a transfer that only completes when canceled.
                Thread {
                    while (cancelCalls.get() == 0) {
                        Thread.sleep(5)
                    }
                    callback(
                        BeatmapDownloadResult(
                            status = BeatmapDownloadStatus.CANCELED,
                            suggestedFilename = "set",
                            importResult = null,
                            error = null,
                        )
                    )
                }.start()
                BeatmapDownloadStart.STARTED
            },
            cancelDownload = { token ->
                assertSame(issuedToken.get(), token)
                cancelCalls.incrementAndGet()
            },
            onComplete = { completed = it },
        )

        val thread = Thread { batch.run() }
        thread.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        batch.handle.cancel()
        thread.join(3000)

        assertFalse(thread.isAlive)
        assertTrue(cancelCalls.get() >= 1)
        assertTrue(completed!!.canceled)
        assertEquals(1, completed!!.unresolved.size)
    }

    @Test
    fun `Canceling while waiting for a busy downloader leaves the other request alone`() {
        val maps = listOf(courseMap(HASH_A, 11L))
        val definition = definition(maps)
        val sleepStarted = CountDownLatch(1)
        val cancelCalls = AtomicInteger(0)
        var completed: CourseRecoveryResult? = null

        val batch = batch(
            definition = definition,
            scan = { snapshot(maps, MISSING) },
            resolveSet = { setModel(100L) },
            startDownload = { _, _, _, _, _ -> BeatmapDownloadStart.BUSY },
            cancelDownload = { cancelCalls.incrementAndGet() },
            sleep = {
                sleepStarted.countDown()
                Thread.sleep(10)
            },
            onComplete = { completed = it },
        )

        val thread = Thread { batch.run() }
        thread.start()
        assertTrue(sleepStarted.await(2, TimeUnit.SECONDS))
        batch.handle.cancel()
        thread.join(3000)

        assertFalse(thread.isAlive)
        // No reservation was owned while BUSY, so the external download must not be canceled.
        assertEquals(0, cancelCalls.get())
        assertTrue(completed!!.canceled)
        assertFalse(completed!!.allReady)
    }

    @Test
    fun `Cancellation during import waits for the delayed callback`() {
        val maps = listOf(courseMap(HASH_A, 11L))
        val definition = definition(maps)
        val started = CountDownLatch(1)
        val cancelSeen = CountDownLatch(1)
        var ready = false
        var completed: CourseRecoveryResult? = null
        val completedLatch = CountDownLatch(1)

        val batch = batch(
            definition = definition,
            scan = { snapshot(maps, if (ready) READY else MISSING) },
            resolveSet = { setModel(100L) },
            startDownload = { _, _, _, callback, _ ->
                started.countDown()
                Thread {
                    // Simulate an importer that only reports after a commit/rollback.
                    cancelSeen.await(2, TimeUnit.SECONDS)
                    Thread.sleep(400)
                    ready = true
                    callback(downloadResult(successImport()))
                }.start()
                BeatmapDownloadStart.STARTED
            },
            cancelDownload = { cancelSeen.countDown() },
            onComplete = {
                completed = it
                completedLatch.countDown()
            },
        )

        val thread = Thread { batch.run() }
        thread.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        batch.handle.cancel()
        assertTrue(cancelSeen.await(2, TimeUnit.SECONDS))

        // The import callback is deliberately delayed; the batch must not return early.
        assertFalse(completedLatch.await(100, TimeUnit.MILLISECONDS))
        thread.join(3000)

        assertFalse(thread.isAlive)
        assertTrue(completed!!.canceled)
        assertTrue(completed!!.allReady)
        assertEquals(0, completed!!.unresolved.size)
    }

    @Test
    fun `Initial and final scan failures never report ready`() {
        val maps = listOf(courseMap(HASH_A, 11L))
        val definition = definition(maps)
        var scans = AtomicInteger(0)
        var completed: CourseRecoveryResult? = null

        val batch = batch(
            definition = definition,
            scan = {
                scans.incrementAndGet()
                throw IllegalStateException("scan failed")
            },
            resolveSet = { setModel(100L) },
            startDownload = { _, _, _, _, _ -> BeatmapDownloadStart.BUSY },
            onComplete = { completed = it },
        )

        batch.run()

        assertEquals(2, scans.get())
        assertFalse(completed!!.allReady)
        assertFalse(completed!!.finalScanSucceeded)
        assertFalse(completed!!.finalScanComplete)
        assertTrue(completed!!.unresolved.isEmpty())
        assertTrue(completed!!.errors.any { it.contains("Final course rescan failed") })
    }

    @Test
    fun `Failure in one set continues unrelated jobs`() {
        val maps = listOf(courseMap(HASH_A, 11L), courseMap(HASH_B, 12L))
        val definition = definition(maps)
        val resolvers = AtomicInteger(0)
        val downloads = ArrayList<String>()
        var completed: CourseRecoveryResult? = null

        val batch = batch(
            definition = definition,
            scan = { snapshot(maps, MISSING) },
            resolveSet = { id ->
                if (id == 11L) setModel(100L) else setModel(200L)
            },
            startDownload = { _, filename, _, callback, _ ->
                downloads += filename
                if (filename == "first") {
                    callback(
                        downloadResult(
                            BeatmapImportResult(
                                success = false,
                                archiveName = "first.osz",
                                affectedDirectories = emptyList(),
                                importedBeatmaps = emptyList(),
                                identities = emptyList(),
                                expectedMaps = emptyList(),
                                errors = listOf("Required version unavailable."),
                                failure = BeatmapImportFailure.EXPECTED_REVISION_UNAVAILABLE,
                            )
                        )
                    )
                } else {
                    callback(downloadResult(successImport()))
                }
                BeatmapDownloadStart.STARTED
            },
            setFilename = { set -> if (set.id == 100L) "first" else "second" },
            onComplete = { completed = it },
        )

        batch.run()

        assertEquals(2, downloads.size)
        assertTrue(completed!!.errors.any { it.contains("Required version unavailable") })
        assertFalse(completed!!.allReady)
    }

    @Test
    fun `Missing online ID reports manual guidance without a download`() {
        val maps = listOf(courseMap(HASH_A, null))
        val definition = definition(maps)
        val downloads = AtomicInteger(0)
        var completed: CourseRecoveryResult? = null

        val batch = batch(
            definition = definition,
            scan = { snapshot(maps, MISSING) },
            resolveSet = { error("should not resolve") },
            startDownload = { _, _, _, _, _ ->
                downloads.incrementAndGet()
                BeatmapDownloadStart.STARTED
            },
            onComplete = { completed = it },
        )

        batch.run()

        assertEquals(0, downloads.get())
        assertTrue(completed!!.errors.single().contains("Import the required revision manually"))
        assertEquals(1, completed!!.unresolved.size)
    }

    @Test
    fun `Wrong revision failure does not restore availability`() {
        val maps = listOf(courseMap(HASH_A, 11L))
        val definition = definition(maps)
        var completed: CourseRecoveryResult? = null

        val batch = batch(
            definition = definition,
            scan = { snapshot(maps, OUTDATED) },
            resolveSet = { setModel(100L) },
            startDownload = { _, _, _, callback, _ ->
                callback(
                    downloadResult(
                        BeatmapImportResult(
                            success = false,
                            archiveName = "wrong.osz",
                            affectedDirectories = emptyList(),
                            importedBeatmaps = emptyList(),
                            identities = emptyList(),
                            expectedMaps = listOf(
                                com.osudroid.beatmaplisting.ExpectedMapImportResult(
                                    mapHash = HASH_A,
                                    matched = false,
                                    installedDirectory = null,
                                    message = "Required version unavailable.",
                                )
                            ),
                            errors = listOf("Required version unavailable."),
                            failure = BeatmapImportFailure.EXPECTED_REVISION_UNAVAILABLE,
                        )
                    )
                )
                BeatmapDownloadStart.STARTED
            },
            onComplete = { completed = it },
        )

        batch.run()

        assertEquals(CourseMapStatus.OUTDATED, completed!!.unresolved.single().status)
        assertFalse(completed!!.allReady)
    }

    private fun batch(
        definition: CourseDefinition,
        scan: () -> CourseAvailabilitySnapshot,
        resolveSet: (Long) -> BeatmapSetModel?,
        startDownload: (
            String,
            String,
            List<CourseMap>,
            (BeatmapDownloadResult) -> Unit,
            BeatmapDownloadToken,
        ) -> BeatmapDownloadStart,
        onComplete: (CourseRecoveryResult) -> Unit,
        setFilename: (BeatmapSetModel) -> String = { "set" },
        cancelDownload: (BeatmapDownloadToken) -> Unit = {},
        sleep: (Long) -> Unit = {},
    ) = CourseRecoveryBatch(
        definition = definition,
        scan = scan,
        resolveSet = resolveSet,
        setUrl = { "https://example.invalid/${it.id}" },
        setFilename = setFilename,
        startDownload = startDownload,
        cancelDownload = cancelDownload,
        dispatch = { it() },
        sleep = sleep,
        onProgress = {},
        onComplete = onComplete,
    )

    private fun snapshot(maps: List<CourseMap>, status: CourseMapStatus) =
        snapshot(maps, maps.map { status })

    private fun snapshot(maps: List<CourseMap>, statuses: List<CourseMapStatus>): CourseAvailabilitySnapshot {
        val entries = maps.mapIndexed { index, map ->
            CourseMapAvailability(
                originalIndex = index,
                courseMap = map,
                status = statuses[index],
                candidates = emptyList(),
                resolvedBeatmap = null,
                reason = null,
            )
        }
        val issues = entries.filter { it.status != CourseMapStatus.READY }.map {
            CourseAvailabilityIssue(
                originalIndex = it.originalIndex,
                courseMap = it.courseMap,
                status = it.status,
                reason = it.reason,
                candidates = it.candidates,
            )
        }
        return CourseAvailabilitySnapshot(
            entries = entries,
            allReady = entries.isNotEmpty() && entries.all { it.status == CourseMapStatus.READY },
            issues = issues,
            libraryRevision = 1L,
        )
    }

    private fun definition(maps: List<CourseMap>) =
        CourseDefinition(courseName = "Test", author = "Tester", maps = maps)

    private fun courseMap(hash: String, beatmapId: Long?) = CourseMap(
        mapHash = hash,
        objectCounts = listOf(1, 0, 0),
        artist = "Artist",
        artistUnicode = "Artist",
        title = "Title",
        titleUnicode = "Title",
        mapper = "Mapper",
        difficulty = "Difficulty",
        beatmapid = beatmapId,
    )

    private fun setModel(id: Long) = BeatmapSetModel(
        id = id,
        title = "Title",
        titleUnicode = "Title",
        artist = "Artist",
        artistUnicode = "Artist",
        status = RankedStatus.ranked,
        creator = "Mapper",
        thumbnail = null,
        beatmaps = emptyList(),
        hasVideo = false,
    )

    private fun successImport() = BeatmapImportResult(
        success = true,
        archiveName = "set.osz",
        affectedDirectories = listOf("set"),
        importedBeatmaps = emptyList(),
        identities = emptyList(),
        expectedMaps = emptyList(),
        errors = emptyList(),
        failure = BeatmapImportFailure.NONE,
    )

    private fun downloadResult(importResult: BeatmapImportResult) = BeatmapDownloadResult(
        status = if (importResult.success) {
            BeatmapDownloadStatus.COMPLETED
        } else {
            BeatmapDownloadStatus.IMPORT_FAILED
        },
        suggestedFilename = "set",
        importResult = importResult,
        error = importResult.errors.firstOrNull(),
    )

    private companion object {
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val READY = CourseMapStatus.READY
        val MISSING = CourseMapStatus.MISSING
        val OUTDATED = CourseMapStatus.OUTDATED
    }
}

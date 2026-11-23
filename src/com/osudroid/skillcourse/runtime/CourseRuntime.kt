package com.osudroid.skillcourse.runtime

import android.util.Log
import com.osudroid.data.BeatmapInfo
import com.osudroid.data.SkillCourseEntity
import com.osudroid.multiplayer.Multiplayer
import com.osudroid.skillcourse.CourseAccumulator
import com.osudroid.skillcourse.CourseCodec
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseJudgment
import com.osudroid.skillcourse.CourseModPolicy
import com.osudroid.skillcourse.CourseRepository
import com.osudroid.skillcourse.CourseLibraryGate
import com.osudroid.skillcourse.CourseLibraryGuard
import com.osudroid.skillcourse.recovery.CourseAvailabilityScanner
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.deserializeMods
import com.osudroid.utils.async
import com.osudroid.utils.delayed
import com.osudroid.utils.updateThread
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.ToastLogger
import ru.nsu.ccfit.zuev.osu.game.GameScene
import ru.nsu.ccfit.zuev.osu.scoring.StatisticV2
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Owns the full attempt; individual GameScenes retain their ordinary one-map statistics. */
object CourseRuntime {
    private val json = Json { encodeDefaults = true }
    @Volatile private var run: Run? = null
    @Volatile private var preflighting = false
    private val preflightGeneration = AtomicLong()
    @Volatile private var preflightLease: CourseLibraryGate.Lease? = null

    /** Installed by the course result presenter, always invoked on the engine update thread. */
    var onResult: ((CourseResult) -> Unit)? = null

    private class Run(
        val entity: SkillCourseEntity,
        val definition: CourseDefinition,
        val beatmaps: List<BeatmapInfo>,
        val mods: List<ModHashMap>,
        val order: List<Int>,
        val additionalMods: ModHashMap,
        val speeds: Map<Int, Float>,
        val additionalModsByIndex: Map<Int, ModHashMap>,
        val libraryLease: CourseLibraryGate.Lease
    ) {
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val hash = CourseCodec.hash(definition)
        val directory = File(Config.getCorePath(), "SkillCourses/Attempts/$id")
        val accumulator = CourseAccumulator(definition)
        val clock = CourseClock()
        val components = mutableListOf<CourseComponentResult>()
        val pendingEvents = mutableListOf<Pair<Long, CourseJudgment>>()
        val events = mutableListOf<CourseEventRecord>()
        val finalObjects = hashSetOf<Int>()
        var index = 0
        var started = false
        var saving = false
        var rawExAtStart = 0L
        var hpAtStart = definition.gaugeSettings.startingHp
        var lastJudgmentNanos = 0L
        var pendingOutcome: String? = null
        var reason: String? = null
        var playerName = Config.getOnlineUsername().ifBlank { "Player" }
        var lastBackNanos = 0L
        var retryRequested = false

        fun snapshot(outcome: String) = CourseResult(
            id, entity.id, hash, definition, playerName, createdAt, outcome, reason,
            order = order.toList(), components = components.toList(),
            rawExTotal = accumulator.rawExTotal, activeNanos = clock.totalNanos,
            replayDirectory = directory.absolutePath,
            additionalModsJson = additionalMods.serializeMods(includeIrrelevantMods = true), speeds = speeds,
            additionalModsByIndexJson = additionalModsByIndex.mapValues { it.value.serializeMods(includeIrrelevantMods = true) }
        )
    }

    @JvmStatic fun isRunning() = run != null
    @JvmStatic fun isBusy() = isRunning() || preflighting
    @JvmStatic fun isPreflighting() = preflighting

    /** Retry the exact frozen choices, including history retained after delete/reimport. */
    fun retry(result: CourseResult) {
        val entity = CourseRepository.getEntity(result.courseId) ?: CourseRepository.getEntityByHash(result.courseHash)
        if (entity == null) {
            ToastLogger.showText("This course was deleted. Import it again to retry.", true)
            return
        }
        try {
            start(entity, result.definition, deserializeMods(result.additionalModsJson), result.speeds,
                result.additionalModsByIndexJson.mapValues { deserializeMods(it.value) })
        } catch (e: Exception) {
            ToastLogger.showText("Unable to restore the course choices: ${e.message}", true)
        }
    }

    @JvmStatic @Synchronized fun cancelPreflight() {
        if (!preflighting) return
        preflightGeneration.incrementAndGet()
        preflightLease?.close()
        preflightLease = null
        preflighting = false
        CourseClient.flushDeferredImports()
    }
    @JvmStatic fun isGlobalGauge() = run?.definition?.lifeGauge == "global"
    @JvmStatic fun ignoreNativeHp() = isGlobalGauge()
    @JvmStatic fun allowsNativeFailure() = run?.definition?.lifeGauge?.let { it == "standard" } ?: true
    @JvmStatic fun isPauseAllowed() = run?.definition?.allowPause != false
    @JvmStatic fun shouldFinishMap() = run?.let { it.started && it.pendingOutcome != null && !it.saving } == true
    @JvmStatic fun isCourseLoading() = run?.let { !it.started } == true

    @Synchronized fun start(
        entity: SkillCourseEntity,
        definition: CourseDefinition,
        additionalMods: ModHashMap,
        speeds: Map<Int, Float>,
        additionalModsByIndex: Map<Int, ModHashMap> = emptyMap()
    ) {
        if (run != null || preflighting) return
        if (!CourseClient.isReady()) {
            ToastLogger.showText("Preparing local course history. Try again in a moment.", false)
            return
        }
        if (Multiplayer.isMultiplayer) {
            ToastLogger.showText("Leave multiplayer before starting a course.", true)
            return
        }
        if (com.osudroid.beatmaplisting.BeatmapDownloader.isBusy || com.osudroid.skillcourse.recovery.CourseMapRecovery.hasActiveBatch) {
            ToastLogger.showText("Wait for beatmap downloads to finish before starting a course.", true)
            return
        }
        val extraSnapshot: ModHashMap
        val mapExtrasSnapshot: Map<Int, ModHashMap>
        val speedSnapshot: Map<Int, Float>
        try {
            extraSnapshot = additionalMods.deepCopy()
            mapExtrasSnapshot = additionalModsByIndex.mapValues { it.value.deepCopy() }
            speedSnapshot = speeds.toMap()
        } catch (e: Exception) {
            ToastLogger.showText("Unable to freeze the course choices: ${e.message}", true)
            return
        }
        val lease = CourseLibraryGuard.tryCourse()
        if (lease == null) {
            ToastLogger.showText("Wait for the beatmap import to finish before starting a course.", true)
            return
        }
        preflightLease = lease
        preflighting = true
        CourseClient.holdDeferredImports()
        val token = preflightGeneration.incrementAndGet()
        async {
            try {
                CourseCodec.validate(definition)
                val availability = CourseAvailabilityScanner.scan(definition)
                check(availability.allReady) { "Download the missing or outdated course maps first." }
                val maps = availability.entries.map { checkNotNull(it.resolvedBeatmap) }
                val mods = definition.maps.mapIndexed { index, map ->
                    CourseModPolicy.effectiveMods(definition, map, mapExtrasSnapshot[index] ?: extraSnapshot, speedSnapshot[index])
                }
                val order = definition.maps.indices.toList().let { if (definition.randomOrder) it.shuffled() else it }
                check(mapExtrasSnapshot.keys.all { it in definition.maps.indices }) { "Invalid course map preference index." }
                check(speedSnapshot.keys.all { it in definition.maps.indices }) { "Invalid course speed index." }
                val next = Run(entity, definition, maps, mods, order, extraSnapshot, speedSnapshot, mapExtrasSnapshot, lease)
                check(next.directory.mkdirs() || next.directory.isDirectory) { "Cannot create the course replay directory." }
                if (token != preflightGeneration.get()) return@async
                persist(next.snapshot("in_progress"))
                updateThread {
                    synchronized(this@CourseRuntime) {
                        if (token != preflightGeneration.get()) {
                            async { persist(next.snapshot("interrupted").copy(reason = "Course preflight was canceled.")) }
                            return@updateThread
                        }
                        run = next
                        preflighting = false
                        preflightLease = null
                        GlobalManager.getInstance().songMenu.stopMusic()
                        startMap(next)
                    }
                }
            } catch (e: Exception) {
                lease.close()
                if (token != preflightGeneration.get()) return@async
                preflightLease = null
                preflighting = false
                CourseClient.flushDeferredImports()
                Log.e("SkillCourse", "Course preflight failed", e)
                ToastLogger.showText(e.message ?: "Unable to start the course.", true)
            }
        }
    }

    private fun startMap(current: Run) {
        if (run !== current || current.pendingOutcome != null) return
        current.started = false
        current.saving = false
        current.events.clear()
        current.pendingEvents.clear()
        current.finalObjects.clear()
        current.lastJudgmentNanos = 0L
        current.rawExAtStart = current.accumulator.rawExTotal
        current.hpAtStart = current.accumulator.globalHp
        val original = current.order[current.index]
        val global = GlobalManager.getInstance()
        global.selectedBeatmap = current.beatmaps[original]
        global.gameScene.startGame(current.beatmaps[original], null, current.mods[original].deepCopy())
    }

    @JvmStatic fun onMapPrepared(stat: StatisticV2) {
        val current = run ?: return
        stat.canFail = current.definition.lifeGauge == "standard"
        if (isGlobalGauge()) stat.setCourseHp(current.accumulator.globalHp.toFloat() / current.definition.gaugeSettings.totalHp)
    }

    @JvmStatic fun onMapStarted(game: GameScene) {
        val current = run ?: return
        if (current.started || current.saving) return
        if (current.definition.autoSkip) game.skip(true)
        current.started = true
        current.playerName = game.stat.playerName
        current.clock.startComponent()
    }

    @JvmStatic fun recordFinalJudgment(objectId: Int, score: Int, mapTimeMs: Long) {
        val current = run ?: return
        if (!current.started || current.saving || current.pendingOutcome != null || !current.finalObjects.add(objectId)) return
        val judgment = when (score) {
            300 -> CourseJudgment.HIT300
            100 -> CourseJudgment.HIT100
            50 -> CourseJudgment.HIT50
            else -> CourseJudgment.HIT0
        }
        record(current, objectId, judgment, mapTimeMs)
        val expected = current.definition.maps[current.order[current.index]].objectCounts.sum()
        if (current.finalObjects.size == expected) {
            current.lastJudgmentNanos = System.nanoTime()
            current.clock.finishComponent()
        }
    }

    @JvmStatic fun recordSliderBreak(objectId: Int, mapTimeMs: Long) {
        run?.let { record(it, objectId, CourseJudgment.SLIDERBREAK, mapTimeMs) }
    }

    @JvmStatic fun recordSliderEndMiss(objectId: Int, mapTimeMs: Long) {
        run?.let { record(it, objectId, CourseJudgment.SLIDEREND, mapTimeMs) }
    }

    private fun record(current: Run, objectId: Int, judgment: CourseJudgment, mapTimeMs: Long) {
        if (!current.started || current.saving || current.pendingOutcome != null) return
        current.events += CourseEventRecord(current.events.size, objectId, mapTimeMs, judgment.name)
        current.pendingEvents += mapTimeMs to judgment
    }

    @JvmStatic fun flushEvents(stat: StatisticV2) {
        val current = run ?: return
        current.pendingEvents.groupBy({ it.first }, { it.second }).toSortedMap().values.forEach {
            current.accumulator.onJudgmentBatch(it)
        }
        current.pendingEvents.clear()
        if (isGlobalGauge()) stat.setCourseHp(current.accumulator.globalHp.toFloat() / current.definition.gaugeSettings.totalHp)
        if (current.accumulator.failed) requestEnd("failed", "The global life gauge reached zero.")
    }

    @JvmStatic fun requestEnd(outcome: String, reason: String?) {
        val current = run ?: return
        if (current.pendingOutcome != null) return
        current.pendingOutcome = outcome
        current.reason = reason
        current.clock.finishComponent()
        current.lastJudgmentNanos = System.nanoTime()
    }

    /** Pause-menu retry ends the old attempt and starts a fresh whole course after saving it. */
    @JvmStatic fun requestRetry() {
        val current = run ?: return
        if (current.pendingOutcome != null) return
        current.retryRequested = true
        requestEnd("aborted", "The player restarted the course.")
    }

    @JvmStatic fun onPause() { run?.clock?.pause() }
    @JvmStatic fun onResume() { run?.clock?.resume() }

    /** Called on the update thread even if the loader or a transition owns the scene. */
    @JvmStatic fun onFocusLost() {
        if (preflighting) cancelPreflight()
        val current = run ?: return
        val game = GlobalManager.getInstance().gameScene
        if (!current.started) {
            requestEnd("interrupted", "The app lost focus while loading the course.")
            game.cancelLoading()
            if (!current.saving) saveAndAdvance(current, current.pendingOutcome ?: "interrupted")
        } else if (!current.definition.allowPause) {
            requestEnd("interrupted", "The app lost focus during a no-pause course.")
            game.quit()
        } else if (!game.isPaused) game.pause()
    }

    /** Back twice exits a no-pause course, without modifying multiplayer state. */
    @JvmStatic fun confirmNoPauseExit(): Boolean {
        val current = run ?: return false
        val now = System.nanoTime()
        if (current.lastBackNanos != 0L && now - current.lastBackNanos <= 1_500_000_000L) {
            requestEnd("aborted", "The player left the course.")
            return true
        }
        current.lastBackNanos = now
        ToastLogger.showText("Tap again to leave the course.", false)
        return false
    }

    @JvmStatic fun onMapFinished(stat: StatisticV2, beatmap: BeatmapInfo, replayPath: String?) {
        val current = run ?: return
        if (current.saving) return
        flushEvents(stat)
        current.saving = true
        current.started = false
        current.clock.finishComponent()
        if (current.lastJudgmentNanos == 0L) current.lastJudgmentNanos = System.nanoTime()
        val original = current.order[current.index]
        val completed = stat.notesHit == current.definition.maps[original].objectCounts.sum()
        val component = CourseComponentResult(
            original, current.index, beatmap.md5, stat.totalScoreWithMultiplier, stat.scoreMaxCombo,
            stat.hit300, stat.hit100, stat.hit50, stat.misses,
            stat.sliderHeadHits, stat.sliderTickHits, stat.sliderRepeatHits, stat.sliderEndHits,
            stat.mod.serializeMods(includeIrrelevantMods = true), current.accumulator.rawExTotal - current.rawExAtStart,
            current.hpAtStart.takeIf { isGlobalGauge() }, current.accumulator.globalHp.takeIf { isGlobalGauge() },
            current.clock.currentComponentNanos, completed, null, null, null,
            hit300k = stat.hit300k, hit100k = stat.hit100k
        )
        val events = current.events.toList()
        async {
            try {
                val source = replayPath?.let(::File)
                check(source != null && source.isFile && source.length() > 0) { "The component replay could not be saved." }
                val replay = File(current.directory, "${current.index}.odr")
                val digest = atomicCopyReplay(source, replay)
                val evidence = File(current.directory, "${current.index}.events.json")
                atomicWrite(evidence, json.encodeToString(events).toByteArray(Charsets.UTF_8))
                val saved = component.copy(replayFilename = replay.name, replaySha256 = digest, eventsFilename = evidence.name)
                updateThread {
                    if (run !== current) return@updateThread
                    current.components += saved
                    if (!completed && current.pendingOutcome == null) requestEnd("interrupted", "The map ended before all objects were judged.")
                    val outcome = current.pendingOutcome ?: if (current.index == current.order.lastIndex) "completed" else "in_progress"
                    saveAndAdvance(current, outcome)
                }
            } catch (e: Exception) {
                Log.e("SkillCourse", "Component evidence save failed", e)
                updateThread {
                    if (run !== current) return@updateThread
                    current.components += component
                    requestEnd("interrupted", e.message ?: "Component evidence could not be saved.")
                    saveAndAdvance(current, "interrupted")
                }
            }
        }
    }

    private fun saveAndAdvance(current: Run, outcome: String) {
        // Loading cancellation and focus loss can arrive in the same frame. Reserve the
        // journal write before dispatching I/O, just as onMapFinished does for replay saves.
        current.saving = true
        val snapshot = current.snapshot(outcome)
        async {
            try {
                persist(snapshot)
                updateThread {
                    if (run !== current) return@updateThread
                    if (outcome == "in_progress" && current.pendingOutcome != null) {
                        saveAndAdvance(current, current.pendingOutcome!!)
                    } else if (outcome != "in_progress") {
                        finish(current, snapshot)
                    } else {
                        current.index++
                        current.saving = false
                        val remainingMs = (current.definition.mapTransitionMs - (System.nanoTime() - current.lastJudgmentNanos) / 1_000_000L).coerceAtLeast(0)
                        delayed(remainingMs, Runnable { updateThread { startMap(current) } })
                    }
                }
            } catch (e: Exception) {
                Log.e("SkillCourse", "Attempt save failed", e)
                updateThread {
                    current.retryRequested = false
                    val unsaved = if (outcome == "in_progress") current.snapshot("interrupted").copy(
                        reason = "The course stopped because its result could not be saved."
                    ) else snapshot
                    finish(current, unsaved.copy(storageError = e.message ?: "Result storage failed."))
                }
            }
        }
    }

    @JvmStatic fun onLoadingCanceled() {
        val current = run ?: return
        if (current.started || current.saving) return
        GlobalManager.getInstance().gameScene.cancelLoading()
        requestEnd("aborted", "Course loading was canceled.")
        saveAndAdvance(current, current.pendingOutcome ?: "aborted")
    }

    private fun finish(current: Run, result: CourseResult) {
        if (run !== current) return
        run = null
        current.libraryLease.close()
        if (current.retryRequested) {
            // Keep the saved old attempt visible if the fresh preflight cannot start.
            onResult?.invoke(result)
            start(current.entity, current.definition, current.additionalMods, current.speeds, current.additionalModsByIndex)
            return
        }
        onResult?.invoke(result) ?: run {
            GlobalManager.getInstance().engine.scene = GlobalManager.getInstance().songMenu.scene
            ToastLogger.showText("Course ${result.outcome}. Result saved locally.", true)
        }
    }

    private fun persist(result: CourseResult) {
        CourseAttemptStore.persist(result.copy(storageError = null))
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "Cannot commit ${file.name}." }
    }

    private fun atomicCopyReplay(source: File, destination: File): String {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        check(temporary.renameTo(destination)) { "Cannot commit the component replay." }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @JvmStatic fun liveExScore(): Long = run?.accumulator?.rawExTotal ?: 0
    @JvmStatic fun progressText(): String = run?.let { "${it.index + 1}/${it.order.size}  ${it.definition.courseName}" } ?: ""
    @JvmStatic fun activeTimeMillis(): Long = run?.clock?.totalMillis ?: 0
    @JvmStatic fun scoringMode(): String? = run?.definition?.scoring
    @JvmStatic fun isSpeedrun(): Boolean = run?.definition?.isSpeedrun == true

    @JvmStatic fun liveMetric(stat: StatisticV2): String {
        val current = run ?: return ""
        return when (current.definition.scoring) {
            "exscore" -> "EX ${current.accumulator.rawExTotal}"
            "accuracy" -> {
                val numerator = current.components.sumOf { it.accuracyNumerator } + 300L * stat.hit300 + 100L * stat.hit100 + 50L * stat.hit50
                val denominator = current.components.sumOf { it.accuracyDenominator } + 300L * stat.notesHit
                String.format(java.util.Locale.US, "Accuracy %.2f%%", if (denominator == 0L) 100.0 else numerator * 100.0 / denominator)
            }
            else -> "Course score ${current.components.sumOf { it.score } + stat.totalScoreWithMultiplier}"
        }
    }
}

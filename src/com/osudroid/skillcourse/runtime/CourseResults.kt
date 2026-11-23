package com.osudroid.skillcourse.runtime

import android.app.AlertDialog
import com.osudroid.data.SkillCourseEntity
import com.osudroid.skillcourse.CourseRepository
import com.osudroid.utils.async
import com.osudroid.utils.updateThread
import com.reco1l.andengine.UIScene
import kotlinx.serialization.json.Json
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.LibraryManager
import ru.nsu.ccfit.zuev.osu.ToastLogger
import ru.nsu.ccfit.zuev.osu.scoring.Replay
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/** Course presentation is read-only: rendering never saves or uploads a score. */
object CourseResults {
    private val json = Json { ignoreUnknownKeys = true }
    private val replayStartGuard = AtomicBoolean(false)

    var replayReturn: CourseResult? = null
        private set
    private var replayEndTimeMs: Long? = null

    @JvmStatic fun isReplayingComponent() = replayReturn != null
    @JvmStatic fun shouldStopReplay(mapTimeMs: Long) = replayEndTimeMs?.let { mapTimeMs >= it } == true

    @JvmStatic fun show(result: CourseResult) {
        val global = GlobalManager.getInstance()
        global.scoring.loadCourse(result)
        global.engine.scene = global.scoring.scene
    }

    @JvmStatic fun render(result: CourseResult): UIScene {
        val retryingSave = AtomicBoolean(false)
        val retrySavingAction: (() -> Unit)? = if (result.storageError != null) {
            { retrySaving(result, retryingSave) }
        } else {
            null
        }
        return CourseResultPresenter.render(
            result,
            CourseResultActions(
                onBack = { back() },
                onBreakdown = { updateThread { openBreakdown(result) } },
                onRetryCourse = { retryCourse(result) },
                onRetrySaving = retrySavingAction
            )
        )
    }

    @JvmStatic fun back() {
        val global = GlobalManager.getInstance()
        global.engine.scene = global.songMenu.scene
        global.songMenu.refreshBackground()
        global.songMenu.updateScore()
        CourseView.refreshCourseViewIfVisible()
        val selected = global.songMenu.selectedBeatmap
        global.selectedBeatmap = selected
        if (selected != null) global.songMenu.playMusic(selected.audioPath, selected.previewTime)
        CourseClient.flushDeferredImports()
    }

    fun history(entity: SkillCourseEntity) {
        val activity = GlobalManager.getInstance().mainActivity
        val attempts = CourseRepository.listAttempts(entity.id, entity.courseHash)
        if (attempts.isEmpty()) {
            ToastLogger.showText("No local attempts for this course yet.", false)
            return
        }
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert).setTitle("Local course history")
            .setItems(attempts.map { "${it.outcome}  ·  ${Date(it.createdAt)}" }.toTypedArray()) { _, index ->
                try {
                    val result = json.decodeFromString<CourseResult>(attempts[index].resultJson)
                    updateThread { show(result) }
                } catch (e: Exception) {
                    ToastLogger.showText("Unable to read this course result: ${e.message}", true)
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun openBreakdown(result: CourseResult) {
        val global = GlobalManager.getInstance()
        if (global.engine.scene !== global.scoring.scene) {
            return
        }
        CourseBreakdownPanel(result) { component -> replayComponent(result, component) }.show()
    }

    private fun retryCourse(result: CourseResult) {
        CourseRuntime.retry(result)
    }

    private fun retrySaving(result: CourseResult, guard: AtomicBoolean) {
        if (!guard.compareAndSet(false, true)) {
            return
        }
        async {
            try {
                val saved = result.copy(storageError = null)
                CourseAttemptStore.persist(saved)
                updateThread { show(saved) }
            } catch (e: Exception) {
                ToastLogger.showText("Unable to save the result: ${e.message}", true)
            } finally {
                guard.set(false)
            }
        }
    }

    /**
     * Starts a native component replay without touching any persisted course result.
     *
     * The first pass only reads the native statistics. GameScene allocates object data from the
     * exact map and performs the actual replay load; the normal aggregate return path then restores
     * the same course result.
     */
    private fun replayComponent(result: CourseResult, component: CourseComponentResult) {
        val replayFilename = component.replayFilename
        if (replayFilename == null) {
            ToastLogger.showText("No replay was recorded for this component.", true)
            return
        }

        val beatmap = LibraryManager.findBeatmapByMD5(component.mapHash)
        val file = File(result.replayDirectory, replayFilename)
        if (beatmap == null || !file.isFile) {
            ToastLogger.showText("The exact map or its replay is unavailable.", true)
            return
        }
        if (!replayStartGuard.compareAndSet(false, true)) {
            return
        }

        async {
            val cutoff = if (!component.completed) {
                readReplayCutoff(result, component)
            } else {
                null
            }

            updateThread {
                try {
                    val global = GlobalManager.getInstance()
                    if (global.engine.scene !== global.scoring.scene) {
                        return@updateThread
                    }
                    val replay = Replay()
                    if (replay.load(file.path, false)) {
                        replayReturn = result
                        replayEndTimeMs = cutoff
                        global.scoring.replayStat = replay.stat
                        global.gameScene.startGame(beatmap, file.path, replay.stat.mod)
                    } else {
                        replayReturn = null
                        replayEndTimeMs = null
                        ToastLogger.showText("Unable to load the component replay.", true)
                    }
                } catch (e: Exception) {
                    replayReturn = null
                    replayEndTimeMs = null
                    ToastLogger.showText("Unable to load the component replay: ${e.message}", true)
                } finally {
                    replayStartGuard.set(false)
                }
            }
        }
    }

    /** A partial component replay stops at its last recorded judgment. */
    private fun readReplayCutoff(result: CourseResult, component: CourseComponentResult): Long? {
        val eventsFilename = component.eventsFilename ?: return null
        return try {
            val events = json.decodeFromString<List<CourseEventRecord>>(
                File(result.replayDirectory, eventsFilename).readText()
            )
            events.lastOrNull()?.mapTimeMs ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    @JvmStatic fun consumeReplayReturn(): CourseResult? = replayReturn.also {
        replayReturn = null
        replayEndTimeMs = null
    }
}

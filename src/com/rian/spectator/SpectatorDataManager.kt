package com.rian.spectator

import android.util.Log
import com.osudroid.multiplayer.api.RoomAPI
import com.osudroid.beatmaps.hitobjects.HitCircle
import com.osudroid.beatmaps.hitobjects.Slider
import ru.nsu.ccfit.zuev.osu.game.GameScene
import ru.nsu.ccfit.zuev.osu.scoring.Replay
import ru.nsu.ccfit.zuev.osu.scoring.StatisticV2
import ru.nsu.ccfit.zuev.osu.scoring.TouchType
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.*
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Holds spectator data that will be sent to the server periodically.
 */
class SpectatorDataManager(
    private val gameScene: GameScene,
    private val replay: Replay,
    private val stat: StatisticV2
) {
    // Appended to on the game thread and read on the submission coroutine's thread: guard every access with
    // synchronized(objectData) / synchronized(events). Cursor moves are guarded the same way, by their MoveArray.
    private val objectData = mutableListOf<SpectatorObjectData>()
    private var beginningObjectDataIndex = 0
    private var endObjectDataIndex = 0

    private val events = mutableListOf<SpectatorEvent>()
    private var beginningEventIndex = 0
    private var endEventIndex = 0

    private val beginningCursorMoveIndexes = IntArray(GameScene.CursorCount)
    private val endCursorMoveIndexes = IntArray(GameScene.CursorCount)

    private val submissionPeriod = 5000L.toDuration(DurationUnit.MILLISECONDS)

    @Volatile
    private var gameEnded = false

    // Reused by every submission, so the buffer keeps its capacity between chunks. Both streams only hold memory, so
    // there is nothing to release by closing them.
    private val chunkBuffer = ByteArrayOutputStream(8192)
    private val chunkOutput = DataOutputStream(chunkBuffer)

    /**
     * Sends every chunk, one [submissionPeriod] after the previous one was sent, until the game has ended or
     * [stopSubmission] is called.
     *
     * A delay loop rather than a fixed-rate schedule: after a stall (a long GC pause or a slow send), the next chunk
     * simply carries more data instead of several submissions firing back to back.
     */
    private val submissionJob = CoroutineScope(Dispatchers.IO).launch {
        while (isActive) {
            delay(submissionPeriod)

            if (!submitChunk()) {
                break
            }
        }
    }

    /**
     * Writes and sends everything recorded since the last successful submission.
     *
     * @return Whether to keep submitting, which is `false` once the chunk sent after the game ended has gone out.
     */
    private fun submitChunk(): Boolean {
        // Important: this is not using `elapsedTime` as it is recorded *before* `GameScene`'s update pass is ran,
        // so a chunk could claim a time whose data only arrives in the next chunk. Everything up to
        // `processedTime` is already recorded, and the copies below take whatever has been recorded since.
        val secPassed = gameScene.processedTime
        val gameHasEnded = gameEnded

        // Any exception is caught, not only I/O ones: one escaping would end the loop, and with it every later
        // submission of the round. The data stays queued and goes out with the next chunk instead.
        val submitted = try {
            writeChunk(secPassed)

            // The socket keeps the payload until it is sent, so it gets its own copy rather than the reused buffer.
            RoomAPI.submitSpectatorData(chunkBuffer.toByteArray())
        } catch (e: Exception) {
            Log.e("SpectatorDataManager", "Failed to submit spectator data", e)

            // Retried with the next chunk even after the game has ended, as before.
            return true
        }

        if (gameHasEnded) {
            return false
        }

        if (submitted) {
            endCursorMoveIndexes.copyInto(beginningCursorMoveIndexes)
            beginningObjectDataIndex = endObjectDataIndex
            beginningEventIndex = endEventIndex
        }

        return true
    }

    private fun writeChunk(secPassed: Float) {
        chunkBuffer.reset()

        chunkOutput.writeFloat(secPassed)
        chunkOutput.writeInt(endCursorMoveIndexes.size)

        // Each slice is written while holding its lock rather than copied out first. The game thread only waits for
        // that one slice's writes into memory.
        for (i in endCursorMoveIndexes.indices) {
            val move = replay.cursorMoves[i]

            synchronized(move) {
                val begin = beginningCursorMoveIndexes[i]
                val end = move.size

                endCursorMoveIndexes[i] = end
                chunkOutput.writeInt(end - begin)

                for (j in begin until end) {
                    val movement = move.movements[j]

                    chunkOutput.writeInt((movement.time shl 2) + movement.touchType.id)

                    if (movement.touchType != TouchType.UP) {
                        chunkOutput.writeFloat(movement.x)
                        chunkOutput.writeFloat(movement.y)
                    }
                }
            }
        }

        synchronized(objectData) {
            endObjectDataIndex = objectData.size
            chunkOutput.writeInt(endObjectDataIndex - beginningObjectDataIndex)

            for (j in beginningObjectDataIndex until endObjectDataIndex) {
                objectData[j].writeTo(chunkOutput)
            }
        }

        synchronized(events) {
            endEventIndex = events.size
            chunkOutput.writeInt(endEventIndex - beginningEventIndex)

            for (j in beginningEventIndex until endEventIndex) {
                val event = events[j]

                chunkOutput.writeFloat(event.time)
                chunkOutput.writeLong(event.score)
                chunkOutput.writeInt(event.combo)
                chunkOutput.writeInt(event.hit300)
                chunkOutput.writeInt(event.hit100)
                chunkOutput.writeInt(event.hit50)
                chunkOutput.writeInt(event.misses)
            }
        }

        chunkOutput.flush()
    }

    /**
     * Adds a hit object data.
     *
     * @param objectId The ID of the object.
     */
    fun addObjectData(objectId: Int) {
        val replayObjectData = replay.objectData ?: return

        if (objectId < 0 || objectId >= replayObjectData.size) {
            return
        }

        val obj = gameScene.playableBeatmap!!.hitObjects.objects[objectId]
        val replayData = replayObjectData[objectId]
        var time = obj.endTime
        val hitWindow = if (obj is Slider) obj.head.hitWindow else obj.hitWindow

        if (hitWindow == null) {
            return
        }

        if (obj is HitCircle) {
            // Special handling for circles that are not tapped.
            time += (
                if (replayData.accuracy == 10000.toShort()) hitWindow.mehWindow
                else replayData.accuracy
            ).toDouble()
        }

        val data = SpectatorObjectData(
            objectId,
            time,
            replayData,
            (obj as? Slider)?.nestedHitObjects?.size ?: 1
        )

        synchronized(objectData) { objectData.add(data) }
    }

    /**
     * Adds a snapshot of a slider that has been partially judged.
     *
     * The snapshot stands alone: it copies [tickSet] as the slider keeps mutating it, and it never touches the
     * replay's object data. Nothing is stored when [judgedCount] reaches the amount of nested hit objects of the
     * slider, as that judgement is carried by the final record added via [addObjectData].
     *
     * @param objectId The ID of the slider.
     * @param accuracy The accuracy of the slider head's hit, in milliseconds.
     * @param tickSet The tick set of the slider. Bit `i - 1` represents whether nested hit object `i` was hit.
     * @param judgedCount The number of parts of the slider that have been judged, including the head.
     */
    fun addPartialObjectData(objectId: Int, accuracy: Int, tickSet: BitSet, judgedCount: Int) {
        val objects = gameScene.playableBeatmap?.hitObjects?.objects ?: return

        if (objectId < 0 || objectId >= objects.size) {
            return
        }

        val slider = objects[objectId] as? Slider ?: return

        if (judgedCount < 1 || judgedCount >= slider.nestedHitObjects.size) {
            return
        }

        val replayData = Replay.ReplayObjectData().also {
            it.accuracy = accuracy.toShort()
            it.tickSet = tickSet.clone() as BitSet
        }

        val data = SpectatorObjectData(
            objectId,
            SpectatorObjectData.partialSliderTime(slider, judgedCount, accuracy),
            replayData,
            judgedCount
        )

        synchronized(objectData) { objectData.add(data) }
    }

    /**
     * Adds a spectator event.
     */
    fun addEvent() {
        val event = SpectatorEvent(
            gameScene.elapsedTime * 1000,
            stat.totalScoreWithMultiplier,
            stat.combo,
            stat.hit300,
            stat.hit100,
            stat.hit50,
            stat.misses
        )

        synchronized(events) { events.add(event) }
    }

    /**
     * Stops submitting spectator data for good. Submission cannot be restarted afterwards.
     */
    fun stopSubmission() = submissionJob.cancel()

    fun setGameEnded(gameEnded: Boolean) {
        this.gameEnded = gameEnded
    }
}
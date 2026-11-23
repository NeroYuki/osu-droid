package com.osudroid.skillcourse.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.osudroid.data.BeatmapInfo
import com.osudroid.skillcourse.recovery.CourseAvailabilitySnapshot
import com.osudroid.skillcourse.recovery.CourseMapStatus
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import ru.nsu.ccfit.zuev.osu.Config

/**
 * Builds and caches the dimmed horizontal course-card collage.
 *
 * Candidate backgrounds are taken from the availability cache's ready/outdated entries. Only the
 * already-known `backgroundFilename` is used; `.osu` files are never parsed here. The selected
 * paths depend deterministically on the course hash so a card does not shuffle while scrolling.
 */
internal object CourseCardCollage {

    /** Roughly the width of the manager card list at common phone densities. */
    const val TARGET_WIDTH = 768
    const val TARGET_HEIGHT = 160

    private const val MIN_TILES = 3
    private const val MAX_TILES = 5
    private const val CACHE_KB = 8 * 1024

    private val placeholderColor = 0xFF161622.toInt()

    private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp")

    private val bitmaps = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "skill-course-collage").apply { isDaemon = true }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** A deterministic collage request. The key changes only when the selected backgrounds change. */
    internal data class Spec(
        val key: String,
        val backgroundPaths: List<String>,
    )

    /** Cancellation handle for one background decode. Cancelling never releases the shared cache. */
    internal class Handle internal constructor(private val canceled: AtomicBoolean) {
        fun cancel() {
            canceled.set(true)
        }
    }

    fun specFor(courseHash: String, snapshot: CourseAvailabilitySnapshot?): Spec? {
        if (Config.isSafeBeatmapBg()) {
            // The safe-background setting asks the game to avoid beatmap images entirely.
            return null
        }

        val paths = selectCollageBackgroundPaths(courseHash, snapshot)
        if (paths.isEmpty()) {
            return null
        }
        return Spec(
            // An imported archive can replace an image at the same path.
            key = "${snapshot?.libraryRevision}\u0000${paths.joinToString("\u0000")}",
            backgroundPaths = paths,
        )
    }

    /**
     * Returns a cached collage immediately or decodes one on a bounded background pool. The callback
     * is delivered on the main thread and is suppressed after [Handle.cancel].
     */
    fun request(spec: Spec, callback: (Bitmap?) -> Unit): Handle {
        bitmaps.get(spec.key)?.let { bitmap ->
            callback(bitmap)
            return Handle(AtomicBoolean(true))
        }

        val canceled = AtomicBoolean(false)
        executor.execute {
            if (canceled.get()) {
                return@execute
            }

            val bitmap = build(spec, canceled)
            if (bitmap != null) {
                bitmaps.put(spec.key, bitmap)
            }

            if (!canceled.get()) {
                post { callback(bitmap) }
            }
        }
        return Handle(canceled)
    }

    /**
     * Pure, deterministic candidate selection. Entries are considered only when they are installed
     * (ready) or an installed revision can be used for practice (outdated). The authored entry order
     * is preserved and duplicate background paths collapse.
     */
    internal fun selectCollageBackgroundPaths(
        courseHash: String,
        snapshot: CourseAvailabilitySnapshot?,
    ): List<String> {
        if (snapshot == null) {
            return emptyList()
        }

        val paths = LinkedHashSet<String>()
        for (entry in snapshot.entries) {
            if (entry.status != CourseMapStatus.READY && entry.status != CourseMapStatus.OUTDATED) {
                continue
            }
            for (candidate in entry.candidates) {
                candidate.backgroundPathOrNull()?.let { paths += it }
            }
        }

        if (paths.isEmpty()) {
            return emptyList()
        }

        val sorted = paths.sorted()
        val seed = courseHash.hashCode()
        val desired = MIN_TILES + Math.floorMod(seed, MAX_TILES - MIN_TILES + 1)
        val count = desired.coerceAtMost(sorted.size)
        val start = Math.floorMod(seed / 3, sorted.size)
        return List(count) { index -> sorted[(start + index) % sorted.size] }
    }

    private fun build(spec: Spec, canceled: AtomicBoolean): Bitmap? {
        val files = spec.backgroundPaths
            .map { File(it) }
            .filter { isSafeBackground(it) }
            .take(MAX_TILES)
        if (files.isEmpty()) {
            return null
        }

        val target = try {
            Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }

        val tiles = ArrayList<Bitmap>(files.size)
        var success = false
        try {
            val canvas = Canvas(target)
            canvas.drawColor(placeholderColor)

            val tileWidth = (TARGET_WIDTH / files.size).coerceAtLeast(1)
            for (file in files) {
                if (canceled.get()) {
                    return null
                }
                decodeTile(file, tileWidth)?.let { tiles += it }
            }

            if (tiles.isEmpty() || canceled.get()) {
                return null
            }

            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val segmentWidth = TARGET_WIDTH / tiles.size
            for ((index, tile) in tiles.withIndex()) {
                val left = index * segmentWidth
                val right = if (index == tiles.lastIndex) TARGET_WIDTH else left + segmentWidth
                if (right > left) {
                    drawCenterCrop(
                        canvas = canvas,
                        bitmap = tile,
                        destination = Rect(left, 0, right, TARGET_HEIGHT),
                        paint = paint,
                    )
                }
            }

            success = true
            return target
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            return null
        } finally {
            for (tile in tiles) {
                if (!tile.isRecycled) {
                    tile.recycle()
                }
            }
            if (!success && !target.isRecycled) {
                target.recycle()
            }
        }
    }

    /** Canonical-path containment rejects a database row that points outside the beatmap root. */
    private fun isSafeBackground(file: File): Boolean {
        return try {
            if (!file.isFile) {
                false
            } else {
                val root = File(Config.getBeatmapPath()).canonicalFile
                val path = file.canonicalFile
                path.path == root.path || path.path.startsWith(root.path + File.separator)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeTile(file: File, tileWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.absolutePath, bounds)
        } catch (_: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                requiredWidth = (tileWidth * 2).coerceAtLeast(1),
                requiredHeight = TARGET_HEIGHT * 2,
            )
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun sampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        requiredWidth: Int,
        requiredHeight: Int,
    ): Int {
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= requiredWidth &&
            sourceHeight / (sample * 2) >= requiredHeight
        ) {
            sample *= 2
        }
        return sample
    }

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, destination: Rect, paint: Paint) {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return
        }

        val destinationAspect = destination.width().toFloat() / destination.height()
        var cropWidth = sourceWidth
        var cropHeight = (sourceWidth / destinationAspect).toInt()
        if (cropHeight > sourceHeight) {
            cropHeight = sourceHeight
            cropWidth = (sourceHeight * destinationAspect).toInt()
        }
        cropWidth = cropWidth.coerceIn(1, sourceWidth)
        cropHeight = cropHeight.coerceIn(1, sourceHeight)

        val left = (sourceWidth - cropWidth) / 2
        val top = (sourceHeight - cropHeight) / 2
        canvas.drawBitmap(
            bitmap,
            Rect(left, top, left + cropWidth, top + cropHeight),
            destination,
            paint,
        )
    }

    /**
     * The background path without touching the filesystem. Missing/unsafe files are filtered when
     * the collage is decoded off the UI thread.
     */
    private fun BeatmapInfo.backgroundPathOrNull(): String? {
        val filename = backgroundFilename?.trim().orEmpty()
        if (filename.isEmpty()) {
            return null
        }
        val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in supportedExtensions) {
            return null
        }
        return backgroundPath.takeUnless { it.isBlank() }
    }

    private fun post(block: () -> Unit) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                block()
            } else {
                mainHandler.post(block)
            }
        } catch (_: Exception) {
            // Unit-test JVMs may not have a prepared main looper.
            block()
        }
    }
}

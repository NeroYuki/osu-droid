package com.osudroid.skillcourse.runtime

/** Counts real active gameplay time without including loaders, pauses, or result lead-out. */
class CourseClock(private val nowNanos: () -> Long = System::nanoTime) {
    private var accumulatedNanos = 0L
    private var componentNanos = 0L
    private var startedAt: Long? = null
    private var componentOpen = false

    @get:Synchronized val totalNanos: Long
        get() = accumulatedNanos + runningNanos()

    @get:Synchronized val currentComponentNanos: Long
        get() = componentNanos + runningNanos()

    val totalMillis: Long
        get() = totalNanos / 1_000_000L

    @Synchronized fun startComponent() {
        check(!componentOpen) { "The previous course component has not finished." }
        componentNanos = 0
        componentOpen = true
        startedAt = nowNanos()
    }

    @Synchronized fun pause() {
        val start = startedAt ?: return
        val elapsed = (nowNanos() - start).coerceAtLeast(0)
        accumulatedNanos += elapsed
        componentNanos += elapsed
        startedAt = null
    }

    @Synchronized fun resume() {
        if (componentOpen && startedAt == null) {
            startedAt = nowNanos()
        }
    }

    @Synchronized fun finishComponent(): Long {
        pause()
        componentOpen = false
        return componentNanos
    }

    private fun runningNanos(): Long = startedAt?.let { (nowNanos() - it).coerceAtLeast(0) } ?: 0
}

package com.rian.spectator

import java.io.DataOutput

/**
 * Represents a spectator event.
 */
data class SpectatorEvent(
    /**
     * The time at which the event occurred, in milliseconds.
     */
    @JvmField
    val time: Float,

    /**
     * The score of the player after this event.
     */
    @JvmField
    val score: Long,

    /**
     * The combo of the player after this event.
     */
    @JvmField
    val combo: Int,

    /**
     * The amount of 300s achieved after this event.
     */
    @JvmField
    val hit300: Int,

    /**
     * The amount of 100s achieved after this event.
     */
    @JvmField
    val hit100: Int,

    /**
     * The amount of 50s achieved after this event.
     */
    @JvmField
    val hit50: Int,

    /**
     * The number of misses after this event.
     */
    @JvmField
    val misses: Int,

    /**
     * The cumulative amount of slider head hits after this event.
     */
    @JvmField
    val sliderHeadHits: Int,

    /**
     * The cumulative amount of slider tick hits after this event.
     */
    @JvmField
    val sliderTickHits: Int,

    /**
     * The cumulative amount of slider repeat hits after this event.
     */
    @JvmField
    val sliderRepeatHits: Int,

    /**
     * The cumulative amount of slider end hits after this event.
     */
    @JvmField
    val sliderEndHits: Int
) {
    companion object {
        /**
         * The magic value identifying the slider count trailer.
         */
        private const val SLIDER_COUNTS_MAGIC = 0x534c4452 // 'SLDR'

        /**
         * The version of the slider count trailer.
         */
        private const val SLIDER_COUNTS_VERSION = 1

        /**
         * Writes the spectator event block for a bounded range of events.
         *
         * The event block starts with the amount of serialized events, followed by the event
         * records themselves. When at least one event is serialized, a slider count trailer is
         * appended after all event records. The trailer is optional and may be ignored by older
         * decoders, which only read the event count and the event records.
         *
         * @param output The output to write to.
         * @param events The events to serialize.
         * @param fromIndex The index of the first event to serialize, inclusive.
         * @param toIndex The index after the last event to serialize, exclusive.
         */
        internal fun writeEventData(
            output: DataOutput,
            events: List<SpectatorEvent>,
            fromIndex: Int,
            toIndex: Int
        ) {
            require(fromIndex in 0..toIndex) { "Invalid event range: [$fromIndex, $toIndex)" }
            require(toIndex <= events.size) { "Invalid event range: [$fromIndex, $toIndex)" }

            val count = toIndex - fromIndex

            output.writeInt(count)

            for (i in fromIndex until toIndex) {
                val event = events[i]

                output.writeFloat(event.time)
                output.writeLong(event.score)
                output.writeInt(event.combo)
                output.writeInt(event.hit300)
                output.writeInt(event.hit100)
                output.writeInt(event.hit50)
                output.writeInt(event.misses)
            }

            if (count == 0) {
                // Older decoders treat a packet with no events as the end of the event block,
                // and the trailer is only meaningful for events that were actually sent.
                return
            }

            output.writeInt(SLIDER_COUNTS_MAGIC)
            output.writeInt(SLIDER_COUNTS_VERSION)
            output.writeInt(count)

            for (i in fromIndex until toIndex) {
                val event = events[i]

                output.writeInt(event.sliderHeadHits)
                output.writeInt(event.sliderTickHits)
                output.writeInt(event.sliderRepeatHits)
                output.writeInt(event.sliderEndHits)
            }
        }
    }
}

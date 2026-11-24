package com.rian.spectator

import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class SpectatorEventSerializationTest {
    @Test
    fun `Test serializes events and slider count trailer`() {
        val bytes = serializePacket(goldenEvents, 0, goldenEvents.size)

        Assert.assertEquals(GOLDEN_PACKET_HEX, toHex(bytes))
    }

    @Test
    fun `Test old decoder reads event records and ignores trailer`() {
        val bytes = serializePacket(goldenEvents, 0, goldenEvents.size)

        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readFloat()
            input.readInt()
            input.readInt()

            Assert.assertEquals(goldenEvents.size, input.readInt())

            for (expected in goldenEvents) {
                Assert.assertEquals(expected.time, input.readFloat(), 0f)
                Assert.assertEquals(expected.score, input.readLong())
                Assert.assertEquals(expected.combo, input.readInt())
                Assert.assertEquals(expected.hit300, input.readInt())
                Assert.assertEquals(expected.hit100, input.readInt())
                Assert.assertEquals(expected.hit50, input.readInt())
                Assert.assertEquals(expected.misses, input.readInt())
            }

            // An old decoder stops after the original event records, leaving the trailer unread.
            Assert.assertEquals(TRAILER_HEADER_SIZE + goldenEvents.size * TRAILER_EVENT_SIZE, input.available())
        }
    }

    @Test
    fun `Test serializes all slider counts independently including zeros`() {
        val bytes = serializeEventBlock(goldenEvents, 0, goldenEvents.size)

        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            Assert.assertEquals(goldenEvents.size, input.readInt())
            repeat(goldenEvents.size) { input.readEventRecord() }

            Assert.assertEquals(SLIDER_COUNTS_MAGIC, input.readInt())
            Assert.assertEquals(SLIDER_COUNTS_VERSION, input.readInt())
            Assert.assertEquals(goldenEvents.size, input.readInt())

            for (expected in goldenEvents) {
                Assert.assertEquals(expected.sliderHeadHits, input.readInt())
                Assert.assertEquals(expected.sliderTickHits, input.readInt())
                Assert.assertEquals(expected.sliderRepeatHits, input.readInt())
                Assert.assertEquals(expected.sliderEndHits, input.readInt())
            }

            Assert.assertEquals(0, input.available())
        }
    }

    @Test
    fun `Test serializes no trailer for an empty event list`() {
        val bytes = serializeEventBlock(emptyList(), 0, 0)

        Assert.assertArrayEquals(byteArrayOf(0, 0, 0, 0), bytes)
    }

    @Test
    fun `Test serializes only the requested range with a nonzero from index`() {
        val events = goldenEvents + SpectatorEvent(
            300f,
            7_000_000_000L,
            30,
            5,
            0,
            0,
            1,
            7,
            8,
            9,
            10
        )
        val bytes = serializeEventBlock(events, 1, events.size)

        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val count = input.readInt()

            Assert.assertEquals(2, count)

            // The first event in the chunk must be the event at the requested from index.
            Assert.assertEquals(200f, input.readFloat(), 0f)
            input.readLong()
            input.readInt()
            input.readInt()
            input.readInt()
            input.readInt()
            input.readInt()

            Assert.assertEquals(300f, input.readFloat(), 0f)
            input.readLong()
            input.readInt()
            input.readInt()
            input.readInt()
            input.readInt()
            input.readInt()

            Assert.assertEquals(SLIDER_COUNTS_MAGIC, input.readInt())
            Assert.assertEquals(SLIDER_COUNTS_VERSION, input.readInt())
            Assert.assertEquals(2, input.readInt())

            repeat(4) { Assert.assertEquals(0, input.readInt()) }
            repeat(4) { Assert.assertEquals(7 + it, input.readInt()) }

            Assert.assertEquals(0, input.available())
        }

        Assert.assertEquals(
            4 + 2 * EVENT_RECORD_SIZE + TRAILER_HEADER_SIZE + 2 * TRAILER_EVENT_SIZE,
            bytes.size
        )
    }

    @Test
    fun `Test preserves wide scores in event records`() {
        val bytes = serializeEventBlock(goldenEvents, 0, goldenEvents.size)

        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            Assert.assertEquals(2, input.readInt())

            Assert.assertEquals(goldenEvents[0].time, input.readFloat(), 0f)
            Assert.assertEquals(5_000_000_000L, input.readLong())
            input.readInt()
            input.readInt()
            input.readInt()
            input.readInt()
            input.readInt()

            Assert.assertEquals(goldenEvents[1].time, input.readFloat(), 0f)
            Assert.assertEquals(6_000_000_000L, input.readLong())
        }
    }

    private fun DataInputStream.readEventRecord() {
        readFloat()
        readLong()
        readInt()
        readInt()
        readInt()
        readInt()
        readInt()
    }

    companion object {
        private const val EVENT_RECORD_SIZE = 32
        private const val TRAILER_HEADER_SIZE = 12
        private const val TRAILER_EVENT_SIZE = 16
        private const val SLIDER_COUNTS_MAGIC = 0x534c4452
        private const val SLIDER_COUNTS_VERSION = 1

        /**
         * A minimal packet prefix (elapsed time, cursor count and object count) followed by the
         * golden event block. The same packet is used by the web decoder test.
         */
        private const val GOLDEN_PACKET_HEX =
            "41480000000000000000000000000002" +
                "42c80000000000012a05f2000000000a00000001000000000000000000000001" +
                "434800000000000165a0bc000000001400000003000000000000000000000001" +
                "534c4452000000010000000200000003000000040000000500000006" +
                "00000000000000000000000000000000"

        private val goldenEvents = listOf(
            SpectatorEvent(100f, 5_000_000_000L, 10, 1, 0, 0, 1, 3, 4, 5, 6),
            SpectatorEvent(200f, 6_000_000_000L, 20, 3, 0, 0, 1, 0, 0, 0, 0)
        )

        private fun serializePacket(
            events: List<SpectatorEvent>,
            fromIndex: Int,
            toIndex: Int
        ): ByteArray {
            val output = ByteArrayOutputStream()

            DataOutputStream(output).apply {
                writeFloat(12.5f)
                writeInt(0)
                writeInt(0)
                SpectatorEvent.writeEventData(this, events, fromIndex, toIndex)
                flush()
            }

            return output.toByteArray()
        }

        private fun serializeEventBlock(
            events: List<SpectatorEvent>,
            fromIndex: Int,
            toIndex: Int
        ): ByteArray {
            val output = ByteArrayOutputStream()

            DataOutputStream(output).apply {
                SpectatorEvent.writeEventData(this, events, fromIndex, toIndex)
                flush()
            }

            return output.toByteArray()
        }

        private fun toHex(bytes: ByteArray): String {
            val builder = StringBuilder(bytes.size * 2)

            for (byte in bytes) {
                builder.append(HEX_DIGITS[(byte.toInt() shr 4) and 0xF])
                builder.append(HEX_DIGITS[byte.toInt() and 0xF])
            }

            return builder.toString()
        }

        private const val HEX_DIGITS = "0123456789abcdef"
    }
}

package com.osudroid.skillcourse

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The course speedrun rate contract.
 *
 * Rates are stored as integer hundredths in the inclusive [MIN_HUNDREDTHS]..[MAX_HUNDREDTHS]
 * range on [STEP_HUNDREDTHS] boundaries. Typed decimal input is rounded half-up to the nearest
 * step and then clamped, which is why this helper uses [BigDecimal] instead of binary floats.
 */
object CourseSpeed {

    const val MIN_HUNDREDTHS = 50
    const val MAX_HUNDREDTHS = 300
    const val STEP_HUNDREDTHS = 5

    /**
     * The neutral rate used when no preference has been saved yet.
     */
    const val DEFAULT_HUNDREDTHS = 100

    /**
     * Alias used by the preflight rate stepper.
     */
    const val STEP = STEP_HUNDREDTHS

    private val MIN_RATE = BigDecimal("0.50")
    private val MAX_RATE = BigDecimal("3.00")
    private val HUNDRED = BigDecimal(100)
    private val STEP_DECIMAL = BigDecimal(STEP_HUNDREDTHS)

    /**
     * Parses typed decimal text and returns a valid hundredths value, or `null` for invalid input.
     *
     * Examples: `"1.225"` -> `125`, `".001"` -> `50`, `"9"` -> `300`, `"NaN"` -> `null`.
     */
    @JvmStatic
    fun normalize(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val value = try {
            BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            return null
        }

        return normalize(value)
    }

    /**
     * Converts an already-parsed finite rate to the nearest valid hundredths value.
     */
    @JvmStatic
    fun normalize(rate: Float): Int? {
        if (!rate.isFinite()) {
            return null
        }

        return try {
            normalize(BigDecimal(rate.toString()))
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun normalize(value: BigDecimal): Int {
        // Compare before expanding huge exponents such as "1e100000000" into a full integer.
        if (value < MIN_RATE) {
            return MIN_HUNDREDTHS
        }
        if (value > MAX_RATE) {
            return MAX_HUNDREDTHS
        }

        val steps = try {
            value.multiply(HUNDRED).divide(STEP_DECIMAL, 0, RoundingMode.HALF_UP)
        } catch (_: ArithmeticException) {
            return DEFAULT_HUNDREDTHS
        }

        return steps.multiply(STEP_DECIMAL).toInt().coerceIn(MIN_HUNDREDTHS, MAX_HUNDREDTHS)
    }

    /**
     * Converts a valid hundredths value to its effective native rate.
     *
     * @throws IllegalArgumentException If [hundredths] is not a supported step.
     */
    @JvmStatic
    fun rate(hundredths: Int): Float {
        requireValid(hundredths)
        return hundredths / 100f
    }

    /**
     * Formats a hundredths value with exactly two decimal places, independent of the device locale.
     *
     * This accepts any value so callers can display stored or authored rates before normalizing
     * them through [normalize].
     */
    @JvmStatic
    fun format(hundredths: Int): String =
        BigDecimal.valueOf(hundredths.toLong(), 2).toPlainString()

    /**
     * Whether [hundredths] is inside the supported range and on a supported step.
     */
    @JvmStatic
    fun isValid(hundredths: Int): Boolean =
        hundredths in MIN_HUNDREDTHS..MAX_HUNDREDTHS && hundredths % STEP_HUNDREDTHS == 0

    private fun requireValid(hundredths: Int) {
        require(isValid(hundredths)) {
            "Rate must be between 0.50 and 3.00 in 0.05 increments."
        }
    }
}

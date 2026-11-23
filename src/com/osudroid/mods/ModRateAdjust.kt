package com.osudroid.mods

import com.reco1l.framework.math.roundBy
import com.osudroid.mods.settings.*

/**
 * Represents a [Mod] that adjusts the track's playback rate.
 *
 * @param trackRateMultiplier The initial multiplier for the track's playback rate.
 * @param defaultValue The setting default exposed to serialization/customization. Static rate mods
 * pass their native rate here so their default state stays implicit while explicit course rates can
 * still be stored and restored.
 */
sealed class ModRateAdjust(
    trackRateMultiplier: Float = 1f,
    defaultValue: Float = 1f
) : Mod(), IModApplicableToTrackRate {

    /**
     * The multiplier for the track's playback rate.
     */
    open var trackRateMultiplier by FloatModSetting(
        name = "Track rate multiplier",
        key = "rateMultiplier",
        valueFormatter = { "${it.roundBy(2)}x" },
        defaultValue = defaultValue,
        minValue = 0.5f,
        // 3.00x is the maximum supported by the course speedrun contract. Native serialization and
        // customization must not clamp a course-authored rate to 2.00x.
        maxValue = 3f,
        step = 0.05f,
        precision = 2
    )

    init {
        this.trackRateMultiplier = trackRateMultiplier
    }

    final override val isRelevant
        get() = trackRateMultiplier != 1f || this !is ModCustomSpeed

    final override val isValidForMultiplayerAsFreeMod = false

    final override fun applyToRate(time: Double, rate: Float) = rate * trackRateMultiplier
}

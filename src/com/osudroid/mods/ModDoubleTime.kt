package com.osudroid.mods

import com.osudroid.utils.isInstanceOfAny

/**
 * Represents the Double Time mod.
 */
class ModDoubleTime : ModRateAdjust(1.5f, 1.5f) {
    override val name = "Double Time"
    override val acronym = "DT"
    override val description = "Zoooooooooom..."
    override val type = ModType.DifficultyIncrease
    override val isRanked = true

    override fun isCompatibleWith(other: Mod) =
        !other.isInstanceOfAny(ModNightCore::class, ModHalfTime::class) && super.isCompatibleWith(other)

}

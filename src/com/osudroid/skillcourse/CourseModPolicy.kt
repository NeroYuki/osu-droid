package com.osudroid.skillcourse

import com.osudroid.mods.APIMod
import com.osudroid.mods.Mod
import com.osudroid.mods.ModCustomSpeed
import com.osudroid.mods.ModNoFail
import com.osudroid.mods.ModPerfect
import com.osudroid.mods.ModRateAdjust
import com.osudroid.mods.ModScoreV2
import com.osudroid.mods.ModSuddenDeath
import com.osudroid.mods.ModTimeRamp
import com.osudroid.mods.settings.BooleanModSetting
import com.osudroid.mods.settings.EnumModSetting
import com.osudroid.mods.settings.IModSettingWithPrecision
import com.osudroid.mods.settings.ModSetting
import com.osudroid.mods.settings.NullableNumberModSetting
import com.osudroid.mods.settings.NullableRangeConstrainedModSetting
import com.osudroid.mods.settings.NumberModSetting
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.allModsClassesByAcronym
import java.math.BigDecimal
import kotlin.reflect.full.createInstance
import kotlinx.serialization.json.*

/**
 * Pure mod-selection policy for a Skill Course map.
 *
 * Authored mods are always required and keep their configured settings. Player additions are only
 * accepted when they appear in [CourseDefinition.allowedMods]; additions that would replace or
 * evict an authored mod are rejected. Speedrun owns a single effective rate control instead of
 * stacking another multiplier.
 */
object CourseModPolicy {

    /**
     * Builds the effective mods for one map.
     *
     * @param definition The course whose whitelist, gauge, scoring, and speedrun policy applies.
     * @param map The map's authored snapshot.
     * @param additional Player-selected mods. Only mods listed in [CourseDefinition.allowedMods]
     * are accepted; additions never evict authored mods.
     * @param speed The speedrun rate for this map, in the inclusive 0.50..3.00 range with 0.05
     * steps. Must be `null` outside speedrun courses.
     * @throws CoursePolicyException If a required mod is invalid or an addition is incompatible.
     */
    @JvmStatic
    @JvmOverloads
    fun effectiveMods(
        definition: CourseDefinition,
        map: CourseMap,
        additional: ModHashMap,
        speed: Float? = null
    ): ModHashMap {
        val effective = buildAuthoredMods(map)

        if (!definition.isSpeedrun && speed != null) {
            throw CoursePolicyException("A map speed is only valid in a speedrun course.")
        }

        for (addition in additional.values.sortedBy { it.acronym }) {
            addAdditionalMod(effective, definition, addition.deepCopy())
        }

        applySpeedrunRate(definition, effective, speed)
        applyScoringPolicy(definition, effective)
        validateEffectiveRules(definition, effective)

        return effective
    }

    /**
     * Validates one additional-mod selection against every map before a run starts.
     *
     * @param speeds Optional per-map speedrun rates, in the same order as [maps]. For a speedrun
     * course this must contain exactly one entry per map.
     */
    @JvmStatic
    @JvmOverloads
    fun validateAdditionalMods(
        definition: CourseDefinition,
        maps: List<CourseMap>,
        additional: ModHashMap,
        speeds: List<Float?>? = null
    ) {
        if (definition.isSpeedrun) {
            if (speeds == null || speeds.size != maps.size) {
                throw CoursePolicyException("A speedrun rate is required for every map.")
            }
        } else if (speeds != null) {
            throw CoursePolicyException("Per-map speeds are only valid in a speedrun course.")
        }

        for (index in maps.indices) {
            effectiveMods(
                definition = definition,
                map = maps[index],
                additional = additional,
                speed = speeds?.get(index)
            )
        }
    }

    /**
     * Validates an authored [APIMod] against the mod's real [ModSetting] metadata.
     *
     * The raw JSON is checked before the mod settings are applied, so unsupported keys, wrong JSON
     * types, out-of-range values, non-step-aligned numbers, and invalid enum ordinals are reported
     * instead of being silently clamped or dropped.
     */
    @JvmStatic
    fun validateAPIMod(apiMod: APIMod): Mod {
        val modClass = allModsClassesByAcronym[apiMod.acronym]
            ?: throw CoursePolicyException("Unknown mod acronym: ${apiMod.acronym}")

        val metadataMod = try {
            modClass.createInstance()
        } catch (e: Exception) {
            throw CoursePolicyException("Could not inspect mod ${apiMod.acronym}.", e)
        }

        if (!metadataMod.isUserPlayable) {
            throw CoursePolicyException("Mod ${apiMod.acronym} is not user-playable.")
        }

        apiMod.settings?.let { validateSettings(apiMod.acronym, metadataMod, it) }

        val configured = apiMod.toMod()
            ?: throw CoursePolicyException("Unknown mod acronym: ${apiMod.acronym}")

        apiMod.settings?.let { validateAppliedSettings(apiMod.acronym, configured, it) }

        return configured
    }

    /**
     * Builds and validates the authored mods of one map, including compatibility and the course's
     * fail-controller and ScoreV2 policy.
     */
    internal fun buildAuthoredMods(map: CourseMap): ModHashMap {
        val result = ModHashMap()

        for (apiMod in map.mods) {
            addWithoutEviction(result, validateAPIMod(apiMod), "map ${map.mapHash}")
        }

        return result
    }

    /**
     * Validates a map's authored mods in the context of a definition.
     */
    internal fun validateAuthoredMods(definition: CourseDefinition, map: CourseMap) {
        val mods = buildAuthoredMods(map)

        if (definition.isSpeedrun && mods.values.any { it is ModTimeRamp }) {
            throw CoursePolicyException(
                "Map ${map.mapHash} uses a variable-rate mod, which is not supported in speedrun courses."
            )
        }

        if (definition.isSpeedrun && mods.values.count { it is ModRateAdjust } > 1) {
            throw CoursePolicyException(
                "Map ${map.mapHash} may have at most one authored static rate mod " +
                    "(DT, HT, NC, or CS) in a speedrun course."
            )
        }

        if (definition.scoring == "scorev1" && mods.values.any { it is ModScoreV2 }) {
            throw CoursePolicyException(
                "Map ${map.mapHash} uses ScoreV2 while the course scoring is scorev1."
            )
        }

        validateFailControllers(definition, mods, "map ${map.mapHash}")
    }

    private fun addAdditionalMod(target: ModHashMap, definition: CourseDefinition, mod: Mod) {
        if (!mod.isUserPlayable) {
            throw CoursePolicyException("Mod ${mod.acronym} is not user-playable.")
        }

        if (mod.acronym !in definition.allowedMods) {
            throw CoursePolicyException("Mod ${mod.acronym} is not in the course's allowed mods.")
        }

        if (definition.isSpeedrun && (mod is ModRateAdjust || mod is ModTimeRamp)) {
            throw CoursePolicyException(
                "Mod ${mod.acronym} cannot be added in a speedrun course; use the speed control instead."
            )
        }

        if (mod is ModScoreV2 && definition.scoring != "scorev2") {
            throw CoursePolicyException("ScoreV2 cannot be added unless the course scoring is scorev2.")
        }

        addWithoutEviction(target, mod, "additional mods")
    }

    private fun applySpeedrunRate(definition: CourseDefinition, mods: ModHashMap, speed: Float?) {
        if (!definition.isSpeedrun) {
            return
        }

        val authoredRateMods = mods.values.filterIsInstance<ModRateAdjust>()

        if (authoredRateMods.size > 1) {
            throw CoursePolicyException(
                "A speedrun map may have at most one authored static rate mod (DT, HT, NC, or CS)."
            )
        }

        if (speed == null) {
            return
        }

        validateSpeed(speed)

        if (authoredRateMods.isNotEmpty()) {
            // Keep the authored mod class so its pitch behavior is preserved, but replace its rate
            // instead of multiplying it with a second rate mod.
            authoredRateMods[0].trackRateMultiplier = speed
        } else if (speed != 1f) {
            addWithoutEviction(mods, ModCustomSpeed(speed), "speedrun rate")
        }
    }

    private fun applyScoringPolicy(definition: CourseDefinition, mods: ModHashMap) {
        when (definition.scoring) {
            "scorev1" -> {
                if (mods.values.any { it is ModScoreV2 }) {
                    throw CoursePolicyException("ScoreV2 conflicts with the course's scorev1 scoring.")
                }
            }

            "scorev2" -> {
                if (mods.values.none { it is ModScoreV2 }) {
                    // The internal effective ScoreV2 choice is not an authored or player mod; it is
                    // recorded as part of the effective mods so the engine and result snapshot agree.
                    addWithoutEviction(mods, ModScoreV2(), "scorev2 scoring")
                }
            }
        }
    }

    private fun validateEffectiveRules(definition: CourseDefinition, mods: ModHashMap) {
        validateFailControllers(definition, mods, "effective mods")

        if (definition.isSpeedrun && mods.values.any { it is ModTimeRamp }) {
            throw CoursePolicyException("Variable-rate mods are not supported in speedrun courses.")
        }
    }

    private fun validateFailControllers(definition: CourseDefinition, mods: ModHashMap, where: String) {
        val hasNoFail = mods.values.any { it is ModNoFail }
        val hasFailOnMiss = mods.values.any { it is ModPerfect || it is ModSuddenDeath }

        if (definition.lifeGauge != "none" && hasNoFail) {
            throw CoursePolicyException("No Fail conflicts with the course's failing gauge ($where).")
        }

        if (definition.lifeGauge == "none" && hasFailOnMiss) {
            throw CoursePolicyException("Perfect and Sudden Death conflict with the course's none gauge ($where).")
        }
    }

    private fun validateSpeed(speed: Float) {
        if (!speed.isFinite()) {
            throw CoursePolicyException("Speedrun rate must be between 0.50 and 3.00.")
        }

        val value = BigDecimal(speed.toString())
        val minimum = BigDecimal("0.50")
        val maximum = BigDecimal("3.00")
        val step = BigDecimal("0.05")

        if (value < minimum || value > maximum) {
            throw CoursePolicyException("Speedrun rate must be between 0.50 and 3.00.")
        }
        if (value.subtract(minimum).remainder(step).compareTo(BigDecimal.ZERO) != 0) {
            throw CoursePolicyException("Speedrun rate must use 0.05 increments.")
        }
    }

    private fun addWithoutEviction(target: ModHashMap, mod: Mod, where: String) {
        if (target[mod::class.java] != null) {
            throw CoursePolicyException("Mod ${mod.acronym} is already present ($where).")
        }

        for (existing in target.values) {
            if (!mod.isCompatibleWith(existing) || !existing.isCompatibleWith(mod)) {
                throw CoursePolicyException(
                    "Mod ${mod.acronym} is incompatible with ${existing.acronym} ($where)."
                )
            }
        }

        // All compatibility checks above are symmetric and complete, so this put cannot evict an
        // existing mod. Calling put keeps the normal ModHashMap invariants.
        target.put(mod)
    }

    private fun validateSettings(acronym: String, mod: Mod, settings: JsonObject) {
        val settingsByKey = mod.settings
            .mapNotNull { setting -> setting.key?.let { it to setting } }
            .toMap()

        for ((key, value) in settings) {
            val setting = settingsByKey[key]
                ?: throw CoursePolicyException("Mod $acronym does not have a setting named '$key'.")

            validateSettingValue(acronym, key, setting, value)
        }
    }

    /**
     * Confirms that applying the settings did not clamp, round, or otherwise change a validated
     * raw value. This also catches settings whose valid range depends on another setting.
     */
    private fun validateAppliedSettings(acronym: String, mod: Mod, settings: JsonObject) {
        val settingsByKey = mod.settings
            .mapNotNull { setting -> setting.key?.let { it to setting } }
            .toMap()

        for ((key, raw) in settings) {
            val setting = settingsByKey[key] ?: continue
            val actual = setting.value

            if (raw is JsonNull) {
                if (actual != null) {
                    throw CoursePolicyException("Mod $acronym setting '$key' was not preserved as null.")
                }
                continue
            }

            val primitive = raw as JsonPrimitive
            val preserved = when (setting) {
                is BooleanModSetting -> actual == primitive.booleanOrNull

                is EnumModSetting<*> -> {
                    val rawOrdinal = primitive.intOrNull
                    (actual as? Enum<*>)?.ordinal == rawOrdinal
                }

                is NumberModSetting<*> -> {
                    val numberSetting = setting as NumberModSetting<*>
                    numberPreserved(actual, primitive, numberSetting.minValue)
                }

                is NullableNumberModSetting<*> -> {
                    val numberSetting = setting as NullableNumberModSetting<*>
                    numberPreserved(actual, primitive, numberSetting.minValue)
                }
                else -> false
            }

            if (!preserved) {
                throw CoursePolicyException(
                    "Mod $acronym setting '$key' would be clamped or rounded by the mod."
                )
            }
        }
    }

    private fun numberPreserved(actual: Any?, primitive: JsonPrimitive, minValue: Any): Boolean {
        if (actual == null) {
            return false
        }

        return when (minValue) {
            is Int -> actual is Int && actual == primitive.content.toIntOrNull()
            is Long -> actual is Long && actual == primitive.content.toLongOrNull()
            is Float -> actual is Float && BigDecimal(actual.toString())
                .compareTo(parseDecimalUnsafe(primitive)) == 0

            is Double -> actual is Double && BigDecimal(actual.toString())
                .compareTo(parseDecimalUnsafe(primitive)) == 0

            else -> false
        }
    }

    private fun parseDecimalUnsafe(primitive: JsonPrimitive): BigDecimal =
        try {
            BigDecimal(primitive.content)
        } catch (e: NumberFormatException) {
            throw CoursePolicyException("Invalid numeric mod setting '${primitive.content}'.", e)
        }

    private fun validateSettingValue(
        acronym: String,
        key: String,
        setting: ModSetting<*>,
        value: kotlinx.serialization.json.JsonElement
    ) {
        val nullable = setting is NullableRangeConstrainedModSetting<*>

        if (value is JsonNull) {
            if (!nullable) {
                throw CoursePolicyException("Mod $acronym setting '$key' cannot be null.")
            }
            return
        }

        val primitive = value as? JsonPrimitive
            ?: throw CoursePolicyException("Mod $acronym setting '$key' must be a JSON primitive.")

        if (primitive.isString) {
            throw CoursePolicyException("Mod $acronym setting '$key' must not be a string.")
        }

        when (setting) {
            is BooleanModSetting -> {
                if (primitive.booleanOrNull == null) {
                    throw CoursePolicyException("Mod $acronym setting '$key' must be a boolean.")
                }
            }

            is EnumModSetting<*> -> {
                val enumSetting = setting as EnumModSetting<*>
                val ordinal = primitive.intOrNull
                    ?: throw CoursePolicyException("Mod $acronym setting '$key' must be an integer ordinal.")
                if (ordinal !in enumSetting.entries.indices) {
                    throw CoursePolicyException(
                        "Mod $acronym setting '$key' has invalid enum ordinal $ordinal."
                    )
                }
            }

            is NumberModSetting<*> -> {
                val numberSetting = setting as NumberModSetting<*>
                validateNumberSetting(
                    acronym, key, primitive, numberSetting.minValue, numberSetting.maxValue, numberSetting.step,
                    (numberSetting as? IModSettingWithPrecision)?.precision
                )
            }

            is NullableNumberModSetting<*> -> {
                val numberSetting = setting as NullableNumberModSetting<*>
                validateNumberSetting(
                    acronym, key, primitive, numberSetting.minValue, numberSetting.maxValue, numberSetting.step,
                    (numberSetting as? IModSettingWithPrecision)?.precision
                )
            }

            else -> throw CoursePolicyException(
                "Mod $acronym setting '$key' uses an unsupported setting type."
            )
        }
    }

    private fun validateNumberSetting(
        acronym: String,
        key: String,
        primitive: JsonPrimitive,
        minValue: Any,
        maxValue: Any,
        step: Any,
        precision: Int?
    ) {
        when (minValue) {
            is Int -> {
                val value = primitive.content.toIntOrNull()
                    ?: throw CoursePolicyException("Mod $acronym setting '$key' must be an integer.")
                val min = minValue
                val max = maxValue as Int
                val stepValue = step as Int

                if (value < min || value > max) {
                    throw CoursePolicyException(
                        "Mod $acronym setting '$key' is outside the supported range $min..$max."
                    )
                }
                if (stepValue > 0 && (value - min) % stepValue != 0) {
                    throw CoursePolicyException(
                        "Mod $acronym setting '$key' must use $stepValue increments."
                    )
                }
            }

            is Long -> {
                val value = primitive.content.toLongOrNull()
                    ?: throw CoursePolicyException("Mod $acronym setting '$key' must be an integer.")
                val min = minValue
                val max = maxValue as Long
                val stepValue = step as Long

                if (value < min || value > max) {
                    throw CoursePolicyException(
                        "Mod $acronym setting '$key' is outside the supported range $min..$max."
                    )
                }
                if (stepValue > 0L && (value - min) % stepValue != 0L) {
                    throw CoursePolicyException(
                        "Mod $acronym setting '$key' must use $stepValue increments."
                    )
                }
            }

            is Float -> {
                val value = parseDecimal(acronym, key, primitive)
                val min = BigDecimal(minValue.toString())
                val max = BigDecimal((maxValue as Float).toString())
                val stepValue = BigDecimal((step as Float).toString())

                if (value < min || value > max) {
                    throw CoursePolicyException(
                        "Mod $acronym setting '$key' is outside the supported range $minValue..$maxValue."
                    )
                }
                if (stepValue.signum() != 0 && value.subtract(min).remainder(stepValue).compareTo(BigDecimal.ZERO) != 0) {
                    throw CoursePolicyException(
                        "Mod $acronym setting '$key' must use $stepValue increments."
                    )
                }
                if (precision != null && value.stripTrailingZeros().scale() > precision) {
                    throw CoursePolicyException(
                        "Mod $acronym setting '$key' has more than $precision decimal places."
                    )
                }
            }

            is Double -> {
                val value = parseDecimal(acronym, key, primitive)
                val min = BigDecimal(minValue.toString())
                val max = BigDecimal((maxValue as Double).toString())
                val stepValue = BigDecimal((step as Double).toString())

                if (value < min || value > max) {
                    throw CoursePolicyException(
                        "Mod $acronym setting '$key' is outside the supported range $minValue..$maxValue."
                    )
                }
                if (stepValue.signum() != 0 && value.subtract(min).remainder(stepValue).compareTo(BigDecimal.ZERO) != 0) {
                    throw CoursePolicyException(
                        "Mod $acronym setting '$key' must use $stepValue increments."
                    )
                }
                if (precision != null && value.stripTrailingZeros().scale() > precision) {
                    throw CoursePolicyException(
                        "Mod $acronym setting '$key' has more than $precision decimal places."
                    )
                }
            }

            else -> throw CoursePolicyException(
                "Mod $acronym setting '$key' uses an unsupported numeric type."
            )
        }
    }

    private fun parseDecimal(
        acronym: String,
        key: String,
        primitive: JsonPrimitive
    ): BigDecimal = try {
        BigDecimal(primitive.content)
    } catch (e: NumberFormatException) {
        throw CoursePolicyException("Mod $acronym setting '$key' must be a finite number.", e)
    }
}

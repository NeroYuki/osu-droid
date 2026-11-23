package com.osudroid.skillcourse.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.edlplan.ui.fragment.BaseFragment
import com.osudroid.data.SkillCourseEntity
import com.osudroid.mods.APIMod
import com.osudroid.mods.ModRateAdjust
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseMap
import com.osudroid.skillcourse.CourseModPolicy
import com.osudroid.skillcourse.CoursePlayPreferences
import com.osudroid.skillcourse.CourseSpeed
import com.osudroid.skillcourse.recovery.CourseAvailabilityCache
import com.osudroid.skillcourse.recovery.CourseAvailabilityListener
import com.osudroid.skillcourse.recovery.CourseAvailabilitySnapshot
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.deserializeMods
import com.osudroid.utils.mainThread
import kotlin.math.roundToInt
import ru.nsu.ccfit.zuev.osuplus.R

/**
 * Local preflight for a full course run.
 *
 * It observes cached availability, keeps authored required mods locked, exposes the course-wide optional
 * whitelist per map through the native mod selector, owns the per-map speedrun rate control,
 * validates every effective map through [CourseModPolicy], and only then delivers a frozen play
 * request.
 */
class CoursePreflightFragment : BaseFragment() {

    private var entity: SkillCourseEntity? = null
    private var definition: CourseDefinition? = null

    private var snapshot: CourseAvailabilitySnapshot? = null
    private var scanning = false
    private var scanFailed = false
    private val availabilityListener = CourseAvailabilityListener { hash ->
        if (!cleanedUp && isAdded && (hash == null || hash == entity?.courseHash)) {
            readAvailability()
        }
    }
    private var definitionGeneration = 0
    private var selectionGeneration = 0
    private var cleanedUp = false

    private val mapAdditionalMods = mutableMapOf<Int, ModHashMap>()
    private val speeds = mutableMapOf<Int, Int>()
    private val effectiveMods = mutableMapOf<Int, ModHashMap>()
    private val invalidSpeedIndices = mutableSetOf<Int>()
    private val storageErrors = mutableMapOf<String, String>()
    private val mapRows = mutableListOf<MapRow>()

    private var policyError: String? = null
    private var notice: String? = null
    private var suppressSpeedText = false

    private var status: TextView? = null
    private var summary: TextView? = null
    private var mapsContainer: LinearLayout? = null
    private var errorView: TextView? = null
    private var startButton: Button? = null

    init {
        isDismissOnBackgroundClick = false
        isDismissOnBackPress = true
    }

    override val layoutID: Int
        get() = R.layout.skill_course_preflight_fragment

    fun show(entity: SkillCourseEntity, definition: CourseDefinition) {
        this.entity = entity
        this.definition = definition
        this.snapshot = null
        this.scanning = false
        this.scanFailed = false
        this.policyError = null
        this.notice = null
        this.definitionGeneration++
        this.selectionGeneration = 0
        this.cleanedUp = false
        this.mapAdditionalMods.clear()
        this.effectiveMods.clear()
        this.speeds.clear()
        this.invalidSpeedIndices.clear()
        this.storageErrors.clear()
        this.mapRows.clear()

        for (index in definition.maps.indices) {
            mapAdditionalMods[index] = ModHashMap()
        }
        loadPreferences(definition)
        show()
    }

    override fun onLoadView() {
        cleanedUp = false
        CourseUi.attachPreflight(this)

        val body = findViewById<View>(R.id.frg_body)
        body?.layoutParams = body?.layoutParams?.also {
            it.width = CourseUi.boundedWidth(requireContext(), 680f)
        }

        status = findViewById(R.id.skill_course_preflight_status)
        summary = findViewById(R.id.skill_course_preflight_summary)
        mapsContainer = findViewById(R.id.skill_course_preflight_maps)
        errorView = findViewById(R.id.skill_course_preflight_error)
        startButton = findViewById(R.id.skill_course_preflight_start)

        findViewById<View>(R.id.skill_course_preflight_close)?.setOnClickListener { dismiss() }
        findViewById<View>(R.id.skill_course_preflight_cancel)?.setOnClickListener { dismiss() }
        startButton?.setOnClickListener { startCourse() }

        renderMaps()
        recomputeEffectiveMods()
        updateStartState()

        applyImeBottomPadding()
        CourseAvailabilityCache.addListener(availabilityListener)
        readAvailability()
    }

    /**
     * The per-map rate input can open the IME. Shrink the dialog by the IME inset while the
     * keyboard is open so Start stays reachable instead of being covered by the host window's pan
     * mode.
     */
    private fun applyImeBottomPadding() {
        val root = findViewById<View>(R.id.frg_background) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val chromeVisibility = if (imeBottom > 0) View.GONE else View.VISIBLE
            findViewById<View>(R.id.skill_course_preflight_header)?.visibility = chromeVisibility
            status?.visibility = chromeVisibility
            summary?.visibility = chromeVisibility
            if (view.paddingBottom != imeBottom) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, imeBottom)
                if (imeBottom > 0) {
                    view.post {
                        view.findFocus()?.let { focused ->
                            focused.requestRectangleOnScreen(
                                android.graphics.Rect(0, 0, focused.width, focused.height), false
                            )
                        }
                    }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun readAvailability() {
        val entity = entity ?: return
        val state = CourseAvailabilityCache.get(entity.courseHash)
        scanning = state.isChecking
        scanFailed = state.failure != null
        snapshot = state.snapshot
        updateSummary()
        updateStartState()
    }

    private fun updateSummary() {
        val definition = definition ?: return
        val snapshot = snapshot

        summary?.text = getString(
            R.string.skill_course_preflight_summary,
            definition.courseName,
            definition.maps.size
        )
        status?.text = when {
            snapshot == null && scanFailed -> getString(R.string.skill_course_check_failed)
            snapshot == null -> getString(R.string.skill_course_preflight_checking)
            snapshot.allReady -> getString(R.string.skill_course_preflight_ready)
            else -> getString(R.string.skill_course_preflight_unresolved)
        }
    }

    private fun renderMaps() {
        val container = mapsContainer ?: return
        val definition = definition ?: return
        container.removeAllViews()
        mapRows.clear()

        val inflater = android.view.LayoutInflater.from(requireContext())
        for ((index, map) in definition.maps.withIndex()) {
            val view = inflater.inflate(R.layout.skill_course_preflight_map_item, container, false)
            val title = view.findViewById<TextView>(R.id.skill_course_preflight_map_title)
            val meta = view.findViewById<TextView>(R.id.skill_course_preflight_map_meta)
            val required = view.findViewById<TextView>(R.id.skill_course_preflight_map_required)
            val selected = view.findViewById<TextView>(R.id.skill_course_preflight_map_selected)
            val effective = view.findViewById<TextView>(R.id.skill_course_preflight_map_effective)
            val selectMods = view.findViewById<Button>(R.id.skill_course_preflight_map_select_mods)
            val speedRow = view.findViewById<View>(R.id.skill_course_preflight_map_speed_row)
            val speedMinus = view.findViewById<Button>(R.id.skill_course_preflight_map_speed_minus)
            val speedInput = view.findViewById<EditText>(R.id.skill_course_preflight_map_speed_input)
            val speedPlus = view.findViewById<Button>(R.id.skill_course_preflight_map_speed_plus)
            val speedError = view.findViewById<TextView>(R.id.skill_course_preflight_map_speed_error)

            title.text = getString(R.string.skill_course_preflight_speed_map, index + 1, shortMapName(map))
            title.contentDescription = title.text
            meta.text = getString(
                R.string.skill_course_preflight_map_meta,
                map.mapper.ifBlank { getString(R.string.skill_course_preflight_none) },
                map.objectCounts.sum()
            )
            required.text = getString(
                R.string.skill_course_preflight_map_required,
                authoredSummary(map)
            )

            selectMods.isEnabled = definition.allowedMods.isNotEmpty()
            selectMods.alpha = if (selectMods.isEnabled) 1f else 0.5f
            selectMods.contentDescription =
                "${getString(R.string.skill_course_preflight_select_mods)}: ${shortMapName(map)}"
            selectMods.setOnClickListener { openPlayerModSelector(index) }

            val row = MapRow(
                index = index,
                map = map,
                selectedView = selected,
                effectiveView = effective,
                selectButton = selectMods,
                speedMinus = speedMinus,
                speedInput = speedInput,
                speedPlus = speedPlus,
                speedErrorView = speedError
            )

            if (definition.isSpeedrun) {
                speedRow.visibility = View.VISIBLE
                val initial = speeds[index] ?: defaultSpeedHundredths(map)
                speeds[index] = initial
                suppressSpeedText = true
                speedInput.setText(CourseSpeed.format(initial))
                speedInput.setSelection(speedInput.text?.length ?: 0)
                suppressSpeedText = false
                speedInput.imeOptions = EditorInfo.IME_ACTION_DONE or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI
                speedInput.contentDescription = getString(
                    R.string.skill_course_preflight_speed_input,
                    index + 1,
                    shortMapName(map)
                )

                speedInput.doAfterTextChanged { editable ->
                    if (suppressSpeedText || cleanedUp) {
                        return@doAfterTextChanged
                    }
                    onSpeedTextChanged(row, editable?.toString().orEmpty())
                }
                speedInput.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        commitSpeedText(row)
                        ViewCompat.getWindowInsetsController(speedInput)
                            ?.hide(WindowInsetsCompat.Type.ime())
                        true
                    } else {
                        false
                    }
                }
                speedInput.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus && !cleanedUp) {
                        commitSpeedText(row)
                    }
                }
                speedMinus.setOnClickListener { stepSpeed(row, -CourseSpeed.STEP) }
                speedPlus.setOnClickListener { stepSpeed(row, CourseSpeed.STEP) }
                updateSpeedControls(row)
            } else {
                speedRow.visibility = View.GONE
            }

            mapRows += row
            updateMapRow(row)
            container.addView(view)
        }
    }

    private fun updateMapRow(row: MapRow) {
        val extras = mapAdditionalMods[row.index] ?: ModHashMap()
        row.selectedView.text = getString(
            R.string.skill_course_preflight_map_selected,
            selectedSummary(extras)
        )
        val effective = effectiveMods[row.index]
        val effectiveText = when {
            effective == null -> getString(R.string.skill_course_preflight_effective_invalid)
            effective.isEmpty() -> getString(R.string.skill_course_preflight_none)
            else -> effective.toDisplayModString(includeNonUserPlayable = true)
        }
        row.effectiveView.text = getString(
            R.string.skill_course_preflight_map_effective,
            effectiveText
        )
        row.selectButton.isEnabled = definition?.allowedMods?.isNotEmpty() == true && !cleanedUp
        row.selectButton.alpha = if (row.selectButton.isEnabled) 1f else 0.5f
        row.speedErrorView.visibility =
            if (row.index in invalidSpeedIndices) View.VISIBLE else View.GONE
        row.speedErrorView.text = getString(
            R.string.skill_course_preflight_speed_invalid_for,
            row.index + 1,
            shortMapName(row.map)
        )
    }

    private fun updateSpeedControls(row: MapRow) {
        val resolved = CourseSpeed.normalize(row.speedInput.text?.toString().orEmpty())
            ?: speeds[row.index]
            ?: defaultSpeedHundredths(row.map)
        row.speedMinus.isEnabled = resolved > CourseSpeed.MIN_HUNDREDTHS
        row.speedPlus.isEnabled = resolved < CourseSpeed.MAX_HUNDREDTHS
        row.speedMinus.alpha = if (row.speedMinus.isEnabled) 1f else 0.5f
        row.speedPlus.alpha = if (row.speedPlus.isEnabled) 1f else 0.5f
    }

    private fun onSpeedTextChanged(row: MapRow, raw: String) {
        val normalized = CourseSpeed.normalize(raw)
        if (normalized == null) {
            invalidSpeedIndices.add(row.index)
        } else {
            invalidSpeedIndices.remove(row.index)
        }
        updateSpeedControls(row)
        recomputeEffectiveMods()
        updateStartState()
    }

    private fun commitSpeedText(row: MapRow) {
        val normalized = CourseSpeed.normalize(row.speedInput.text?.toString().orEmpty())
        if (normalized == null) {
            invalidSpeedIndices.add(row.index)
            updateMapRow(row)
            updateStartState()
            refreshError()
            return
        }
        applySpeed(row, normalized, persist = true)
    }

    private fun stepSpeed(row: MapRow, delta: Int) {
        val textValue = CourseSpeed.normalize(row.speedInput.text?.toString().orEmpty())
        val base = textValue ?: speeds[row.index] ?: defaultSpeedHundredths(row.map)
        val next = (base + delta).coerceIn(
            CourseSpeed.MIN_HUNDREDTHS,
            CourseSpeed.MAX_HUNDREDTHS
        )
        // At a boundary a step cannot move the value, but a non-canonical typed value still gets
        // normalized and committed like any other accepted step.
        applySpeed(row, next, persist = true)
    }

    private fun applySpeed(row: MapRow, hundredths: Int, persist: Boolean) {
        val normalized = hundredths.coerceIn(
            CourseSpeed.MIN_HUNDREDTHS,
            CourseSpeed.MAX_HUNDREDTHS
        )
        speeds[row.index] = normalized
        invalidSpeedIndices.remove(row.index)
        val text = CourseSpeed.format(normalized)
        if (row.speedInput.text?.toString() != text) {
            suppressSpeedText = true
            row.speedInput.setText(text)
            row.speedInput.setSelection(row.speedInput.text?.length ?: 0)
            suppressSpeedText = false
        }
        if (persist) {
            persistSpeed(row.index, normalized)
        }
        updateSpeedControls(row)
        recomputeEffectiveMods()
        updateStartState()
    }

    private fun openPlayerModSelector(index: Int) {
        val definition = definition ?: return
        val map = definition.maps.getOrNull(index) ?: return
        if (definition.allowedMods.isEmpty()) {
            setNotice(getString(R.string.skill_course_preflight_no_allowed_mods))
            return
        }
        if (definition.isSpeedrun) {
            mapRows.firstOrNull { it.index == index }?.let { commitSpeedText(it) }
            if (index in invalidSpeedIndices) return
        }

        val expectedGeneration = ++selectionGeneration
        val expectedDefinitionGeneration = definitionGeneration
        val initial = (mapAdditionalMods[index] ?: ModHashMap()).deepCopy()
        CourseModSelection.choosePlayerMods(
            this,
            definition,
            map,
            initial,
            speedForPolicy(index),
            onSave = { selected ->
            mainThread {
                if (cleanedUp || !isAdded || expectedGeneration != selectionGeneration ||
                    expectedDefinitionGeneration != definitionGeneration
                ) {
                    return@mainThread
                }
                val currentDefinition = this.definition ?: return@mainThread
                if (currentDefinition !== definition || currentDefinition.maps.getOrNull(index) != map) {
                    // The course was replaced while the native selector owned the window.
                    return@mainThread
                }
                applyPlayerMods(index, map, selected)
            }
            },
            onError = { message -> context?.let { CourseUi.showMessage(it, message) } }
        )
    }

    private fun applyPlayerMods(index: Int, map: CourseMap, selected: ModHashMap) {
        val definition = definition ?: return
        val reconciled = CoursePreflightSelection.reconcile(
            definition,
            map,
            selected,
            speedForPolicy(index)
        )

        if (reconciled.baseError != null) {
            setNotice(
                getString(
                    R.string.skill_course_preflight_map_policy_failed,
                    index + 1,
                    shortMapName(map),
                    reconciled.baseError
                )
            )
            return
        }

        if (reconciled.dropped.isNotEmpty()) {
            setNotice(
                getString(
                    R.string.skill_course_preflight_selector_mods_discarded,
                    index + 1,
                    shortMapName(map),
                    droppedSummary(reconciled.dropped)
                )
            )
        }

        mapAdditionalMods[index] = reconciled.mods
        persistMods(index, reconciled.mods)
        recomputeEffectiveMods()
        updateStartState()
    }

    private fun recomputeEffectiveMods() {
        val definition = definition ?: return
        effectiveMods.clear()
        policyError = null

        for ((index, map) in definition.maps.withIndex()) {
            val extras = mapAdditionalMods[index] ?: ModHashMap()
            try {
                effectiveMods[index] = CourseModPolicy.effectiveMods(
                    definition,
                    map,
                    extras,
                    speedForPolicy(index)
                )
            } catch (e: Exception) {
                if (policyError == null) {
                    policyError = getString(
                        R.string.skill_course_preflight_map_policy_failed,
                        index + 1,
                        shortMapName(map),
                        messageOf(e)
                    )
                }
            }
        }

        for (row in mapRows) {
            updateMapRow(row)
        }
        refreshError()
    }

    private fun updateStartState() {
        val definition = definition ?: return
        val ready = snapshot?.allReady == true &&
            definition.maps.isNotEmpty() &&
            !scanning &&
            invalidSpeedIndices.isEmpty() &&
            policyError == null
        startButton?.isEnabled = ready
        startButton?.alpha = if (ready) 1f else 0.5f
        updateSummary()
    }

    private fun startCourse() {
        val definition = definition ?: return
        val entity = entity ?: return

        if (snapshot?.allReady != true || definition.maps.isEmpty()) {
            showError(getString(R.string.skill_course_preflight_unresolved))
            return
        }

        // Round any typed value to the nearest hundredth step, clamp it, and commit it before the
        // frozen request is built. Invalid text never overwrites the stored valid value.
        var speedInvalid = false
        if (definition.isSpeedrun) {
            for (row in mapRows) {
                val normalized = CourseSpeed.normalize(row.speedInput.text?.toString().orEmpty())
                if (normalized == null) {
                    invalidSpeedIndices.add(row.index)
                    speedInvalid = true
                    continue
                }
                invalidSpeedIndices.remove(row.index)
                if (speeds[row.index] != normalized) {
                    speeds[row.index] = normalized
                    persistSpeed(row.index, normalized)
                }
                val text = CourseSpeed.format(normalized)
                if (row.speedInput.text?.toString() != text) {
                    suppressSpeedText = true
                    row.speedInput.setText(text)
                    row.speedInput.setSelection(row.speedInput.text?.length ?: 0)
                    suppressSpeedText = false
                }
                updateSpeedControls(row)
            }
        }

        if (speedInvalid || invalidSpeedIndices.isNotEmpty()) {
            for (row in mapRows) {
                updateMapRow(row)
            }
            refreshError()
            updateStartState()
            return
        }

        recomputeEffectiveMods()
        if (policyError != null) {
            refreshError()
            updateStartState()
            return
        }

        val frozenSpeeds = HashMap<Int, Float>()
        val frozenAdditional = HashMap<Int, ModHashMap>()
        for ((index, map) in definition.maps.withIndex()) {
            val extras = (mapAdditionalMods[index] ?: ModHashMap()).deepCopy()
            frozenAdditional[index] = extras
            val rate = if (definition.isSpeedrun) {
                CourseSpeed.rate(speeds[index] ?: defaultSpeedHundredths(map))
            } else {
                null
            }
            if (rate != null) {
                frozenSpeeds[index] = rate
            }

            try {
                // Final per-map validation against the exact frozen choices.
                CourseModPolicy.effectiveMods(definition, map, extras, rate)
            } catch (e: Exception) {
                showError(getString(R.string.skill_course_preflight_failed, messageOf(e)))
                updateStartState()
                return
            }
        }

        CourseUi.requestPlay(
            CoursePlayRequest(
                entity = entity,
                definition = definition,
                // Per-map choices are authoritative; the legacy global argument stays empty.
                additionalMods = ModHashMap(),
                speeds = frozenSpeeds,
                additionalModsByIndex = frozenAdditional
            )
        )
    }

    private fun loadPreferences(definition: CourseDefinition) {
        val entity = entity ?: return
        val loaded = definition.maps.mapIndexed { index, map ->
            try {
                CoursePlayPreferences.get(entity.id, map.mapHash)
            } catch (e: Exception) {
                recordStorageError(
                    "load:$index",
                    getString(
                        R.string.skill_course_preflight_preferences_load_failed,
                        messageOf(e)
                    )
                )
                null
            }
        }

        if (definition.isSpeedrun) {
            for ((index, map) in definition.maps.withIndex()) {
                val saved = loaded[index]?.speedHundredths
                if (saved == null) {
                    speeds[index] = defaultSpeedHundredths(map)
                    continue
                }

                val normalized = normalizeStoredSpeed(saved)
                speeds[index] = normalized
                if (normalized != saved) {
                    // The old stored value was not a valid hundredth step; normalize it once.
                    persistSpeed(index, normalized)
                }
            }
        }

        for ((index, map) in definition.maps.withIndex()) {
            val raw = loaded[index]?.additionalModsJson
            if (raw.isNullOrBlank()) {
                mapAdditionalMods[index] = ModHashMap()
                continue
            }

            val savedMods = try {
                deserializeMods(raw)
            } catch (e: Exception) {
                mapAdditionalMods[index] = ModHashMap()
                appendNotice(
                    getString(
                        R.string.skill_course_preflight_saved_mods_unreadable,
                        index + 1,
                        shortMapName(map)
                    )
                )
                persistMods(index, ModHashMap())
                continue
            }

            val reconciled = CoursePreflightSelection.reconcile(
                definition,
                map,
                savedMods,
                speedForPolicy(index)
            )

            if (reconciled.baseError != null) {
                mapAdditionalMods[index] = ModHashMap()
                if (policyError == null) {
                    policyError = getString(
                        R.string.skill_course_preflight_map_policy_failed,
                        index + 1,
                        shortMapName(map),
                        reconciled.baseError
                    )
                }
                continue
            }

            mapAdditionalMods[index] = reconciled.mods
            if (reconciled.dropped.isNotEmpty()) {
                appendNotice(
                    getString(
                        R.string.skill_course_preflight_saved_mods_discarded,
                        index + 1,
                        shortMapName(map),
                        droppedSummary(reconciled.dropped)
                    )
                )
                // Keep the valid subset instead of resetting every saved choice, but show what
                // changed so the current choice is never silently altered.
                persistMods(index, reconciled.mods)
            }
        }
    }

    private fun normalizeStoredSpeed(value: Int): Int {
        val text = try {
            CourseSpeed.format(value)
        } catch (_: Exception) {
            return 100
        }
        return CourseSpeed.normalize(text) ?: 100
    }

    private fun persistSpeed(index: Int, hundredths: Int) {
        val entity = entity ?: return
        val map = definition?.maps?.getOrNull(index) ?: return
        val key = "speed:$index"
        try {
            CoursePlayPreferences.saveSpeed(entity.id, map.mapHash, hundredths)
            storageErrors.remove(key)
            storageErrors.remove("load:$index")
        } catch (e: Exception) {
            recordStorageError(
                key,
                getString(R.string.skill_course_preflight_speed_save_failed, messageOf(e))
            )
        }
        refreshError()
    }

    private fun persistMods(index: Int, mods: ModHashMap) {
        val entity = entity ?: return
        val map = definition?.maps?.getOrNull(index) ?: return
        val key = "mods:$index"
        try {
            val speed = speeds[index].takeIf { definition?.isSpeedrun == true }
            CoursePlayPreferences.saveMods(entity.id, map.mapHash, mods.deepCopy(), speed)
            storageErrors.remove(key)
            storageErrors.remove("load:$index")
        } catch (e: Exception) {
            recordStorageError(
                key,
                getString(R.string.skill_course_preflight_mods_save_failed, messageOf(e))
            )
        }
        refreshError()
    }

    private fun recordStorageError(key: String, message: String) {
        storageErrors[key] = message
    }

    private fun appendNotice(message: String) {
        notice = if (notice.isNullOrBlank()) message else notice + "\n" + message
    }

    private fun setNotice(message: String) {
        notice = message
        refreshError()
    }

    private fun refreshError() {
        val speedError = if (definition?.isSpeedrun == true) {
            invalidSpeedIndices.firstOrNull()?.let { index ->
                val map = definition?.maps?.getOrNull(index)
                if (map == null) {
                    getString(R.string.skill_course_preflight_speed_invalid)
                } else {
                    getString(
                        R.string.skill_course_preflight_speed_invalid_for,
                        index + 1,
                        shortMapName(map)
                    )
                }
            }
        } else {
            null
        }
        val message = speedError
            ?: policyError
            ?: storageErrors.values.firstOrNull()
            ?: notice
        showError(message)
    }

    private fun speedForPolicy(index: Int): Float? {
        val definition = definition ?: return null
        if (!definition.isSpeedrun) {
            return null
        }
        val row = mapRows.firstOrNull { it.index == index }
        val resolved = row?.let {
            CourseSpeed.normalize(it.speedInput.text?.toString().orEmpty())
        } ?: speeds[index] ?: defaultSpeedHundredths(definition.maps[index])
        return CourseSpeed.rate(resolved)
    }

    private fun defaultSpeedHundredths(map: CourseMap): Int {
        for (apiMod in map.mods) {
            val mod = try {
                apiMod.toMod()
            } catch (_: Exception) {
                null
            }
            if (mod is ModRateAdjust) {
                val rate = mod.trackRateMultiplier
                if (rate.isFinite()) {
                    val hundredths = (rate * 100f).roundToInt().coerceIn(
                        CourseSpeed.MIN_HUNDREDTHS,
                        CourseSpeed.MAX_HUNDREDTHS
                    )
                    return CourseSpeed.normalize(CourseSpeed.format(hundredths)) ?: hundredths
                }
            }
        }
        return 100
    }

    private fun shortMapName(map: CourseMap): String {
        val artist = map.artistUnicode.takeUnless { it.isBlank() } ?: map.artist
        val title = map.titleUnicode.takeUnless { it.isBlank() } ?: map.title
        return "$artist - $title [${map.difficulty}]"
    }

    private fun authoredSummary(map: CourseMap): String =
        if (map.mods.isEmpty()) {
            getString(R.string.skill_course_preflight_none)
        } else {
            map.mods.joinToString(", ") { apiModToDisplay(it) }
        }

    private fun selectedSummary(mods: ModHashMap): String =
        if (mods.isEmpty()) {
            getString(R.string.skill_course_preflight_none)
        } else {
            mods.values.sortedBy { it.acronym }.joinToString(", ") { it.toString() }
        }

    private fun droppedSummary(dropped: List<CoursePreflightSelection.DroppedMod>): String =
        dropped.joinToString("; ") { "${it.acronym}: ${it.reason}" }

    private fun apiModToDisplay(apiMod: APIMod): String {
        return try {
            apiMod.toMod()?.toString() ?: apiMod.acronym
        } catch (_: Exception) {
            apiMod.acronym
        }
    }

    private fun showError(message: CharSequence?) {
        val view = errorView ?: return
        if (message.isNullOrBlank()) {
            view.visibility = View.GONE
            view.text = ""
        } else {
            view.text = message
            view.visibility = View.VISIBLE
        }
    }

    override fun dismiss() {
        cleanup()
        super.dismiss()
    }

    fun closeImmediately() {
        cleanup()
        super.dismiss()
    }

    override fun onDestroyView() {
        cleanup()
        super.onDestroyView()
    }

    private fun cleanup() {
        if (cleanedUp) {
            return
        }
        cleanedUp = true
        CourseAvailabilityCache.removeListener(availabilityListener)
        definitionGeneration++
        selectionGeneration++
        mapRows.clear()
        CourseUi.detachPreflight(this)
    }

    private fun messageOf(error: Throwable): String =
        error.message?.takeUnless { it.isBlank() } ?: error.javaClass.simpleName

    private data class MapRow(
        val index: Int,
        val map: CourseMap,
        val selectedView: TextView,
        val effectiveView: TextView,
        val selectButton: Button,
        val speedMinus: Button,
        val speedInput: EditText,
        val speedPlus: Button,
        val speedErrorView: TextView
    )
}

/**
 * Pure reconciliation of previously saved optional mods against the current course definition.
 *
 * Authored mods are never returned or removed here; they are rebuilt by [CourseModPolicy] for every
 * candidate. A compatible saved subset is retained rather than rejecting the whole saved choice,
 * and every dropped acronym carries the concrete policy reason so the UI can explain the change.
 */
internal object CoursePreflightSelection {

    data class DroppedMod(val acronym: String, val reason: String)

    data class SelectionResult(
        val mods: ModHashMap,
        val dropped: List<DroppedMod>,
        val baseError: String?
    )

    fun reconcile(
        definition: CourseDefinition,
        map: CourseMap,
        saved: ModHashMap,
        speed: Float?
    ): SelectionResult {
        try {
            // A broken authored map or speed is not an optional-choice problem; report it as a
            // policy failure instead of pretending a saved addition caused it.
            CourseModPolicy.effectiveMods(definition, map, ModHashMap(), speed)
        } catch (e: Exception) {
            return SelectionResult(ModHashMap(), emptyList(), messageOf(e))
        }

        val accepted = ModHashMap()
        val dropped = mutableListOf<DroppedMod>()

        for (candidate in saved.values.sortedBy { it.acronym }) {
            var reason: String? = when {
                accepted.containsKey(candidate::class.java) -> "already selected"
                accepted.values.any {
                    !candidate.isCompatibleWith(it) || !it.isCompatibleWith(candidate)
                } -> "incompatible with another selected mod"
                else -> null
            }

            if (reason == null) {
                val trial = ModHashMap(accepted)
                trial[candidate::class.java] = candidate.deepCopy()
                try {
                    CourseModPolicy.effectiveMods(definition, map, trial, speed)
                    accepted[candidate::class.java] = candidate.deepCopy()
                } catch (e: Exception) {
                    reason = messageOf(e)
                }
            }

            reason?.let {
                dropped += DroppedMod(candidate.acronym, it)
            }
        }

        return SelectionResult(accepted, dropped, null)
    }

    private fun messageOf(error: Throwable): String =
        error.message?.takeUnless { it.isBlank() } ?: error.javaClass.simpleName
}

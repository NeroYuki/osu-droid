package com.osudroid.skillcourse.ui

import android.animation.Animator
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.edlplan.framework.easing.Easing
import com.edlplan.ui.BaseAnimationListener
import com.edlplan.ui.EasingHelper
import com.edlplan.ui.fragment.BaseFragment
import com.osudroid.data.BeatmapInfo
import com.osudroid.data.SkillCourseEntity
import com.osudroid.skillcourse.CourseCodec
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseExscoreSettings
import com.osudroid.skillcourse.CourseGaugeSettings
import com.osudroid.skillcourse.CourseMap
import com.osudroid.skillcourse.CourseRepository
import com.osudroid.skillcourse.*
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.allModsInstances
import com.osudroid.utils.mainThread
import com.reco1l.osu.ui.MessageDialog
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.online.OnlineManager
import ru.nsu.ccfit.zuev.osuplus.R

/**
 * Native create/edit editor for a portable course definition.
 *
 * All rules are edited as typed values, never as raw JSON. Saving validates the complete candidate
 * first; a validation failure leaves the stored definition untouched and keeps the editor open.
 */
class CourseEditorFragment : BaseFragment() {

    private var entity: SkillCourseEntity? = null
    private var baseDefinition: CourseDefinition? = null
    private var definition: CourseDefinition? = null
    private var workingMaps = mutableListOf<CourseMap>()
    private var locked = false
    private var dirty = false
    private var suppressDirty = false
    private var loadError: String? = null
    private var saved = false

    private var nameInput: EditText? = null
    private var authorInput: EditText? = null
    private var gaugeSpinner: Spinner? = null
    private var scoringSpinner: Spinner? = null
    private var totalHpInput: EditText? = null
    private var startingHpInput: EditText? = null
    private var transitionInput: EditText? = null
    private var allowPauseCheck: CheckBox? = null
    private var autoSkipCheck: CheckBox? = null
    private var speedrunCheck: CheckBox? = null
    private var randomOrderCheck: CheckBox? = null
    private var hpRow: View? = null
    private var eventsLabel: TextView? = null
    private var eventsContainer: LinearLayout? = null
    private var eventsHeader: View? = null
    private var eventsHpHeader: TextView? = null
    private var eventsExHeader: TextView? = null
    private var mapsContainer: LinearLayout? = null
    private var mapsEmpty: TextView? = null
    private var mapsLocked: TextView? = null
    private var mapsHint: TextView? = null
    private var allowedSummary: TextView? = null
    private var allowedSelectButton: Button? = null
    private var errorView: TextView? = null
    private var scrollView: ScrollView? = null
    private var saveButton: Button? = null

    private var allowedSelectionGeneration = 0
    private var cleanedUp = false

    private val eventRows = mutableListOf<EventRow>()
    private val selectedAllowed = LinkedHashSet<String>()

    private val gaugeKeys = arrayOf("none", "standard", "global")
    private val scoringKeys = arrayOf("scorev1", "scorev2", "exscore", "accuracy")

    init {
        isDismissOnBackgroundClick = false
        isDismissOnBackPress = false
    }

    override val layoutID: Int
        get() = R.layout.skill_course_editor_fragment

    fun showCreate(selectedBeatmap: BeatmapInfo?, selectedMods: ModHashMap?) {
        this.entity = null
        this.loadError = null
        this.saved = false
        this.dirty = false
        this.suppressDirty = false
        this.cleanedUp = false
        this.allowedSelectionGeneration++
        val author = defaultAuthor()
        val activity = ru.nsu.ccfit.zuev.osu.GlobalManager.getInstance().mainActivity
        val defaultName = activity?.getString(R.string.skill_course_editor_title_create) ?: "New Course"
        val seed = CourseDefinition.emptyDraft(
            courseName = defaultName,
            author = author
        )
        val pending = selectedBeatmap?.let {
            CourseMap.fromBeatmap(it, selectedMods?.deepCopy() ?: ModHashMap())
        }
        baseDefinition = seed
        definition = if (pending == null) seed else seed.copy(maps = listOf(pending))
        workingMaps = definition!!.maps.toMutableList()
        locked = false
        show()
    }

    fun showEdit(entity: SkillCourseEntity) {
        this.entity = entity
        this.locked = entity.locked
        this.loadError = null
        this.saved = false
        this.dirty = false
        this.suppressDirty = false
        this.cleanedUp = false
        this.allowedSelectionGeneration++
        try {
            val decoded = CourseCodec.decode(entity.definitionJson, allowDraft = true)
            baseDefinition = decoded
            definition = decoded
            workingMaps = decoded.maps.toMutableList()
        } catch (e: Exception) {
            loadError = e.message ?: e.javaClass.simpleName
            baseDefinition = CourseDefinition.emptyDraft()
            definition = baseDefinition
            workingMaps = mutableListOf()
        }
        show()
    }

    override fun onLoadView() {
        cleanedUp = false
        val body = findViewById<View>(R.id.frg_body)
        body?.layoutParams = body?.layoutParams?.also {
            it.width = CourseUi.boundedWidth(requireContext(), 720f)
        }

        nameInput = findViewById(R.id.skill_course_editor_name)
        authorInput = findViewById(R.id.skill_course_editor_author)
        gaugeSpinner = findViewById(R.id.skill_course_editor_gauge)
        scoringSpinner = findViewById(R.id.skill_course_editor_scoring)
        totalHpInput = findViewById(R.id.skill_course_editor_total_hp)
        startingHpInput = findViewById(R.id.skill_course_editor_starting_hp)
        transitionInput = findViewById(R.id.skill_course_editor_transition)
        listOf(nameInput, authorInput, totalHpInput, startingHpInput, transitionInput).forEach {
            it?.imeOptions = (it?.imeOptions ?: 0) or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        allowPauseCheck = findViewById(R.id.skill_course_editor_allow_pause)
        autoSkipCheck = findViewById(R.id.skill_course_editor_auto_skip)
        speedrunCheck = findViewById(R.id.skill_course_editor_speedrun)
        randomOrderCheck = findViewById(R.id.skill_course_editor_random_order)
        hpRow = findViewById(R.id.skill_course_editor_hp_row)
        eventsLabel = findViewById(R.id.skill_course_editor_events_label)
        eventsContainer = findViewById(R.id.skill_course_editor_events)
        eventsHeader = findViewById(R.id.skill_course_editor_events_header)
        eventsHpHeader = findViewById(R.id.skill_course_editor_events_hp_header)
        eventsExHeader = findViewById(R.id.skill_course_editor_events_ex_header)
        mapsContainer = findViewById(R.id.skill_course_editor_maps)
        mapsEmpty = findViewById(R.id.skill_course_editor_maps_empty)
        mapsLocked = findViewById(R.id.skill_course_editor_maps_locked)
        mapsHint = findViewById(R.id.skill_course_editor_maps_hint)
        allowedSummary = findViewById(R.id.skill_course_editor_allowed_mods_summary_value)
        allowedSelectButton = findViewById(R.id.skill_course_editor_allowed_mods_select)
        errorView = findViewById(R.id.skill_course_editor_error)
        scrollView = findViewById(R.id.skill_course_editor_scroll)
        saveButton = findViewById(R.id.skill_course_editor_save)

        findViewById<TextView>(R.id.skill_course_editor_title)?.text =
            getString(if (entity == null) R.string.skill_course_editor_title_create else R.string.skill_course_editor_title_edit)
        findViewById<TextView>(R.id.skill_course_editor_version)?.text =
            (definition?.courseVersion ?: 1).toString()

        bindSpinners()
        bindEventRows()
        bindAllowedMods()
        bindFields()
        bindActions()
        applyLockedState()
        updateConditionalSections()
        renderMaps()

        val error = loadError
        if (error != null) {
            showError(getString(R.string.skill_course_editor_save_failed, error))
        }

        applyImeBottomPadding()
        playOnLoadAnim()
    }

    /**
     * The host window uses adjustPan, so the IME can cover the fixed action bar. Shrink the dialog
     * by the IME inset while the keyboard is open so Create/Save stays reachable above it.
     */
    private fun applyImeBottomPadding() {
        val root = findViewById<View>(R.id.frg_background) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            findViewById<View>(R.id.skill_course_editor_header)?.visibility =
                if (imeBottom > 0) View.GONE else View.VISIBLE
            if (view.paddingBottom != imeBottom) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, imeBottom)
                if (imeBottom > 0) view.post {
                    view.findFocus()?.let { focused ->
                        focused.requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, focused.width, focused.height), false
                        )
                    }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindSpinners() {
        val gaugeAdapter = ArrayAdapter(
            requireContext(),
            R.layout.skill_course_spinner_item,
            arrayOf(
                getString(R.string.skill_course_editor_gauge_none),
                getString(R.string.skill_course_editor_gauge_standard),
                getString(R.string.skill_course_editor_gauge_global)
            )
        ).apply { setDropDownViewResource(R.layout.skill_course_spinner_dropdown_item) }
        gaugeSpinner?.adapter = gaugeAdapter

        val scoringAdapter = ArrayAdapter(
            requireContext(),
            R.layout.skill_course_spinner_item,
            arrayOf(
                getString(R.string.skill_course_editor_scoring_scorev1),
                getString(R.string.skill_course_editor_scoring_scorev2),
                getString(R.string.skill_course_editor_scoring_exscore),
                getString(R.string.skill_course_editor_scoring_accuracy)
            )
        ).apply { setDropDownViewResource(R.layout.skill_course_spinner_dropdown_item) }
        scoringSpinner?.adapter = scoringAdapter

        val def = definition ?: return
        gaugeSpinner?.setSelection(gaugeKeys.indexOf(def.lifeGauge).coerceAtLeast(0), false)
        scoringSpinner?.setSelection(scoringKeys.indexOf(def.scoring).coerceAtLeast(0), false)

        gaugeSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != gaugeKeys.indexOf(def.lifeGauge)) dirty = true
                updateConditionalSections()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        scoringSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != scoringKeys.indexOf(def.scoring)) dirty = true
                updateConditionalSections()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun bindFields() {
        val def = definition ?: return

        suppressDirty = true
        nameInput?.setText(def.courseName)
        authorInput?.setText(def.author)
        totalHpInput?.setText(def.gaugeSettings.totalHp.toString())
        startingHpInput?.setText(def.gaugeSettings.startingHp.toString())
        transitionInput?.setText(def.mapTransitionMs.toString())
        allowPauseCheck?.isChecked = def.allowPause
        autoSkipCheck?.isChecked = def.autoSkip
        speedrunCheck?.isChecked = def.isSpeedrun
        randomOrderCheck?.isChecked = def.randomOrder
        suppressDirty = false

        nameInput?.doAfterTextChanged { markDirty() }
        authorInput?.doAfterTextChanged { markDirty() }
        totalHpInput?.doAfterTextChanged { markDirty() }
        startingHpInput?.doAfterTextChanged { markDirty() }
        transitionInput?.doAfterTextChanged { markDirty() }
        allowPauseCheck?.setOnCheckedChangeListener { _, _ -> markDirty() }
        autoSkipCheck?.setOnCheckedChangeListener { _, _ -> markDirty() }
        speedrunCheck?.setOnCheckedChangeListener { _, _ -> markDirty() }
        randomOrderCheck?.setOnCheckedChangeListener { _, _ -> markDirty() }
    }

    private fun bindEventRows() {
        val container = eventsContainer ?: return
        val def = definition ?: return
        container.removeAllViews()
        eventRows.clear()

        val inflater = LayoutInflater.from(requireContext())
        val rows = listOf(
            EventRow(
                R.string.skill_course_editor_event_hit300,
                { it.hit300 },
                { it.hit300 },
                { g, value -> g.copy(hit300 = value) },
                { e, value -> e.copy(hit300 = value) }
            ),
            EventRow(
                R.string.skill_course_editor_event_hit100,
                { it.hit100 },
                { it.hit100 },
                { g, value -> g.copy(hit100 = value) },
                { e, value -> e.copy(hit100 = value) }
            ),
            EventRow(
                R.string.skill_course_editor_event_hit50,
                { it.hit50 },
                { it.hit50 },
                { g, value -> g.copy(hit50 = value) },
                { e, value -> e.copy(hit50 = value) }
            ),
            EventRow(
                R.string.skill_course_editor_event_hit0,
                { it.hit0 },
                { it.hit0 },
                { g, value -> g.copy(hit0 = value) },
                { e, value -> e.copy(hit0 = value) }
            ),
            EventRow(
                R.string.skill_course_editor_event_sliderbreak,
                { it.sliderbreak },
                { it.sliderbreak },
                { g, value -> g.copy(sliderbreak = value) },
                { e, value -> e.copy(sliderbreak = value) }
            ),
            EventRow(
                R.string.skill_course_editor_event_sliderend,
                { it.sliderend },
                { it.sliderend },
                { g, value -> g.copy(sliderend = value) },
                { e, value -> e.copy(sliderend = value) }
            )
        )

        for (row in rows) {
            val view = inflater.inflate(R.layout.skill_course_event_row, container, false)
            val label = view.findViewById<TextView>(R.id.skill_course_event_name)
            val hp = view.findViewById<EditText>(R.id.skill_course_event_hp)
            val ex = view.findViewById<EditText>(R.id.skill_course_event_ex)

            label.setText(row.labelRes)
            hp.setText(row.gaugeValue(def.gaugeSettings).toString())
            ex.setText(row.exValue(def.exscoreSettings).toString())
            hp.contentDescription = "${getString(row.labelRes)} ${getString(R.string.skill_course_editor_event_col_hp)}"
            ex.contentDescription = "${getString(row.labelRes)} ${getString(R.string.skill_course_editor_event_col_ex)}"

            hp.doAfterTextChanged { markDirty() }
            ex.doAfterTextChanged { markDirty() }

            val bound = EventRow(row.labelRes, row.gaugeValue, row.exValue, row.withGauge, row.withEx)
            bound.hpView = hp
            bound.exView = ex
            eventRows += bound
            container.addView(view)
        }
    }

    private fun bindAllowedMods() {
        val def = definition ?: return
        selectedAllowed.clear()
        selectedAllowed.addAll(def.allowedMods)
        allowedSelectButton?.setOnClickListener { openAllowedModSelector() }
        renderAllowedSummary()
    }

    private fun renderAllowedSummary() {
        allowedSummary?.text = if (selectedAllowed.isEmpty()) {
            getString(R.string.skill_course_editor_allowed_mods_none)
        } else {
            getString(
                R.string.skill_course_editor_allowed_mods_value,
                selectedAllowed.joinToString(", ")
            )
        }
        val editable = !locked
        allowedSelectButton?.isEnabled = editable
        allowedSelectButton?.alpha = if (editable) 1f else 0.5f
    }

    private fun openAllowedModSelector() {
        if (locked || cleanedUp) {
            return
        }
        val generation = ++allowedSelectionGeneration
        val initial = selectedAllowed.toSet()

        CourseModSelection.chooseAllowedMods(this, initial, onSave = { selected ->
            mainThread {
                if (cleanedUp || !isAdded || generation != allowedSelectionGeneration) {
                    return@mainThread
                }
                val updated = CourseEditorSelection.mergeAllowedSelection(
                    current = selectedAllowed,
                    returned = selected
                )
                if (updated != selectedAllowed.toList()) {
                    selectedAllowed.clear()
                    selectedAllowed.addAll(updated)
                    markDirty()
                }
                renderAllowedSummary()
            }
        }, onError = { message -> context?.let { CourseUi.showMessage(it, message) } })
    }

    private fun bindActions() {
        findViewById<View>(R.id.skill_course_editor_close)?.setOnClickListener { requestClose() }
        findViewById<View>(R.id.skill_course_editor_cancel)?.setOnClickListener { requestClose() }
        saveButton?.setOnClickListener { saveCourse() }
        saveButton?.text = getString(
            if (entity == null) R.string.skill_course_create else R.string.skill_course_save
        )
    }

    private fun updateConditionalSections() {
        val gauge = gaugeKeys.getOrElse(gaugeSpinner?.selectedItemPosition ?: 0) { "standard" }
        val scoring = scoringKeys.getOrElse(scoringSpinner?.selectedItemPosition ?: 0) { "scorev1" }
        val showHp = gauge == "global"
        val showEx = scoring == "exscore"

        hpRow?.visibility = if (showHp) View.VISIBLE else View.GONE
        eventsHpHeader?.visibility = if (showHp) View.VISIBLE else View.INVISIBLE
        eventsExHeader?.visibility = if (showEx) View.VISIBLE else View.INVISIBLE

        for (row in eventRows) {
            row.hpView?.visibility = if (showHp) View.VISIBLE else View.GONE
            row.exView?.visibility = if (showEx) View.VISIBLE else View.GONE
        }

        val showEvents = showHp || showEx
        eventsLabel?.visibility = if (showEvents) View.VISIBLE else View.GONE
        eventsHeader?.visibility = if (showEvents) View.VISIBLE else View.GONE
        eventsContainer?.visibility = if (showEvents) View.VISIBLE else View.GONE
    }

    private fun applyLockedState() {
        val banner = findViewById<View>(R.id.skill_course_editor_locked_banner)
        banner?.visibility = if (locked) View.VISIBLE else View.GONE
        mapsLocked?.visibility = if (locked) View.VISIBLE else View.GONE

        val editable = !locked
        nameInput?.isEnabled = editable
        authorInput?.isEnabled = editable
        gaugeSpinner?.isEnabled = editable
        scoringSpinner?.isEnabled = editable
        totalHpInput?.isEnabled = editable
        startingHpInput?.isEnabled = editable
        transitionInput?.isEnabled = editable
        allowPauseCheck?.isEnabled = editable
        autoSkipCheck?.isEnabled = editable
        speedrunCheck?.isEnabled = editable
        randomOrderCheck?.isEnabled = editable
        saveButton?.isEnabled = editable
        saveButton?.alpha = if (editable) 1f else 0.5f

        for (row in eventRows) {
            row.hpView?.isEnabled = editable
            row.exView?.isEnabled = editable
        }
        renderAllowedSummary()
    }

    private fun renderMaps() {
        val container = mapsContainer ?: return
        container.removeAllViews()

        val showHint = entity == null && workingMaps.isNotEmpty() &&
            baseDefinition?.maps?.isEmpty() == true
        mapsHint?.visibility = if (showHint) View.VISIBLE else View.GONE
        mapsEmpty?.visibility = if (workingMaps.isEmpty()) View.VISIBLE else View.GONE

        if (workingMaps.isEmpty()) {
            mapsEmpty?.setText(
                if (definition?.maps?.isEmpty() == true && entity == null) {
                    R.string.skill_course_editor_maps_empty
                } else {
                    R.string.skill_course_editor_maps_empty
                }
            )
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        for (index in workingMaps.indices) {
            val map = workingMaps[index]
            val view = inflater.inflate(R.layout.skill_course_editor_map_item, container, false)
            val title = view.findViewById<TextView>(R.id.skill_course_editor_map_title)
            val meta = view.findViewById<TextView>(R.id.skill_course_editor_map_meta)
            val mods = view.findViewById<TextView>(R.id.skill_course_editor_map_mods)
            val up = view.findViewById<Button>(R.id.skill_course_editor_map_up)
            val down = view.findViewById<Button>(R.id.skill_course_editor_map_down)
            val remove = view.findViewById<Button>(R.id.skill_course_editor_map_remove)

            title.text = "#${index + 1} ${mapDisplayName(map)}"
            title.contentDescription = title.text
            meta.text = buildString {
                append(map.mapper.ifBlank { "Unknown mapper" })
                append(" · ")
                append(map.objectCounts.sum())
                append(" objects")
            }
            mods.text = getString(
                R.string.skill_course_editor_map_mods,
                if (map.mods.isEmpty()) getString(R.string.skill_course_no_mods)
                else map.mods.joinToString(", ") { it.acronym }
            )

            up.isEnabled = !locked && index > 0
            down.isEnabled = !locked && index < workingMaps.lastIndex
            remove.isEnabled = !locked
            up.contentDescription = "${getString(R.string.skill_course_editor_move_up)}: ${mapDisplayName(map)}"
            down.contentDescription = "${getString(R.string.skill_course_editor_move_down)}: ${mapDisplayName(map)}"
            remove.contentDescription = "${getString(R.string.skill_course_editor_remove_map)}: ${mapDisplayName(map)}"

            up.setOnClickListener {
                if (index > 0) {
                    val previous = workingMaps.removeAt(index)
                    workingMaps.add(index - 1, previous)
                    markDirty()
                    renderMaps()
                }
            }
            down.setOnClickListener {
                if (index < workingMaps.lastIndex) {
                    val next = workingMaps.removeAt(index)
                    workingMaps.add(index + 1, next)
                    markDirty()
                    renderMaps()
                }
            }
            remove.setOnClickListener {
                workingMaps.removeAt(index)
                markDirty()
                renderMaps()
            }

            container.addView(view)
        }
    }

    private fun saveCourse() {
        if (locked) {
            showError(getString(R.string.skill_course_locked_edit))
            return
        }

        val candidate = buildCandidate() ?: return
        try {
            CourseCodec.validate(candidate, true)
            val current = entity
            if (current == null) {
                CourseRepository.create(candidate, false)
            } else {
                CourseRepository.save(current.id, candidate)
            }

            saved = true
            dirty = false
            CourseUi.notifyCoursesChanged()
            CourseUi.showMessage(
                requireContext(),
                getString(if (current == null) R.string.skill_course_editor_created else R.string.skill_course_editor_saved)
            )
            closeImmediately()
        } catch (e: Exception) {
            showError(getString(R.string.skill_course_editor_save_failed, messageOf(e)))
        }
    }

    private fun buildCandidate(): CourseDefinition? {
        clearError()
        val def = definition ?: return null

        val name = nameInput?.text?.toString()?.trim().orEmpty()
        if (!isValidDisplayText(name)) {
            showError(getString(R.string.skill_course_editor_invalid_name))
            return null
        }

        val author = authorInput?.text?.toString()?.trim().orEmpty()
        if (!isValidDisplayText(author)) {
            showError(getString(R.string.skill_course_editor_invalid_author))
            return null
        }

        val transitionText = transitionInput?.text?.toString()?.trim().orEmpty()
        val transition = transitionText.toLongOrNull()
        if (transition == null || transition !in 0L..60000L) {
            showError(getString(R.string.skill_course_editor_invalid_transition))
            return null
        }

        val gauge = gaugeKeys.getOrElse(gaugeSpinner?.selectedItemPosition ?: 0) { "standard" }
        val scoring = scoringKeys.getOrElse(scoringSpinner?.selectedItemPosition ?: 0) { "scorev1" }

        var gaugeSettings = def.gaugeSettings
        var exscoreSettings = def.exscoreSettings

        if (gauge == "global") {
            val total = parseNumber(
                getString(R.string.skill_course_editor_total_hp),
                totalHpInput?.text?.toString(),
                1,
                1_000_000
            ) ?: return null
            val starting = parseNumber(
                getString(R.string.skill_course_editor_starting_hp),
                startingHpInput?.text?.toString(),
                1,
                total
            ) ?: return null

            gaugeSettings = gaugeSettings.copy(totalHp = total, startingHp = starting)
            gaugeSettings = applyGaugeEvents(gaugeSettings) ?: return null
        }

        if (scoring == "exscore") {
            exscoreSettings = applyExscoreEvents(exscoreSettings) ?: return null
        }

        return def.copy(
            courseName = name,
            author = author,
            lifeGauge = gauge,
            scoring = scoring,
            gaugeSettings = gaugeSettings,
            exscoreSettings = exscoreSettings,
            allowPause = allowPauseCheck?.isChecked ?: true,
            mapTransitionMs = transition,
            autoSkip = autoSkipCheck?.isChecked ?: false,
            isSpeedrun = speedrunCheck?.isChecked ?: false,
            randomOrder = randomOrderCheck?.isChecked ?: false,
            allowedMods = selectedAllowed.toList(),
            maps = workingMaps.toList()
        )
    }

    private fun applyGaugeEvents(initial: CourseGaugeSettings): CourseGaugeSettings? {
        var settings = initial
        for (row in eventRows) {
            val value = parseNumber(
                getString(row.labelRes),
                row.hpView?.text?.toString(),
                -1_000_000,
                1_000_000
            ) ?: return null
            settings = row.withGauge(settings, value)
        }
        return settings
    }

    private fun applyExscoreEvents(initial: CourseExscoreSettings): CourseExscoreSettings? {
        var settings = initial
        for (row in eventRows) {
            val value = parseNumber(
                getString(row.labelRes),
                row.exView?.text?.toString(),
                -1_000_000,
                1_000_000
            ) ?: return null
            settings = row.withEx(settings, value)
        }
        return settings
    }

    private fun parseNumber(label: String, raw: String?, min: Int, max: Int): Int? {
        val text = raw?.trim().orEmpty()
        val value = text.toIntOrNull()
        if (value == null) {
            showError(getString(R.string.skill_course_editor_invalid_number, label))
            return null
        }
        if (value !in min..max) {
            showError(getString(R.string.skill_course_editor_invalid_range, label, min, max))
            return null
        }
        return value
    }

    private fun isValidDisplayText(value: String): Boolean = CourseUiLogic.isValidDisplayText(value)

    private fun requestClose() {
        if (!dirty || saved) {
            closeImmediately()
            return
        }

        MessageDialog()
            .setTitle(getString(R.string.skill_course_editor_unsaved))
            .addButton(getString(R.string.skill_course_editor_discard)) {
                it.dismiss()
                closeImmediately()
            }
            .addButton(getString(R.string.skill_course_editor_keep_editing)) { it.dismiss() }
            .show()
    }

    fun closeImmediately() {
        cleanedUp = true
        allowedSelectionGeneration++
        saved = true
        dirty = false
        super.dismiss()
    }

    override fun onDestroyView() {
        cleanedUp = true
        allowedSelectionGeneration++
        super.onDestroyView()
    }

    override fun callDismissOnBackPress() {
        requestClose()
    }

    override fun dismiss() {
        requestClose()
    }

    private fun markDirty() {
        if (!suppressDirty) {
            dirty = true
        }
    }

    private fun showError(message: CharSequence?) {
        val view = errorView ?: return
        if (message.isNullOrBlank()) {
            view.visibility = View.GONE
            view.text = ""
            return
        }
        view.text = message
        view.visibility = View.VISIBLE
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearError() {
        errorView?.visibility = View.GONE
        errorView?.text = ""
    }

    private fun mapDisplayName(map: CourseMap): String {
        val artist = map.artistUnicode.takeUnless { it.isBlank() } ?: map.artist
        val title = map.titleUnicode.takeUnless { it.isBlank() } ?: map.title
        val primary = "$artist - $title [${map.difficulty}]"
        val romanized = "${map.artist} - ${map.title} [${map.difficulty}]"
        return if (romanized.isNotBlank() && romanized != primary) "$primary ($romanized)" else primary
    }

    private fun defaultAuthor(): String {
        val online = OnlineManager.getInstance()
        val onlineName = online.username
        return if (online.isStayOnline && !onlineName.isNullOrBlank() && !onlineName.equals("Guest", true)) {
            onlineName
        } else {
            Config.getOnlineUsername().ifBlank { "Player" }
        }
    }

    private fun playOnLoadAnim() {
        val body = findViewById<View>(R.id.frg_body) ?: return
        body.alpha = 0f
        body.translationY = 160f
        body.animate().cancel()
        body.animate()
            .translationY(0f)
            .alpha(1f)
            .setInterpolator(EasingHelper.asInterpolator(Easing.InOutQuad))
            .setDuration(150)
            .start()
        playBackgroundHideInAnim(150)
    }

    private fun playEndAnim(action: Runnable?) {
        val body = findViewById<View>(R.id.frg_body) ?: run {
            action?.run()
            return
        }
        body.animate().cancel()
        body.animate()
            .translationY(160f)
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(EasingHelper.asInterpolator(Easing.OutQuad))
            .setListener(object : BaseAnimationListener() {
                override fun onAnimationEnd(animation: Animator) {
                    action?.run()
                }
            })
            .start()
        playBackgroundHideOutAnim(180)
    }

    private fun messageOf(error: Throwable): String =
        error.message?.takeUnless { it.isBlank() } ?: error.javaClass.simpleName

    private data class EventRow(
        val labelRes: Int,
        val gaugeValue: (CourseGaugeSettings) -> Int,
        val exValue: (CourseExscoreSettings) -> Int,
        val withGauge: (CourseGaugeSettings, Int) -> CourseGaugeSettings,
        val withEx: (CourseExscoreSettings, Int) -> CourseExscoreSettings
    ) {
        var hpView: EditText? = null
        var exView: EditText? = null
    }
}

/**
 * Pure allowlist-draft rules for the editor.
 *
 * The native selector returns a set-like selection, but the portable definition preserves the
 * authored order because that order is part of the course hash. Existing acronyms therefore keep
 * their position and only genuinely new acronyms are appended.
 */
internal object CourseEditorSelection {

    fun mergeAllowedSelection(
        current: Collection<String>,
        returned: Collection<String>,
        knownUserPlayable: Set<String> = allModsInstances
            .asSequence()
            .filter { it.isUserPlayable }
            .map { it.acronym }
            .toSet()
    ): List<String> {
        val wanted = LinkedHashSet<String>()
        for (acronym in returned) {
            val normalized = acronym.trim()
            if (normalized.isNotEmpty() && normalized in knownUserPlayable) {
                wanted.add(normalized)
            }
        }

        val merged = LinkedHashSet<String>()
        for (acronym in current) {
            if (acronym in wanted) {
                merged.add(acronym)
            }
        }
        for (acronym in returned) {
            val normalized = acronym.trim()
            if (normalized in wanted) {
                merged.add(normalized)
            }
        }
        return merged.toList()
    }
}

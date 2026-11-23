package com.osudroid.skillcourse.ui

import android.animation.Animator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.edlplan.framework.easing.Easing
import com.edlplan.ui.BaseAnimationListener
import com.edlplan.ui.EasingHelper
import com.edlplan.ui.fragment.BaseFragment
import com.osudroid.data.BeatmapInfo
import com.osudroid.data.SkillCourseEntity
import com.osudroid.skillcourse.CourseCodec
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseMap
import com.osudroid.skillcourse.CourseRepository
import com.osudroid.skillcourse.recovery.CourseAvailabilityIssue
import com.osudroid.skillcourse.recovery.CourseAvailabilityScanner
import com.osudroid.skillcourse.recovery.CourseAvailabilitySnapshot
import com.osudroid.skillcourse.recovery.CourseLibraryEvents
import com.osudroid.skillcourse.recovery.CourseLibraryListener
import com.osudroid.skillcourse.recovery.CourseMapStatus
import com.osudroid.skillcourse.recovery.CourseRecoveryResult
import com.osudroid.skillcourse.recovery.CourseMapRecovery
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.async
import com.osudroid.utils.mainThread
import com.reco1l.osu.ui.MessageDialog
import com.reco1l.osu.ui.Option
import com.reco1l.osu.ui.SelectDialog
import ru.nsu.ccfit.zuev.osuplus.R

/**
 * The native course manager.
 *
 * Availability is derived state and is always scanned off the UI thread. A generation counter and
 * the library revision prevent a slow scan from overwriting a newer library snapshot.
 */
class CourseManagerFragment : BaseFragment() {

    private data class CourseRow(
        val entity: SkillCourseEntity,
        val definition: CourseDefinition?,
        val snapshot: CourseAvailabilitySnapshot? = null,
        val scanComplete: Boolean = false
    )

    private var selectedBeatmap: BeatmapInfo? = null
    private var selectedMods: ModHashMap? = null

    private var rows = mutableListOf<CourseRow>()
    private var adapter: CourseAdapter? = null
    private var scanGeneration = 0
    private var cleanedUp = false
    private var recoveryDialog: CourseRecoveryFragment? = null

    private val libraryListener = CourseLibraryListener {
        mainThread {
            if (isAdded && !cleanedUp) {
                refreshCourses()
            }
        }
    }

    private val refreshListener: () -> Unit = {
        if (isAdded && !cleanedUp) {
            refreshCourses()
        }
    }

    init {
        isDismissOnBackgroundClick = true
        isDismissOnBackPress = true
    }

    override val layoutID: Int
        get() = R.layout.skill_course_manager_fragment

    fun show(selectedBeatmap: BeatmapInfo?, selectedMods: ModHashMap?) {
        this.selectedBeatmap = selectedBeatmap
        this.selectedMods = selectedMods?.deepCopy()
        show()
    }

    override fun onLoadView() {
        CourseUi.attachManager(this)
        CourseUi.addRefreshListener(refreshListener)
        CourseLibraryEvents.addListener(libraryListener)

        val body = findViewById<View>(R.id.frg_body)
        body?.layoutParams = body?.layoutParams?.also {
            it.width = CourseUi.boundedWidth(requireContext(), 720f)
        }

        val recycler = findViewById<RecyclerView>(R.id.skill_course_manager_list)
        recycler?.layoutManager = LinearLayoutManager(requireContext())
        adapter = CourseAdapter()
        recycler?.adapter = adapter

        findViewById<View>(R.id.skill_course_manager_close)?.setOnClickListener { dismiss() }
        findViewById<View>(R.id.skill_course_new)?.setOnClickListener {
            CourseEditorFragment().showCreate(selectedBeatmap, selectedMods)
        }
        findViewById<View>(R.id.skill_course_import)?.setOnClickListener {
            CourseFiles.launchImport(requireActivity())
        }

        refreshCourses()
        playOnLoadAnim()
    }

    private fun refreshCourses() {
        if (cleanedUp) {
            return
        }

        val generation = ++scanGeneration
        val entities = try {
            CourseRepository.listCourses()
        } catch (e: Exception) {
            showStatus(getString(R.string.skill_course_error) + ": " + messageOf(e))
            rows = mutableListOf()
            adapter?.notifyDataSetChanged()
            updateEmptyState()
            return
        }

        val loaded = entities.map { entity ->
            val definition = try {
                CourseCodec.decode(entity.definitionJson, allowDraft = true)
            } catch (_: Exception) {
                null
            }
            CourseRow(entity, definition)
        }

        rows = loaded.toMutableList()
        adapter?.notifyDataSetChanged()
        updateEmptyState()

        if (loaded.isEmpty()) {
            showStatus(null)
            return
        }

        showStatus(getString(R.string.skill_course_checking))

        for (row in loaded) {
            val definition = row.definition
            if (definition == null) {
                continue
            }

            async {
                val snapshot = try {
                    CourseAvailabilityScanner.scan(definition)
                } catch (_: Exception) {
                    null
                }

                mainThread {
                    if (cleanedUp || generation != scanGeneration || !isAdded) {
                        return@mainThread
                    }

                    if (snapshot != null && snapshot.libraryRevision < CourseLibraryEvents.revision) {
                        // A library commit happened while this scan was running. Rescan instead of
                        // publishing a stale result.
                        refreshCourses()
                        return@mainThread
                    }

                    val index = rows.indexOfFirst { it.entity.id == row.entity.id }
                    if (index >= 0) {
                        rows[index] = rows[index].copy(snapshot = snapshot, scanComplete = true)
                        adapter?.notifyItemChanged(index)
                    }
                    updateScanStatus(generation)
                }
            }
        }

        // A row without a decodable definition never produces a scan completion.
        updateScanStatus(generation)
    }

    private fun updateScanStatus(generation: Int) {
        if (generation != scanGeneration || cleanedUp) {
            return
        }
        if (rows.any { it.definition != null && !it.scanComplete }) {
            showStatus(getString(R.string.skill_course_checking))
        } else {
            showStatus(null)
        }
    }

    private fun updateEmptyState() {
        findViewById<View>(R.id.skill_course_manager_empty)?.visibility =
            if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showStatus(text: CharSequence?) {
        val view = findViewById<TextView>(R.id.skill_course_manager_status) ?: return
        if (text.isNullOrBlank()) {
            view.visibility = View.GONE
        } else {
            view.text = text
            view.visibility = View.VISIBLE
        }
    }

    private fun toggleMembership(row: CourseRow) {
        val beatmap = selectedBeatmap ?: return
        if (row.entity.locked) {
            CourseUi.showMessage(requireContext(), getString(R.string.skill_course_locked_membership))
            return
        }

        val definition = row.definition ?: return
        val existing = definition.maps.any { it.mapHash.equals(beatmap.md5, ignoreCase = true) }
        val map = CourseMap.fromBeatmap(beatmap, selectedMods?.deepCopy() ?: ModHashMap())

        try {
            CourseRepository.toggleMap(row.entity.id, map)
            CourseUi.showMessage(
                requireContext(),
                getString(if (existing) R.string.skill_course_map_removed else R.string.skill_course_map_added)
            )
            refreshCourses()
        } catch (e: Exception) {
            CourseUi.showMessage(requireContext(), messageOf(e))
        }
    }

    private fun openRecovery(row: CourseRow) {
        val definition = row.definition ?: return
        if (CourseMapRecovery.hasActiveBatch) {
            CourseUi.showMessage(requireContext(), getString(R.string.skill_course_recovery_busy))
            return
        }

        val dialog = CourseRecoveryFragment()
        recoveryDialog = dialog
        dialog.show(definition) { result ->
            recoveryDialog = null
            handleRecoveryComplete(result)
            refreshCourses()
        }
    }

    private fun handleRecoveryComplete(result: CourseRecoveryResult) {
        if (cleanedUp || !isAdded) {
            return
        }

        val message = when {
            result.canceled -> getString(R.string.skill_course_recovery_canceled)
            !result.finalScanSucceeded -> getString(R.string.skill_course_check_failed)
            result.allReady -> getString(R.string.skill_course_recovery_all_ready)
            else -> getString(R.string.skill_course_recovery_complete, result.unresolved.size)
        }
        CourseUi.showMessage(requireContext(), message)
    }

    private fun showActions(row: CourseRow) {
        val dialog = SelectDialog()
        val lockText = if (row.entity.locked) R.string.skill_course_unlock else R.string.skill_course_lock

        dialog.setTitle(row.entity.courseName)
        dialog.addOption(Option(getString(lockText), "lock"))
        dialog.addOption(Option(getString(R.string.skill_course_edit), "edit"))
        dialog.addOption(Option(getString(R.string.skill_course_export), "export"))
        dialog.addOption(Option(getString(R.string.skill_course_history), "history"))
        dialog.addOption(Option(getString(R.string.skill_course_delete), "delete"))

        dialog.setOnSelectListener { value ->
            when (value) {
                "lock" -> {
                    try {
                        CourseRepository.setLocked(row.entity.id, !row.entity.locked)
                        refreshCourses()
                    } catch (e: Exception) {
                        CourseUi.showMessage(requireContext(), messageOf(e))
                    }
                }

                "edit" -> CourseEditorFragment().showEdit(
                    CourseRepository.getEntity(row.entity.id) ?: row.entity
                )

                "export" -> CourseFiles.launchExport(requireActivity(), row.entity.id)

                "history" -> CourseUi.requestHistory(row.entity)

                "delete" -> confirmDelete(row)
            }
        }

        dialog.show()
    }

    private fun confirmDelete(row: CourseRow) {
        MessageDialog()
            .setTitle(getString(R.string.skill_course_delete_title))
            .setMessage(getString(R.string.skill_course_delete_message))
            .addButton(getString(android.R.string.yes)) {
                it.dismiss()
                try {
                    CourseRepository.delete(row.entity.id)
                    refreshCourses()
                } catch (e: Exception) {
                    CourseUi.showMessage(requireContext(), messageOf(e))
                }
            }
            .addButton(getString(android.R.string.no)) { it.dismiss() }
            .show()
    }

    private fun playOrDownload(row: CourseRow) {
        val snapshot = row.snapshot
        val definition = row.definition
        if (snapshot == null || definition == null) {
            CourseUi.showMessage(requireContext(), getString(R.string.skill_course_play_blocked))
            return
        }

        if (definition.maps.isEmpty()) {
            CourseUi.showMessage(requireContext(), getString(R.string.skill_course_empty_draft_play))
            return
        }

        if (snapshot.allReady) {
            if (CourseUi.onPlay == null) {
                CourseUi.showUnavailable(R.string.skill_course_feature_unavailable_play)
                return
            }
            CoursePreflightFragment().show(row.entity, definition)
        } else {
            openRecovery(row)
        }
    }

    private fun populateIssues(container: LinearLayout, issues: List<CourseAvailabilityIssue>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        for (issue in issues) {
            val view = inflater.inflate(R.layout.skill_course_issue_item, container, false)
            val title = view.findViewById<TextView>(R.id.skill_course_issue_title)
            val detail = view.findViewById<TextView>(R.id.skill_course_issue_detail)
            val md5 = view.findViewById<TextView>(R.id.skill_course_issue_md5)
            val reason = view.findViewById<TextView>(R.id.skill_course_issue_reason)
            val copy = view.findViewById<Button>(R.id.skill_course_issue_copy)

            title.text = getString(R.string.skill_course_issue_position, issue.originalIndex + 1) +
                " " + statusLabel(issue.status)
            detail.text = mapDisplayName(issue.courseMap)
            md5.text = getString(R.string.skill_course_issue_required_md5, issue.courseMap.mapHash)

            val reasonText = issue.reason
            if (reasonText.isNullOrBlank()) {
                reason.visibility = View.GONE
            } else {
                reason.visibility = View.VISIBLE
                reason.text = reasonText
            }

            copy.contentDescription = "${getString(R.string.skill_course_copy)}: ${mapDisplayName(issue.courseMap)}"
            copy.setOnClickListener {
                copyIssue(issue)
            }

            container.addView(view)
        }
    }

    private fun copyIssue(issue: CourseAvailabilityIssue) {
        val text = buildString {
            append(statusLabel(issue.status))
            append('\n')
            append(mapDisplayName(issue.courseMap))
            append('\n')
            append(getString(R.string.skill_course_issue_required_md5, issue.courseMap.mapHash))
            issue.reason?.takeUnless { it.isBlank() }?.let {
                append('\n')
                append(it)
            }
            if (issue.candidates.isNotEmpty()) {
                append('\n')
                append(issue.candidates.joinToString("\n") { "${it.setDirectory}/${it.filename} (${it.md5})" })
            }
        }

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("course map", text))
        CourseUi.showMessage(requireContext(), getString(R.string.skill_course_copied))
    }

    private fun statusLabel(status: CourseMapStatus): String = getString(
        when (status) {
            CourseMapStatus.READY -> R.string.skill_course_issue_ready
            CourseMapStatus.OUTDATED -> R.string.skill_course_issue_outdated
            CourseMapStatus.MISSING -> R.string.skill_course_issue_missing
            CourseMapStatus.INVALID -> R.string.skill_course_issue_invalid
        }
    )

    private fun mapDisplayName(map: CourseMap): String {
        val artist = map.artistUnicode.takeUnless { it.isBlank() } ?: map.artist
        val title = map.titleUnicode.takeUnless { it.isBlank() } ?: map.title
        val primary = "$artist - $title [${map.difficulty}]"
        val romanized = "${map.artist} - ${map.title} [${map.difficulty}]"
        return if (romanized.isNotBlank() && romanized != primary) "$primary ($romanized)" else primary
    }

    private fun playOnLoadAnim() {
        val body = findViewById<View>(R.id.frg_body) ?: return
        body.translationY = 400f
        body.animate().cancel()
        body.animate()
            .translationY(0f)
            .setDuration(180)
            .start()
        playBackgroundHideInAnim(180)
    }

    private fun playEndAnim(action: Runnable) {
        val body = findViewById<View>(R.id.frg_body)
        if (body == null) {
            action.run()
            return
        }
        body.animate().cancel()
        body.animate()
            .translationY(400f)
            .alpha(0f)
            .setDuration(160)
            .setInterpolator(EasingHelper.asInterpolator(Easing.OutQuad))
            .setListener(object : BaseAnimationListener() {
                override fun onAnimationEnd(animation: Animator) {
                    action.run()
                }
            })
            .start()
        playBackgroundHideOutAnim(160)
    }

    override fun dismiss() {
        if (cleanedUp) {
            return
        }
        cleanup()
        playEndAnim(Runnable { super.dismiss() })
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
        scanGeneration++
        recoveryDialog?.closeImmediately()
        recoveryDialog = null
        CourseLibraryEvents.removeListener(libraryListener)
        CourseUi.removeRefreshListener(refreshListener)
        CourseUi.detachManager(this)
    }

    private fun messageOf(error: Throwable): String =
        error.message?.takeUnless { it.isBlank() } ?: error.javaClass.simpleName

    private inner class CourseAdapter : RecyclerView.Adapter<CourseHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.skill_course_manager_item, parent, false)
            return CourseHolder(view)
        }

        override fun onBindViewHolder(holder: CourseHolder, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size
    }

    private inner class CourseHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.skill_course_item_name)
        private val author: TextView = itemView.findViewById(R.id.skill_course_item_author)
        private val lock: TextView = itemView.findViewById(R.id.skill_course_item_lock)
        private val mapCount: TextView = itemView.findViewById(R.id.skill_course_item_map_count)
        private val issueSummary: TextView = itemView.findViewById(R.id.skill_course_item_issue_summary)
        private val savedMods: TextView = itemView.findViewById(R.id.skill_course_item_saved_mods)
        private val membership: Button = itemView.findViewById(R.id.skill_course_item_membership)
        private val viewButton: Button = itemView.findViewById(R.id.skill_course_item_view)
        private val playButton: Button = itemView.findViewById(R.id.skill_course_item_play)
        private val issueToggle: Button = itemView.findViewById(R.id.skill_course_item_issue_toggle)
        private val more: Button = itemView.findViewById(R.id.skill_course_item_more)
        private val issuesContainer: LinearLayout = itemView.findViewById(R.id.skill_course_item_issues)

        fun bind(row: CourseRow) {
            val definition = row.definition
            val snapshot = row.snapshot

            name.text = row.entity.courseName
            name.contentDescription = row.entity.courseName
            author.text = getString(R.string.skill_course_meta_author, row.entity.author)
            author.contentDescription = author.text
            lock.text = getString(
                if (row.entity.locked) R.string.skill_course_locked else R.string.skill_course_unlocked
            )
            mapCount.text = getString(R.string.skill_course_maps_count, definition?.maps?.size ?: 0)

            more.contentDescription = "${getString(R.string.skill_course_more)}: ${row.entity.courseName}"
            more.setOnClickListener { showActions(row) }

            val selectedMap = selectedBeatmap
            if (selectedMap == null) {
                membership.visibility = View.GONE
            } else {
                membership.visibility = View.VISIBLE
                val contains = definition?.maps?.any {
                    it.mapHash.equals(selectedMap.md5, ignoreCase = true)
                } == true
                membership.text = getString(
                    if (contains) R.string.skill_course_remove_map else R.string.skill_course_add_map
                )
                membership.setCompoundDrawablesWithIntrinsicBounds(
                    if (contains) R.drawable.remove_24px else R.drawable.add_24px,
                    0, 0, 0
                )
                membership.contentDescription = if (row.entity.locked) {
                    "${getString(R.string.skill_course_locked_membership)}: ${row.entity.courseName}"
                } else {
                    "${membership.text}: ${row.entity.courseName}"
                }
                membership.isEnabled = !row.entity.locked && definition != null
                membership.alpha = if (membership.isEnabled) 1f else 0.5f
                membership.setOnClickListener { toggleMembership(row) }
            }

            val selectedEntry = definition?.maps?.firstOrNull {
                selectedMap != null && it.mapHash.equals(selectedMap.md5, ignoreCase = true)
            }
            if (selectedEntry == null) {
                savedMods.visibility = View.GONE
            } else {
                savedMods.visibility = View.VISIBLE
                savedMods.text = getString(
                    R.string.skill_course_saved_mods,
                    if (selectedEntry.mods.isEmpty()) getString(R.string.skill_course_no_mods)
                    else selectedEntry.mods.joinToString(", ") { it.acronym }
                )
            }

            viewButton.isEnabled = definition != null
            viewButton.alpha = if (viewButton.isEnabled) 1f else 0.5f
            viewButton.setOnClickListener {
                val entity = CourseRepository.getEntity(row.entity.id) ?: row.entity
                CourseUi.requestView(entity)
            }

            when {
                definition == null -> {
                    issueSummary.text = getString(R.string.skill_course_issue_invalid)
                    playButton.isEnabled = false
                    playButton.text = getString(R.string.skill_course_play)
                    playButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                }

                definition.maps.isEmpty() -> {
                    issueSummary.text = getString(R.string.skill_course_no_maps_yet)
                    playButton.isEnabled = false
                    playButton.text = getString(R.string.skill_course_play)
                    playButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.play_arrow_24px, 0, 0, 0)
                }

                snapshot == null -> {
                    issueSummary.text = if (row.scanComplete) {
                        getString(R.string.skill_course_check_failed)
                    } else {
                        getString(R.string.skill_course_checking)
                    }
                    playButton.isEnabled = false
                    playButton.text = getString(R.string.skill_course_play)
                    playButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                }

                snapshot.allReady -> {
                    issueSummary.text = getString(R.string.skill_course_issues_ready)
                    playButton.isEnabled = true
                    playButton.setText(R.string.skill_course_play)
                    playButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.play_arrow_24px, 0, 0, 0)
                }

                else -> {
                    issueSummary.text = issueSummaryText(snapshot)
                    playButton.isEnabled = true
                    playButton.setText(R.string.skill_course_download_missing)
                    playButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.download_24px, 0, 0, 0)
                }
            }
            playButton.contentDescription = "${playButton.text}: ${row.entity.courseName}"
            playButton.alpha = if (playButton.isEnabled) 1f else 0.5f
            playButton.setOnClickListener { playOrDownload(row) }
            issueSummary.contentDescription = issueSummary.text

            val issues = snapshot?.issues.orEmpty()
            if (issues.isEmpty() || definition == null) {
                issueToggle.visibility = View.GONE
                issuesContainer.visibility = View.GONE
            } else {
                issueToggle.visibility = View.VISIBLE
                issueToggle.text = getString(R.string.skill_course_show_issues, issues.size)
                issueToggle.contentDescription = issueToggle.text
                issueToggle.setOnClickListener {
                    val showing = issuesContainer.visibility == View.VISIBLE
                    if (showing) {
                        issuesContainer.visibility = View.GONE
                        issueToggle.text = getString(R.string.skill_course_show_issues, issues.size)
                        issueToggle.contentDescription = issueToggle.text
                    } else {
                        populateIssues(issuesContainer, issues)
                        issuesContainer.visibility = View.VISIBLE
                        issueToggle.setText(R.string.skill_course_hide_issues)
                        issueToggle.contentDescription = issueToggle.text
                    }
                }
            }

            if (definition == null) {
                issueSummary.text = getString(R.string.skill_course_issue_invalid)
            }
        }

        private fun issueSummaryText(snapshot: CourseAvailabilitySnapshot): String {
            val parts = mutableListOf<String>()
            val counts = snapshot.statusCounts
            val missing = counts[CourseMapStatus.MISSING] ?: 0
            val outdated = counts[CourseMapStatus.OUTDATED] ?: 0
            val invalid = counts[CourseMapStatus.INVALID] ?: 0
            if (missing > 0) parts += getString(R.string.skill_course_issues_missing, missing)
            if (outdated > 0) parts += getString(R.string.skill_course_issues_outdated, outdated)
            if (invalid > 0) parts += getString(R.string.skill_course_issues_invalid, invalid)
            return if (parts.isEmpty()) getString(R.string.skill_course_no_maps_yet)
            else parts.joinToString(" · ")
        }
    }
}

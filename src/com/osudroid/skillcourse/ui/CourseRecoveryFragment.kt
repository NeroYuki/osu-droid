package com.osudroid.skillcourse.ui

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.edlplan.ui.fragment.BaseFragment
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.skillcourse.CourseMap
import com.osudroid.skillcourse.recovery.CourseMapRecovery
import com.osudroid.skillcourse.recovery.CourseMapStatus
import com.osudroid.skillcourse.recovery.CourseRecoveryHandle
import com.osudroid.skillcourse.recovery.CourseRecoveryResult
import com.google.android.material.progressindicator.CircularProgressIndicator
import ru.nsu.ccfit.zuev.osuplus.R

/**
 * Progress, cancellation and completion summary for one sequential map-recovery batch.
 *
 * The fragment never starts the course and never rewrites the portable definition. The manager
 * rescans availability after the completion callback and only then decides whether Play can return.
 */
class CourseRecoveryFragment : BaseFragment() {

    private var definition: CourseDefinition? = null
    private var onComplete: ((CourseRecoveryResult) -> Unit)? = null
    private var handle: CourseRecoveryHandle? = null
    private var started = false
    private var cleanedUp = false
    private var completionDelivered = false

    private var status: TextView? = null
    private var details: TextView? = null
    private var indicator: CircularProgressIndicator? = null
    private var cancelButton: Button? = null
    private var closeButton: Button? = null

    init {
        isDismissOnBackgroundClick = false
        isDismissOnBackPress = true
    }

    override val layoutID: Int
        get() = R.layout.skill_course_recovery_fragment

    fun show(definition: CourseDefinition, onComplete: (CourseRecoveryResult) -> Unit) {
        this.definition = definition
        this.onComplete = onComplete
        show()
    }

    override fun onLoadView() {
        val body = findViewById<View>(R.id.frg_body)
        body?.layoutParams = body?.layoutParams?.also {
            it.width = CourseUi.boundedWidth(requireContext(), 520f)
        }

        status = findViewById(R.id.skill_course_recovery_status)
        details = findViewById(R.id.skill_course_recovery_details)
        indicator = findViewById(R.id.skill_course_recovery_indicator)
        cancelButton = findViewById(R.id.skill_course_recovery_cancel)
        closeButton = findViewById(R.id.skill_course_recovery_close)

        cancelButton?.setOnClickListener {
            handle?.cancel()
            status?.setText(R.string.skill_course_recovery_canceled)
            cancelButton?.isEnabled = false
        }
        closeButton?.setOnClickListener { closeImmediately() }

        start()
    }

    private fun start() {
        if (started || cleanedUp) {
            return
        }
        started = true

        val definition = definition ?: return
        handle = CourseMapRecovery.start(
            definition = definition,
            onProgress = { message ->
                if (isAdded && !cleanedUp) {
                    status?.text = message
                }
            },
            onComplete = { result ->
                if (!cleanedUp) {
                    finish(result)
                } else if (!completionDelivered) {
                    completionDelivered = true
                    onComplete?.invoke(result)
                }
            }
        )
    }

    private fun finish(result: CourseRecoveryResult) {
        handle = null
        indicator?.visibility = View.GONE
        cancelButton?.visibility = View.GONE
        closeButton?.visibility = View.VISIBLE
        closeButton?.isEnabled = true

        if (result.canceled) {
            status?.setText(R.string.skill_course_recovery_canceled)
        } else if (!result.finalScanSucceeded) {
            status?.setText(R.string.skill_course_check_failed)
        } else if (result.allReady) {
            status?.setText(R.string.skill_course_recovery_all_ready)
        } else {
            status?.text = getString(R.string.skill_course_recovery_complete, result.unresolved.size)
        }

        val detailText = buildSummary(result)
        val detailView = details
        if (detailView != null) {
            if (detailText.isBlank()) {
                detailView.visibility = View.GONE
            } else {
                detailView.visibility = View.VISIBLE
                detailView.text = detailText
            }
        }

        if (!completionDelivered) {
            completionDelivered = true
            onComplete?.invoke(result)
        }
    }

    private fun buildSummary(result: CourseRecoveryResult): String = buildString {
        if (result.unresolved.isNotEmpty()) {
            append(getString(R.string.skill_course_recovery_manual))
            append('\n')
            for (entry in result.unresolved) {
                append(statusLabel(entry.status))
                append(" #")
                append(entry.originalIndex + 1)
                append(": ")
                append(mapLabel(entry.courseMap))
                append(" — ")
                append(getString(R.string.skill_course_issue_required_md5, entry.courseMap.mapHash))
                append('\n')
            }
        }

        if (result.errors.isNotEmpty()) {
            if (isNotEmpty()) {
                append('\n')
            }
            append(result.errors.joinToString("\n"))
        }
    }

    private fun statusLabel(status: CourseMapStatus): String = getString(
        when (status) {
            CourseMapStatus.READY -> R.string.skill_course_issue_ready
            CourseMapStatus.OUTDATED -> R.string.skill_course_issue_outdated
            CourseMapStatus.MISSING -> R.string.skill_course_issue_missing
            CourseMapStatus.INVALID -> R.string.skill_course_issue_invalid
        }
    )

    private fun mapLabel(map: CourseMap): String {
        val artist = map.artistUnicode.takeUnless { it.isBlank() } ?: map.artist
        val title = map.titleUnicode.takeUnless { it.isBlank() } ?: map.title
        return "$artist - $title [${map.difficulty}]"
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
        handle?.cancel()
        handle = null
    }
}

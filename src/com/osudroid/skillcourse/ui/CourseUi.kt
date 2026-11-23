package com.osudroid.skillcourse.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.Toast
import com.osudroid.data.BeatmapInfo
import com.osudroid.data.SkillCourseEntity
import com.osudroid.skillcourse.CourseDefinition
import com.osudroid.utils.ModHashMap
import com.reco1l.osu.ui.MessageDialog
import java.util.concurrent.CopyOnWriteArrayList
import ru.nsu.ccfit.zuev.osuplus.R

/**
 * Public entry point for the native Skill Course management UI.
 *
 * The gameplay owner installs [onView], [onPlay] and [onHistory]. This object deliberately does not
 * start gameplay by itself; when a callback is missing the requested action is reported as
 * unavailable instead of pretending that it succeeded.
 */
object CourseUi {

    /** Opens a read-only course view. The host owns filtering and song-menu state. */
    @JvmStatic
    var onView: ((SkillCourseEntity) -> Unit)? = null

    /** Starts a fully validated course run. */
    @JvmStatic
    var onPlay: ((CoursePlayRequest) -> Unit)? = null

    /** Opens local attempt history. The host owns the history screen. */
    @JvmStatic
    var onHistory: ((SkillCourseEntity) -> Unit)? = null

    private val refreshListeners = CopyOnWriteArrayList<() -> Unit>()

    internal var activeManager: CourseManagerFragment? = null
        private set
    internal var activePreflight: CoursePreflightFragment? = null
        private set

    /**
     * Shows the standalone course manager, optionally with the currently selected difficulty and
     * mods captured for membership actions.
     */
    @JvmStatic
    @JvmOverloads
    fun showManager(selectedBeatmap: BeatmapInfo? = null, selectedMods: ModHashMap? = null) {
        val action = Runnable {
            activeManager?.let {
                if (it.isAdded) {
                    it.closeImmediately()
                }
            }
            CourseManagerFragment().apply {
                show(selectedBeatmap, selectedMods?.deepCopy())
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }

    internal fun attachManager(manager: CourseManagerFragment) {
        activeManager = manager
    }

    internal fun detachManager(manager: CourseManagerFragment) {
        if (activeManager === manager) {
            activeManager = null
        }
    }

    internal fun attachPreflight(preflight: CoursePreflightFragment) {
        activePreflight = preflight
    }

    internal fun detachPreflight(preflight: CoursePreflightFragment) {
        if (activePreflight === preflight) {
            activePreflight = null
        }
    }

    internal fun addRefreshListener(listener: () -> Unit) {
        if (!refreshListeners.contains(listener)) {
            refreshListeners.add(listener)
        }
    }

    internal fun removeRefreshListener(listener: () -> Unit) {
        refreshListeners.remove(listener)
    }

    internal fun notifyCoursesChanged() {
        runOnMain {
            for (listener in refreshListeners) {
                try {
                    listener()
                } catch (_: Exception) {
                    // A stale or closing fragment must not break other listeners.
                }
            }
        }
    }

    internal fun requestView(entity: SkillCourseEntity) {
        val callback = onView
        if (callback == null) {
            showUnavailable(R.string.skill_course_feature_unavailable_view)
            return
        }
        activePreflight?.closeImmediately()
        activeManager?.closeImmediately()
        runOnMain { callback(entity) }
    }

    internal fun requestHistory(entity: SkillCourseEntity) {
        val callback = onHistory
        if (callback == null) {
            showUnavailable(R.string.skill_course_feature_unavailable_history)
            return
        }
        activePreflight?.closeImmediately()
        activeManager?.closeImmediately()
        runOnMain { callback(entity) }
    }

    internal fun requestPlay(request: CoursePlayRequest) {
        val callback = onPlay
        if (callback == null) {
            showUnavailable(R.string.skill_course_feature_unavailable_play)
            return
        }

        // The manager and preflight must be gone before the engine starts switching scenes.
        activePreflight?.closeImmediately()
        activeManager?.closeImmediately()
        activePreflight = null
        activeManager = null

        runOnMain { callback(request) }
    }

    internal fun showUnavailable(messageRes: Int) {
        runOnMain {
            val activity = ru.nsu.ccfit.zuev.osu.GlobalManager.getInstance().mainActivity ?: return@runOnMain
            MessageDialog()
                .setTitle(activity.getString(R.string.skill_course_error))
                .setMessage(activity.getString(messageRes))
                .addButton(activity.getString(android.R.string.ok)) { it.dismiss() }
                .show()
        }
    }

    internal fun showMessage(context: Context, text: CharSequence) {
        runOnMain { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post { block() }
        }
    }

    /**
     * The preferred width bounded by the available display width and a small margin on each side.
     */
    internal fun boundedWidth(context: Context, preferredDp: Float): Int {
        val metrics = context.resources.displayMetrics
        val preferred = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            preferredDp,
            metrics
        ).toInt()
        val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, metrics).toInt()
        return (metrics.widthPixels - margin).coerceAtMost(preferred).coerceAtLeast(1)
    }
}

/**
 * A frozen course start request. The host must not need to gather any further configuration because
 * the per-map speeds and additional mods were validated against every authored map before this was
 * delivered.
 */
data class CoursePlayRequest(
    val entity: SkillCourseEntity,
    val definition: CourseDefinition,
    val additionalMods: ModHashMap,
    val speeds: Map<Int, Float>,
    val additionalModsByIndex: Map<Int, ModHashMap> = emptyMap(),
)

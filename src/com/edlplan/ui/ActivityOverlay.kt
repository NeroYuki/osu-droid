package com.edlplan.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.edlplan.ui.fragment.BackPressListener
import com.edlplan.ui.fragment.BaseFragment
import java.util.concurrent.atomic.AtomicLong

object ActivityOverlay {
    private var fragmentManager: FragmentManager? = null
    private val displayingOverlay = mutableListOf<Fragment>()
    private var context: Activity? = null
    private var containerId = 0
    private val stateMap = mutableMapOf<String, Fragment.SavedState?>()

    private val nextSuspensionToken = AtomicLong(1L)
    private var activeSuspension: SuspensionState? = null

    /**
     * A scoped suspension token returned by [suspendOverlays].
     *
     * The token is intentionally opaque; callers must pass the exact instance they received back to
     * [resumeOverlays] so a stale restore cannot unhide a newer native scene session.
     */
    class Suspension internal constructor(internal val token: Long)

    private class HiddenView(val view: View, val visibility: Int)

    private class SuspensionState(
        val token: Long,
        val owner: Fragment,
        val activity: FragmentActivity,
        val hiddenViews: MutableList<HiddenView>,
        val onCancelled: () -> Unit
    ) {
        var finished = false
        var ownerObserver: LifecycleEventObserver? = null
        var activityObserver: LifecycleEventObserver? = null
        var decorView: View? = null
        var focusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null

        /**
         * Hiding Android fragment views can dismiss the soft keyboard, which may transiently steal
         * window focus. Ignore focus changes during that short transition so optional mod settings
         * remain usable.
         */
        val ignoreFocusLossUntil = SystemClock.uptimeMillis() + 500L
    }

    @JvmStatic
    fun initial(activity: AppCompatActivity, id: Int) {
        cancelActiveSuspension()
        context = activity
        containerId = id
        fragmentManager = activity.supportFragmentManager
    }

    @JvmStatic
    @Synchronized
    fun onBackPress(): Boolean {
        // While a native scene owns input, Android fragments must not consume Back. This lets the
        // native child scene receive the key through MainActivity and close itself normally.
        if (activeSuspension != null) {
            return false
        }

        if (fragmentManager != null && displayingOverlay.size > 0) {
            val overlay: Fragment = displayingOverlay[displayingOverlay.size - 1]
            if (overlay is BackPressListener) {
                overlay.callDismissOnBackPress()
            }
            return overlay !is BaseFragment || overlay.interceptBackPress
        }
        return false
    }

    @Synchronized
    fun dismissOverlay(fragment: Fragment) {
        if (fragmentManager != null && fragment.isAdded) {

            if (displayingOverlay.contains(fragment)) {
                displayingOverlay.remove(fragment)
                fragmentManager!!.beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
            }
        }
    }

    @Synchronized
    fun addOverlay(fragment: Fragment, tag: String?) {
        if (fragmentManager != null && !fragment.isAdded) {

            if (displayingOverlay.contains(fragment) || fragmentManager!!.findFragmentByTag(tag) != null) {
                displayingOverlay.remove(fragment)
                fragmentManager!!.beginTransaction()
                        .remove(fragment)
                        .add(containerId, fragment, tag)
                        .commitAllowingStateLoss()
                return
            }
            displayingOverlay.add(fragment)
            fragmentManager!!.beginTransaction()
                    .add(containerId, fragment, tag)
                    .commitAllowingStateLoss()
        }
    }

    @Synchronized
    fun saveState(tag: String?) {
        val fragment = fragmentManager!!.findFragmentByTag(tag)
        if (fragmentManager != null && fragment?.isAdded == true) {
            stateMap[tag!!] = fragmentManager!!.saveFragmentInstanceState(fragment)
        }
    }

    @Synchronized
    fun loadState(tag: String?): Fragment.SavedState? {
        val fragment = fragmentManager!!.findFragmentByTag(tag)
        if (fragmentManager != null && fragment?.isAdded == true) {
            return stateMap.remove(tag!!)
        }

        return null
    }

    fun runOnUiThread(runnable: Runnable?) {
        context!!.runOnUiThread(runnable)
    }

    /**
     * Temporarily hides the root views of all displayed Android fragments so that an engine-native
     * child scene can draw and receive input.
     *
     * Only fragment root views are touched; the engine view and fragment state are left intact. Back
     * presses are also handed to the native scene until [resumeOverlays] is called.
     *
     * @return A token, or `null` if another suspension is already active or no activity/owner is
     * available.
     */
    @JvmStatic
    @Synchronized
    fun suspendOverlays(owner: Fragment, onCancelled: () -> Unit): Suspension? {
        if (activeSuspension != null) {
            return null
        }

        val activity = context as? FragmentActivity ?: return null

        if (fragmentManager == null || !owner.isAdded || owner.activity !== activity) {
            return null
        }

        val token = nextSuspensionToken.getAndIncrement()
        val hiddenViews = mutableListOf<HiddenView>()
        val roots = LinkedHashSet<Fragment>()
        roots.addAll(displayingOverlay)
        roots.add(owner)

        for (fragment in roots) {
            if (!fragment.isAdded) {
                continue
            }

            val view = fragment.view ?: continue

            if (view.visibility == View.GONE) {
                continue
            }

            hiddenViews.add(HiddenView(view, view.visibility))
            view.visibility = View.GONE
        }

        val state = SuspensionState(token, owner, activity, hiddenViews, onCancelled)
        activeSuspension = state
        registerLifecycleObservers(state)

        // The activity may already be stopped; in that case the lifecycle observer above cancels
        // this suspension immediately.
        if (state.finished) {
            return null
        }

        registerFocusObserver(state)
        return Suspension(token)
    }

    /**
     * Restores the fragment root views hidden by [suspendOverlays] and re-enables Android overlay
     * Back interception. Safe to call from any thread; view changes are applied on the UI thread.
     */
    @JvmStatic
    fun resumeOverlays(suspension: Suspension?) {
        suspension ?: return

        synchronized(this) {
            val state = activeSuspension ?: return
            if (state.token != suspension.token || state.finished) {
                return
            }

            state.finished = true
            activeSuspension = null
            restoreSuspension(state)
        }
    }

    @Synchronized
    private fun cancelSuspension(token: Long) {
        val state = activeSuspension ?: return
        if (state.token != token || state.finished) {
            return
        }

        state.finished = true
        activeSuspension = null
        restoreSuspension(state)
        runOnMain { state.onCancelled() }
    }

    private fun cancelActiveSuspension() {
        val state = synchronized(this) {
            val active = activeSuspension ?: return
            if (active.finished) {
                return
            }

            active.finished = true
            activeSuspension = null
            active
        }

        restoreSuspension(state)
        runOnMain { state.onCancelled() }
    }

    private fun registerLifecycleObservers(state: SuspensionState) {
        val ownerObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                cancelSuspension(state.token)
            }
        }
        state.ownerObserver = ownerObserver
        state.owner.lifecycle.addObserver(ownerObserver)

        if (state.finished) {
            return
        }

        val activityObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                cancelSuspension(state.token)
            }
        }
        state.activityObserver = activityObserver
        state.activity.lifecycle.addObserver(activityObserver)
    }

    private fun registerFocusObserver(state: SuspensionState) {
        val decorView = state.activity.window?.decorView ?: return
        state.decorView = decorView

        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus &&
                SystemClock.uptimeMillis() >= state.ignoreFocusLossUntil &&
                !state.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                cancelSuspension(state.token)
            }
        }
        state.focusListener = listener
        decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
    }

    private fun restoreSuspension(state: SuspensionState) {
        unregisterObservers(state)

        val activity = state.activity
        if (activity.isDestroyed) {
            state.hiddenViews.clear()
            return
        }

        val restore = Runnable {
            for (hidden in state.hiddenViews) {
                try {
                    hidden.view.visibility = hidden.visibility
                } catch (_: Exception) {
                    // Views may already be detached by a lifecycle transition.
                }
            }
            state.hiddenViews.clear()
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            restore.run()
        } else {
            activity.runOnUiThread(restore)
        }
    }

    private fun unregisterObservers(state: SuspensionState) {
        state.ownerObserver?.let { observer ->
            try {
                state.owner.lifecycle.removeObserver(observer)
            } catch (_: Exception) {
            }
        }
        state.ownerObserver = null

        state.activityObserver?.let { observer ->
            try {
                state.activity.lifecycle.removeObserver(observer)
            } catch (_: Exception) {
            }
        }
        state.activityObserver = null

        state.focusListener?.let { listener ->
            try {
                state.decorView?.viewTreeObserver?.removeOnWindowFocusChangeListener(listener)
            } catch (_: Exception) {
            }
        }
        state.focusListener = null
        state.decorView = null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post { block() }
        }
    }
}

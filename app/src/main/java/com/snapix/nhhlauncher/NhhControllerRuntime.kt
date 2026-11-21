package com.snapix.nhhlauncher

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewTreeObserver
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import com.winlator.xserver.XServer
import java.util.concurrent.CopyOnWriteArrayList

object NhhControllerRuntime {
    const val NHH_PROFILE_ID = 100
    private const val TEMPLATE_PROFILE_ID = 3
    private const val PROFILE_NAME = "NHH Xbox"

    private const val PREF_OVERLAY_OPACITY = "nhh_overlay_opacity"
    private const val PREF_CONTROL_SCALE = "nhh_control_scale"
    private const val PREF_STICK_DEADZONE = "nhh_stick_deadzone"
    private const val PREF_INPUT_API = "nhh_input_api"

    enum class LifecycleState {
        DETACHED, ATTACHED_WAITING_MEASURE, ACTIVE_DISPATCHING, PAUSED
    }

    data class DiagnosticsSnapshot(
        val state: LifecycleState = LifecycleState.DETACHED,
        val profileId: Int = NHH_PROFILE_ID,
        val elementCount: Int = 0,
        val isVirtualGamepad: Boolean = true,
        val touchscreenControlsEnabled: Boolean = false,
        val preferredInputApi: String = "XINPUT",
        val winHandlerReady: Boolean = false,
        val dispatchCount: Long = 0,
        val lastDispatchEvent: String = "Not attached",
        val viewportWidth: Int = 0,
        val viewportHeight: Int = 0
    )

    @Volatile
    var snapshot: DiagnosticsSnapshot = DiagnosticsSnapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(DiagnosticsSnapshot) -> Unit>()
    private var attachedView: InputControlsView? = null
    private var attachedProfile: ControlsProfile? = null

    fun addDiagnosticsListener(listener: (DiagnosticsSnapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot)
    }

    fun removeDiagnosticsListener(listener: (DiagnosticsSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    private fun update(block: (DiagnosticsSnapshot) -> DiagnosticsSnapshot) {
        snapshot = block(snapshot)
        listeners.forEach { it(snapshot) }
    }

    @JvmStatic
    fun ensureNhhProfile(context: Context, forceReset: Boolean = false): ControlsProfile {
        val manager = InputControlsManager(context)
        var profile = manager.getProfile(NHH_PROFILE_ID)

        if (profile == null || forceReset) {
            if (profile != null) {
                manager.removeProfile(profile)
            }

            val template = manager.getProfile(TEMPLATE_PROFILE_ID)?.takeIf { it.isVirtualGamepad }
                ?: manager.getProfiles().firstOrNull { it.isVirtualGamepad }
                ?: manager.getProfiles().firstOrNull()
                ?: throw IllegalStateException("Winlator has no input-control profile.")

            profile = manager.duplicateProfile(template)
            profile.setName(PROFILE_NAME)
            profile.save()
        }

        return profile
    }

    fun resetNhhProfile(context: Context) {
        ensureNhhProfile(context, true)
    }

    @JvmStatic
    fun attachToSession(
        activity: Activity,
        inputControlsView: InputControlsView,
        xServer: XServer?,
        profileId: Int = NHH_PROFILE_ID
    ) {
        val prefs = activity.getSharedPreferences("nhh", Context.MODE_PRIVATE)
        val profile = if (profileId == NHH_PROFILE_ID) {
            ensureNhhProfile(activity)
        } else {
            InputControlsManager.loadProfile(
                activity,
                ControlsProfile.getProfileFile(activity, profileId)
            ) ?: ensureNhhProfile(activity)
        }

        attachedView = inputControlsView
        attachedProfile = profile

        inputControlsView.setXServer(xServer)
        inputControlsView.setOverlayOpacity(getOverlayOpacity(prefs))
        inputControlsView.setShowTouchscreenControls(true)
        inputControlsView.setProfile(profile)
        inputControlsView.visibility = View.VISIBLE
        inputControlsView.bringToFront()

        val finishAttach = Runnable {
            val width = inputControlsView.width
            val height = inputControlsView.height
            if (width <= 0 || height <= 0) return@Runnable

            profile.loadElements(inputControlsView)
            inputControlsView.invalidate()

            update {
                it.copy(
                    state = LifecycleState.ACTIVE_DISPATCHING,
                    profileId = profile.id,
                    elementCount = profile.getElements().size,
                    isVirtualGamepad = profile.isVirtualGamepad,
                    touchscreenControlsEnabled = true,
                    preferredInputApi = getPreferredInputApi(prefs),
                    winHandlerReady = xServer != null && xServer.getWinHandler() != null,
                    viewportWidth = width,
                    viewportHeight = height,
                    lastDispatchEvent = "Controller attached"
                )
            }
        }

        if (inputControlsView.width > 0 && inputControlsView.height > 0) {
            inputControlsView.post(finishAttach)
        } else {
            inputControlsView.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (inputControlsView.width > 0 && inputControlsView.height > 0) {
                            inputControlsView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            finishAttach.run()
                        }
                    }
                }
            )
        }

        update {
            it.copy(
                state = LifecycleState.ATTACHED_WAITING_MEASURE,
                profileId = profile.id,
                preferredInputApi = getPreferredInputApi(prefs),
                touchscreenControlsEnabled = true
            )
        }
    }

    @JvmStatic
    fun onSessionPaused() {
        attachedView?.visibility = View.GONE
        update {
            it.copy(
                state = LifecycleState.PAUSED,
                lastDispatchEvent = "Session paused; overlay hidden"
            )
        }
    }

    @JvmStatic
    fun onSessionResumed(activity: Activity) {
        val view = attachedView ?: return
        view.visibility = View.VISIBLE
        view.post {
            attachedProfile?.loadElements(view)
            view.invalidate()
            update {
                it.copy(
                    state = LifecycleState.ACTIVE_DISPATCHING,
                    viewportWidth = view.width,
                    viewportHeight = view.height,
                    lastDispatchEvent = "Session resumed"
                )
            }
        }
    }

    @JvmStatic
    fun onViewportChanged(activity: Activity, view: InputControlsView) {
        if (attachedView !== view) return
        attachedProfile?.loadElements(view)
        view.invalidate()
        update {
            it.copy(
                viewportWidth = view.width,
                viewportHeight = view.height,
                lastDispatchEvent = "Viewport changed"
            )
        }
    }

    @JvmStatic
    fun detachFromSession() {
        attachedView?.visibility = View.GONE
        attachedView = null
        attachedProfile = null
        update { DiagnosticsSnapshot() }
    }

    @JvmStatic
    fun recordDispatchedEvent(description: String) {
        update {
            it.copy(
                state = LifecycleState.ACTIVE_DISPATCHING,
                winHandlerReady = true,
                dispatchCount = it.dispatchCount + 1L,
                lastDispatchEvent = description
            )
        }
    }

    @JvmStatic
    fun applySafeAreaInsets(activity: Activity, view: View) {
        view.requestApplyInsets()
    }

    fun getOverlayOpacity(prefs: SharedPreferences): Float =
        prefs.getFloat(PREF_OVERLAY_OPACITY, 0.55f).coerceIn(0.15f, 1f)

    fun getControlScale(prefs: SharedPreferences): Float =
        prefs.getFloat(PREF_CONTROL_SCALE, 1f).coerceIn(0.7f, 1.35f)

    fun getStickDeadzone(prefs: SharedPreferences): Float =
        prefs.getFloat(PREF_STICK_DEADZONE, 0.12f).coerceIn(0.02f, 0.35f)

    fun getPreferredInputApi(prefs: SharedPreferences): String =
        prefs.getString(PREF_INPUT_API, "XINPUT") ?: "XINPUT"

    fun saveControllerPreferences(
        context: Context,
        prefs: SharedPreferences,
        opacity: Float,
        scale: Float,
        deadzone: Float,
        inputApi: String
    ) {
        prefs.edit()
            .putFloat(PREF_OVERLAY_OPACITY, opacity.coerceIn(0.15f, 1f))
            .putFloat(PREF_CONTROL_SCALE, scale.coerceIn(0.7f, 1.35f))
            .putFloat(PREF_STICK_DEADZONE, deadzone.coerceIn(0.02f, 0.35f))
            .putString(PREF_INPUT_API, inputApi)
            .apply()
    }
}

package com.snapix.nhhlauncher

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.ViewTreeObserver
import java.io.FileOutputStream
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import com.winlator.xserver.XServer
import java.util.concurrent.CopyOnWriteArrayList

object NhhControllerRuntime {
    const val MAIN_PROFILE_ID = 5
    const val NO_KEY_PROFILE_ID = 6
    const val DEFAULT_PROFILE_ID = MAIN_PROFILE_ID
    private const val PREF_SELECTED_PROFILE = "nhh_selected_profile_id"

    private const val PREF_OVERLAY_OPACITY = "nhh_overlay_opacity"
    private const val PREF_CONTROL_SCALE = "nhh_control_scale"
    private const val PREF_STICK_DEADZONE = "nhh_stick_deadzone"
    private const val PREF_INPUT_API = "nhh_input_api"

    enum class LifecycleState {
        DETACHED, ATTACHED_WAITING_MEASURE, ACTIVE_DISPATCHING, PAUSED
    }

    data class DiagnosticsSnapshot(
        val state: LifecycleState = LifecycleState.DETACHED,
        val profileId: Int = DEFAULT_PROFILE_ID,
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
    fun selectedProfileId(prefs: SharedPreferences): Int {
        return prefs.getInt(PREF_SELECTED_PROFILE, DEFAULT_PROFILE_ID).let {
            if (it == MAIN_PROFILE_ID || it == NO_KEY_PROFILE_ID) it else DEFAULT_PROFILE_ID
        }
    }

    @JvmStatic
    fun setSelectedProfile(prefs: SharedPreferences, profileId: Int) {
        require(profileId == MAIN_PROFILE_ID || profileId == NO_KEY_PROFILE_ID)
        prefs.edit().putInt(PREF_SELECTED_PROFILE, profileId).apply()
    }

    @JvmStatic
    fun ensureNhhProfile(context: Context, profileId: Int = DEFAULT_PROFILE_ID): ControlsProfile {
        require(profileId == MAIN_PROFILE_ID || profileId == NO_KEY_PROFILE_ID)

        // These are normal Winlator controller profiles. Install them into
        // Winlator's real files/profiles directory before loading them.
        installWinlatorProfileIfNeeded(context, profileId)

        val profileFile = ControlsProfile.getProfileFile(context, profileId)
        val profile = InputControlsManager.loadProfile(context, profileFile)
            ?: throw IllegalStateException("NHH controller layout #$profileId could not be loaded.")
        profile.loadControllers()
        return profile
    }

    private fun installWinlatorProfileIfNeeded(context: Context, profileId: Int) {
        val assetPath = "inputcontrols/profiles/controls-$profileId.icp"
        val destination = ControlsProfile.getProfileFile(context, profileId)

        val existing = if (destination.isFile) {
            InputControlsManager.loadProfile(context, destination)
        } else null

        // Preserve a valid profile so edits made in the Controls Editor survive.
        if (existing != null &&
            existing.id == profileId &&
            ((profileId == MAIN_PROFILE_ID && existing.getName() == "NHH MAIN") ||
             (profileId == NO_KEY_PROFILE_ID && existing.getName() == "NHH MAIN no key"))) {
            return
        }

        destination.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }

    fun resetNhhProfile(context: Context) {
        val prefs = context.getSharedPreferences("nhh", Context.MODE_PRIVATE)
        val selected = selectedProfileId(prefs)
        ensureNhhProfile(context, selected)
        ensureNhhProfile(context, if (selected == MAIN_PROFILE_ID) NO_KEY_PROFILE_ID else MAIN_PROFILE_ID)
    }

    @JvmStatic
    fun attachToSession(
        activity: Activity,
        inputControlsView: InputControlsView,
        xServer: XServer?,
        profileId: Int = DEFAULT_PROFILE_ID
    ) {
        val prefs = activity.getSharedPreferences("nhh", Context.MODE_PRIVATE)
        val profile = ensureNhhProfile(activity, profileId)

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
    fun onViewportChanged(view: InputControlsView) {
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

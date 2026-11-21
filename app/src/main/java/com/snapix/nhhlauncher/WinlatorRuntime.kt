package com.snapix.nhhlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import com.winlator.ControlsEditorActivity
import com.winlator.XServerDisplayActivity
import com.winlator.box64.Box64Preset
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.AppUtils
import com.winlator.core.Callback
import com.winlator.core.WineThemeManager
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import com.winlator.container.GraphicsDrivers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WinlatorRuntime {
    const val PREF_CONTAINER_ID = "winlator_container_id"
    private const val PREF_GAME_PATH = "game_exe"
    private const val PREF_PROFILE = "nhh_selected_profile_id"
    private const val PREF_FULLSCREEN = "nhh_force_fullscreen"
    private const val CONTAINER_NAME = "NHH"

    fun installCrashRecorder(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            NhhControllerRuntime.detachFromSession()
            appendLaunchLog(app, "UNCAUGHT " + thread.name + ": " + Log.getStackTraceString(throwable))
            previous?.uncaughtException(thread, throwable)
        }
    }

    suspend fun ensureReady(
        activity: AppCompatActivity,
        executable: File,
        prefs: SharedPreferences,
        onProgress: (Float, String) -> Unit,
        forceRebuild: Boolean
    ) {
        val root = File(activity.filesDir, "game").canonicalFile
        val exe = executable.canonicalFile
        require(exe.isFile) { "The selected executable no longer exists." }
        require(exe.path.startsWith(root.path + File.separator)) {
            "The executable is outside the NHH game directory."
        }

        var rootFs = RootFS.find(activity)
        val latest = RootFSInstaller.LATEST_VERSION.toInt()

        if (!rootFs.isValid() || rootFs.getVersion() < latest) {
            onProgress(0.02f, "Installing required system components…")
            RootFSInstaller.installIfNeeded(activity)

            var waited = 0L
            while (true) {
                delay(300)
                waited += 300
                rootFs = RootFS.find(activity)
                val progress = RootFSInstaller.getNhhInstallProgress()

                if (rootFs.isValid() && rootFs.getVersion() >= latest) break
                if (progress < 0) throw IllegalStateException("Could not install the required system components.")
                if (waited > 15 * 60 * 1000) throw IllegalStateException("System setup timed out.")

                onProgress(
                    if (progress > 0) (progress / 100f).coerceIn(0.02f, 0.38f) else 0.02f,
                    "Installing required components…"
                )
            }
        }

        onProgress(0.42f, "Preparing NHH touch controls…")
        val profile = NhhControllerRuntime.ensureNhhProfile(activity, NhhControllerRuntime.selectedProfileId(prefs))
        val manager = ContainerManager(activity)
        val oldId = prefs.getInt(PREF_CONTAINER_ID, 0)
        val existing = if (oldId > 0) manager.getContainerById(oldId) else null

        if (!forceRebuild && existing != null) {
            mapGameDrive(existing, root)
            existing.saveData()
            prefs.edit()
                .putString(PREF_GAME_PATH, exe.absolutePath)
                .putInt(PREF_PROFILE, NhhControllerRuntime.selectedProfileId(prefs))
                .apply()
            onProgress(1f, "NHH runtime verified.")
            return
        }

        onProgress(0.55f, "Creating the NHH game environment…")
        val container = createContainer(manager, activity)
        mapGameDrive(container, root)
        container.saveData()

        prefs.edit()
            .putInt(PREF_CONTAINER_ID, container.id)
            .putString(PREF_GAME_PATH, exe.absolutePath)
            .putInt(PREF_PROFILE, profile.id)
            .apply()

        if (existing != null) {
            manager.removeContainerAsync(existing) {}
        }

        onProgress(1f, "NHH runtime ready.")
    }

    private fun mapGameDrive(container: Container, root: File) {
        val legacySandboxPrefix = "/data/data/" + "com." + "winlator/"
        val nhhSandboxPrefix = "/data/data/com.noheroshere.snap/"
        val drives = container.drivesIterator()
            .filterNot { it.letter.equals("G", true) }
            .map { drive ->
                val path = when {
                    drive.letter.equals("E", true) -> AppUtils.INTERNAL_STORAGE
                    drive.path.startsWith(legacySandboxPrefix) ->
                        drive.path.replaceFirst(legacySandboxPrefix, nhhSandboxPrefix)
                    else -> drive.path
                }
                drive.letter + ":" + path.trimEnd('/') + "/"
            }
            .toMutableList()

        drives.add("G:" + root.canonicalPath.trimEnd('/') + "/")
        container.setDrives(drives.joinToString(""))
    }

    private suspend fun createContainer(manager: ContainerManager, context: Context): Container =
        suspendCancellableCoroutine { continuation ->
            val data = JSONObject()
            data.put("name", CONTAINER_NAME)
            data.put("screenSize", Container.DEFAULT_SCREEN_SIZE)
            data.put("envVars", Container.DEFAULT_ENV_VARS)
            data.put("cpuList", "")
            data.put("cpuListWoW64", "")
            data.put("graphicsDriver", GraphicsDrivers.getDefaultDriver(context))
            data.put("dxwrapper", Container.DEFAULT_DXWRAPPER)
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER)
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS)
            data.put("drives", Container.DEFAULT_DRIVES)
            data.put("hudMode", 0)
            data.put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL)
            data.put("box64Preset", Box64Preset.DEFAULT)
            data.put("desktopTheme", WineThemeManager.DEFAULT_DESKTOP_THEME)

            manager.createContainerAsync(data, object : Callback<Container> {
                override fun call(container: Container?) {
                    if (!continuation.isActive) return
                    if (container != null) {
                        continuation.resume(container)
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException("Could not create the NHH game environment.")
                        )
                    }
                }
            })
        }

    fun openControlsEditor(activity: AppCompatActivity, profileId: Int = NhhControllerRuntime.selectedProfileId(activity.getSharedPreferences("nhh", Context.MODE_PRIVATE))) {
        val profile = NhhControllerRuntime.ensureNhhProfile(activity, profileId)
        activity.startActivity(
            Intent(activity, ControlsEditorActivity::class.java).apply {
                putExtra("profile_id", profile.id)
                putExtra("nhh_editor_safe_area", true)
            }
        )
    }

    fun resetControlsLayout(context: Context) {
        NhhControllerRuntime.resetNhhProfile(context)
        appendLaunchLog(context, "Restored both NHH control profiles.")
    }

    fun enterDesktop(activity: MainActivity, prefs: SharedPreferences) {
        val id = prefs.getInt(PREF_CONTAINER_ID, 0)
        require(id > 0) { "The NHH container has not been created yet." }
        require(ContainerManager(activity).getContainerById(id) != null) {
            "The NHH container is missing."
        }

        appendLaunchLog(activity, "Entering NHH desktop mode. container=" + id)
        activity.startActivity(
            Intent(activity, XServerDisplayActivity::class.java).apply {
                putExtra("container_id", id)
                putExtra("nhh_desktop_mode", true)
            }
        )
    }

    fun loadSettings(context: Context, prefs: SharedPreferences): LauncherRuntimeSettings {
        val id = prefs.getInt(PREF_CONTAINER_ID, 0)
        val container = if (id > 0) ContainerManager(context).getContainerById(id) else null

        if (container == null) {
            return LauncherRuntimeSettings(
                forceFullscreen = prefs.getBoolean(PREF_FULLSCREEN, true),
                onScreenControls = prefs.getInt(PREF_PROFILE, NhhControllerRuntime.DEFAULT_PROFILE_ID) != 0,
                preferredInputApi = NhhControllerRuntime.getPreferredInputApi(prefs),
                overlayOpacity = NhhControllerRuntime.getOverlayOpacity(prefs),
                controlScale = NhhControllerRuntime.getControlScale(prefs),
                stickDeadzone = NhhControllerRuntime.getStickDeadzone(prefs)
            )
        }

        val ids = GraphicsDrivers.parseIdentifiers(container.getGraphicsDriver())
        return LauncherRuntimeSettings(
            resolution = container.getScreenSize(),
            vulkanDriver = ids.getOrElse(0) { GraphicsDrivers.DEFAULT_VULKAN_DRIVER },
            openGlDriver = ids.getOrElse(1) { GraphicsDrivers.DEFAULT_OPENGL_DRIVER },
            dxWrapper = container.getDXWrapper(),
            audioDriver = container.getAudioDriver(),
            box64Preset = container.getBox64Preset(),
            wineEsync = !container.getEnvVars().split(Regex("\\s+")).contains("WINEESYNC=0"),
            showFpsHud = container.getHUDMode().toInt() != 0,
            forceFullscreen = prefs.getBoolean(PREF_FULLSCREEN, true),
            onScreenControls = prefs.getInt(PREF_PROFILE, NhhControllerRuntime.DEFAULT_PROFILE_ID) != 0,
            preferredInputApi = NhhControllerRuntime.getPreferredInputApi(prefs),
            overlayOpacity = NhhControllerRuntime.getOverlayOpacity(prefs),
            controlScale = NhhControllerRuntime.getControlScale(prefs),
            stickDeadzone = NhhControllerRuntime.getStickDeadzone(prefs)
        )
    }

    fun saveSettings(context: Context, prefs: SharedPreferences, settings: LauncherRuntimeSettings) {
        val id = prefs.getInt(PREF_CONTAINER_ID, 0)
        require(id > 0) { "Import a game before changing runtime settings." }

        val container = ContainerManager(context).getContainerById(id)
            ?: throw IllegalStateException("The NHH container is missing.")

        container.setScreenSize(settings.resolution)
        container.setGraphicsDriver(settings.vulkanDriver + "," + settings.openGlDriver)
        container.setDXWrapper(settings.dxWrapper)
        container.setAudioDriver(settings.audioDriver)
        container.setBox64Preset(settings.box64Preset)
        container.setEnvVars(
            setEnv(
                container.getEnvVars(),
                "WINEESYNC",
                if (settings.wineEsync) "1" else "0"
            )
        )
        container.setHUDMode((if (settings.showFpsHud) 1 else 0).toByte())
        container.saveData()

        prefs.edit()
            .putInt(PREF_PROFILE, if (settings.onScreenControls) NhhControllerRuntime.selectedProfileId(prefs) else 0)
            .putBoolean(PREF_FULLSCREEN, settings.forceFullscreen)
            .apply()

        NhhControllerRuntime.saveControllerPreferences(
            context,
            prefs,
            settings.overlayOpacity,
            settings.controlScale,
            settings.stickDeadzone,
            settings.preferredInputApi
        )
    }

    fun launchGame(context: Context, prefs: SharedPreferences) {
        val id = prefs.getInt(PREF_CONTAINER_ID, 0)
        require(id > 0) { "The NHH container has not been created." }

        val container = ContainerManager(context).getContainerById(id)
            ?: throw IllegalStateException("The NHH container no longer exists.")

        val path = prefs.getString(PREF_GAME_PATH, null)
            ?: throw IllegalStateException("The game executable path is missing.")
        val executable = File(path).canonicalFile
        require(executable.isFile) { "The selected executable is missing." }

        val profileId = prefs.getInt(PREF_PROFILE, NhhControllerRuntime.DEFAULT_PROFILE_ID)
        if (profileId != 0) NhhControllerRuntime.ensureNhhProfile(context, profileId)

        val intent = Intent(context, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", container.id)
            putExtra("exec_path", executable.absolutePath)
            putExtra("nhh_controls_profile_id", profileId)
            putExtra("nhh_force_fullscreen", prefs.getBoolean(PREF_FULLSCREEN, true))
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }

    fun readLaunchLog(context: Context): String {
        val file = File(context.filesDir, "nhh-launch.log")
        return if (file.isFile) file.readText() else "No launch report recorded."
    }

    fun shareLaunchReport(context: Context) {
        val d = NhhControllerRuntime.snapshot
        val report = buildString {
            appendLine("=== NHH Controller Diagnostics ===")
            appendLine("State: " + d.state)
            appendLine("Profile: " + d.profileId)
            appendLine("Elements: " + d.elementCount)
            appendLine("VirtualGamepad: " + d.isVirtualGamepad)
            appendLine("Dispatches: " + d.dispatchCount)
            appendLine("Last event: " + d.lastDispatchEvent)
            append(readLaunchLog(context))
        }

        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "NHH Launcher diagnostic report")
                    putExtra(Intent.EXTRA_TEXT, report)
                },
                "Share diagnostic report"
            )
        )
    }

    private fun setEnv(current: String, key: String, value: String): String =
        current.split(Regex("\\s+"))
            .filter { it.isNotBlank() && !it.startsWith(key + "=") }
            .plus(key + "=" + value)
            .joinToString(" ")

    private fun appendLaunchLog(context: Context, message: String) {
        try {
            File(context.filesDir, "nhh-launch.log").appendText(
                System.currentTimeMillis().toString() + " " + message + "\\n"
            )
        } catch (_: Throwable) {
        }
    }
}

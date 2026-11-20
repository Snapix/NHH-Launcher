package com.snapix.nhhlauncher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.winlator.XServerDisplayActivity
import com.winlator.box64.Box64Preset
import com.winlator.container.AudioDrivers
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.container.DXWrappers
import com.winlator.container.GraphicsDrivers
import com.winlator.core.WineThemeManager
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import com.winlator.inputcontrols.InputControlsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LauncherRuntimeSettings(
    val resolution: String = "1280x720",
    val vulkanDriver: String = GraphicsDrivers.DEFAULT_VULKAN_DRIVER,
    val openGlDriver: String = GraphicsDrivers.DEFAULT_OPENGL_DRIVER,
    val dxWrapper: String = DXWrappers.DXVK,
    val audioDriver: String = AudioDrivers.ALSA,
    val box64Preset: String = Box64Preset.DEFAULT,
    val wineEsync: Boolean = true,
    val onScreenControls: Boolean = true
)

object WinlatorRuntime {
    private const val PREF_CONTAINER_ID = "winlator_container_id"
    private const val PREF_GAME_PATH = "winlator_game_path"
    private const val PREF_CONTROLS_PROFILE = "nhh_controls_profile_id"
    private const val PREFS = "nhh"

    private const val CONTAINER_NAME = "NHH"
    private const val GAME_DRIVE = "G"
    private val rootfsVersion = RootFSInstaller.LATEST_VERSION.toInt()

    private val resolutions = setOf("960x540", "1280x720", "1600x900", "1920x1080")
    private val vulkanDrivers = setOf(GraphicsDrivers.VORTEK, GraphicsDrivers.TURNIP)
    private val openGlDrivers = setOf(GraphicsDrivers.GLADIO, GraphicsDrivers.ZINK, GraphicsDrivers.VIRGL)
    private val dxWrappers = setOf(DXWrappers.DXVK, DXWrappers.WINED3D)
    private val audioDrivers = setOf(AudioDrivers.ALSA, AudioDrivers.PULSEAUDIO)
    private val box64Presets = setOf(
        Box64Preset.STABILITY,
        Box64Preset.CONSERVATIVE,
        Box64Preset.INTERMEDIATE,
        Box64Preset.PERFORMANCE
    )
    @Volatile private var crashRecorderInstalled = false
    private val setupLock = Mutex()

    @Synchronized
    fun installCrashRecorder(context: Context) {
        if (crashRecorderInstalled) return
        crashRecorderInstalled = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            appendLaunchLog(
                appContext,
                "UNCAUGHT JAVA EXCEPTION on " + thread.name + ":\\n" + Log.getStackTraceString(throwable)
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun shareLaunchReport(context: Context) {
        val report = readLaunchLog(context)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NHH Launcher diagnostic report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share launch report"))
    }

    suspend fun prepare(
        activity: MainActivity,
        gameExe: File,
        prefs: SharedPreferences,
        onProgress: (Float, String) -> Unit
    ) {
        ensureReady(activity, gameExe, prefs, onProgress, forceRebuild = false)
    }

    suspend fun ensureReady(
        activity: MainActivity,
        gameExe: File,
        prefs: SharedPreferences,
        onProgress: (Float, String) -> Unit,
        forceRebuild: Boolean = false
    ) = setupLock.withLock {
        // Winlator's Android-side managers (ContainerManager/InputControlsManager)
        // may create Handlers internally. They must be called from a Looper-backed
        // thread. The previous implementation wrapped the entire runtime setup in
        // Dispatchers.IO, which caused:
        // "Can't create handler inside thread ... that has not called Looper.prepare()".
        // Keep Winlator API calls on the main thread; long waits use suspend delay.
        val canonicalExe = gameExe.canonicalFile
        val gameRoot = File(activity.filesDir, "game").canonicalFile
        require(canonicalExe.isFile) { "The selected NHH executable no longer exists." }
        require(canonicalExe.path.startsWith(gameRoot.path + File.separator)) {
            "The NHH executable is outside the imported game folder. Re-import the game ZIP."
        }

        var rootFS = RootFS.find(activity)
        if (!rootFS.isValid() || rootFS.getVersion() < rootfsVersion) {
            appendLaunchLog(activity, "Winlator rootfs missing/outdated. Starting bootstrap.")
            onProgress(0.02f, "Installing system files…")
            RootFSInstaller.installIfNeeded(activity)

            var waited = 0L
            while (true) {
                delay(300)
                waited += 300
                rootFS = RootFS.find(activity)
                val installProgress = RootFSInstaller.getNhhInstallProgress()

                if (rootFS.isValid() && rootFS.getVersion() >= rootfsVersion) {
                    appendLaunchLog(activity, "Winlator rootfs bootstrap completed.")
                    onProgress(0.38f, "System files installed.")
                    break
                }

                if (installProgress < 0) {
                    appendLaunchLog(activity, "Winlator rootfs bootstrap reported failure.")
                    throw IllegalStateException("Winlator system-file installation failed. Try Run Game again.")
                }

                if (waited > 15 * 60 * 1000L) {
                    throw IllegalStateException("Winlator Bootstrap timed out. Check the launcher diagnostics.")
                }

                val fraction = if (installProgress > 0) installProgress / 100f else 0.02f
                onProgress(
                    fraction.coerceIn(0.02f, 0.38f),
                    if (installProgress > 0) {
                        "Installing system files… " + installProgress + "%"
                    } else {
                        "Installing system files…"
                    }
                )
            }
        }
        appendLaunchLog(activity, "Preparing NHH input profile.")
        val controlsProfileId = ensureControlsProfile(activity)
        val manager = ContainerManager(activity)
        val oldId = prefs.getInt(PREF_CONTAINER_ID, 0)
        val oldContainer = if (oldId > 0) manager.getContainerById(oldId) else null

        if (!forceRebuild && oldContainer != null) {
            mapGameDrive(oldContainer, gameRoot)
            oldContainer.saveData()
            prefs.edit()
                .putString(PREF_GAME_PATH, canonicalExe.absolutePath)
                .putInt(PREF_CONTROLS_PROFILE, controlsProfileId)
                .apply()
            onProgress(1f, "Winlator container verified.")
            return
        }

        onProgress(0.42f, "Creating the NHH Winlator container…")

        val container = createContainer(manager, activity)
        appendLaunchLog(activity, "NHH container created: " + container.id)

        onProgress(0.72f, "Mapping the imported game folder to drive G:…")

        mapGameDrive(container, gameRoot)
        container.saveData()

        prefs.edit()
            .putInt(PREF_CONTAINER_ID, container.id)
            .putString(PREF_GAME_PATH, canonicalExe.absolutePath)
            .putInt(PREF_CONTROLS_PROFILE, controlsProfileId)
            .apply()

        if (oldContainer != null && oldContainer.id != container.id) {
            manager.removeContainerAsync(oldContainer) {}
        }

        appendLaunchLog(activity, "NHH runtime ready. Container=" + container.id)
        onProgress(1f, "Winlator container created. NHH is ready to launch.")
    }

    private fun mapGameDrive(container: Container, gameRoot: File) {
        val canonicalRoot = gameRoot.canonicalFile
        val existing = container.drivesIterator().firstOrNull { it.letter.equals(GAME_DRIVE, ignoreCase = true) }
        if (existing != null && File(existing.path).canonicalFile == canonicalRoot) return

        val driveEntries = container.drivesIterator()
            .filterNot { it.letter.equals(GAME_DRIVE, ignoreCase = true) }
            .map { it.letter + ":" + it.path }
            .toMutableList()
        driveEntries.add(GAME_DRIVE + ":" + canonicalRoot.absolutePath)
        container.setDrives(driveEntries.joinToString(separator = " "))
    }

    private fun ensureControlsProfile(context: Context): Int {
        val manager = InputControlsManager(context)
        val builtin = manager.getProfile(3)
            ?: throw IllegalStateException("The built-in Virtual Gamepad profile is missing from the Winlator runtime.")

        // Never use Winlator's built-in profile as the user's NHH profile.
        // Winlator refreshes built-in profiles on app-version changes, which
        // can silently overwrite the user's control layout.
        val existing = manager.getProfiles(true).firstOrNull {
            it.getName() == "NHH Virtual Gamepad"
        }
        val profile = existing ?: manager.duplicateProfile(builtin).also {
            it.setName("NHH Virtual Gamepad")
            it.save()
        }

        profile.loadControllers()
        return profile.id
    }

    private suspend fun createContainer(
        manager: ContainerManager,
        context: Context
    ): Container = suspendCancellableCoroutine { continuation ->
        val data = JSONObject().apply {
            put("name", CONTAINER_NAME)
            put("screenSize", Container.DEFAULT_SCREEN_SIZE)
            put("envVars", Container.DEFAULT_ENV_VARS)
            put("cpuList", "")
            put("cpuListWoW64", "")
            put("graphicsDriver", GraphicsDrivers.getDefaultDriver(context))
            put("dxwrapper", Container.DEFAULT_DXWRAPPER)
            put("dxwrapperConfig", "")
            put("graphicsDriverConfig", "")
            put("audioDriver", Container.DEFAULT_AUDIO_DRIVER)
            put("audioDriverConfig", "")
            put("wincomponents", Container.DEFAULT_WINCOMPONENTS)
            put("drives", Container.DEFAULT_DRIVES)
            put("hudMode", 0)
            put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL)
            put("box64Preset", Box64Preset.DEFAULT)
            put("desktopTheme", WineThemeManager.DEFAULT_DESKTOP_THEME)
        }

        manager.createContainerAsync(data) { container ->
            if (container != null) {
                if (continuation.isActive) continuation.resume(container)
            } else if (continuation.isActive) {
                continuation.resumeWithException(
                    IllegalStateException("Winlator could not create the NHH container. The container-pattern files may be missing.")
                )
            }
        }
    }

    fun openControlsEditor(activity: MainActivity) {
        val manager = InputControlsManager(activity)
        val profile = manager.getProfile(3)
            ?: throw IllegalStateException("The Virtual Gamepad profile is missing from the Winlator runtime.")
        profile.loadControllers()
        val intent = Intent(activity, com.winlator.ControlsEditorActivity::class.java).apply {
            putExtra("profile_id", profile.id)
        }
        activity.startActivity(intent)
    }

    fun enterDesktop(activity: MainActivity, prefs: SharedPreferences) {
        val containerId = prefs.getInt(PREF_CONTAINER_ID, 0)
        require(containerId > 0) {
            "The NHH container has not been created yet."
        }

        val manager = ContainerManager(activity)
        require(manager.getContainerById(containerId) != null) {
            "The NHH container is missing. Re-import the game ZIP."
        }

        appendLaunchLog(activity, "Opening NHH container desktop. Container=$containerId")
        val intent = Intent(activity, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", containerId)
            putExtra("nhh_desktop_mode", true)
        }
        activity.startActivity(intent)
    }

    fun loadSettings(context: Context, prefs: SharedPreferences): LauncherRuntimeSettings {
        val containerId = prefs.getInt(PREF_CONTAINER_ID, 0)
        val container = if (containerId > 0) {
            ContainerManager(context).getContainerById(containerId)
        } else null

        if (container == null) return LauncherRuntimeSettings()

        val drivers = GraphicsDrivers.parseIdentifiers(container.graphicsDriver)
        val env = container.envVars.split(Regex("\\s+"))
            .firstOrNull { it.startsWith("WINEESYNC=") }
            ?.substringAfter("=")

        return LauncherRuntimeSettings(
            resolution = container.screenSize,
            vulkanDriver = drivers.getOrNull(0) ?: GraphicsDrivers.DEFAULT_VULKAN_DRIVER,
            openGlDriver = drivers.getOrNull(1) ?: GraphicsDrivers.DEFAULT_OPENGL_DRIVER,
            dxWrapper = container.getDXWrapper(),
            audioDriver = container.audioDriver,
            box64Preset = container.box64Preset,
            wineEsync = env != "0",
            onScreenControls = prefs.getInt(PREF_CONTROLS_PROFILE, 3) == 3
        )
    }

    fun saveSettings(
        context: Context,
        prefs: SharedPreferences,
        settings: LauncherRuntimeSettings
    ) {
        require(settings.resolution in resolutions) { "Unsupported display resolution." }
        require(settings.vulkanDriver in vulkanDrivers) { "Unsupported Vulkan driver." }
        require(settings.openGlDriver in openGlDrivers) { "Unsupported OpenGL driver." }
        require(settings.dxWrapper in dxWrappers) { "Unsupported DirectX wrapper." }
        require(settings.audioDriver in audioDrivers) { "Unsupported audio driver." }
        require(settings.box64Preset in box64Presets) { "Unsupported Box64 preset." }

        val containerId = prefs.getInt(PREF_CONTAINER_ID, 0)
        require(containerId > 0) { "Import the game before changing runtime settings." }
        val manager = ContainerManager(context)
        val container = manager.getContainerById(containerId)
            ?: throw IllegalStateException("The NHH Winlator container is missing. Re-import the game ZIP.")

        container.setScreenSize(settings.resolution)
        container.setGraphicsDriver(settings.vulkanDriver + "," + settings.openGlDriver)
        container.setDXWrapper(settings.dxWrapper)
        container.setAudioDriver(settings.audioDriver)
        container.setBox64Preset(settings.box64Preset)
        container.setEnvVars(setEnvironmentVariable(container.envVars, "WINEESYNC", if (settings.wineEsync) "1" else "0"))
        container.saveData()

        prefs.edit()
            .putInt(PREF_CONTROLS_PROFILE, if (settings.onScreenControls) 3 else 0)
            .apply()

        appendLaunchLog(context, "Runtime settings saved: " + settings.toString())
    }

    private fun setEnvironmentVariable(source: String, key: String, value: String): String {
        val entries = source.split(Regex("\\s+"))
            .filter { it.isNotBlank() && !it.startsWith(key + "=") }
            .toMutableList()
        entries.add(key + "=" + value)
        return entries.joinToString(" ")
    }

    fun launchGame(context: Context, prefs: SharedPreferences) {
        val launchStage = "preflight"
        try {
            val rootFS = RootFS.find(context)
            require(rootFS.isValid() && rootFS.getVersion() >= rootfsVersion) {
                "Winlator system files are not installed or are outdated. Re-import the game ZIP to run setup."
            }

            val containerId = prefs.getInt(PREF_CONTAINER_ID, 0)
            require(containerId > 0) { "The NHH Winlator container has not been created. Re-import the game ZIP." }

            val manager = ContainerManager(context)
            val container = manager.getContainerById(containerId)
                ?: throw IllegalStateException("The NHH Winlator container no longer exists. Re-import the game ZIP.")

            val gameExePath = prefs.getString("game_exe", null)
                ?: throw IllegalStateException("The imported NHH executable path is missing. Re-import the game ZIP.")
            val gameExe = File(gameExePath).canonicalFile
            val gameRoot = File(context.filesDir, "game").canonicalFile
            require(gameExe.isFile) { "NHH.exe is missing from the imported game folder. Re-import the game ZIP." }
            require(gameExe.path.startsWith(gameRoot.path + File.separator)) {
                "The selected executable is outside the game folder. Re-import the game ZIP."
            }

            val gameDrive = container.drivesIterator()
                .firstOrNull { it.letter.equals(GAME_DRIVE, ignoreCase = true) }
                ?: throw IllegalStateException("The NHH game drive G: is not configured. Re-import the game ZIP.")

            require(File(gameDrive.path).canonicalFile == gameRoot) {
                "The NHH G: drive points to the wrong folder. Re-import the game ZIP."
            }

            appendLaunchLog(context, "Preflight passed. Container=" + containerId + ", executable=" + gameExe.absolutePath)
            Log.i("NHHLauncher", "Launching NHH through Winlator exec_path: " + gameExe.absolutePath)

            val intent = Intent(context, XServerDisplayActivity::class.java).apply {
                putExtra("container_id", containerId)
                putExtra("exec_path", gameExe.absolutePath)
                putExtra("nhh_controls_profile_id", prefs.getInt(PREF_CONTROLS_PROFILE, 3))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            appendLaunchLog(context, "Launch failed: " + t.javaClass.simpleName + ": " + (t.message ?: "no message"))
            Log.e("NHHLauncher", "NHH launch failed", t)
            throw t
        }
    }

    fun readLaunchLog(context: Context): String {
        val file = File(context.filesDir, "nhh-launch.log")
        return if (file.isFile) file.readText() else "No launch report has been recorded yet."
    }

    private fun appendLaunchLog(context: Context, message: String) {
        try {
            File(context.filesDir, "nhh-launch.log").appendText(
                System.currentTimeMillis().toString() + " " + message + "\n"
            )
        } catch (ignored: Exception) {
            Log.w("NHHLauncher", "Could not write launch report", ignored)
        }
    }
}

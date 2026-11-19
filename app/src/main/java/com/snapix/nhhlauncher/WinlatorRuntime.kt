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
import com.winlator.win32.WinVersions
import com.winlator.core.WineUtils
import com.winlator.core.WineRegistryEditor
import com.winlator.core.KeyValueSet
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LauncherRuntimeSettings(
    val resolution: String = "1280x720", val customWidth: Int = 1280, val customHeight: Int = 720,
    val vulkanDriver: String = GraphicsDrivers.DEFAULT_VULKAN_DRIVER, val openGlDriver: String = GraphicsDrivers.DEFAULT_OPENGL_DRIVER,
    val graphicsDriverConfig: String = "", val dxWrapper: String = DXWrappers.DXVK, val dxWrapperConfig: String = "",
    val audioDriver: String = AudioDrivers.ALSA, val audioDriverConfig: String = "", val box64Preset: String = Box64Preset.DEFAULT,
    val cpuList: String = "", val cpuListWoW64: String = "", val wineVersion: String = "main", val windowsVersion: String = "win10",
    val wineEsync: Boolean = true, val startupSelection: Int = 1, val hudMode: Int = 0,
    val desktopTheme: String = "LIGHT", val desktopBackgroundType: String = "IMAGE", val desktopBackgroundColor: String = "#0277bd",
    val systemFont: String = "Tahoma", val dpi: Int = 96, val mouseWarpOverride: String = "disable",
    val winComponents: Map<String, Boolean> = defaultWinComponents(), val envVars: String = Container.DEFAULT_ENV_VARS, val drives: String = Container.DEFAULT_DRIVES,
    val onScreenControls: Boolean = true, val controlsProfileId: Int = 3, val dinputMapperType: Int = 1, val forceFullscreen: Boolean = true,
    val openAndroidBrowser: Boolean = true, val androidClipboard: Boolean = false, val backgroundProtection: Boolean = false,
    val backgroundWakelock: Boolean = false, val saveMemOnSteam: Boolean = true, val wineDebug: Boolean = false,
    val box64Logs: Int = 0, val saveLogsToFile: Boolean = false, val logFile: String = ""
) {
    companion object {
        fun defaultWinComponents(): Map<String, Boolean> = linkedMapOf(
            "direct3d" to true, "directsound" to true, "directmusic" to true, "directshow" to false,
            "directplay" to false, "xaudio" to true, "vcrun2005" to false, "vcrun2010" to true, "wmdecoder" to true
        )
    }
}

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
        ensureReady(activity, gameExe, prefs, onProgress, forceRebuild = true)
    }

    suspend fun ensureReady(
        activity: MainActivity,
        gameExe: File,
        prefs: SharedPreferences,
        onProgress: (Float, String) -> Unit,
        forceRebuild: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val canonicalExe = gameExe.canonicalFile
        val gameRoot = File(activity.filesDir, "game").canonicalFile
        require(canonicalExe.isFile) { "The selected NHH executable no longer exists." }
        require(canonicalExe.path.startsWith(gameRoot.path + File.separator)) {
            "The NHH executable is outside the imported game folder. Re-import the game ZIP."
        }

        val rootFS = RootFS.find(activity)
        if (!rootFS.isValid() || rootFS.getVersion() < rootfsVersion) {
            withContext(Dispatchers.Main.immediate) {
                onProgress(0.05f, "Installing Winlator system files…")
                RootFSInstaller.installIfNeeded(activity)
            }

            var waited = 0L
            while (!rootFS.isValid() || rootFS.getVersion() < rootfsVersion) {
                delay(300)
                waited += 300
                if (waited > 15 * 60 * 1000L) {
                    throw IllegalStateException("Winlator Bootstrap did not finish. Check the system-files installation.")
                }
                if (waited % 1200L < 300L) {
                    withContext(Dispatchers.Main.immediate) {
                        onProgress(0.08f, "Installing Winlator system files… " + (waited / 1000L).toString() + "s")
                    }
                }
            }
        }

        val manager = ContainerManager(activity)
        val oldId = prefs.getInt(PREF_CONTAINER_ID, 0)
        val oldContainer = if (oldId > 0) manager.getContainerById(oldId) else null

        if (!forceRebuild && oldContainer != null) {
            mapGameDrive(oldContainer, gameRoot)
            oldContainer.saveData()
            prefs.edit().putString(PREF_GAME_PATH, canonicalExe.absolutePath).apply()
            withContext(Dispatchers.Main.immediate) {
                onProgress(1f, "Winlator container verified.")
            }
            return@withContext
        }

        withContext(Dispatchers.Main.immediate) {
            onProgress(0.42f, "Creating the NHH Winlator container…")
        }

        val container = createContainer(manager, activity)

        withContext(Dispatchers.Main.immediate) {
            onProgress(0.72f, "Mapping the imported game folder to drive G:…")
        }

        mapGameDrive(container, gameRoot)
        container.saveData()

        prefs.edit()
            .putInt(PREF_CONTAINER_ID, container.id)
            .putString(PREF_GAME_PATH, canonicalExe.absolutePath)
            .putInt(PREF_CONTROLS_PROFILE, 3)
            .apply()

        if (oldContainer != null && oldContainer.id != container.id) {
            manager.removeContainerAsync(oldContainer) {}
        }

        withContext(Dispatchers.Main.immediate) {
            onProgress(1f, "Winlator container created. NHH is ready to launch.")
        }
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
        container.setDrives(driveEntries.joinToString(separator = ""))
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

    fun loadSettings(context: Context, prefs: SharedPreferences): LauncherRuntimeSettings {
        val id = prefs.getInt(PREF_CONTAINER_ID, 0)
        val c = if (id > 0) ContainerManager(context).getContainerById(id) else null
        if (c == null) return LauncherRuntimeSettings()
        val d = GraphicsDrivers.parseIdentifiers(c.graphicsDriver)
        val env = KeyValueSet(c.envVars)
        val comps = LauncherRuntimeSettings.defaultWinComponents().mapValues { (k, v) -> KeyValueSet(c.winComponents).getBoolean(k, v) }
        val theme = c.desktopTheme.split(",")
        var font = "Tahoma"; var dpi = 96; var warp = "disable"
        val regFile = File(c.rootDir, ".wine/user.reg")
        if (regFile.isFile()) try {
            WineRegistryEditor(regFile).use { reg ->
                font = com.winlator.win32.MSLogFont().fromByteArray(reg.getHexValues("Control Panel\\Desktop\\WindowMetrics", "CaptionFont")).getFaceName()
                dpi = reg.getDwordValue("Control Panel\\Desktop", "LogPixels", 96)
                warp = reg.getStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", "disable")
            }
        } catch (_: Throwable) {}
        val gp = prefs
        return LauncherRuntimeSettings(
            resolution=c.screenSize, customWidth=c.screenSize.substringBefore("x").toIntOrNull() ?: 1280, customHeight=c.screenSize.substringAfter("x").toIntOrNull() ?: 720,
            vulkanDriver=d.getOrNull(0) ?: GraphicsDrivers.DEFAULT_VULKAN_DRIVER, openGlDriver=d.getOrNull(1) ?: GraphicsDrivers.DEFAULT_OPENGL_DRIVER,
            graphicsDriverConfig=c.graphicsDriverConfig, dxWrapper=c.getDXWrapper(), dxWrapperConfig=c.dxWrapperConfig,
            audioDriver=c.audioDriver, audioDriverConfig=c.audioDriverConfig, box64Preset=c.box64Preset,
            cpuList=c.getCPUList(true), cpuListWoW64=c.getCPUListWoW64(true), wineVersion=c.wineVersion,
            windowsVersion=gp.getString("nhh_windows_version", "win10") ?: "win10", wineEsync=env.getBoolean("WINEESYNC", true),
            startupSelection=c.startupSelection.toInt(), hudMode=c.hUDMode.toInt(), desktopTheme=theme.getOrNull(0) ?: "LIGHT",
            desktopBackgroundType=theme.getOrNull(1) ?: "IMAGE", desktopBackgroundColor=theme.getOrNull(2) ?: "#0277bd",
            systemFont=font, dpi=dpi, mouseWarpOverride=warp, winComponents=comps, envVars=c.envVars, drives=c.drives,
            onScreenControls=gp.getInt(PREF_CONTROLS_PROFILE, 3) > 0, controlsProfileId=gp.getInt(PREF_CONTROLS_PROFILE, 3),
            dinputMapperType=gp.getInt("nhh_dinput_mapper", 1), forceFullscreen=gp.getBoolean("nhh_force_fullscreen", true),
            openAndroidBrowser=gp.getBoolean("open_android_browser_from_wine", true), androidClipboard=gp.getBoolean("use_android_clipboard_on_wine", false),
            backgroundProtection=gp.getBoolean("enable_background_protection", false), backgroundWakelock=gp.getBoolean("enable_background_wakelock", false),
            saveMemOnSteam=gp.getBoolean("save_mem_on_run_from_steam", true), wineDebug=gp.getBoolean("enable_wine_debug", false),
            box64Logs=gp.getInt("box64_logs", 0), saveLogsToFile=gp.getBoolean("save_logs_to_file", false), logFile=gp.getString("log_file", "") ?: ""
        )
    }

    fun saveSettings(context: Context, prefs: SharedPreferences, s: LauncherRuntimeSettings) {
        val id = prefs.getInt(PREF_CONTAINER_ID, 0)
        require(id > 0) { "Import the game before changing runtime settings." }
        val c = ContainerManager(context).getContainerById(id) ?: error("The NHH Winlator container is missing.")
        require(s.customWidth in 320..7680 && s.customHeight in 240..4320)
        require(s.vulkanDriver in vulkanDrivers && s.openGlDriver in openGlDrivers && s.dxWrapper in dxWrappers && s.audioDriver in audioDrivers && s.box64Preset in box64Presets)
        val screen = if (s.resolution == "custom") "${s.customWidth and -2}x${s.customHeight and -2}" else s.resolution
        val kv = KeyValueSet(); for ((k,v) in s.winComponents) kv.put(k, if (v) 1 else 0)
        c.setScreenSize(screen); c.setGraphicsDriver(s.vulkanDriver+","+s.openGlDriver); c.setGraphicsDriverConfig(s.graphicsDriverConfig)
        c.setDXWrapper(s.dxWrapper); c.setDXWrapperConfig(s.dxWrapperConfig); c.setAudioDriver(s.audioDriver); c.setAudioDriverConfig(s.audioDriverConfig)
        c.setBox64Preset(s.box64Preset); c.setCPUList(s.cpuList); c.setCPUListWoW64(s.cpuListWoW64); c.setStartupSelection(s.startupSelection.toByte()); c.setHUDMode(s.hudMode.toByte())
        c.setEnvVars(s.envVars.ifBlank { Container.DEFAULT_ENV_VARS }); c.setWinComponents(kv.toString()); c.setDrives(s.drives)
        c.setDesktopTheme("${s.desktopTheme},${s.desktopBackgroundType},${s.desktopBackgroundColor}"); c.saveData()
        val regFile = File(c.rootDir, ".wine/user.reg")
        if (regFile.isFile()) WineRegistryEditor(regFile).use { reg ->
            WineUtils.setSystemFont(reg, s.systemFont.ifBlank { "Tahoma" }); reg.setDwordValue("Control Panel\\Desktop", "LogPixels", s.dpi.coerceIn(72,240))
            reg.setStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", s.mouseWarpOverride)
        }
        val versions = WinVersions.getWinVersions(); val wi = versions.indexOfFirst { it.version == s.windowsVersion }; if (wi >= 0) WineUtils.setWinVersion(c, wi)
        prefs.edit().putInt(PREF_CONTROLS_PROFILE, if (s.onScreenControls) s.controlsProfileId else 0)
            .putInt("nhh_dinput_mapper", s.dinputMapperType).putBoolean("nhh_force_fullscreen", s.forceFullscreen)
            .putBoolean("open_android_browser_from_wine", s.openAndroidBrowser).putBoolean("use_android_clipboard_on_wine", s.androidClipboard)
            .putBoolean("enable_background_protection", s.backgroundProtection).putBoolean("enable_background_wakelock", s.backgroundWakelock)
            .putBoolean("save_mem_on_run_from_steam", s.saveMemOnSteam).putBoolean("enable_wine_debug", s.wineDebug)
            .putInt("box64_logs", s.box64Logs).putBoolean("save_logs_to_file", s.saveLogsToFile).putString("log_file", s.logFile)
            .putString("nhh_windows_version", s.windowsVersion).apply()
        appendLaunchLog(context, "All Winlator container settings saved.")
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

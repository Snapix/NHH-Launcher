package com.snapix.nhhlauncher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.winlator.XServerDisplayActivity
import com.winlator.MainActivity as WinlatorMainActivity
import com.winlator.R as WinlatorR
import com.winlator.box64.Box64Preset
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.container.GraphicsDrivers
import com.winlator.core.FileUtils
import com.winlator.core.WineThemeManager
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WinlatorRuntime {
    private const val PREF_CONTAINER_ID = "winlator_container_id"
    private const val PREF_SHORTCUT = "winlator_shortcut"
    private const val CONTAINER_NAME = "NHH"
    private const val GAME_DRIVE = "G"

    private const val ROOTFS_VERSION = 23

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
        require(gameExe.isFile) { "The selected NHH executable no longer exists." }

        val rootFS = RootFS.find(activity)
        if (!rootFS.isValid() || rootFS.getVersion() < ROOTFS_VERSION) {
            withContext(Dispatchers.Main.immediate) {
                onProgress(0.05f, "Installing Winlator system files…")
            }
            withContext(Dispatchers.Main.immediate) { RootFSInstaller.installIfNeeded(activity) }
            var waited = 0L
            while (!rootFS.isValid() || rootFS.getVersion() < ROOTFS_VERSION) {
                delay(250)
                waited += 250
                if (waited > 15 * 60 * 1000L) {
                    throw IllegalStateException("Winlator Bootstrap did not finish.")
                }
                if (waited % 1000L == 0L) {
                    withContext(Dispatchers.Main.immediate) {
                        onProgress(0.05f, "Installing Winlator system files… " + (waited / 1000L) + "s")
                    }
                }
            }
        }

        val relativeExe = gameExe.relativeTo(File(activity.filesDir, "game")).path
            .replace(File.separatorChar, '/')

        val manager = ContainerManager(activity)
        val oldId = prefs.getInt(PREF_CONTAINER_ID, 0)
        val oldShortcut = prefs.getString(PREF_SHORTCUT, null)
        val oldContainer = if (oldId > 0) manager.getContainers().firstOrNull { it.id == oldId } else null

        if (!forceRebuild && oldContainer != null && oldShortcut != null && File(oldShortcut).isFile) {
            return@withContext
        }

        withContext(Dispatchers.Main.immediate) {
            onProgress(0.45f, "Creating the NHH Winlator container…")
        }

        val container = createContainer(manager, activity)

        withContext(Dispatchers.Main.immediate) {
            onProgress(0.70f, "Mounting the game into Wine as drive G:…")
        }

        // Do not copy the game a second time. Winlator's drive mapping can point
        // directly at the launcher-private game directory.
        val gameRoot = File(activity.filesDir, "game")
        container.setDrives(Container.DEFAULT_DRIVES + GAME_DRIVE + ":" + gameRoot.absolutePath)
        container.saveData()

        val shortcut = createGameShortcut(container, relativeExe)

        prefs.edit()
            .putInt(PREF_CONTAINER_ID, container.id)
            .putString(PREF_SHORTCUT, shortcut.absolutePath)
            .apply()

        if (oldContainer != null && oldContainer.id != container.id) {
            manager.removeContainerAsync(oldContainer) {}
        }

        withContext(Dispatchers.Main.immediate) {
            onProgress(1f, "Winlator bootstrap finished. NHH is ready.")
        }
    }

    private suspend fun createContainer(
        manager: ContainerManager,
        activity: Context
    ): com.winlator.container.Container = suspendCancellableCoroutine { continuation ->
        val data = JSONObject().apply {
            put("name", CONTAINER_NAME)
            put("screenSize", Container.DEFAULT_SCREEN_SIZE)
            put("envVars", Container.DEFAULT_ENV_VARS)
            put("cpuList", "")
            put("cpuListWoW64", "")
            put("graphicsDriver", GraphicsDrivers.getDefaultDriver(activity))
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
            if (container != null) continuation.resume(container)
            else continuation.resumeWithException(IllegalStateException("Could not create the Winlator NHH container."))
        }
    }

    private fun createGameShortcut(container: com.winlator.container.Container, relativeExe: String): File {
        val desktopDir = File(container.getUserDir(), "Desktop")
        if (!desktopDir.isDirectory()) desktopDir.mkdirs()

        val shortcut = File(desktopDir, "NHH.desktop")
        val dosPath = "G:\\" + relativeExe.replace("/", "\\")
        val content = buildString {
            append("[Desktop Entry]\n")
            append("Name=No Hero's Here\n")
            append("Exec=wine " + dosPath + "\n")
            append("Type=Application\n")
            append("StartupNotify=false\n")
            append("\n[Extra Data]\n")
            append("controlsProfile=3\n")
        }
        FileUtils.writeString(shortcut, content)
        return shortcut
    }

    fun launchGame(context: Context, prefs: SharedPreferences) {
        val containerId = prefs.getInt(PREF_CONTAINER_ID, 0)
        val shortcutPath = prefs.getString(PREF_SHORTCUT, null)
        require(containerId > 0 && !shortcutPath.isNullOrBlank() && File(shortcutPath).isFile) {
            "NHH Winlator container is not ready. Re-import the game ZIP."
        }

        val intent = Intent(context, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", containerId)
            putExtra("shortcut_path", shortcutPath)
        }
        context.startActivity(intent)
    }

    fun openGlobalSettings(context: Context) {
        val intent = Intent(context, WinlatorMainActivity::class.java).apply {
            putExtra("selected_menu_item_id", WinlatorR.id.menu_item_settings)
        }
        context.startActivity(intent)
    }

    fun openContainerSettings(context: Context) {
        val intent = Intent(context, WinlatorMainActivity::class.java).apply {
            putExtra("selected_menu_item_id", WinlatorR.id.menu_item_containers)
        }
        context.startActivity(intent)
    }
}

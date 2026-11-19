package com.snapix.nhhlauncher

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.winlator.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

private const val PREFS = "nhh"
private const val GAME_DIR = "game"
private const val GAME_EXE = "game_exe"
private const val PREF_SETUP_IN_PROGRESS = "setup_in_progress"

private enum class SetupStage(val label: String) {
    EXTRACTING("Extracting the ZIP…"),
    ANALYSING("Analysing folders and subfolders…"),
    FOUND("Game executable found"),
    SELECT_EXE("Select the game executable"),
    BOOTSTRAP("Install and setup Winlator essentials"),
    FINISHING("Please wait till the Bootstrap finishes its setup…")
}

class MainActivity : com.winlator.MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra("nhh_launcher", true)
        super.onCreate(savedInstanceState)
        WinlatorRuntime.installCrashRecorder(this)
        setContent { NhhApp(applicationContext, this) }
    }
}

@Composable
private fun NhhApp(context: Context, activity: MainActivity) {
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var gameExe by remember { mutableStateOf(prefs.getString(GAME_EXE, null)) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    var setupStage by remember { mutableStateOf<SetupStage?>(null) }
    var setupProgress by remember { mutableFloatStateOf(0f) }
    var setupDetail by remember { mutableStateOf("") }
    var exeChoices by remember { mutableStateOf<List<String>>(emptyList()) }
    var runtimeSettings by remember { mutableStateOf(LauncherRuntimeSettings()) }
    var settingsSaving by remember { mutableStateOf(false) }
    var settingsFeedback by remember { mutableStateOf<String?>(null) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun openSettings() {
        scope.launch {
            try {
                runtimeSettings = withContext(Dispatchers.IO) {
                    WinlatorRuntime.loadSettings(activity, prefs)
                }
                settingsFeedback = null
                settingsError = null
                settings = true
            } catch (t: Throwable) {
                error = "Could not load runtime settings: " + (t.message ?: "unknown error")
            }
        }
    }

    lateinit var picker: androidx.activity.result.ActivityResultLauncher<Array<String>>
    fun selectZip() {
        error = null
        settings = false
        picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
    }

    picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            try {
                setupStage = SetupStage.EXTRACTING
                setupProgress = 0f
                setupDetail = "Opening ZIP…"

                val result = importGameZip(
                    context,
                    uri,
                    onProgress = { stage, progress, detail ->
                        setupStage = stage
                        setupProgress = progress
                        setupDetail = detail
                    },
                    onNeedsExeSelection = { choices -> exeChoices = choices }
                )
                if (result == null) return@launch

                // Persist the game path BEFORE Winlator bootstrap. The rootfs
                // installer runs asynchronously and may recreate the Activity.
                prefs.edit()
                    .putString(GAME_EXE, result.absolutePath)
                    .putBoolean(PREF_SETUP_IN_PROGRESS, true)
                    .commit()
                gameExe = result.absolutePath

                setupStage = SetupStage.BOOTSTRAP
                setupProgress = 0.02f
                setupDetail = "Installing system files and creating the NHH runtime…"

                WinlatorRuntime.prepare(activity, result, prefs) { progress, detail ->
                    setupProgress = progress
                    setupDetail = detail
                }

                prefs.edit().putBoolean(PREF_SETUP_IN_PROGRESS, false).apply()
                error = null
                setupStage = null
            } catch (t: Throwable) {
                prefs.edit().putBoolean(PREF_SETUP_IN_PROGRESS, false).apply()
                setupStage = null
                error = t.message ?: "Could not import and prepare the game."
            }
        }
    }

    // Setup is persisted. If Android recreates this Activity during bootstrap,
    // keep the imported executable and let RUN GAME verify/finish the runtime.
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF160909),
            surface = Color(0xFF2A1111),
            surfaceContainer = Color(0xFF391818),
            surfaceVariant = Color(0xFF4A2020),
            primary = Color(0xFFFF5A4D),
            onPrimary = Color.White,
            secondary = Color(0xFFFFB44F),
            tertiary = Color(0xFFFF806F),
            onBackground = Color(0xFFFFE9E4),
            onSurface = Color(0xFFFFE9E4),
            onSurfaceVariant = Color(0xFFD9BDB7)
        )
    ) {
        Surface(Modifier.fillMaxSize()) {
            when {
                setupStage == SetupStage.SELECT_EXE -> ExeSelectionScreen(
                    choices = exeChoices,
                    onSelect = { relativePath ->
                        scope.launch {
                            try {
                                val selected = finalizeSelectedExe(context, relativePath)
                                prefs.edit()
                                    .putString(GAME_EXE, selected.absolutePath)
                                    .putBoolean(PREF_SETUP_IN_PROGRESS, true)
                                    .commit()
                                gameExe = selected.absolutePath
                                setupStage = SetupStage.BOOTSTRAP
                                setupProgress = 0.02f
                                setupDetail = "Installing system files and creating the NHH runtime…"
                                WinlatorRuntime.prepare(activity, selected, prefs) { progress, detail ->
                                    setupProgress = progress
                                    setupDetail = detail
                                }
                                prefs.edit().putBoolean(PREF_SETUP_IN_PROGRESS, false).apply()
                                setupStage = null
                                error = null
                            } catch (t: Throwable) {
                                prefs.edit().putBoolean(PREF_SETUP_IN_PROGRESS, false).apply()
                                setupStage = null
                                error = t.message ?: "Could not select the executable."
                            }
                        }
                    },
                    onCancel = {
                        setupStage = null
                        error = "No game executable was selected."
                    }
                )
                setupStage != null -> SetupProgressScreen(
                    stage = setupStage!!,
                    progress = setupProgress,
                    detail = setupDetail
                )
                settings -> LauncherSettingsScreen(
                    settings = runtimeSettings,
                    isSaving = settingsSaving,
                    feedback = settingsFeedback,
                    error = settingsError,
                    onChange = {
                        runtimeSettings = it
                        settingsFeedback = null
                        settingsError = null
                    },
                    onSave = {
                        scope.launch {
                            settingsSaving = true
                            settingsFeedback = null
                            settingsError = null
                            try {
                                withContext(Dispatchers.IO) {
                                    WinlatorRuntime.saveSettings(activity, prefs, runtimeSettings)
                                }
                                settingsFeedback = "Settings saved. They will apply on the next game launch."
                            } catch (t: Throwable) {
                                settingsError = t.message ?: "Could not save runtime settings."
                            } finally {
                                settingsSaving = false
                            }
                        }
                    },
                    onBack = { settings = false },
                    onReimport = { selectZip() },
                    onShareReport = { WinlatorRuntime.shareLaunchReport(activity) }
                )
                gameExe == null || !File(gameExe!!).isFile -> SetupScreen(
                    error = error,
                    onBack = { activity.finish() },
                    onSelectZip = { selectZip() }
                )
                else -> MainMenu(
                    gameExeName = File(gameExe!!).name,
                    error = error,
                    onBack = { activity.finish() },
                    onRun = {
                        scope.launch {
                            try {
                                error = null
                                setupStage = SetupStage.BOOTSTRAP
                                setupProgress = 0f
                                setupDetail = "Checking Winlator runtime…"
                                WinlatorRuntime.ensureReady(
                                    activity,
                                    File(gameExe!!),
                                    prefs,
                                    forceRebuild = false,
                                    onProgress = { progress, detail ->
                                        setupProgress = progress
                                        setupDetail = detail
                                    }
                                )
                                setupStage = null
                                WinlatorRuntime.launchGame(activity, prefs)
                            } catch (t: Throwable) {
                                setupStage = null
                                error = t.message ?: "Could not launch NHH."
                            }
                        }
                    },
                    onSettings = { openSettings() }
                )
            }
        }
    }
}

@Composable
@Composable
private fun LauncherHeader(
    subtitle: String,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
        }
        Image(painterResource(R.drawable.nhh_logo), "No Heroes Here", Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("No Heroes Here", style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, color = Color(0xFFD5BDB8))
        }
        if (onSettings != null) IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun HeroBanner(
    statusText: String,
    ready: Boolean = false,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(24.dp))
    ) {
        Image(
            painter = painterResource(R.drawable.nhh_banner),
            contentDescription = "No Heroes Here",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            color = if (ready) Color(0xE93C7D43) else Color(0xE94A241F),
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(
                    statusText,
                    color = if (ready) Color(0xFFB9F4BD) else Color(0xFFFFB84F),
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

@Composable
private fun SetupSteps(active: Int) {
    val labels = listOf("1. Select ZIP", "2. Extract", "3. Install")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xE52A1111)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6F302B))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("SETUP PROGRESS", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                labels.forEachIndexed { index, label ->
                    val completed = index < active
                    val current = index == active
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = when {
                            completed -> Color(0xFF3D7344)
                            current -> Color(0xFFE63F35)
                            else -> Color(0xFF3A1B1B)
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (current) Color(0xFFFF6C5E) else Color(0xFF69302C)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (completed || current) Icons.Default.CheckCircle else Icons.Default.HourglassTop,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                label,
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Composable
private fun SetupProgressScreen(stage: SetupStage, progress: Float, detail: String) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LauncherHeader("Setting up game")
        Image(painterResource(R.drawable.nhh_banner), null, Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF251414)), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (stage == SetupStage.BOOTSTRAP || stage == SetupStage.FINISHING) "Preparing runtime" else "Installing game", style = MaterialTheme.typography.titleLarge)
                Text(detail.ifBlank { stage.label }, color = Color(0xFFD5BDB8))
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", color = Color(0xFFFF8A76))
            }
        }
    }
}

@Composable
@Composable
private fun SetupScreen(error: String?, onBack: () -> Unit, onSelectZip: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LauncherHeader("Game setup", onBack = onBack)
        Image(painterResource(R.drawable.nhh_banner), null, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF251414)), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Game not installed", style = MaterialTheme.typography.titleLarge)
                Text("Select your No Heroes Here ZIP to install it on this device.", color = Color(0xFFD5BDB8))
                Button(onClick = onSelectZip, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("SELECT GAME ZIP")
                }
                if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
@Composable
private fun MainMenu(gameExeName: String, error: String?, onBack: () -> Unit, onRun: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LauncherHeader("Ready to play", onBack = onBack, onSettings = onSettings)
        Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(18.dp))) {
            Image(painterResource(R.drawable.nhh_banner), "No Heroes Here", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Surface(Modifier.align(Alignment.BottomStart).padding(12.dp), color = Color(0xDD2E6B3B), shape = RoundedCornerShape(18.dp)) {
                Text("READY", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White)
            }
        }
        Button(onClick = onRun, Modifier.fillMaxWidth().height(68.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(10.dp)); Text("RUN GAME", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedButton(onClick = onSettings, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text("SETTINGS")
        }
        if (!error.isNullOrBlank()) Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1717))) { Text(error, Modifier.padding(14.dp), color = Color(0xFFFFB5A9)) }
    }
}

private suspend fun importGameZip(
    context: Context,
    uri: Uri,
    onProgress: suspend (SetupStage, Float, String) -> Unit,
    onNeedsExeSelection: (List<String>) -> Unit
): File? = withContext(Dispatchers.IO) {
    val stagingDir = File(context.filesDir, "$GAME_DIR.staging")
    val gameDir = File(context.filesDir, GAME_DIR)
    if (stagingDir.exists()) stagingDir.deleteRecursively()
    stagingDir.mkdirs()

    suspend fun progress(stage: SetupStage, value: Float, detail: String = "") {
        withContext(Dispatchers.Main.immediate) { onProgress(stage, value, detail) }
    }

    try {
        progress(SetupStage.EXTRACTING, 0f, "Opening ZIP…")

        // One pass only. The old code decompressed the entire archive twice.
        var totalKnownBytes = 0L
        var extractedBytes = 0L
        var lastUiUpdateNanos = 0L
        var lastUiBytes = 0L

        // Android document providers may lazily create a Handler. Opening the
        // provider stream from Dispatchers.IO can throw "Can't create handler
        // inside thread". Open it on main, then perform ZIP I/O on the worker.
        val input = withContext(Dispatchers.Main.immediate) {
            context.contentResolver.openInputStream(uri)
        } ?: throw IllegalArgumentException("Could not read the selected ZIP.")

        input.use {
            ZipInputStream(it.buffered(1024 * 1024)).use { zip ->
                val root = stagingDir.canonicalFile
                val buffer = ByteArray(1024 * 1024)
                var entry = zip.nextEntry

                while (entry != null) {
                    val entryName = entry.name
                    val target = File(stagingDir, entryName).canonicalFile

                    if (!target.path.startsWith(root.path + File.separator) && target != root) {
                        throw IllegalArgumentException("Invalid ZIP path.")
                    }

                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target, false).buffered(1024 * 1024).use { output ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                extractedBytes += read
                            }
                        }
                        if (entry.size > 0) totalKnownBytes += entry.size
                    }

                    val now = System.nanoTime()
                    if (now - lastUiUpdateNanos >= 120_000_000L || extractedBytes - lastUiBytes >= 4L * 1024 * 1024L) {
                        lastUiUpdateNanos = now
                        lastUiBytes = extractedBytes
                        val fraction = if (totalKnownBytes > 0L) {
                            (extractedBytes.toDouble() / totalKnownBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                        } else 0f
                        progress(SetupStage.EXTRACTING, fraction, formatBytes(extractedBytes) + " • " + entryName)
                    }

                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        progress(SetupStage.EXTRACTING, 1f, "Extraction complete")
        progress(SetupStage.ANALYSING, 0f, "Searching folders and subfolders…")

        val executables = stagingDir.walkTopDown().filter {
            it.isFile && it.extension.equals("exe", ignoreCase = true)
        }.toList()

        val preferred = executables.firstOrNull {
            it.name.lowercase(Locale.ROOT) == "nhh.exe"
        } ?: executables.firstOrNull {
            it.name.lowercase(Locale.ROOT) == "nhh_build.exe"
        }

        progress(SetupStage.ANALYSING, 1f, executables.size.toString() + " executable(s) found")

        if (preferred == null) {
            if (executables.isEmpty()) {
                throw IllegalArgumentException("No .exe file was found in the extracted game files.")
            }
            withContext(Dispatchers.Main.immediate) {
                onNeedsExeSelection(executables.map { it.relativeTo(stagingDir).path })
            }
            progress(SetupStage.SELECT_EXE, 1f, "Choose the game executable")
            return@withContext null
        }

        progress(SetupStage.FOUND, 1f, preferred.name + " found")
        finalizeGameInstall(context, preferred.relativeTo(stagingDir).path)
    } catch (t: Throwable) {
        stagingDir.deleteRecursively()
        throw t
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 1024.0) String.format(Locale.US, "%.1f MB", mb)
    else String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private suspend fun finalizeSelectedExe(context: Context, relativePath: String): File =
    withContext(Dispatchers.IO) { finalizeGameInstall(context, relativePath) }

private fun finalizeGameInstall(context: Context, relativePath: String): File {
    val stagingDir = File(context.filesDir, "$GAME_DIR.staging")
    val gameDir = File(context.filesDir, GAME_DIR)
    val selected = File(stagingDir, relativePath).canonicalFile
    val root = stagingDir.canonicalFile

    if (!selected.path.startsWith(root.path + File.separator) || !selected.isFile) {
        throw IllegalArgumentException("Invalid executable selection.")
    }

    if (gameDir.exists()) gameDir.deleteRecursively()
    if (!stagingDir.renameTo(gameDir)) {
        throw IllegalStateException("Could not finalize the extracted game files.")
    }

    return File(gameDir, relativePath).takeIf { it.isFile }
        ?: throw IllegalStateException("The selected executable disappeared while finalizing the install.")
}

@Composable
private fun ExeSelectionScreen(choices: List<String>, onSelect: (String) -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Select game executable", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "NHH.exe or NHH_build.exe was not found automatically. Select the correct .exe below.",
            color = Color(0xFFAAAAAA)
        )
        Spacer(Modifier.height(18.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(choices) { path ->
                Button(onClick = { onSelect(path) }, modifier = Modifier.fillMaxWidth()) { Text(path) }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("CANCEL") }
    }
}

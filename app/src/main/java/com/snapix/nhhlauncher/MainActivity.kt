package com.snapix.nhhlauncher

import android.content.Context
import android.net.Uri
import android.os.Bundle
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

                setupStage = SetupStage.BOOTSTRAP
                setupDetail = "Installing the real Winlator runtime and creating the NHH container…"
                WinlatorRuntime.prepare(activity, result, prefs) { progress, detail ->
                    setupProgress = progress
                    setupDetail = detail
                }

                prefs.edit().putString(GAME_EXE, result.absolutePath).apply()
                gameExe = result.absolutePath
                error = null
                setupStage = null
            } catch (t: Throwable) {
                setupStage = null
                error = t.message ?: "Could not import and prepare the game."
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF160B0B),
            surface = Color(0xFF2A1212),
            surfaceContainer = Color(0xFF3A1B1B),
            primary = Color(0xFFFF6755),
            onPrimary = Color(0xFFFFFFFF),
            secondary = Color(0xFFFFB35C),
            tertiary = Color(0xFFFF8A76),
            onBackground = Color(0xFFFFE8E2),
            onSurface = Color(0xFFFFE8E2)
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
                                setupStage = SetupStage.BOOTSTRAP
                                setupDetail = "Installing the real Winlator runtime and creating the NHH container…"
                                WinlatorRuntime.prepare(activity, selected, prefs) { progress, detail ->
                                    setupProgress = progress
                                    setupDetail = detail
                                }
                                prefs.edit().putString(GAME_EXE, selected.absolutePath).apply()
                                gameExe = selected.absolutePath
                                setupStage = null
                                error = null
                            } catch (t: Throwable) {
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
                setupStage != null -> SetupProgressScreen(setupStage!!, setupProgress, setupDetail)
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
                    onSelectZip = { selectZip() }
                )
                else -> MainMenu(
                    error = error,
                    onRun = {
                        scope.launch {
                            try {
                                error = null
                                setupStage = SetupStage.BOOTSTRAP
                                setupDetail = "Checking Winlator runtime…"
                                WinlatorRuntime.ensureReady(activity, File(gameExe!!), prefs, onProgress = { progress, detail ->
                                    setupProgress = progress
                                    setupDetail = detail
                                })
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
private fun SetupProgressScreen(stage: SetupStage, progress: Float, detail: String) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.nhh_banner),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.16f },
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(Color(0xEE160B0B)))
        Column(
            Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("NO HEROES HERE", style = MaterialTheme.typography.labelLarge, color = Color(0xFFFF8A76))
            Spacer(Modifier.height(8.dp))
            Text(stage.label, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE5221111))
            ) {
                Column(Modifier.padding(22.dp)) {
                    if (stage == SetupStage.EXTRACTING) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text((progress * 100).toInt().toString() + "% extracted", style = MaterialTheme.typography.titleMedium)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Preparing runtime…", style = MaterialTheme.typography.titleMedium)
                    }
                    if (detail.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(detail, color = Color(0xFFD9C4C0), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("NHH Launcher", color = Color(0xFF9E7B75))
        }
    }
}



@Composable
private fun SetupScreen(error: String?, onSelectZip: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.nhh_banner),
            contentDescription = "No Heroes Here",
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.10f },
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(Color(0xF5160B0B)))
        Column(
            Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.nhh_logo),
                    contentDescription = "NHH logo",
                    modifier = Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("No Heroes Here", style = MaterialTheme.typography.headlineSmall)
                    Text("Game Setup & Launcher", color = Color(0xFFCFAFA8))
                }
            }
            Image(
                painter = painterResource(R.drawable.nhh_banner),
                contentDescription = "No Heroes Here",
                modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Crop
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE5221111))
            ) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SELECT GAME ZIP ARCHIVE", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("Import your own No Heroes Here game ZIP.", color = Color(0xFFD5BCB7))
                    Spacer(Modifier.height(16.dp))
                    Image(
                        painter = painterResource(R.drawable.nhh_poster),
                        contentDescription = "No Heroes Here poster",
                        modifier = Modifier.size(150.dp, 190.dp).clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onSelectZip,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("SELECT GAME ZIP", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Required storage: about 1.2 GB", color = Color(0xFFB99D97))
                }
            }
            if (!error.isNullOrBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MainMenu(error: String?, onRun: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.nhh_banner),
            contentDescription = "No Heroes Here",
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.10f },
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(Color(0xF0160B0B)))
        Column(
            Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.nhh_logo),
                    contentDescription = "NHH logo",
                    modifier = Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("No Heroes Here", style = MaterialTheme.typography.headlineSmall)
                    Text("Ready to Play", color = Color(0xFFCFAFA8))
                }
                TextButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
            Image(
                painter = painterResource(R.drawable.nhh_banner),
                contentDescription = "No Heroes Here",
                modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Crop
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE5221111))
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("GAME LAUNCHER", style = MaterialTheme.typography.titleMedium)
                    Text("The game setup is complete. Launch and play No Heroes Here.", color = Color(0xFFD5BCB7))
                    Button(
                        onClick = onRun,
                        modifier = Modifier.fillMaxWidth().height(70.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("RUN GAME", style = MaterialTheme.typography.titleLarge)
                    }
                    FilledTonalButton(
                        onClick = onSettings,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("SETTINGS")
                    }
                }
            }
            if (!error.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF321717)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Launch failed", color = Color(0xFFFF8A76), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(error, color = Color(0xFFE6C9D0))
                    }
                }
            }
        }
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

package com.snapix.nhhlauncher

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    val scope = rememberCoroutineScope()

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
            background = Color(0xFF19181D),
            surface = Color(0xFF242328),
            surfaceContainer = Color(0xFF29282E),
            primary = Color(0xFFFFB82E),
            onPrimary = Color(0xFF2A1800),
            secondary = Color(0xFFB9D98A),
            onBackground = Color(0xFFF5F2F7),
            onSurface = Color(0xFFF5F2F7)
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
                settings -> SettingsScreen(
                    onBack = { settings = false },
                    onReimport = { selectZip() },
                    onWinlatorSettings = { WinlatorRuntime.openGlobalSettings(activity) },
                    onContainerSettings = { WinlatorRuntime.openContainerSettings(activity) },
                    onInputControls = { WinlatorRuntime.openInputControls(activity) }
                )
                gameExe == null || !File(gameExe!!).isFile -> SetupScreen(
                    error = error,
                    onSelectZip = { selectZip() }
                )
                else -> MainMenu(
                    onRun = {
                        scope.launch {
                            try {
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
                    onSettings = { settings = true }
                )
            }
        }
    }
}

@Composable
private fun SetupProgressScreen(stage: SetupStage, progress: Float, detail: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(modifier = Modifier.size(72.dp), strokeWidth = 6.dp)
            Spacer(Modifier.height(28.dp))
            Text(stage.label, style = MaterialTheme.typography.titleLarge)
            if (stage == SetupStage.EXTRACTING) {
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text((progress * 100).toInt().toString() + "% extracted", style = MaterialTheme.typography.labelLarge)
            }
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFAAAAAA))
            }
            Spacer(Modifier.height(10.dp))
            Text("Do not close NHH Launcher.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFAAAAAA))
        }
    }
}

@Composable
private fun SetupScreen(error: String?, onSelectZip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Poster()
        Spacer(Modifier.height(28.dp))
        Text("NHH Launcher", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "Select your NHH game ZIP to set up the launcher.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFC5C2C8)
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSelectZip,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Icon(Icons.Default.Description, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("SELECT GAME ZIP", style = MaterialTheme.typography.titleMedium)
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MainMenu(onRun: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Poster()
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRun,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Icon(Icons.Default.SportsEsports, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("RUN GAME", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = onSettings,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("SETTINGS")
        }
    }
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onReimport: () -> Unit,
    onWinlatorSettings: () -> Unit,
    onContainerSettings: () -> Unit,
    onInputControls: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("DONE") }
        }
        Spacer(Modifier.height(18.dp))
        Text("Winlator runtime", style = MaterialTheme.typography.titleMedium)
        Text(
            "The game uses the upstream Winlator runtime directly: Wine, Box64, graphics, audio, display and input controls.",
            color = Color(0xFFAAAAAA)
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onWinlatorSettings, modifier = Modifier.fillMaxWidth()) { Text("WINLATOR GLOBAL SETTINGS") }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onContainerSettings, modifier = Modifier.fillMaxWidth()) { Text("WINLATOR CONTAINER SETTINGS") }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onInputControls, modifier = Modifier.fillMaxWidth()) { Text("INPUT CONTROLS") }
        Spacer(Modifier.height(22.dp))
        Text("Game", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onReimport, modifier = Modifier.fillMaxWidth()) { Text("RE-IMPORT GAME ZIP") }
    }
}

@Composable
private fun Poster() {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF49B8F2), Color(0xFF7CD5FF), Color(0xFF5D743B), Color(0xFF26301B))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(30.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NHH", style = MaterialTheme.typography.displayLarge, color = Color(0xFFE92B1B))
            Surface(color = Color(0xFFF5D65C), shape = RoundedCornerShape(22.dp)) {
                Text(
                    "HEROES",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color(0xFF247B36)
                )
            }
            Text("HERE", style = MaterialTheme.typography.displayMedium, color = Color(0xFFE92B1B))
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                listOf(Color(0xFFB83E2B), Color(0xFFF2A13A), Color(0xFF5A9A3A), Color(0xFF3280B6)).forEach {
                    Box(Modifier.size(58.dp).clip(RoundedCornerShape(50)).background(it))
                }
            }
        }
        Text(
            "NO HERO'S HERE",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.82f)
        )
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

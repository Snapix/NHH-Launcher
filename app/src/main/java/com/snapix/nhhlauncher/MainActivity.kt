package com.snapix.nhhlauncher

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

private enum class SetupStage(val label: String) {
    EXTRACTING("Extracting the ZIP…"),
    ANALYSING("Analysing folders and subfolders…"),
    FOUND("NHH.exe found"),
    BOOTSTRAP("Install and setup Winlator essentials"),
    SELECT_EXE("Select the game executable"),
    BOOTSTRAP("Install and setup Winlator essentials"),
    FINISHING("Please wait till the Bootstrap finishes its setup…")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NhhApp(applicationContext) }
    }
}

@Composable
private fun NhhApp(context: Context) {
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var gameExe by remember { mutableStateOf(prefs.getString("game_exe", null)) }
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
        picker.launch(arrayOf("application/zip", "application/octet-stream"))
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
                    onNeedsExeSelection = { choices ->
                        exeChoices = choices
                    }
                )

                if (result == null) return@launch

                setupStage = SetupStage.BOOTSTRAP
                // Real Winlator bootstrap is wired here after the native runtime is vendored.
                setupStage = SetupStage.FINISHING
                withContext(Dispatchers.IO) { Thread.sleep(250) }

                prefs.edit().putString("game_exe", result.absolutePath).apply()
                gameExe = result.absolutePath
                error = null
                setupStage = null
            } catch (t: Throwable) {
                setupStage = null
                error = t.message ?: "Could not import the game ZIP."
            }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0B0B0B),
            surface = Color(0xFF111111),
            primary = Color.White,
            onPrimary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
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
                                prefs.edit().putString("game_exe", selected.absolutePath).apply()
                                gameExe = selected.absolutePath
                                setupStage = SetupStage.BOOTSTRAP
                                withContext(Dispatchers.IO) { Thread.sleep(250) }
                                setupStage = SetupStage.FINISHING
                                withContext(Dispatchers.IO) { Thread.sleep(250) }
                                setupStage = null
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
                    onReimport = { selectZip() }
                )
                gameExe == null -> SetupScreen(
                    error = error,
                    onSelectZip = { selectZip() }
                )
                else -> MainMenu(
                    onRun = {
                        error = "Winlator native runtime is not bundled yet. RUN GAME is enabled after Wine/Box64/XServer integration."
                    },
                    onSettings = { settings = true }
                )
            }
        }
    }
}

@Composable
private fun SetupProgressScreen(stage: SetupStage, progress: Float, detail: String) {
    Box(
        Modifier.fillMaxSize().padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(72.dp),
                strokeWidth = 6.dp
            )
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
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                }
            } else if (detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFAAAAAA))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Do not close NHH Launcher.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFAAAAAA)
            )
        }
    }
}

@Composable
private fun SetupScreen(error: String?, onSelectZip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Poster()
        Spacer(Modifier.height(28.dp))
        Text("NHH Launcher", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Select your NHH game ZIP to set up the launcher.",
            color = Color(0xFFAAAAAA)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSelectZip) { Text("SELECT GAME ZIP") }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = Color(0xFFFF6B6B))
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
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = onRun,
            Modifier.fillMaxWidth().height(58.dp)
        ) { Text("RUN GAME") }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("SETTINGS")
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, onReimport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("DONE") }
        }
        Spacer(Modifier.height(20.dp))
        Text("Controls", style = MaterialTheme.typography.titleMedium)
        Text("Touch, Xbox controller and keyboard mapping.", color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(18.dp))
        Text("Graphics", style = MaterialTheme.typography.titleMedium)
        Text("Renderer, resolution and graphics options.", color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(18.dp))
        Text("Compatibility", style = MaterialTheme.typography.titleMedium)
        Text("Wine, Box64 and diagnostics.", color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(28.dp))
        Button(onClick = onReimport) { Text("RE-IMPORT GAME ZIP") }
    }
}

@Composable
private fun Poster() {
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.72f).background(
            Brush.verticalGradient(listOf(Color(0xFF292929), Color(0xFF0E0E0E))),
            RoundedCornerShape(28.dp)
        ),
        contentAlignment = Alignment.Center
    ) {
        Text("NHH", style = MaterialTheme.typography.displayLarge)
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
        withContext(Dispatchers.Main.immediate) {
            onProgress(stage, value, detail)
        }
    }

    try {
        progress(SetupStage.EXTRACTING, 0f, "Preparing ZIP…")

        var totalBytes = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.size > 0) totalBytes += entry.size
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("Could not read the selected ZIP.")

        var extractedBytes = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                val root = stagingDir.canonicalFile
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(stagingDir, entry.name).canonicalFile
                    if (!target.path.startsWith(root.path + File.separator) && target != root) {
                        throw IllegalArgumentException("Invalid ZIP path.")
                    }

                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                extractedBytes += read
                                val fraction = if (totalBytes > 0) {
                                    (extractedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
                                } else 0f
                                progress(SetupStage.EXTRACTING, fraction, entry.name)
                            }
                        }
                    }

                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("Could not read the selected ZIP.")

        progress(SetupStage.EXTRACTING, 1f, "Extraction complete")
        progress(SetupStage.ANALYSING, 0f, "Searching folders and subfolders…")

        val executables = stagingDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }
            .toList()

        val preferred = executables.firstOrNull {
            it.name.lowercase(Locale.ROOT) == "nhh.exe"
        } ?: executables.firstOrNull {
            it.name.lowercase(Locale.ROOT) == "nhh_build.exe"
        }

        progress(
            SetupStage.ANALYSING,
            1f,
            executables.size.toString() + " executable(s) found"
        )

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

private suspend fun finalizeSelectedExe(context: Context, relativePath: String): File =
    withContext(Dispatchers.IO) {
        finalizeGameInstall(context, relativePath)
    }

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
private fun ExeSelectionScreen(
    choices: List<String>,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Select game executable", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "NHH.exe or NHH_build.exe was not found automatically. Select the correct .exe below.",
            color = Color(0xFFAAAAAA)
        )
        Spacer(Modifier.height(18.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(choices) { path ->
                Button(
                    onClick = { onSelect(path) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(path)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("CANCEL")
        }
    }
}

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
import java.util.zip.ZipInputStream

private const val PREFS = "nhh"
private const val GAME_DIR = "game"
private const val NHH_EXE = "NHH.exe"

private enum class SetupStage(val label: String) {
    EXTRACTING("Extracting the ZIP…"),
    ANALYSING("Analysing folders and subfolders…"),
    FOUND("NHH.exe found"),
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
                val result = importGameZip(context, uri) { stage ->
                    setupStage = stage
                }

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

    MaterialExpressiveTheme(
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
                setupStage != null -> SetupProgressScreen(setupStage!!)
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
private fun SetupProgressScreen(stage: SetupStage) {
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
    onStage: (SetupStage) -> Unit
): File = withContext(Dispatchers.IO) {
    val stagingDir = File(context.filesDir, "$GAME_DIR.staging")
    val gameDir = File(context.filesDir, GAME_DIR)

    if (stagingDir.exists()) stagingDir.deleteRecursively()
    stagingDir.mkdirs()

    try {
        onStage(SetupStage.EXTRACTING)
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
                            zip.copyTo(output, DEFAULT_BUFFER_SIZE)
                        }
                    }

                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("Could not read the selected ZIP.")

        onStage(SetupStage.ANALYSING)
        val exe = stagingDir.walkTopDown()
            .firstOrNull { it.isFile && it.name.equals(NHH_EXE, ignoreCase = true) }
            ?: throw IllegalArgumentException("NHH.exe was not found in the selected ZIP.")

        onStage(SetupStage.FOUND)

        val relativeExe = exe.relativeTo(stagingDir).path
        if (gameDir.exists()) gameDir.deleteRecursively()
        if (!stagingDir.renameTo(gameDir)) {
            throw IllegalStateException("Could not finalize the extracted game files.")
        }

        File(gameDir, relativeExe).takeIf { it.exists() }
            ?: throw IllegalStateException("NHH.exe disappeared while finalizing the install.")
    } catch (t: Throwable) {
        stagingDir.deleteRecursively()
        throw t
    }
}

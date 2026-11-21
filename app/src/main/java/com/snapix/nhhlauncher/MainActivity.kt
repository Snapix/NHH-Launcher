package com.snapix.nhhlauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

private const val PREFS = "nhh"
private const val GAME_DIR = "game"
private const val GAME_EXE = "game_exe"

private enum class LauncherScreen { HOME, SETTINGS, CONTROLLER_TEST }
private enum class SetupStage(val label: String) {
    EXTRACTING("Extracting game files…"),
    ANALYSING("Finding game executable…"),
    FOUND("Game executable found"),
    SELECT_EXE("Select executable"),
    BOOTSTRAP("Preparing Winlator runtime…")
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WinlatorRuntime.installCrashRecorder(this)
        val prefs = getSharedPreferences("nhh", Context.MODE_PRIVATE)
        val game = prefs.getString("game_exe", null)?.let(::File)
        if (!prefs.getBoolean("onboarding_complete", false) && game?.isFile != true) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContent { NhhApp(this) }
    }
}

internal val NhhColors = darkColorScheme(
    primary = Color(0xFFFF6B57),
    secondary = Color(0xFFFFC168),
    tertiary = Color(0xFF9CD6A5),
    background = Color(0xFF140B0B),
    surface = Color(0xFF1C1010),
    surfaceContainer = Color(0xFF291717),
    surfaceContainerHigh = Color(0xFF341E1E),
    onBackground = Color(0xFFFCEBE7),
    onSurface = Color(0xFFFCEBE7),
    onSurfaceVariant = Color(0xFFD9C0BA)
)

@Composable
private fun NhhApp(activity: MainActivity) {
    val context = activity.applicationContext
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var gameExe by remember { mutableStateOf(prefs.getString(GAME_EXE, null)) }
    var screen by remember { mutableStateOf(LauncherScreen.HOME) }
    var error by remember { mutableStateOf<String?>(null) }
    var setupStage by remember { mutableStateOf<SetupStage?>(null) }
    var setupProgress by remember { mutableFloatStateOf(0f) }
    var setupDetail by remember { mutableStateOf("") }
    var exeChoices by remember { mutableStateOf<List<String>>(emptyList()) }
    var settings by remember { mutableStateOf(LauncherRuntimeSettings()) }
    var saving by remember { mutableStateOf(false) }
    var settingsFeedback by remember { mutableStateOf<String?>(null) }
    var settingsError by remember { mutableStateOf<String?>(null) }

    fun beginSetup(file: File) {
        scope.launch {
            try {
                setupStage = SetupStage.BOOTSTRAP
                setupProgress = 0f
                setupDetail = "Preparing Winlator runtime…"
                WinlatorRuntime.ensureReady(activity, file, prefs, { p, d ->
                    setupProgress = p
                    setupDetail = d
                }, false)
                prefs.edit().putString(GAME_EXE, file.absolutePath).apply()
                gameExe = file.absolutePath
                setupStage = null
                error = null
            } catch (t: Throwable) {
                setupStage = null
                error = t.message ?: "Runtime preparation failed."
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val result = importGameZip(context, uri,
                    { stage, p, d ->
                        setupStage = stage
                        setupProgress = p
                        setupDetail = d
                    },
                    { exeChoices = it }
                )
                if (result != null) beginSetup(result)
            } catch (t: Throwable) {
                setupStage = null
                error = t.message ?: "Could not import the game ZIP."
            }
        }
    }

    MaterialTheme(colorScheme = NhhColors) {
        Surface(Modifier.fillMaxSize()) {
            when {
                setupStage == SetupStage.SELECT_EXE -> ExeSelectionScreen(
                    exeChoices,
                    { relative ->
                        scope.launch {
                            try {
                                beginSetup(finalizeSelectedExe(context, relative))
                            } catch (t: Throwable) {
                                setupStage = null
                                error = t.message ?: "Could not select executable."
                            }
                        }
                    },
                    {
                        setupStage = null
                        error = "No executable was selected."
                    }
                )

                setupStage != null -> SetupProgressScreen(setupStage!!, setupProgress, setupDetail)

                screen == LauncherScreen.SETTINGS -> LauncherSettingsScreen(
                    settings = settings,
                    isSaving = saving,
                    feedback = settingsFeedback,
                    error = settingsError,
                    onChange = {
                        settings = it
                        settingsFeedback = null
                        settingsError = null
                    },
                    onSave = {
                        scope.launch {
                            saving = true
                            try {
                                WinlatorRuntime.saveSettings(context, prefs, settings)
                                settingsFeedback = "Settings saved. Restart the game to apply runtime changes."
                            } catch (t: Throwable) {
                                settingsError = t.message ?: "Could not save settings."
                            } finally {
                                saving = false
                            }
                        }
                    },
                    onBack = { screen = LauncherScreen.HOME },
                    onReimport = { picker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onResetControls = {
                        WinlatorRuntime.resetControlsLayout(context)
                        settingsFeedback = "NHH Xbox layout restored."
                    },
                    onOpenControllerTest = { screen = LauncherScreen.CONTROLLER_TEST },
                    onShareReport = { WinlatorRuntime.shareLaunchReport(activity) }
                )

                screen == LauncherScreen.CONTROLLER_TEST -> ControllerTestScreen(
                    onBack = { screen = LauncherScreen.HOME },
                    onResetLayout = { WinlatorRuntime.resetControlsLayout(context) }
                )

                gameExe == null || !File(gameExe!!).isFile -> SetupScreen(
                    error,
                    { activity.finish() },
                    { picker.launch(arrayOf("application/zip", "application/octet-stream")) }
                )

                else -> MainMenu(
                    File(gameExe!!).name,
                    error,
                    onSettings = {
                        scope.launch {
                            try {
                                settings = WinlatorRuntime.loadSettings(context, prefs)
                                settingsError = null
                                settingsFeedback = null
                                screen = LauncherScreen.SETTINGS
                            } catch (t: Throwable) {
                                error = t.message ?: "Could not load settings."
                            }
                        }
                    },
                    onRun = {
                        scope.launch {
                            try {
                                setupStage = SetupStage.BOOTSTRAP
                                WinlatorRuntime.ensureReady(
                                    activity, File(gameExe!!), prefs,
                                    { p, d -> setupProgress = p; setupDetail = d }, false
                                )
                                setupStage = null
                                WinlatorRuntime.launchGame(activity, prefs)
                            } catch (t: Throwable) {
                                setupStage = null
                                error = t.message ?: "Could not launch game."
                            }
                        }
                    },
                    onEditControls = { activity.startActivity(Intent(activity, ControlsActivity::class.java)) },
                    onTestController = { screen = LauncherScreen.CONTROLLER_TEST },
                    onEnterDesktop = {
                        scope.launch {
                            try {
                                setupStage = SetupStage.BOOTSTRAP
                                WinlatorRuntime.ensureReady(
                                    activity, File(gameExe!!), prefs,
                                    { p, d -> setupProgress = p; setupDetail = d }, false
                                )
                                setupStage = null
                                WinlatorRuntime.enterDesktop(activity, prefs)
                            } catch (t: Throwable) {
                                setupStage = null
                                error = t.message ?: "Could not enter desktop mode."
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MainMenu(
    gameName: String,
    error: String?,
    onSettings: () -> Unit,
    onRun: () -> Unit,
    onEditControls: () -> Unit,
    onTestController: () -> Unit,
    onEnterDesktop: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("NHH", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("No Heroes Here", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text(gameName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, "Settings")
            }
        }

        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
            AnimatedGameGif()
        }

        Button(
            onClick = onRun,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(30.dp)
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("RUN GAME", fontWeight = FontWeight.ExtraBold)
        }

        FilledTonalButton(onClick = onEditControls, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.Gamepad, null)
            Spacer(Modifier.width(8.dp))
            Text("CONTROLS")
        }

        FilledTonalButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.Settings, null)
            Spacer(Modifier.width(8.dp))
            Text("SETTINGS")
        }

        OutlinedButton(
            onClick = onEnterDesktop,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.DesktopWindows, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(8.dp))
            Text("ENTER DESKTOP", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("NHH XBOX CONTROL MAP", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Left Stick / D-Pad  — Move and menus")
                Text("A / B  — Confirm / Back")
                Text("X / Y  — Interact / Dash")
                Text("LB / LT  — Switch")
                Text("RB / RT  — Combat")
                Text("Menu  — Pause")
            }
        }

        if (!error.isNullOrBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(error, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}



@Composable
private fun AnimatedGameGif() {
    AndroidView(
        factory = { ctx ->
            android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageResource(com.snapix.nhhlauncher.R.drawable.nhh_gameplay)
                post {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().height(180.dp)
    )
}

@Composable
private fun SetupScreen(error: String?, onBack: () -> Unit, onSelectZip: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("NHH Launcher Setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, Color(0xDD140B0B)))),
            contentAlignment = Alignment.Center
        ) {
            Text("NO HEROES HERE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Import Game ZIP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Extract the game, create its Winlator container, map the game as G:, and preload the NHH Xbox control profile.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onSelectZip, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("SELECT GAME ZIP")
                }
                if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SetupProgressScreen(stage: SetupStage, progress: Float, detail: String) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Preparing NHH", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stage.label, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        Text((progress.coerceIn(0f, 1f) * 100f).toInt().toString() + "%")
    }
}

@Composable
private fun ExeSelectionScreen(
    choices: List<String>,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Select Game Executable", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "NHH.exe was not found automatically. Select the executable that starts the game.",
            Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(
            Modifier.weight(1f).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(choices) { path ->
                FilledTonalButton(onClick = { onSelect(path) }, modifier = Modifier.fillMaxWidth()) {
                    Text(path)
                }
            }
        }
        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("CANCEL") }
    }
}

private suspend fun importGameZip(
    context: Context,
    uri: Uri,
    onProgress: suspend (SetupStage, Float, String) -> Unit,
    onNeedsSelection: (List<String>) -> Unit
): File? = withContext(Dispatchers.IO) {
    val staging = File(context.filesDir, GAME_DIR + ".staging")
    if (staging.exists()) staging.deleteRecursively()
    staging.mkdirs()

    suspend fun progress(stage: SetupStage, value: Float, detail: String) {
        withContext(Dispatchers.Main.immediate) { onProgress(stage, value, detail) }
    }

    try {
        progress(SetupStage.EXTRACTING, 0f, "Opening ZIP…")
        var total = 0L
        var done = 0L
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not read the ZIP.")

        input.use { raw ->
            ZipInputStream(raw.buffered(1024 * 1024)).use { zip ->
                val buffer = ByteArray(1024 * 1024)
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(staging, entry.name).canonicalFile
                    val root = staging.canonicalFile
                    if (!target.path.startsWith(root.path + File.separator) && target != root) {
                        throw IllegalArgumentException("ZIP contains an unsafe path.")
                    }

                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).buffered(1024 * 1024).use { out ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                            }
                        }
                        if (entry.size > 0) total += entry.size
                    }

                    progress(
                        SetupStage.EXTRACTING,
                        if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f,
                        entry.name
                    )
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        val executables = staging.walkTopDown()
            .filter { it.isFile && it.extension.equals("exe", true) }
            .map { it.relativeTo(staging).path }
            .toList()

        val preferred = executables.firstOrNull { File(it).name.equals("NHH.exe", true) }
            ?: executables.firstOrNull { File(it).name.equals("NHH_build.exe", true) }

        if (preferred == null) {
            if (executables.isEmpty()) {
                throw IllegalArgumentException("No .exe file was found in the ZIP.")
            }
            withContext(Dispatchers.Main.immediate) { onNeedsSelection(executables) }
            withContext(Dispatchers.Main.immediate) {
                onProgress(SetupStage.SELECT_EXE, 1f, "Choose the game executable")
            }
            return@withContext null
        }

        progress(SetupStage.FOUND, 1f, File(preferred).name)
        finalizeGameInstall(context, preferred)
    } catch (t: Throwable) {
        staging.deleteRecursively()
        throw t
    }
}

private suspend fun finalizeSelectedExe(context: Context, relativePath: String): File =
    withContext(Dispatchers.IO) { finalizeGameInstall(context, relativePath) }

private fun finalizeGameInstall(context: Context, relativePath: String): File {
    val staging = File(context.filesDir, GAME_DIR + ".staging").canonicalFile
    val target = File(staging, relativePath).canonicalFile
    if (!target.isFile || !target.path.startsWith(staging.path + File.separator)) {
        throw IllegalArgumentException("Invalid executable selection.")
    }
    val gameDir = File(context.filesDir, GAME_DIR)
    if (gameDir.exists()) gameDir.deleteRecursively()
    if (!staging.renameTo(gameDir)) {
        throw IllegalStateException("Could not finalize game installation.")
    }
    return File(gameDir, relativePath).canonicalFile
}

package com.snapix.nhhlauncher

import com.winlator.R

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
import androidx.compose.material.icons.filled.Home
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

private enum class LauncherScreen { HOME, GAME, SETTINGS, CONTROLLER_TEST }
private enum class SetupStage(val label: String) {
    EXTRACTING("Extracting game files…"),
    ANALYSING("Finding game executable…"),
    FOUND("Game executable found"),
    SELECT_EXE("Select executable"),
    BOOTSTRAP("Preparing game environment…")
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
                setupDetail = "Preparing game environment…"
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
                    onChange = { value ->
                        settings = value
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

                else -> {
                    val openSettings: () -> Unit = {
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
                    }
                    val runGame: () -> Unit = {
                        scope.launch {
                            try {
                                setupStage = SetupStage.BOOTSTRAP
                                WinlatorRuntime.ensureReady(
                                    activity, File(gameExe!!), prefs,
                                    { p, d -> setupProgress = p; setupDetail = d }, false
                                )
                                setupStage = null
                                WinlatorRuntime.launchGame(activity, prefs)
                            } catch (first: Throwable) {
                                try {
                                    setupStage = SetupStage.BOOTSTRAP
                                    setupDetail = "Repairing the NHH game environment…"
                                    WinlatorRuntime.ensureReady(
                                        activity, File(gameExe!!), prefs,
                                        { p, d -> setupProgress = p; setupDetail = d }, true
                                    )
                                    setupStage = null
                                    WinlatorRuntime.launchGame(activity, prefs)
                                } catch (second: Throwable) {
                                    setupStage = null
                                    error = "Game launch failed. ${second.message ?: first.message ?: "Unknown Winlator error."}"
                                }
                            }
                        }
                    }
                    val enterDesktop: () -> Unit = {
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
                    when (screen) {
                        LauncherScreen.HOME -> HomeScreen(
                            gameName = File(gameExe!!).name,
                            onGame = { screen = LauncherScreen.GAME },
                            onSettings = openSettings,
                            error = error
                        )
                        LauncherScreen.GAME -> GameScreen(
                            gameName = File(gameExe!!).name,
                            error = error,
                            onRun = runGame,
                            onEditControls = { activity.startActivity(Intent(activity, ControlsActivity::class.java)) },
                            onTestController = { screen = LauncherScreen.CONTROLLER_TEST },
                            onEnterDesktop = enterDesktop,
                            onSettings = openSettings,
                            onHome = { screen = LauncherScreen.HOME },
                            onReimport = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }
                        )
                        else -> Unit
                    }
                })
            }
        }
    }
}

@Composable
private fun HomeScreen(
    gameName: String,
    onGame: () -> Unit,
    onSettings: () -> Unit,
    error: String?
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NO HEROES HERE", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text("NHH Launcher", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) { AnimatedGameGif() }
        ElevatedCard(onClick = onGame, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("GAME", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text(gameName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("OPEN", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        Text("Winlator runtime", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Dedicated NHH container, Unity-safe launch arguments, touch controls and desktop mode.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!error.isNullOrBlank()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(error, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
        LauncherNav(active = LauncherScreen.HOME, onHome = {}, onGame = onGame, onSettings = onSettings)
    }
}

@Composable
private fun GameScreen(
    gameName: String,
    error: String?,
    onRun: () -> Unit,
    onEditControls: () -> Unit,
    onTestController: () -> Unit,
    onEnterDesktop: () -> Unit,
    onSettings: () -> Unit,
    onHome: () -> Unit,
    onReimport: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GAME", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(gameName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No Heroes Here", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text("Winlator 11.2 • NHH container • Unity launch profile", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = onRun, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("RUN GAME", fontWeight = FontWeight.Black)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = onEditControls, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Default.Gamepad, null)
                Spacer(Modifier.width(6.dp))
                Text("CONTROLS")
            }
            FilledTonalButton(onClick = onTestController, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Default.Tune, null)
                Spacer(Modifier.width(6.dp))
                Text("TEST")
            }
        }
        OutlinedButton(onClick = onEnterDesktop, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.DesktopWindows, null)
            Spacer(Modifier.width(8.dp))
            Text("ENTER WINLATOR DESKTOP")
        }
        OutlinedButton(onClick = onReimport, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("REIMPORT GAME ZIP")
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("IN-GAME MENU", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("The MENU button, Android Back, or four-finger tap opens Resume, controls, editor, Cast and Exit.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!error.isNullOrBlank()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(error, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
        LauncherNav(active = LauncherScreen.GAME, onHome = onHome, onGame = {}, onSettings = onSettings)
    }
}

@Composable
private fun LauncherNav(active: LauncherScreen, onHome: () -> Unit, onGame: () -> Unit, onSettings: () -> Unit) {
    NavigationBar {
        NavigationBarItem(selected = active == LauncherScreen.HOME, onClick = onHome, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
        NavigationBarItem(selected = active == LauncherScreen.GAME, onClick = onGame, icon = { Icon(Icons.Default.PlayArrow, null) }, label = { Text("Game") })
        NavigationBarItem(selected = active == LauncherScreen.SETTINGS, onClick = onSettings, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
    }
}


@Composable
private fun AnimatedGameGif() {
    AndroidView(
        factory = { ctx ->
            android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setAdjustViewBounds(true)
                setImageResource(R.drawable.nhh_hero)
                setBackgroundColor(android.graphics.Color.rgb(20, 11, 11))
            }
        },
        modifier = Modifier.fillMaxWidth().height(190.dp)
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
                    "Extract the game files, prepare the game environment, map the game drive, and install the NHH controller layouts.",
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

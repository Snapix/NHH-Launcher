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
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

private const val PREFS = "nhh"
private const val GAME_DIR = "game"
private const val NHH_EXE = "NHH.exe"

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

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val exe = importGameZip(context, uri)
            prefs.edit().putString("game_exe", exe.absolutePath).apply()
            gameExe = exe.absolutePath
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Could not import the game ZIP."
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0B0B)) {
            when {
                settings -> SettingsScreen(
                    onBack = { settings = false },
                    onReimport = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }
                )
                gameExe == null -> SetupScreen(
                    error = error,
                    onSelectZip = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }
                )
                else -> MainMenu(
                    onRun = { error = "Winlator runtime integration is the next milestone." },
                    onSettings = { settings = true }
                )
            }
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
        Text("NHH Launcher", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("Select your NHH game ZIP to set up the launcher.", color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSelectZip,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) { Text("SELECT GAME ZIP") }
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
            Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) { Text("RUN GAME") }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("SETTINGS", color = Color.White)
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, onReimport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("DONE") }
        }
        Spacer(Modifier.height(20.dp))
        Text("Controls", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text("Touch, Xbox controller and keyboard mapping.", color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(18.dp))
        Text("Graphics", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text("Renderer, resolution and graphics options.", color = Color(0xFFAAAAAA))
        Spacer(Modifier.height(18.dp))
        Text("Compatibility", color = Color.White, style = MaterialTheme.typography.titleMedium)
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
            RoundedCornerShape(14.dp)
        ),
        contentAlignment = Alignment.Center
    ) { Text("NHH", color = Color.White, style = MaterialTheme.typography.displayLarge) }
}

private fun importGameZip(context: Context, uri: Uri): File {
    val gameDir = File(context.filesDir, GAME_DIR)
    if (gameDir.exists()) gameDir.deleteRecursively()
    gameDir.mkdirs()

    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input.buffered()).use { zip ->
            val root = gameDir.canonicalFile
            var entry = zip.nextEntry
            while (entry != null) {
                val target = File(gameDir, entry.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator) && target != root) {
                    throw IllegalArgumentException("Invalid ZIP path.")
                }
                if (entry.isDirectory) target.mkdirs()
                else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } ?: throw IllegalArgumentException("Could not read the selected ZIP.")

    return gameDir.walkTopDown().firstOrNull { it.isFile && it.name.equals(NHH_EXE, true) }
        ?: throw IllegalArgumentException("NHH.exe was not found in the selected ZIP.")
}

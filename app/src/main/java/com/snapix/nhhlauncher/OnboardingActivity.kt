package com.snapix.nhhlauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

private const val PREFS = "nhh"
private const val PREF_ONBOARDING = "onboarding_complete"
private const val GAME_DIR = "game"
private const val GAME_EXE = "game_exe"

class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OnboardingApp(this) }
    }
}

private enum class Page { WELCOME, RUNTIME, OWNERSHIP, ZIP, FOUND, NOT_FOUND, INSTALLING, COMPLETE, CREDITS }

@Composable
private fun OnboardingApp(activity: OnboardingActivity) {
    val context = activity.applicationContext
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.WELCOME) }
    var detail by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    fun finish() {
        prefs.edit().putBoolean(PREF_ONBOARDING, true).apply()
        activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        activity.finish()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                page = Page.FOUND
                error = null
                detail = "Reading ZIP…"
                val exe = importZip(context, uri) { p, d -> progress = p; detail = d }
                if (exe == null) {
                    page = Page.NOT_FOUND
                    return@launch
                }
                detail = exe.name
                page = Page.INSTALLING
                WinlatorRuntime.ensureReady(activity, exe, prefs, { p, d -> progress = p; detail = d }, false)
                prefs.edit().putString(GAME_EXE, exe.absolutePath).apply()
                page = Page.COMPLETE
            } catch (t: Throwable) {
                error = t.message ?: "Import failed."
                page = Page.NOT_FOUND
            }
        }
    }

    MaterialTheme(colorScheme = NhhColors) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (page) {
                    Page.WELCOME -> {
                        GameImage(R.drawable.nhh_poster, 300.dp)
                        Text("Welcome to No heroes here Android port", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Button(onClick = { page = Page.RUNTIME }, Modifier.fillMaxWidth().height(56.dp)) { Text("NEXT") }
                    }
                    Page.RUNTIME -> {
                        Text("Powered by an x86 → ARM translation layer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text("The Android port uses Wine together with x86-to-ARM translation through the Winlator runtime.")
                        Button(onClick = { page = Page.OWNERSHIP }, Modifier.fillMaxWidth().height(56.dp)) { Text("NEXT") }
                    }
                    Page.OWNERSHIP -> {
                        Text("You must own the game", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text("You must own No Heroes Here on Steam before importing it through a game ZIP.", fontWeight = FontWeight.Bold)
                        Text("On your computer, open the Steam installation folder, copy the installed game files to this device, and compress them into a ZIP.")
                        Text("Please use only game files you lawfully own. Pirated copies may violate copyright law; this app does not provide, host, or distribute the game.", color = MaterialTheme.colorScheme.secondary)
                        Button(onClick = { page = Page.ZIP }, Modifier.fillMaxWidth().height(56.dp)) { Text("I UNDERSTAND") }
                    }
                    Page.ZIP -> {
                        GameImage(R.drawable.nhh_hero, 210.dp)
                        Text("Select ZIP file", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text("Select the ZIP containing your extracted Steam game files.")
                        Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, Modifier.fillMaxWidth().height(58.dp)) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text("SELECT ZIP")
                        }
                    }
                    Page.FOUND -> {
                        Text("Game found!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text(detail)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Page.NOT_FOUND -> {
                        Text("Game not found", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text(error ?: "Please check your ZIP and make sure the game executable is inside it.")
                        Button(onClick = { error = null; page = Page.ZIP }, Modifier.fillMaxWidth().height(56.dp)) { Text("TRY ANOTHER ZIP") }
                    }
                    Page.INSTALLING -> {
                        Text("Installing runtime layer…", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text(detail.ifBlank { "Preparing the runtime. This is only needed when the runtime is missing or outdated." })
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        Text("The Winlator installer popup is hidden. Progress is shown here instead.")
                    }
                    Page.COMPLETE -> {
                        GameImage(R.drawable.nhh_hero, 230.dp)
                        Text("You're all set!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text("The game and runtime are ready.")
                        Button(onClick = { page = Page.CREDITS }, Modifier.fillMaxWidth().height(56.dp)) { Text("NEXT") }
                    }
                    Page.CREDITS -> {
                        GameImage(R.drawable.nhh_poster, 230.dp)
                        Text("Port made by Snappy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Button(onClick = { open(activity, "https://github.com/Snapix/NHH-Launcher") }, Modifier.fillMaxWidth()) { Text("GITHUB") }
                        Button(onClick = { open(activity, "https://www.instagram.com/snapix_yt?lqvp=aTZudG53bzB3bWx2") }, Modifier.fillMaxWidth()) { Text("INSTAGRAM") }
                        Button(onClick = { open(activity, "https://oksnap.vercel.app") }, Modifier.fillMaxWidth()) { Text("ALL PROJECTS") }
                        Button(onClick = { finish() }, Modifier.fillMaxWidth().height(56.dp)) { Text("FINISH") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameImage(resId: Int, height: androidx.compose.ui.unit.Dp) {
    Image(painterResource(resId), null, Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(22.dp)), contentScale = ContentScale.Crop)
}

private fun open(activity: OnboardingActivity, url: String) {
    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private suspend fun importZip(context: Context, uri: Uri, progress: (Float, String) -> Unit): File? = withContext(Dispatchers.IO) {
    val staging = File(context.filesDir, GAME_DIR + ".staging")
    staging.deleteRecursively()
    staging.mkdirs()
    val input = context.contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Could not read the ZIP.")

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
                        }
                    }
                }
                withContext(Dispatchers.Main.immediate) { progress(0f, entry.name) }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    val exes = staging.walkTopDown()
        .filter { it.isFile && it.extension.equals("exe", true) }
        .map { it.relativeTo(staging).path }
        .filterNot {
            val n = File(it).name.lowercase()
            n.startsWith("unins") || n.startsWith("setup") || n.contains("redist") || n.contains("crash")
        }
        .toList()

    val preferred = exes.firstOrNull { File(it).name.equals("NHH.exe", true) }
        ?: exes.firstOrNull { File(it).name.lowercase().contains("heroes") }
        ?: exes.singleOrNull()

    if (preferred == null) return@withContext null

    val game = File(context.filesDir, GAME_DIR)
    val old = File(context.filesDir, GAME_DIR + ".previous")
    old.deleteRecursively()
    if (game.exists()) game.renameTo(old)
    if (!staging.renameTo(game)) {
        old.renameTo(game)
        throw IllegalStateException("Could not finalize the game installation.")
    }
    old.deleteRecursively()
    File(game, preferred).canonicalFile
}

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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

private enum class Page { WELCOME, OWNERSHIP, ZIP, INSTALLING, COMPLETE, CREDITS, ERROR }

@Composable
private fun OnboardingApp(activity: OnboardingActivity) {
    val context = activity.applicationContext
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.WELCOME) }
    var detail by remember { mutableStateOf("Getting things ready…") }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun finish() {
        prefs.edit().putBoolean(PREF_ONBOARDING, true).apply()
        activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        activity.finish()
    }

    fun importSelectedZip(uri: Uri) {
        scope.launch {
            busy = true
            error = null
            page = Page.INSTALLING
            progress = 0f
            detail = "Reading your game archive…"
            try {
                val exe = importZip(context, uri) { p, d -> progress = p; detail = d }
                if (exe == null) {
                    error = "No game executable was found. Select a ZIP containing the installed game files."
                    page = Page.ERROR
                    return@launch
                }
                detail = "Preparing your game environment…"
                progress = 0.02f
                WinlatorRuntime.ensureReady(activity, exe, prefs, { p, d -> progress = p; detail = d }, false)
                prefs.edit().putString(GAME_EXE, exe.absolutePath).apply()
                progress = 1f
                detail = "Everything is ready."
                page = Page.COMPLETE
            } catch (t: Throwable) {
                error = t.message ?: "The game could not be installed."
                page = Page.ERROR
            } finally { busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && !busy) importSelectedZip(uri)
    }

    fun primaryAction() {
        when (page) {
            Page.WELCOME -> page = Page.OWNERSHIP
            Page.OWNERSHIP -> page = Page.ZIP
            Page.ZIP -> picker.launch(arrayOf("application/zip", "application/octet-stream"))
            Page.COMPLETE -> page = Page.CREDITS
            Page.CREDITS -> finish()
            Page.ERROR -> { error = null; page = Page.ZIP }
            Page.INSTALLING -> Unit
        }
    }

    MaterialTheme(colorScheme = NhhColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 22.dp, vertical = 14.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center) {
                        Text("NHH", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("NO HEROES HERE", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                        Text("ANDROID EDITION", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        when (page) {
                            Page.WELCOME, Page.OWNERSHIP, Page.ZIP -> "SETUP"
                            Page.INSTALLING -> "PREPARING"
                            Page.COMPLETE, Page.CREDITS -> "READY"
                            Page.ERROR -> "RETRY"
                        },
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(14.dp))
                if (page == Page.WELCOME || page == Page.OWNERSHIP || page == Page.ZIP) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(3) { index ->
                            val active = when (page) {
                                Page.WELCOME -> index == 0
                                Page.OWNERSHIP -> index <= 1
                                else -> true
                            }
                            Box(Modifier.weight(1f).height(3.dp).clip(CircleShape)
                                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh))
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 7 }) togetherWith
                                (fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 7 })
                        },
                        label = "NHH onboarding page"
                    ) { target ->
                        when (target) {
                            Page.WELCOME -> WelcomePage()
                            Page.OWNERSHIP -> OwnershipPage()
                            Page.ZIP -> ZipPage()
                            Page.INSTALLING -> InstallingPage(progress, detail)
                            Page.COMPLETE -> CompletePage()
                            Page.CREDITS -> CreditsPage(
                                { open(activity, "https://github.com/Snapix/NHH-Launcher") },
                                { open(activity, "https://www.instagram.com/snapix_yt/") },
                                { open(activity, "https://oksnap.vercel.app") }
                            )
                            Page.ERROR -> ErrorPage(error ?: "The game could not be installed.")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (page != Page.INSTALLING) {
                    Button(onClick = ::primaryAction, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
                        if (page == Page.ZIP) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(9.dp))
                        }
                        Text(
                            when (page) {
                                Page.WELCOME -> "LET'S GET STARTED"
                                Page.OWNERSHIP -> "I OWN THE GAME"
                                Page.ZIP -> "CHOOSE GAME ZIP"
                                Page.COMPLETE -> "CONTINUE"
                                Page.CREDITS -> "START PLAYING"
                                Page.ERROR -> "CHOOSE ANOTHER ZIP"
                                Page.INSTALLING -> "PLEASE WAIT"
                            }, fontWeight = FontWeight.ExtraBold
                        )
                    }
                } else {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    Text((progress.coerceIn(0f, 1f) * 100f).toInt().toString() + "%",
                        Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    val transition = rememberInfiniteTransition(label = "hero pulse")
    val scale by transition.animateFloat(
        0.985f,
        1.015f,
        infiniteRepeatable(tween(2300), RepeatMode.Reverse),
        label = "hero scale"
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val heroHeight = (maxHeight * 0.40f).coerceIn(112.dp, 230.dp)
        val compact = maxHeight < 390.dp
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 13.dp)
        ) {
            Image(
                painterResource(R.drawable.nhh_poster),
                "No Heroes Here artwork",
                Modifier.widthIn(max = 292.dp).fillMaxWidth().height(heroHeight)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Fit
            )
            Text(
                "Your game.\nYour Android.",
                style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Text(
                "A dedicated Android experience for No Heroes Here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun OwnershipPage() {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val artworkHeight = (maxHeight * 0.30f).coerceIn(88.dp, 165.dp)
        val compact = maxHeight < 390.dp
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 15.dp)
        ) {
            Image(
                painterResource(R.drawable.nhh_hero),
                "Game artwork",
                Modifier.fillMaxWidth().height(artworkHeight).clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Fit
            )
            Text(
                "Bring your own\ngame files",
                style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Text(
                "You must own No Heroes Here on Steam. Copy the installed game folder from your computer and create a ZIP archive.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                "Your game files stay on your device. This app does not include or distribute the game.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ZipPage() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(94.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text("Select your game ZIP", style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text("Choose the archive containing the installed game files. The app will locate the game executable and prepare everything automatically.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun InstallingPage(progress: Float, detail: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(68.dp), strokeWidth = 5.dp)
        Text("Preparing your game", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text(detail.ifBlank { "Setting up required system components…" },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text("Keep the app open while setup completes.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CompletePage() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Default.CheckCircle, null, Modifier.size(76.dp), tint = MaterialTheme.colorScheme.tertiary)
        Text("You're all set.", style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text("Your game environment is ready. One last screen, then you can jump in.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CreditsPage(onGithub: () -> Unit, onInstagram: () -> Unit, onProjects: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.nhh_poster), "No Heroes Here",
            Modifier.widthIn(max = 220.dp).fillMaxWidth().height(140.dp), contentScale = ContentScale.Fit)
        Text("Built by Snappy", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text("Follow the work and find more projects.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        ActionLink("GitHub", Icons.Default.Code, onGithub)
        ActionLink("Instagram", Icons.Default.CameraAlt, onInstagram)
        ActionLink("Projects", Icons.Default.GridView, onProjects)
    }
}

@Composable
private fun ErrorPage(message: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Setup didn't finish", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ActionLink(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(15.dp)) {
        Icon(icon, null)
        Spacer(Modifier.width(10.dp))
        Text(title, fontWeight = FontWeight.Bold)
    }
}

private fun open(activity: OnboardingActivity, url: String) {
    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private suspend fun importZip(context: Context, uri: Uri, progress: (Float, String) -> Unit): File? =
    withContext(Dispatchers.IO) {
        val staging = File(context.filesDir, GAME_DIR + ".staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not read the ZIP archive.")
        input.use { raw ->
            ZipInputStream(raw.buffered(1024 * 1024)).use { zip ->
                val buffer = ByteArray(1024 * 1024)
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(staging, entry.name).canonicalFile
                    val root = staging.canonicalFile
                    if (!target.path.startsWith(root.path + File.separator) && target != root)
                        throw IllegalArgumentException("The ZIP contains an unsafe file path.")
                    if (entry.isDirectory) target.mkdirs()
                    else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).buffered(1024 * 1024).use { out ->
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                    withContext(Dispatchers.Main.immediate) { progress(0f, entry.name) }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val executables = staging.walkTopDown().filter { it.isFile && it.extension.equals("exe", true) }
            .map { it.relativeTo(staging).path }
            .filterNot {
                val name = File(it).name.lowercase()
                name.startsWith("unins") || name.startsWith("setup") || name.contains("redist") || name.contains("crash")
            }.toList()
        val preferred = executables.firstOrNull { File(it).name.equals("NHH.exe", true) }
            ?: executables.firstOrNull { File(it).name.lowercase().contains("heroes") }
            ?: executables.singleOrNull()
        if (preferred == null) return@withContext null

        val game = File(context.filesDir, GAME_DIR)
        val previous = File(context.filesDir, GAME_DIR + ".previous")
        previous.deleteRecursively()
        if (game.exists() && !game.renameTo(previous))
            throw IllegalStateException("Could not move the previous game installation.")
        if (!staging.renameTo(game)) {
            previous.renameTo(game)
            throw IllegalStateException("Could not finish installing the game files.")
        }
        previous.deleteRecursively()
        File(game, preferred).canonicalFile
    }

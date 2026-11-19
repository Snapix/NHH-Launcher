package com.snapix.nhhlauncher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val CardColor = Color(0xFF251414)
private val Accent = Color(0xFFFF6A5A)
private val Muted = Color(0xFFD5BDB8)

@Composable
internal fun LauncherSettingsScreen(
    settings: LauncherRuntimeSettings,
    isSaving: Boolean,
    feedback: String?,
    error: String?,
    onChange: (LauncherRuntimeSettings) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onReimport: () -> Unit,
    onShareReport: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        SettingsGroup("Display", Icons.Default.GraphicEq) {
            ChoiceField("Resolution", settings.resolution, listOf(
                "960x540" to "960 × 540",
                "1280x720" to "1280 × 720",
                "1600x900" to "1600 × 900",
                "1920x1080" to "1920 × 1080"
            )) { onChange(settings.copy(resolution = it)) }
        }

        SettingsGroup("Graphics", Icons.Default.Tune) {
            ChoiceField("Vulkan", settings.vulkanDriver, listOf(
                "vortek" to "Vortek",
                "turnip" to "Turnip"
            )) { onChange(settings.copy(vulkanDriver = it)) }
            ChoiceField("OpenGL", settings.openGlDriver, listOf(
                "gladio" to "Gladio",
                "zink" to "Zink",
                "virgl" to "VirGL"
            )) { onChange(settings.copy(openGlDriver = it)) }
            ChoiceField("DirectX", settings.dxWrapper, listOf(
                "dxvk" to "DXVK",
                "wined3d" to "WineD3D"
            )) { onChange(settings.copy(dxWrapper = it)) }
        }

        SettingsGroup("Performance", Icons.Default.Memory) {
            ChoiceField("Box64", settings.box64Preset, listOf(
                "STABILITY" to "Stability",
                "CONSERVATIVE" to "Conservative",
                "INTERMEDIATE" to "Intermediate",
                "PERFORMANCE" to "Performance"
            )) { onChange(settings.copy(box64Preset = it)) }
            SettingSwitch("ESYNC", settings.wineEsync) { onChange(settings.copy(wineEsync = it)) }
        }

        SettingsGroup("Controls & audio", Icons.Default.SettingsInputComponent) {
            SettingSwitch("Touch controls", settings.onScreenControls) { onChange(settings.copy(onScreenControls = it)) }
            ChoiceField("Audio", settings.audioDriver, listOf(
                "alsa" to "ALSA",
                "pulseaudio" to "PulseAudio"
            )) { onChange(settings.copy(audioDriver = it)) }
        }

        SettingsGroup("Game files", Icons.Default.Build) {
            Text("Re-importing replaces the current game files.", color = Muted)
            OutlinedButton(onClick = onReimport, Modifier.fillMaxWidth().height(52.dp)) {
                Text("RE-IMPORT ZIP")
            }
            OutlinedButton(onClick = onShareReport, Modifier.fillMaxWidth().height(52.dp)) {
                Text("SHARE LOG")
            }
        }

        if (!feedback.isNullOrBlank()) Text(feedback, color = Color(0xFFFFB35C))
        if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)

        Button(
            onClick = onSave,
            enabled = !isSaving,
            Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            Text(if (isSaving) "SAVING…" else "SAVE")
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Accent)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun ChoiceField(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == selected }?.second ?: selected
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { expanded = true },
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(display, Modifier.weight(1f))
                Text("▾", color = Accent)
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.second) },
                    onClick = { expanded = false; onSelected(option.first) }
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onCheckedChange)
    }
}

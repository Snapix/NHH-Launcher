package com.snapix.nhhlauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val PageBackground = Color(0xFF160B0B)
private val CardColor = Color(0xFF321717)
private val CardColor2 = Color(0xFF3A1B1B)
private val Accent = Color(0xFFFF735F)
private val Accent2 = Color(0xFFFFB35C)
private val MutedText = Color(0xFFD1B9B3)

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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Text("NHH Runtime Configuration", color = MutedText)
            }
            Text(
                "GROUPED",
                color = Accent,
                style = MaterialTheme.typography.labelSmall
            )
        }

        InfoCard()

        SettingsGroup(
            title = "DISPLAY & AUDIO",
            icon = Icons.Default.GraphicEq
        ) {
            ChoiceField(
                label = "Display resolution",
                selected = settings.resolution,
                options = listOf(
                    "960x540" to "960 × 540",
                    "1280x720" to "1280 × 720",
                    "1600x900" to "1600 × 900",
                    "1920x1080" to "1920 × 1080"
                ),
                onSelected = { onChange(settings.copy(resolution = it)) }
            )
            ChoiceField(
                label = "Audio backend",
                selected = settings.audioDriver,
                options = listOf("alsa" to "ALSA", "pulseaudio" to "PulseAudio"),
                onSelected = { onChange(settings.copy(audioDriver = it)) }
            )
        }

        SettingsGroup(
            title = "GRAPHICS",
            icon = Icons.Default.Tune
        ) {
            ChoiceField(
                label = "Vulkan driver",
                selected = settings.vulkanDriver,
                options = listOf("vortek" to "Vortek", "turnip" to "Turnip · Adreno"),
                onSelected = { onChange(settings.copy(vulkanDriver = it)) }
            )
            ChoiceField(
                label = "OpenGL driver",
                selected = settings.openGlDriver,
                options = listOf(
                    "gladio" to "Gladio",
                    "zink" to "Zink",
                    "virgl" to "VirGL"
                ),
                onSelected = { onChange(settings.copy(openGlDriver = it)) }
            )
            ChoiceField(
                label = "DirectX translation",
                selected = settings.dxWrapper,
                options = listOf("dxvk" to "DXVK", "wined3d" to "WineD3D"),
                onSelected = { onChange(settings.copy(dxWrapper = it)) }
            )
        }

        SettingsGroup(
            title = "RUNTIME & COMPATIBILITY",
            icon = Icons.Default.Memory
        ) {
            ChoiceField(
                label = "Box64 preset",
                selected = settings.box64Preset,
                options = listOf(
                    "STABILITY" to "Stability",
                    "CONSERVATIVE" to "Conservative",
                    "INTERMEDIATE" to "Intermediate",
                    "PERFORMANCE" to "Performance"
                ),
                onSelected = { onChange(settings.copy(box64Preset = it)) }
            )
            SettingSwitch(
                title = "Wine ESYNC",
                subtitle = "Event synchronization for supported games.",
                checked = settings.wineEsync,
                onCheckedChange = { onChange(settings.copy(wineEsync = it)) }
            )
        }

        SettingsGroup(
            title = "INPUT & CONTROLS",
            icon = Icons.Default.SettingsInputComponent
        ) {
            SettingSwitch(
                title = "On-screen controls",
                subtitle = "NHH forces the real Virtual Gamepad profile into the game session.",
                checked = settings.onScreenControls,
                onCheckedChange = { onChange(settings.copy(onScreenControls = it)) }
            )
            Text(
                "The runtime loads the controller profile before Wine starts; no separate Winlator screen is required.",
                color = MutedText,
                style = MaterialTheme.typography.bodySmall
            )
        }

        SettingsGroup(
            title = "WINDOWS COMPONENTS",
            icon = Icons.Default.Build
        ) {
            SettingInfo("Direct3D / DXVK", "Configured by the NHH container")
            SettingInfo("Audio / XAudio", "Configured by the NHH container")
            SettingInfo("VC runtime", "Configured by the NHH container")
            SettingInfo("Wine runtime", "Installed from the bundled Winlator rootfs")
        }

        SettingsGroup(
            title = "ENVIRONMENT & DIAGNOSTICS",
            icon = Icons.Default.Code
        ) {
            SettingInfo("Game drive", "G: → imported NHH game folder")
            SettingInfo("Startup", "Direct executable launch")
            SettingInfo("Runtime UI", "NHH shell; upstream drawer hidden")
            OutlinedButton(
                onClick = onShareReport,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("SHARE LAUNCH DIAGNOSTICS")
            }
            OutlinedButton(
                onClick = onReimport,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("RE-IMPORT GAME ZIP")
            }
        }

        if (!feedback.isNullOrBlank()) {
            Text(feedback, color = Accent2, style = MaterialTheme.typography.bodyMedium)
        }
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            Text(
                if (isSaving) "SAVING…" else "SAVE ALL CHANGES",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }

        Text(
            "Runtime changes are applied on the next game launch.",
            modifier = Modifier.padding(bottom = 18.dp),
            color = MutedText,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor2)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("NHH Runtime Configuration", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Grouped settings keep display, graphics, runtime, input and diagnostics separate instead of exposing a giant Winlator page.",
                color = MutedText,
                style = MaterialTheme.typography.bodySmall
            )
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Accent)
                Spacer(Modifier.padding(horizontal = 5.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun SettingInfo(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(value, color = MutedText, style = MaterialTheme.typography.bodySmall)
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
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        androidx.compose.material3.OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(selectedLabel, Modifier.weight(1f))
                Text("⌄", color = Accent, style = MaterialTheme.typography.titleLarge)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.second) },
                    onClick = {
                        expanded = false
                        onSelected(option.first)
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = MutedText, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

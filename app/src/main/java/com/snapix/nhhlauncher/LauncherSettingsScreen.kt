package com.snapix.nhhlauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

private val SettingsCardColor = Color(0xFF211D2B)
private val MutedText = Color(0xFFBEB8CA)
private val Violet = Color(0xFFC99BFF)
private val Cyan = Color(0xFF55CFF0)

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
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("BACK") }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Text("Runtime configuration", style = MaterialTheme.typography.bodyMedium, color = MutedText)
            }
        }

        SettingsSectionTitle("DISPLAY", Cyan)
        SettingsCard {
            ChoiceField(
                label = "Resolution",
                selected = settings.resolution,
                options = listOf(
                    "960x540" to "960 × 540",
                    "1280x720" to "1280 × 720",
                    "1600x900" to "1600 × 900",
                    "1920x1080" to "1920 × 1080"
                ),
                onSelected = { onChange(settings.copy(resolution = it)) }
            )
            Text(
                "Applied to the Winlator XServer on the next game launch.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText
            )
        }

        SettingsSectionTitle("GRAPHICS", Violet)
        SettingsCard {
            ChoiceField(
                label = "Vulkan driver",
                selected = settings.vulkanDriver,
                options = listOf(
                    "vortek" to "Vortek",
                    "turnip" to "Turnip · Adreno"
                ),
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
                options = listOf(
                    "dxvk" to "DXVK",
                    "wined3d" to "WineD3D"
                ),
                onSelected = { onChange(settings.copy(dxWrapper = it)) }
            )
            Text(
                "Turnip is intended for Qualcomm Adreno GPUs. Vortek is the safer starting point on other GPU families.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedText
            )
        }

        SettingsSectionTitle("PERFORMANCE", Cyan)
        SettingsCard {
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
                subtitle = "Enable event synchronization for supported games.",
                checked = settings.wineEsync,
                onCheckedChange = { onChange(settings.copy(wineEsync = it)) }
            )
        }

        SettingsSectionTitle("AUDIO", Violet)
        SettingsCard {
            ChoiceField(
                label = "Audio backend",
                selected = settings.audioDriver,
                options = listOf(
                    "alsa" to "ALSA",
                    "pulseaudio" to "PulseAudio"
                ),
                onSelected = { onChange(settings.copy(audioDriver = it)) }
            )
        }

        SettingsSectionTitle("CONTROLS", Cyan)
        SettingsCard {
            SettingSwitch(
                title = "On-screen gamepad",
                subtitle = "Use Winlator's Virtual Gamepad layout. External controllers and keyboards remain available.",
                checked = settings.onScreenControls,
                onCheckedChange = { onChange(settings.copy(onScreenControls = it)) }
            )
        }

        if (!feedback.isNullOrBlank()) {
            Text(feedback, color = Cyan, style = MaterialTheme.typography.bodyMedium)
        }
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Violet,
                contentColor = Color(0xFF241333)
            )
        ) {
            Text(if (isSaving) "SAVING…" else "SAVE SETTINGS")
        }

        OutlinedButton(
            onClick = onShareReport,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text("SHARE LAST LAUNCH REPORT")
        }

        OutlinedButton(
            onClick = onReimport,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text("RE-IMPORT GAME ZIP")
        }

        Text(
            "Runtime changes are saved to the NHH container and apply on the next launch.",
            modifier = Modifier.padding(bottom = 18.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MutedText
        )
    }
}

@Composable
private fun SettingsSectionTitle(title: String, accent: Color) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = accent,
        modifier = Modifier.padding(start = 8.dp, top = 3.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = SettingsCardColor)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
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

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(selectedLabel, modifier = Modifier.weight(1f))
                    Text("⌄", color = Violet, style = MaterialTheme.typography.titleLarge)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
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
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

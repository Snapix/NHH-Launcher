package com.snapix.nhhlauncher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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
    onResetControls: () -> Unit,
    onOpenControllerTest: () -> Unit,
    onShareReport: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column {
                Text("Runtime Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("NHH container configuration", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SettingCard("Controller & Input", Icons.Default.SportsEsports) {
            SwitchRow("On-screen Xbox controls", "Use the NHH profile in game", settings.onScreenControls) {
                onChange(settings.copy(onScreenControls = it))
            }
            Text("Input API: " + settings.preferredInputApi)
            SliderRow("Overlay opacity", settings.overlayOpacity, 0.15f..1f) {
                onChange(settings.copy(overlayOpacity = it))
            }
            SliderRow("Control scale", settings.controlScale, 0.7f..1.35f) {
                onChange(settings.copy(controlScale = it))
            }
            SliderRow("Stick deadzone", settings.stickDeadzone, 0.02f..0.35f) {
                onChange(settings.copy(stickDeadzone = it))
            }
            Button(onClick = onOpenControllerTest, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Tune, null)
                Spacer(Modifier.width(8.dp))
                Text("OPEN CONTROLLER TEST")
            }
            OutlinedButton(onClick = onResetControls, Modifier.fillMaxWidth()) {
                Text("RESTORE DEFAULT CONTROL LAYOUT")
            }
        }

        SettingCard("Display & Graphics", Icons.Default.Build) {
            Text("Resolution: " + settings.resolution)
            Text("Vulkan: " + settings.vulkanDriver)
            Text("OpenGL: " + settings.openGlDriver)
            Text("DX wrapper: " + settings.dxWrapper)
            SwitchRow("Force fullscreen", "Start game fullscreen", settings.forceFullscreen) {
                onChange(settings.copy(forceFullscreen = it))
            }
            SwitchRow("FPS HUD", "Show Winlator frame-rate overlay", settings.showFpsHud) {
                onChange(settings.copy(showFpsHud = it))
            }
        }

        SettingCard("Audio & Performance", Icons.Default.VolumeUp) {
            Text("Audio driver: " + settings.audioDriver)
            Text("Box64 preset: " + settings.box64Preset)
            SwitchRow("Wine Esync", "Keep WINEESYNC enabled", settings.wineEsync) {
                onChange(settings.copy(wineEsync = it))
            }
        }

        SettingCard("Game Files", Icons.Default.Build) {
            Button(onClick = onReimport, Modifier.fillMaxWidth()) {
                Text("RE-IMPORT GAME ZIP")
            }
        }

        SettingCard("Diagnostics", Icons.Default.Share) {
            Button(onClick = onShareReport, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("SHARE DIAGNOSTIC REPORT")
            }
        }

        if (!feedback.isNullOrBlank()) {
            Text(feedback, color = MaterialTheme.colorScheme.secondary)
        }
        if (!error.isNullOrBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth().height(58.dp)
        ) {
            Text(if (isSaving) "SAVING…" else "APPLY & SAVE SETTINGS", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Text(label + ": " + (value * 100f).roundToInt() + "%")
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

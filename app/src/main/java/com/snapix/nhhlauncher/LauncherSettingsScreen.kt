package com.snapix.nhhlauncher

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val Accent = Color(0xFFD0BCFF)
private val Accent2 = Color(0xFF8ED8FF)
private val Card = Color(0xFF211D2B)
private val Muted = Color(0xFFBDB7C8)

private enum class SettingsCategory(val title: String, val subtitle: String) {
    DISPLAY("Display", "Resolution, HUD and startup"),
    GRAPHICS("Graphics", "Drivers, DX translation and wrapper tuning"),
    PERFORMANCE("Performance", "Box64, CPU affinity and Wine"),
    AUDIO("Audio", "ALSA / PulseAudio configuration"),
    WINE("Wine", "Windows version, desktop and input behaviour"),
    COMPONENTS("Windows components", "DirectX, audio and runtime components"),
    ENVIRONMENT("Environment", "Environment variables and mounted drives"),
    INPUT("Input", "Touch controls and controller mapping"),
    SYSTEM("System & diagnostics", "Background behaviour and logging")
}

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
    var category by remember { mutableStateOf<SettingsCategory?>(null) }

    AnimatedContent(
        targetState = category,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "settings-navigation"
    ) { selected ->
        if (selected == null) {
            SettingsHome(settings, onCategory = { category = it }, onBack, onSave, isSaving, feedback, error)
        } else {
            SettingsCategoryPage(selected, settings, onChange, onBack = { category = null }, onSave, isSaving, onReimport, onShareReport)
        }
    }
}

@Composable
private fun SettingsHome(
    settings: LauncherRuntimeSettings,
    onCategory: (SettingsCategory) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    isSaving: Boolean,
    feedback: String?,
    error: String?
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Settings", style = MaterialTheme.typography.headlineLarge)
                    Text("NHH runtime • Winlator configuration", color = Muted)
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(28.dp), color = Card, tonalElevation = 2.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("NHH Runtime", style = MaterialTheme.typography.titleLarge)
                    Text("Winlator configuration, grouped so you can change one area without hunting through a giant page.", color = Muted)
                }
            }
        }
        items(SettingsCategory.entries) { c -> CategoryCard(c, settings, onClick = { onCategory(c) }) }
        item {
            Button(onClick = onSave, enabled = !isSaving, Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(20.dp)) {
                Text(if (isSaving) "SAVING…" else "SAVE ALL CHANGES")
            }
        }
        item {
            if (!feedback.isNullOrBlank()) Text(feedback, color = Accent2)
            if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CategoryCard(category: SettingsCategory, settings: LauncherRuntimeSettings, onClick: () -> Unit) {
    val summary = when (category) {
        SettingsCategory.DISPLAY -> settings.resolution
        SettingsCategory.GRAPHICS -> "${settings.vulkanDriver} • ${settings.dxWrapper}"
        SettingsCategory.PERFORMANCE -> settings.box64Preset
        SettingsCategory.AUDIO -> settings.audioDriver
        SettingsCategory.WINE -> "${settings.windowsVersion} • ${settings.desktopTheme}"
        SettingsCategory.COMPONENTS -> "${settings.winComponents.count { it.value }} enabled"
        SettingsCategory.ENVIRONMENT -> "${settings.envVars.split(" ").count { it.isNotBlank() }} variables"
        SettingsCategory.INPUT -> if (settings.onScreenControls) "On-screen controls" else "External input only"
        SettingsCategory.SYSTEM -> if (settings.wineDebug) "Debug logging enabled" else "Normal"
    }
    val icon = when (category) {
        SettingsCategory.DISPLAY -> Icons.Default.DisplaySettings
        SettingsCategory.GRAPHICS -> Icons.Default.Tune
        SettingsCategory.PERFORMANCE -> Icons.Default.Speed
        SettingsCategory.AUDIO -> Icons.Default.VolumeUp
        SettingsCategory.WINE -> Icons.Default.DesktopWindows
        SettingsCategory.COMPONENTS -> Icons.Default.Extension
        SettingsCategory.ENVIRONMENT -> Icons.Default.Code
        SettingsCategory.INPUT -> Icons.Default.SportsEsports
        SettingsCategory.SYSTEM -> Icons.Default.Build
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Card,
        tonalElevation = 1.dp
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(18.dp), color = Accent.copy(alpha = .14f)) {
                Icon(icon, null, tint = Accent, modifier = Modifier.padding(14.dp).size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.titleMedium)
                Text(category.subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
                Spacer(Modifier.height(4.dp))
                Text(summary, color = Accent2, style = MaterialTheme.typography.labelLarge)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
private fun SettingsCategoryPage(
    category: SettingsCategory,
    settings: LauncherRuntimeSettings,
    onChange: (LauncherRuntimeSettings) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    isSaving: Boolean,
    onReimport: () -> Unit,
    onShareReport: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.headlineSmall)
                Text(category.subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = onSave, enabled = !isSaving, shape = RoundedCornerShape(16.dp)) {
                Text(if (isSaving) "…" else "Save")
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (category) {
                SettingsCategory.DISPLAY -> item { DisplaySettings(settings, onChange) }
                SettingsCategory.GRAPHICS -> item { GraphicsSettings(settings, onChange) }
                SettingsCategory.PERFORMANCE -> item { PerformanceSettings(settings, onChange) }
                SettingsCategory.AUDIO -> item { AudioSettings(settings, onChange) }
                SettingsCategory.WINE -> item { WineSettings(settings, onChange) }
                SettingsCategory.COMPONENTS -> item { ComponentSettings(settings, onChange) }
                SettingsCategory.ENVIRONMENT -> item { EnvironmentSettings(settings, onChange) }
                SettingsCategory.INPUT -> item { InputSettings(settings, onChange) }
                SettingsCategory.SYSTEM -> item { SystemSettings(settings, onChange, onReimport, onShareReport) }
            }
        }
    }
}

@Composable private fun DisplaySettings(s: LauncherRuntimeSettings, c: (LauncherRuntimeSettings)->Unit) {
    SettingsGroup("Screen") {
        Choice("Resolution", s.resolution, listOf("960x540" to "960 × 540","1280x720" to "1280 × 720","1600x900" to "1600 × 900","1920x1080" to "1920 × 1080","custom" to "Custom")) { c(s.copy(resolution=it)) }
        if (s.resolution == "custom") Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            NumberField("Width", s.customWidth.toString()) { c(s.copy(customWidth=it.toIntOrNull() ?: s.customWidth)) }
            NumberField("Height", s.customHeight.toString()) { c(s.copy(customHeight=it.toIntOrNull() ?: s.customHeight)) }
        }
        Choice("HUD / FPS overlay", s.hudMode.toString(), listOf("0" to "Disabled","1" to "Simple FPS","2" to "Detailed HUD")) { c(s.copy(hudMode=it.toInt())) }
        Choice("Windows startup", s.startupSelection.toString(), listOf("0" to "Normal","1" to "Essential services","2" to "Aggressive")) { c(s.copy(startupSelection=it.toInt())) }
    }
}

@Composable private fun GraphicsSettings(s: LauncherRuntimeSettings, c: (LauncherRuntimeSettings)->Unit) {
    SettingsGroup("Graphics") {
        Choice("Vulkan driver", s.vulkanDriver, listOf("vortek" to "Vortek","turnip" to "Turnip")) { c(s.copy(vulkanDriver=it)) }
        Choice("OpenGL driver", s.openGlDriver, listOf("gladio" to "Gladio","zink" to "Zink","virgl" to "VirGL")) { c(s.copy(openGlDriver=it)) }
        Choice("DirectX wrapper", s.dxWrapper, listOf("dxvk" to "DXVK","wined3d" to "WineD3D")) { c(s.copy(dxWrapper=it)) }
        TextFieldSetting("Graphics driver config", s.graphicsDriverConfig) { c(s.copy(graphicsDriverConfig=it)) }
        TextFieldSetting("DX wrapper config", s.dxWrapperConfig) { c(s.copy(dxWrapperConfig=it)) }
    }
}

@Composable private fun PerformanceSettings(s: LauncherRuntimeSettings, c: (LauncherRuntimeSettings)->Unit) {
    SettingsGroup("CPU & runtime") {
        Choice("Box64 preset", s.box64Preset, listOf("STABILITY" to "Stability","CONSERVATIVE" to "Conservative","INTERMEDIATE" to "Intermediate","PERFORMANCE" to "Performance")) { c(s.copy(box64Preset=it)) }
        TextFieldSetting("CPU affinity", s.cpuList, "Comma-separated CPU indexes") { c(s.copy(cpuList=it)) }
        TextFieldSetting("WoW64 CPU affinity", s.cpuListWoW64, "Comma-separated CPU indexes") { c(s.copy(cpuListWoW64=it)) }
        Choice("Wine version", s.wineVersion, listOf("main" to "Main Wine")) { c(s.copy(wineVersion=it)) }
        SwitchSetting("Wine ESYNC", "Event synchronization", s.wineEsync) { c(s.copy(wineEsync=it)) }
    }
}

@Composable private fun AudioSettings(s: LauncherRuntimeSettings, c: (LauncherRuntimeSettings)->Unit) {
    SettingsGroup("Audio") {
        Choice("Audio driver", s.audioDriver, listOf("alsa" to "ALSA","pulseaudio" to "PulseAudio")) { c(s.copy(audioDriver=it)) }
        TextFieldSetting("Audio driver configuration", s.audioDriverConfig) { c(s.copy(audioDriverConfig=it)) }
    }
}

@Composable private fun WineSettings(s: LauncherRuntimeSettings, c: (LauncherRuntimeSettings)->Unit) {
    SettingsGroup("Wine configuration") {
        Choice("Windows version", s.windowsVersion, listOf("win11" to "Windows 11","win10" to "Windows 10","win81" to "Windows 8.1","win7" to "Windows 7","winxp" to "Windows XP")) { c(s.copy(windowsVersion=it)) }
        Choice("Desktop theme", s.desktopTheme, listOf("LIGHT" to "Light","DARK" to "Dark")) { c(s.copy(desktopTheme=it)) }
        Choice("Desktop background", s.desktopBackgroundType, listOf("IMAGE" to "Winlator wallpaper","COLOR" to "Solid color")) { c(s.copy(desktopBackgroundType=it)) }
        TextFieldSetting("Background color", s.desktopBackgroundColor) { c(s.copy(desktopBackgroundColor=it)) }
        TextFieldSetting("System font", s.systemFont) { c(s.copy(systemFont=it)) }
        NumberField("DPI", s.dpi.toString()) { c(s.copy(dpi=it.toIntOrNull() ?: s.dpi)) }
        Choice("Mouse warp", s.mouseWarpOverride, listOf("disable" to "Disable","enable" to "Enable","force" to "Force")) { c(s.copy(mouseWarpOverride=it)) }
        SwitchSetting("Open Android browser", "Open links through Android browser", s.openAndroidBrowser) { c(s.copy(openAndroidBrowser=it)) }
        SwitchSetting("Android clipboard", "Expose Android clipboard to Wine", s.androidClipboard) { c(s.copy(androidClipboard=it)) }
    }
}

@Composable private fun ComponentSettings(s: LauncherRuntimeSettings, c: (LauncherRuntimeSettings)->Unit) {
    SettingsGroup("Windows components") {
        s.winComponents.forEach { (key, value) ->
            SwitchSetting(key, "Use the native Winlator component where available", value) {
                c(s.copy(winComponents=s.winComponents + (key to it)))
            }
        }
    }
}

@Composable private fun EnvironmentSettings(s: LauncherRuntimeSettings, c: (LauncherRuntimeSettings)->Unit) {
    SettingsGroup("Environment & drives") {
        Text("These use Winlator's native container formats.", color=Muted, style=MaterialTheme.typography.bodySmall)
        TextFieldSetting("Environment variables", s.envVars, "KEY=value separated by spaces") { c(s.copy(envVars=it)) }
        TextFieldSetting("Mounted drives", s.drives, "Example: G:/path") { c(s.copy(drives=it)) }
    }
}

@Composable private fun InputSettings(s: LauncherRuntimeSettings, c: (LauncherRuntimeSettings)->Unit) {
    SettingsGroup("Controls") {
        SwitchSetting("On-screen controls", "Show the Winlator Virtual Gamepad", s.onScreenControls) { c(s.copy(onScreenControls=it)) }
        NumberField("Control profile ID", s.controlsProfileId.toString()) { c(s.copy(controlsProfileId=it.toIntOrNull() ?: s.controlsProfileId)) }
        Choice("Input mapper", s.dinputMapperType.toString(), listOf("0" to "DirectInput","1" to "XInput")) { c(s.copy(dinputMapperType=it.toInt())) }
        SwitchSetting("Force fullscreen", "Start the game in fullscreen", s.forceFullscreen) { c(s.copy(forceFullscreen=it)) }
    }
}

@Composable private fun SystemSettings(s: LauncherRuntimeSettings, c: (LauncherRuntimeSettings)->Unit, onReimport: ()->Unit, onShareReport: ()->Unit) {
    SettingsGroup("Runtime behaviour") {
        SwitchSetting("Background protection", "Keep the runtime protected while Android backgrounds it", s.backgroundProtection) { c(s.copy(backgroundProtection=it)) }
        SwitchSetting("Background wakelock", "Keep the device awake while runtime is active", s.backgroundWakelock) { c(s.copy(backgroundWakelock=it)) }
        SwitchSetting("Save memory on Steam launch", "Winlator global memory optimization", s.saveMemOnSteam) { c(s.copy(saveMemOnSteam=it)) }
        SwitchSetting("Wine debug", "Enable Wine debug logging", s.wineDebug) { c(s.copy(wineDebug=it)) }
        Choice("Box64 logs", s.box64Logs.toString(), listOf("0" to "Disabled","1" to "Basic","2" to "Detailed")) { c(s.copy(box64Logs=it.toInt())) }
        SwitchSetting("Save logs to file", "Persist runtime logs", s.saveLogsToFile) { c(s.copy(saveLogsToFile=it)) }
        TextFieldSetting("Log file", s.logFile) { c(s.copy(logFile=it)) }
        HorizontalDivider()
        OutlinedButton(onClick=onShareReport, modifier=Modifier.fillMaxWidth()) { Text("SHARE LAST LAUNCH REPORT") }
        OutlinedButton(onClick=onReimport, modifier=Modifier.fillMaxWidth()) { Text("RE-IMPORT GAME ZIP") }
    }
}

@Composable private fun SettingsGroup(title:String, content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(26.dp), color=Card, tonalElevation=2.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(title, style=MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable private fun Choice(label:String, value:String, options:List<Pair<String,String>>, onSelected:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    val text = options.firstOrNull { it.first == value }?.second ?: value
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label, style=MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick={expanded=true}, Modifier.fillMaxWidth().height(52.dp), shape=RoundedCornerShape(17.dp)) {
                Text(text, Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded=expanded, onDismissRequest={expanded=false}) {
                options.forEach { (k,v) ->
                    DropdownMenuItem(text={Text(v)}, onClick={ expanded=false; onSelected(k) })
                }
            }
        }
    }
}

@Composable private fun SwitchSetting(title:String, subtitle:String, checked:Boolean, onChecked:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style=MaterialTheme.typography.titleMedium); Text(subtitle, color=Muted, style=MaterialTheme.typography.bodySmall) }
        Switch(checked=checked, onCheckedChange=onChecked)
    }
}

@Composable private fun TextFieldSetting(label:String, value:String, supporting:String="", onChange:(String)->Unit) {
    OutlinedTextField(value=value, onValueChange=onChange, label={Text(label)}, supportingText={if(supporting.isNotBlank()) Text(supporting)}, modifier=Modifier.fillMaxWidth(), minLines=1, maxLines=5, shape=RoundedCornerShape(17.dp))
}

@Composable private fun NumberField(label:String, value:String, onChange:(String)->Unit) {
    OutlinedTextField(value=value, onValueChange=onChange, label={Text(label)}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), modifier=Modifier.weight(1f), shape=RoundedCornerShape(17.dp), singleLine=true)
}

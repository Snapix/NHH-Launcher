package com.snapix.nhhlauncher

import com.winlator.box64.Box64Preset
import com.winlator.container.Container
import com.winlator.container.GraphicsDrivers

data class LauncherRuntimeSettings(
    val resolution: String = Container.DEFAULT_SCREEN_SIZE,
    val vulkanDriver: String = GraphicsDrivers.DEFAULT_VULKAN_DRIVER,
    val openGlDriver: String = GraphicsDrivers.DEFAULT_OPENGL_DRIVER,
    val dxWrapper: String = Container.DEFAULT_DXWRAPPER,
    val audioDriver: String = Container.DEFAULT_AUDIO_DRIVER,
    val box64Preset: String = Box64Preset.DEFAULT,
    val wineEsync: Boolean = true,
    val showFpsHud: Boolean = false,
    val forceFullscreen: Boolean = true,
    val onScreenControls: Boolean = true,
    val preferredInputApi: String = "XINPUT",
    val overlayOpacity: Float = 0.55f,
    val controlScale: Float = 1f,
    val stickDeadzone: Float = 0.12f
)

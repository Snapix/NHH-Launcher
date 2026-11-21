package com.snapix.nhhlauncher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ControllerTestScreen(
    onBack: () -> Unit,
    onResetLayout: () -> Unit
) {
    var snapshot by remember { mutableStateOf(NhhControllerRuntime.snapshot) }

    DisposableEffect(Unit) {
        val listener: (NhhControllerRuntime.DiagnosticsSnapshot) -> Unit = { snapshot = it }
        NhhControllerRuntime.addDiagnosticsListener(listener)
        onDispose { NhhControllerRuntime.removeDiagnosticsListener(listener) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Column {
                Text("Controller Test", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Native Winlator input pipeline", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("LIVE PIPELINE", fontWeight = FontWeight.Bold)
                Text("State: " + snapshot.state)
                Text("Profile: #" + snapshot.profileId)
                Text("Elements: " + snapshot.elementCount)
                Text("Virtual gamepad: " + snapshot.isVirtualGamepad)
                Text("Touch controls: " + snapshot.touchscreenControlsEnabled)
                Text("WinHandler: " + snapshot.winHandlerReady)
                Text("Dispatches: " + snapshot.dispatchCount)
                Text("Last event: " + snapshot.lastDispatchEvent)
                Text("Viewport: " + snapshot.viewportWidth + " x " + snapshot.viewportHeight)
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VERIFY", fontWeight = FontWeight.Bold)
                Text("Launch a game and press every on-screen control.")
                Text("Hold the left stick while pressing a face button.")
                Text("Use two or more fingers at the same time.")
                Text("Dispatches are reported from Winlator's InputControlsView; this screen does not fake input.")
            }
        }

        Button(onClick = onResetLayout, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("RESTORE NHH XBOX LAYOUT")
        }
    }
}

package com.snapix.nhhlauncher

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class ControllerTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = NhhColors) {
                var snapshot by remember { mutableStateOf(NhhControllerRuntime.snapshot) }
                DisposableEffect(Unit) {
                    val listener: (NhhControllerRuntime.DiagnosticsSnapshot) -> Unit = { snapshot = it }
                    NhhControllerRuntime.addDiagnosticsListener(listener)
                    onDispose { NhhControllerRuntime.removeDiagnosticsListener(listener) }
                }
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Text("Controller Test", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("LIVE INPUT PIPELINE", fontWeight = FontWeight.Bold)
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
                    Text("This reads events from Winlator's native InputControlsView; it does not simulate controller input.")
                    Button(onClick = { NhhControllerRuntime.resetNhhProfile(this@ControllerTestActivity) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("RESTORE BOTH LAYOUTS")
                    }
                }
            }
        }
    }
}

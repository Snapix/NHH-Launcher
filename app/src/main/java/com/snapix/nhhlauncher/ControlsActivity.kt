package com.snapix.nhhlauncher

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class ControlsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = NhhColors) {
                val prefs = getSharedPreferences("nhh", Context.MODE_PRIVATE)
                var selected by remember { mutableIntStateOf(NhhControllerRuntime.selectedProfileId(prefs)) }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Text("Controls", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Text("Only the two supplied NHH layouts are installed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProfileCard(
                        "NHH MAIN",
                        "D-pad + Xbox controls + ESC / ENTER",
                        selected == NhhControllerRuntime.MAIN_PROFILE_ID,
                        {
                            NhhControllerRuntime.setSelectedProfile(prefs, NhhControllerRuntime.MAIN_PROFILE_ID)
                            selected = NhhControllerRuntime.MAIN_PROFILE_ID
                        },
                        { WinlatorRuntime.openControlsEditor(this@ControlsActivity, NhhControllerRuntime.MAIN_PROFILE_ID) }
                    )
                    ProfileCard(
                        "NHH MAIN no key",
                        "Xbox controls without keyboard keys",
                        selected == NhhControllerRuntime.NO_KEY_PROFILE_ID,
                        {
                            NhhControllerRuntime.setSelectedProfile(prefs, NhhControllerRuntime.NO_KEY_PROFILE_ID)
                            selected = NhhControllerRuntime.NO_KEY_PROFILE_ID
                        },
                        { WinlatorRuntime.openControlsEditor(this@ControlsActivity, NhhControllerRuntime.NO_KEY_PROFILE_ID) }
                    )
                    Button(onClick = { startActivity(android.content.Intent(this@ControlsActivity, ControllerTestActivity::class.java)) }, Modifier.fillMaxWidth()) {
                        Text("TEST CONTROLLER")
                    }
                    OutlinedButton(onClick = { NhhControllerRuntime.resetNhhProfile(this@ControlsActivity); selected = NhhControllerRuntime.MAIN_PROFILE_ID }, Modifier.fillMaxWidth()) {
                        Text("RESTORE BOTH LAYOUTS")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(title: String, subtitle: String, active: Boolean, use: () -> Unit, edit: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = use, Modifier.weight(1f)) { Text(if (active) "ACTIVE" else "USE") }
                OutlinedButton(onClick = edit, Modifier.weight(1f)) { Text("EDIT") }
            }
        }
    }
}

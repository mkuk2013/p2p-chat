package com.p2pchat.messenger.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.AppContainer
import com.p2pchat.messenger.service.MeshForegroundService
import kotlinx.coroutines.launch

/**
 * Relay, battery and security settings.
 *
 * Duty-cycle choices are written to the same SharedPreferences
 * ("mesh_settings", keys scan_on_ms / scan_off_ms) the foreground
 * service reads every cycle, so changes apply live.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(MeshForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val identity by container.identity.collectAsState()

    var relayOn by remember {
        mutableStateOf(prefs.getBoolean(MeshForegroundService.KEY_RELAY_ENABLED, false))
    }
    var dutySelection by remember {
        val onMs = (prefs.all[MeshForegroundService.KEY_SCAN_ON_MS] as? Number)?.toLong()
            ?: MeshForegroundService.DEFAULT_SCAN_ON_MS
        val offMs = (prefs.all[MeshForegroundService.KEY_SCAN_OFF_MS] as? Number)?.toLong()
            ?: MeshForegroundService.DEFAULT_SCAN_OFF_MS
        mutableStateOf(onMs to offMs)
    }
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearedNote by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------------- relay
        Text(
            text = "Relay",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Background relay mode", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Runs the mesh foreground service so this phone keeps " +
                        "relaying with the screen locked. A persistent notification " +
                        "is shown while it is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = relayOn,
                onCheckedChange = { enabled ->
                    relayOn = enabled
                    prefs.edit()
                        .putBoolean(MeshForegroundService.KEY_RELAY_ENABLED, enabled)
                        .apply()
                    if (enabled) {
                        MeshForegroundService.start(context)
                    } else {
                        MeshForegroundService.stop(context)
                    }
                }
            )
        }
        Text(
            text = "Platform limits: after a force-stop, a reboot on some phones, " +
                "or aggressive OEM battery killing, relaying pauses until the app " +
                "is opened again. No app can honestly promise otherwise.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // ---------------------------------------------------------- battery
        Text(
            text = "Battery — scan duty cycle",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "How long the radio scans for peers, then rests. Shorter " +
                "rest finds peers faster; longer rest saves battery.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        DUTY_OPTIONS.forEach { option ->
            val selected = dutySelection == (option.onMs to option.offMs)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected) {
                        dutySelection = option.onMs to option.offMs
                        prefs.edit()
                            .putLong(MeshForegroundService.KEY_SCAN_ON_MS, option.onMs)
                            .putLong(MeshForegroundService.KEY_SCAN_OFF_MS, option.offMs)
                            .apply()
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    onClick = {
                        dutySelection = option.onMs to option.offMs
                        prefs.edit()
                            .putLong(MeshForegroundService.KEY_SCAN_ON_MS, option.onMs)
                            .putLong(MeshForegroundService.KEY_SCAN_OFF_MS, option.offMs)
                            .apply()
                    }
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(option.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        option.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ---------------------------------------------------------- security
        Text(
            text = "Privacy & security",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        val currentIdentity = identity
        if (currentIdentity != null) {
            Text(
                text = "Your identity fingerprint (Ed25519)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = container.fingerprintHex(currentIdentity),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = "Messages are end-to-end encrypted (X25519 key agreement, " +
                "HKDF-SHA256, AES-256-GCM) and signed (Ed25519). Relay phones " +
                "forward sealed envelopes — they cannot read message content. " +
                "Private keys are stored encrypted with a key held in the " +
                "Android Keystore and never leave this phone.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Recovery limitation: if you lose this phone, your identity " +
                "and history cannot be recovered — there is no server backup " +
                "by design. A new phone means a new identity and a new Peer ID.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(Modifier.height(24.dp))

        // ---------------------------------------------------------- data
        Text(
            text = "Data",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showClearConfirm = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear all data")
        }
        if (clearedNote != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = clearedNote!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all data?") },
            text = {
                Text(
                    "This permanently deletes every conversation, message, " +
                        "queued envelope, known peer and your identity keys " +
                        "from this phone. It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        scope.launch {
                            container.clearAllData()
                            clearedNote = "All local data cleared. Restart the app " +
                                "so the relay stops using the old identity."
                        }
                    }
                ) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

private data class DutyOption(val title: String, val subtitle: String, val onMs: Long, val offMs: Long)

private val DUTY_OPTIONS = listOf(
    DutyOption("Responsive", "Scan 10 s · rest 10 s — finds peers fast, uses more battery", 10_000L, 10_000L),
    DutyOption("Balanced", "Scan 10 s · rest 25 s — the default", 10_000L, 25_000L),
    DutyOption("Battery saver", "Scan 10 s · rest 60 s — slowest discovery, gentlest on battery", 10_000L, 60_000L)
)

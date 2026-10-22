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
    onOpenMesh: () -> Unit,
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

        // ------------------------------------------------------- connection
        Text(
            text = "Connection",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "See who you are connected to and measure how fast " +
                "the connection is.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOpenMesh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Check connection speed")
        }

        Spacer(Modifier.height(24.dp))

        // ---------------------------------------------------------- relay
        Text(
            text = "Helping others",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Pass messages in the background", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Keeps passing other people's messages along " +
                        "with the screen locked. Android shows a small " +
                        "notice while this is on.",
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
            text = "Honest limit: if the app is force-stopped, the phone " +
                "restarts, or the battery saver kills it, passing messages " +
                "pauses until you open the app again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // ---------------------------------------------------------- battery
        Text(
            text = "Battery",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "How often this phone looks for people nearby. Looking " +
                "more often finds people faster but uses more battery.",
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
                text = "Your safety code",
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
            text = "Your messages are locked so only you and the person " +
                "you write to can read them — phones that pass a message " +
                "along cannot open it. Your secret keys stay on this " +
                "phone, protected by Android, and never leave it.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "If you lose this phone, your chats cannot be " +
                "recovered — there is no server copy, by design. A new " +
                "phone starts fresh, and people will see a new you.",
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
                    "This permanently deletes every chat, message, " +
                        "waiting message, person you know, call history " +
                        "and your secret keys from this phone. It cannot " +
                        "be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        scope.launch {
                            container.clearAllData()
                            clearedNote = "Everything on this phone was " +
                                "cleared. Restart the app to finish."
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
    DutyOption("Responsive", "Look 10 s · rest 10 s — finds people fast, uses more battery", 10_000L, 10_000L),
    DutyOption("Balanced", "Look 10 s · rest 25 s — the default", 10_000L, 25_000L),
    DutyOption("Battery saver", "Look 10 s · rest 60 s — slowest to find people, gentlest on battery", 10_000L, 60_000L)
)

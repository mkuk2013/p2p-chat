package com.p2pchat.messenger.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.R
import com.p2pchat.messenger.ble.BlePermissions

/**
 * First-launch setup in three short steps, in plain words:
 *
 *  1. Welcome — what P2P Chat is, in two lines.
 *  2. What P2P Chat needs — Bluetooth on + the nearby-phones
 *     permission, each explained in one line. Bluetooth is NEVER
 *     switched on silently: the button fires Android's official
 *     [BluetoothAdapter.ACTION_REQUEST_ENABLE] prompt. Microphone and
 *     camera are NOT asked here — only at first use.
 *  3. Your name — the name people nearby will see.
 *
 * State is re-read whenever [refreshTick] moves (launcher results,
 * manual re-checks), so every check shows the real current state —
 * nothing is hard-coded "OK".
 */
@Composable
fun OnboardingScreen(
    hasIdentity: Boolean,
    onCreateIdentity: (String) -> Unit,
    onReady: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var step by remember { mutableIntStateOf(0) }
    var deniedOnce by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }

    // ---- real, current device state (recomputed when refreshTick moves)
    val bleSupported = remember(refreshTick) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }
    val bluetoothManager = remember {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    val btEnabled = remember(refreshTick) { bluetoothManager?.adapter?.isEnabled == true }
    val missingPermissions = remember(refreshTick) { BlePermissions.missingPermissions(context) }
    val notificationsApplicable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val notificationsMissing = remember(refreshTick) {
        notificationsApplicable &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { !it }) deniedOnce = true
        refreshTick++
    }
    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshTick++ }

    val permissionsReady = missingPermissions.isEmpty()
    val needsReady = bleSupported && btEnabled && permissionsReady
    val allReady = needsReady && hasIdentity

    fun requestPermissions() {
        val toRequest = BlePermissions.requiredPermissions().toMutableList()
        if (notificationsApplicable) {
            // Needed to show the background-help notice on API 33+.
            toRequest += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(toRequest.toTypedArray())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo_splash),
            contentDescription = "P2P Chat logo",
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(10.dp))

        when (step) {
            // -------------------------------------------------- 1. welcome
            0 -> {
                Text(
                    text = "Welcome to P2P Chat",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Chat with people near you — no internet, " +
                        "no SIM, no account.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Your messages travel phone to phone, locked " +
                        "so only the person you write to can read them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(28.dp))
                if (!bleSupported) {
                    Text(
                        text = "This phone does not support the Bluetooth " +
                            "P2P Chat needs, so the app cannot run on it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Button(
                        onClick = { step = 1 },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Next")
                    }
                }
            }

            // --------------------------------------------- 2. what it needs
            1 -> {
                Text(
                    text = "What P2P Chat needs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                CheckRow(
                    ok = btEnabled,
                    label = if (btEnabled) {
                        "Bluetooth is on"
                    } else {
                        "Bluetooth is off — it is how your phone reaches " +
                            "people nearby"
                    }
                )
                CheckRow(
                    ok = permissionsReady,
                    label = if (permissionsReady) {
                        "Allowed to find and connect to nearby phones"
                    } else {
                        "Permission to find and connect to nearby phones"
                    }
                )
                if (notificationsApplicable) {
                    CheckRow(
                        ok = !notificationsMissing,
                        label = "Permission to show a small notice while " +
                            "the app helps in the background"
                    )
                }
                Spacer(Modifier.height(14.dp))

                if (!btEnabled) {
                    Button(
                        onClick = {
                            enableBtLauncher.launch(
                                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Turn on Bluetooth")
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (!permissionsReady || notificationsMissing) {
                    Button(
                        onClick = { requestPermissions() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (deniedOnce) "Try again" else "Allow permissions")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            "Why: Android asks you first before any app " +
                                "can look for nearby phones."
                        } else {
                            "Why: on this Android version, finding nearby " +
                                "phones needs the location permission — " +
                                "P2P Chat never reads your location."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (deniedOnce) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Without this, the app can't find " +
                                "anyone nearby. You can also allow it " +
                                "later in your phone's Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Text(
                    text = "The microphone is only asked when you first " +
                        "record or call — never now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { step = 0 },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Back")
                    }
                    Spacer(Modifier.size(12.dp))
                    Button(
                        onClick = { step = 2 },
                        enabled = needsReady,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Next")
                    }
                }
                if (!needsReady) {
                    Spacer(Modifier.height(8.dp))
                    // For the case where the user changed a system
                    // setting outside the app's own prompts.
                    androidx.compose.material3.TextButton(
                        onClick = { refreshTick++ }
                    ) {
                        Text("Check again")
                    }
                }
            }

            // ------------------------------------------------- 3. your name
            else -> {
                Text(
                    text = "Your name",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This is the name people nearby will see. You " +
                        "can change it later in your profile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                if (!hasIdentity) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Your name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onCreateIdentity(displayName.trim()) },
                        enabled = displayName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create and start")
                    }
                } else {
                    Text(
                        text = "You are all set.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onReady,
                        enabled = allReady,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start chatting")
                    }
                    if (!allReady) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "One thing on the last step still " +
                                "needs doing — go back and finish it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { step = 1 },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Back")
                }
            }
        }
    }
}

@Composable
private fun CheckRow(ok: Boolean, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = if (ok) "✓" else "✗",
            color = if (ok) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.error
            },
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

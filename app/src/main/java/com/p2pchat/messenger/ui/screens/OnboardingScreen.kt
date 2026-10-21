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
 * First-launch setup: BLE support, Bluetooth state, runtime permissions
 * and (on first run) identity creation.
 *
 * Rules honoured here:
 *  - Bluetooth is NEVER enabled silently — the "Enable Bluetooth" button
 *    fires Android's official [BluetoothAdapter.ACTION_REQUEST_ENABLE]
 *    prompt and the user decides.
 *  - Permissions are requested only via the system dialog, after the
 *    in-app explanation below. Denial shows what will not work and offers
 *    a retry instead of a dead end.
 *  - On Android 12+ the app asks for BLUETOOTH_SCAN / CONNECT / ADVERTISE.
 *    On older versions BLE scan results are gated behind location by the
 *    platform — the app asks for it only there, and never reads GPS.
 *
 * State is re-read on every recomposition triggered by [refreshTick]
 * (launcher results, explicit re-checks), so the checklist always shows
 * the real current state — nothing is hard-coded "OK".
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
    val notificationsMissing = remember(refreshTick) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
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
    val allReady = bleSupported && btEnabled && permissionsReady && hasIdentity

    fun requestPermissions() {
        val toRequest = BlePermissions.requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Needed to show the foreground-relay notification on API 33+.
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
        Text(
            text = "Welcome to P2P Chat",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Private messaging over Bluetooth — no SIM, no Wi-Fi, " +
                "no server. Phones around you relay encrypted messages hop by hop.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // ---- checklist of truthful live states ----
        CheckRow(
            ok = bleSupported,
            label = if (bleSupported) {
                "Bluetooth Low Energy is supported on this phone"
            } else {
                "This phone does not support Bluetooth Low Energy — P2P Chat cannot run on it"
            }
        )
        CheckRow(
            ok = btEnabled,
            label = if (btEnabled) "Bluetooth is on" else "Bluetooth is off"
        )
        CheckRow(
            ok = permissionsReady,
            label = if (permissionsReady) {
                "Bluetooth permissions granted"
            } else {
                "Bluetooth permissions missing: " +
                    missingPermissions.joinToString { it.substringAfterLast('.') }
            }
        )
        CheckRow(
            ok = hasIdentity,
            label = if (hasIdentity) {
                "Your identity is created (keys stay on this phone)"
            } else {
                "No identity yet — create one below"
            }
        )
        if (notificationsMissing) {
            CheckRow(
                ok = false,
                label = "Notification permission missing — the background relay " +
                    "notification cannot be shown until it is granted"
            )
        }

        Spacer(Modifier.height(20.dp))

        if (!btEnabled && bleSupported) {
            Button(
                onClick = {
                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Bluetooth")
            }
            Spacer(Modifier.height(10.dp))
        }

        if (!permissionsReady || notificationsMissing) {
            Button(
                onClick = { requestPermissions() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (deniedOnce) "Retry permissions" else "Grant permissions")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "Why: Android 12+ requires your permission before an app may " +
                        "scan for, advertise to, or connect to nearby Bluetooth devices."
                } else {
                    "Why: on this Android version, Bluetooth scan results are only " +
                        "delivered to apps that hold the location permission. P2P Chat " +
                        "never reads your GPS location — the permission only unlocks scanning."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (deniedOnce) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Permission denied. Without it the app cannot discover or " +
                        "connect to any peer — messaging and relaying stay disabled. " +
                        "You can retry here, or grant it later in system Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        OutlinedButton(
            onClick = { refreshTick++ },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Re-check status")
        }

        if (!hasIdentity) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Create your identity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "A fresh key pair is generated on this phone. Your display " +
                    "name is what nearby peers see; your private keys never leave the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onCreateIdentity(displayName.trim()) },
                enabled = displayName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Create identity")
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onReady,
            enabled = allReady,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
        if (!allReady) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Continue unlocks when every check above passes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

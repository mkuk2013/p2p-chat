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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.AppContainer
import com.p2pchat.messenger.service.MeshForegroundService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live mesh network status. Every value comes from the transport's real
 * state flows, the router's [com.p2pchat.messenger.mesh.PeerTable] or the
 * Room outbox — there are no sample peers or invented counts here.
 */
@Composable
fun MeshStatusScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val transport = container.bleTransport
    val prefs = remember {
        context.getSharedPreferences(MeshForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var relayOn by remember {
        mutableStateOf(prefs.getBoolean(MeshForegroundService.KEY_RELAY_ENABLED, false))
    }

    // Live transport state (defaults when no identity/transport exists yet).
    val connectionsState = transport?.connections?.collectAsState()
    val connections = connectionsState?.value ?: emptyMap()
    val scanningState = transport?.isScanning?.collectAsState()
    val advertisingState = transport?.isAdvertising?.collectAsState()
    val connectedCount = transport?.readyAddresses()?.size ?: 0
    val tablePeers = remember(connections) { container.peerTable.all() }
    val outboxPending by container.database.outboxDao().observeCount()
        .collectAsState(initial = 0)
    val knownPeers by container.peerRepository.peers.collectAsState(initial = emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                text = "Mesh network status",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(12.dp))

        StatusRow("Bluetooth", if (container.isBluetoothEnabled()) "On" else "Off")
        StatusRow("Scanning", if (scanningState?.value == true) "Active" else "Idle")
        StatusRow("Advertising", if (advertisingState?.value == true) "Active" else "Idle")
        StatusRow("Connected peers (ready links)", connectedCount.toString())
        StatusRow("Pending in outbox", outboxPending.toString())
        StatusRow("Known peers (saved)", knownPeers.size.toString())

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Background relay",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Keep relaying with the screen locked via a foreground " +
                        "service (visible notification, stop any time).",
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

        Spacer(Modifier.height(20.dp))
        Text(
            text = "Peer table",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        if (tablePeers.isEmpty()) {
            Text(
                text = "No peers in the table yet. Entries appear when a peer's " +
                    "identity is read or one of its envelopes passes through this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            tablePeers.forEach { peer ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        text = peer.displayName ?: "Peer ${peer.peerId.take(8)}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "ID ${peer.peerId}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Hops away: ${peer.hopsAway} · Last seen: " +
                            formatStatusTime(peer.lastSeenMs) +
                            (peer.address?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "How to read this: a phone can only ever list peers it really " +
                "heard. “Hops away 1” is a direct Bluetooth neighbour; larger " +
                "numbers are senders whose envelopes arrived through relays.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatStatusTime(timestampMs: Long): String =
    if (timestampMs <= 0) "never" else {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
    }

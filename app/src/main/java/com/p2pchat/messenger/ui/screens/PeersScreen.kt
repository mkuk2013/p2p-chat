package com.p2pchat.messenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.ble.BleTransport
import com.p2pchat.messenger.ble.model.ConnState
import com.p2pchat.messenger.data.repository.ChatRepository
import com.p2pchat.messenger.data.repository.PeerRepository
import kotlinx.coroutines.launch

/**
 * Nearby P2P Chat peers, from the transport's live scan results only.
 * The scan filter matches the P2P Chat GATT service UUID, so every row is
 * a real discovered device running P2P Chat — never a fabricated list.
 * A row's name/peerId appear only after the peer's identity is resolved
 * (advertisement hint or IDENTITY characteristic read).
 */
@Composable
fun PeersScreen(
    bleTransport: BleTransport?,
    chatRepository: ChatRepository?,
    peerRepository: PeerRepository,
    onOpenChat: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // Discover + be discoverable while this screen is open. The transport
    // is intentionally NOT stopped on dispose: the foreground relay
    // service and the chat screen share the same links.
    LaunchedEffect(bleTransport) {
        bleTransport?.start()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                text = "Nearby peers",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))

        if (bleTransport == null) {
            Text(
                text = "Create your identity first (setup screen) — Bluetooth " +
                    "discovery starts once this phone has a peer identity.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        val peers by bleTransport.peers.collectAsState()
        val connections by bleTransport.connections.collectAsState()
        val isScanning by bleTransport.isScanning.collectAsState()

        if (isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Scanning for P2P Chat peers…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        if (peers.isEmpty()) {
            Text(
                text = if (bleTransport.isBluetoothEnabled()) {
                    "No P2P Chat phones found yet. Make sure the other phone " +
                        "has P2P Chat open (or its relay on) with Bluetooth enabled."
                } else {
                    "Bluetooth is off — turn it on to discover peers."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(peers, key = { it.deviceAddress }) { peer ->
                    val state = connections[peer.deviceAddress] ?: ConnState.DISCONNECTED
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = peer.displayName
                                ?: peer.peerId?.let { "Peer ${it.take(8)}" }
                                ?: "P2P Chat device (identity not read yet)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Address ${peer.deviceAddress} · RSSI ${peer.rssi} dBm · $state",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (peer.peerId != null) {
                            Text(
                                text = "Peer ID ${peer.peerId}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (state) {
                                ConnState.READY, ConnState.CONNECTED,
                                ConnState.DISCOVERING, ConnState.CONNECTING -> {
                                    OutlinedButton(
                                        onClick = { bleTransport.disconnect(peer.deviceAddress) }
                                    ) { Text("Disconnect") }
                                }
                                else -> {
                                    Button(
                                        onClick = { bleTransport.connect(peer.deviceAddress) },
                                        enabled = peer.connectable
                                    ) { Text("Connect") }
                                }
                            }
                            val resolvedPeerId = peer.peerId
                            if (resolvedPeerId != null && chatRepository != null) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val known = peerRepository.getPeer(resolvedPeerId)
                                            chatRepository.ensureConversation(
                                                peerId = resolvedPeerId,
                                                displayName = peer.displayName
                                                    ?: known?.displayName
                                                    ?: resolvedPeerId.take(8)
                                            )
                                            onOpenChat(resolvedPeerId)
                                        }
                                    }
                                ) { Text("Start chat") }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Only phones actually discovered over Bluetooth are listed. " +
                "A peer's name and Peer ID appear after its identity is read on " +
                "connect — until then it can be connected to, but not chatted with.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

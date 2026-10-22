package com.p2pchat.messenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.AppContainer
import com.p2pchat.messenger.ble.BleTransport
import com.p2pchat.messenger.ble.model.ConnState
import com.p2pchat.messenger.data.repository.ChatRepository
import com.p2pchat.messenger.data.repository.PeerRepository
import com.p2pchat.messenger.nearby.NearbyPermissions
import com.p2pchat.messenger.ui.components.PeerAvatar
import kotlinx.coroutines.launch

/**
 * "Select contact" (WhatsApp's New Chat screen): known peers on top,
 * live-discovered devices below. There is NO separate Connect step —
 * tapping any row starts connecting in the background and opens the chat
 * as soon as the peer's identity is known (immediately for known peers).
 * Only devices actually discovered around the user are ever listed.
 */
@Composable
fun PeersScreen(
    container: AppContainer,
    bleTransport: BleTransport?,
    chatRepository: ChatRepository?,
    peerRepository: PeerRepository,
    onOpenChat: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val nearbyTransport = container.nearbyTransport

    var nearbyPermsGranted by remember {
        mutableStateOf(nearbyTransport == null || NearbyPermissions.hasAll(context))
    }
    val nearbyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { nearbyPermsGranted = NearbyPermissions.hasAll(context) }

    // Discover + be discoverable while this screen is open. Transports
    // are intentionally NOT stopped on dispose (foreground relay +
    // chat screens share the same links).
    LaunchedEffect(bleTransport) { bleTransport?.start() }
    LaunchedEffect(nearbyTransport, nearbyPermsGranted) {
        if (nearbyPermsGranted) nearbyTransport?.start()
    }

    val knownPeers by peerRepository.peers.collectAsState(initial = emptyList())
    val knownIds = remember(knownPeers) { knownPeers.map { it.peerId }.toSet() }

    // -- Live discovery state.
    val blePeers by (bleTransport?.peers ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val bleConnections by (bleTransport?.connections
        ?: kotlinx.coroutines.flow.flowOf(emptyMap()))
        .collectAsState(initial = emptyMap())
    val isScanning by (bleTransport?.isScanning ?: kotlinx.coroutines.flow.flowOf(false))
        .collectAsState(initial = false)
    val nearbyEndpoints by (nearbyTransport?.endpoints
        ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val nearbySessions by (nearbyTransport?.sessions
        ?: kotlinx.coroutines.flow.flowOf(emptyMap()))
        .collectAsState(initial = emptyMap())

    // A first-contact connect in flight: which row is waiting for its
    // identity exchange to complete so the chat can open.
    var connectingBleAddress by remember { mutableStateOf<String?>(null) }
    var connectingEndpointId by remember { mutableStateOf<String?>(null) }
    var knownIdsAtConnectStart by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun openChat(peerId: String, displayName: String) {
        if (chatRepository == null) return
        scope.launch {
            chatRepository.ensureConversation(peerId, displayName)
            onOpenChat(peerId)
        }
    }

    // BLE first contact: once the connecting device's identity resolves,
    // jump straight into its chat.
    LaunchedEffect(blePeers, connectingBleAddress) {
        val address = connectingBleAddress ?: return@LaunchedEffect
        val peer = blePeers.firstOrNull { it.deviceAddress == address } ?: return@LaunchedEffect
        val resolved = peer.peerId ?: return@LaunchedEffect
        connectingBleAddress = null
        openChat(resolved, peer.displayName ?: resolved.take(8))
    }

    // Nearby first contact: the peer lands in the known-peers table when
    // its identity payload arrives over the new connection.
    LaunchedEffect(knownPeers, connectingEndpointId) {
        if (connectingEndpointId == null) return@LaunchedEffect
        val newPeer = knownPeers.firstOrNull { it.peerId !in knownIdsAtConnectStart }
            ?: return@LaunchedEffect
        connectingEndpointId = null
        openChat(newPeer.peerId, newPeer.displayName)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text(
                    text = "Select contact",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap a person or device — the chat opens right away",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (bleTransport == null && nearbyTransport == null) {
            Text(
                text = "Create your identity first (setup screen) — discovery " +
                    "starts once this phone has a peer identity.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        if (nearbyTransport != null && nearbyTransport.available && !nearbyPermsGranted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = "Nearby direct link (faster chats + voice calls) " +
                        "needs a permission.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    nearbyPermLauncher.launch(NearbyPermissions.requiredPermissions())
                }) { Text("Enable") }
            }
        }

        if (isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "Scanning for P2P Chat devices around you…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (knownPeers.isNotEmpty()) {
                item {
                    SectionTitle("Known peers")
                }
                items(knownPeers, key = { "known-${it.peerId}" }) { peer ->
                    ContactRow(
                        name = peer.displayName,
                        peerId = peer.peerId,
                        subtitle = "Known peer · tap to chat",
                        connecting = false,
                        onClick = { openChat(peer.peerId, peer.displayName) }
                    )
                }
            }

            item { SectionTitle("Nearby now") }

            // BLE discoveries not already in the known list.
            val freshBle = blePeers.filter { it.peerId == null || it.peerId !in knownIds }
            items(freshBle, key = { "ble-${it.deviceAddress}" }) { peer ->
                val state = bleConnections[peer.deviceAddress] ?: ConnState.DISCONNECTED
                val connecting = connectingBleAddress == peer.deviceAddress ||
                    state == ConnState.CONNECTING || state == ConnState.DISCOVERING
                ContactRow(
                    name = peer.displayName
                        ?: peer.peerId?.let { "Peer ${it.take(8)}" }
                        ?: "P2P Chat device",
                    peerId = peer.peerId ?: peer.deviceAddress,
                    subtitle = when {
                        connecting -> "Connecting — the chat opens as soon as " +
                            "the identity is read"
                        peer.peerId != null -> "In range · tap to chat"
                        else -> "Bluetooth · signal ${peer.rssi} dBm · tap to " +
                            "connect and chat"
                    },
                    connecting = connecting,
                    onClick = {
                        val resolved = peer.peerId
                        if (resolved != null) {
                            openChat(resolved, peer.displayName ?: resolved.take(8))
                        } else if (bleTransport != null && peer.connectable) {
                            connectingBleAddress = peer.deviceAddress
                            bleTransport.connect(peer.deviceAddress)
                        }
                    }
                )
            }

            // Nearby Connections discoveries.
            items(nearbyEndpoints, key = { "nearby-${it.endpointId}" }) { endpoint ->
                val matchedPeer = endpoint.hint?.let { hint ->
                    knownPeers.firstOrNull { known ->
                        BleTransport.identityHint(known.peerId).contentEquals(hint)
                    }
                }
                val session = nearbySessions[endpoint.endpointId]
                val connecting = connectingEndpointId == endpoint.endpointId ||
                    session == com.p2pchat.messenger.nearby.NearbyLinkState.CONNECTING
                ContactRow(
                    name = matchedPeer?.displayName ?: "P2P Chat device",
                    peerId = matchedPeer?.peerId ?: endpoint.endpointId,
                    subtitle = when {
                        connecting -> "Connecting on the direct link — the chat " +
                            "opens when the identity is verified"
                        matchedPeer != null -> "Nearby direct link · tap to chat"
                        else -> "Nearby direct link · tap to connect and chat"
                    },
                    connecting = connecting,
                    onClick = {
                        if (matchedPeer != null) {
                            openChat(matchedPeer.peerId, matchedPeer.displayName)
                        } else if (nearbyTransport != null) {
                            knownIdsAtConnectStart = knownIds
                            connectingEndpointId = endpoint.endpointId
                            nearbyTransport.connect(endpoint.endpointId)
                        }
                    }
                )
            }

            if (knownPeers.isEmpty() && freshBle.isEmpty() && nearbyEndpoints.isEmpty()) {
                item {
                    Text(
                        text = if (bleTransport?.isBluetoothEnabled() == false) {
                            "Bluetooth is off — turn it on to discover devices."
                        } else {
                            "No P2P Chat devices found yet. Make sure the other " +
                                "phone has P2P Chat open with Bluetooth on."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }

        Text(
            text = "Only devices actually discovered around you are listed. " +
                "A device's name appears once its identity is verified — " +
                "nothing here is guessed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 10.dp)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun ContactRow(
    name: String,
    peerId: String,
    subtitle: String,
    connecting: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !connecting, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PeerAvatar(name = name, peerId = peerId, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (connecting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

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
 * "People nearby" (WhatsApp's New Chat screen, and the People tab of
 * the home shell when [embedded]): people you know on top, people
 * found around you below. There is NO separate Connect step and NO
 * waiting room — tapping a known person opens the chat immediately,
 * and tapping a newly found device opens its chat immediately while
 * connecting proceeds inside it (bounded, with a truthful failure
 * state and retry). Only devices actually discovered around the user
 * are ever listed.
 */
@Composable
fun PeersScreen(
    container: AppContainer,
    bleTransport: BleTransport?,
    chatRepository: ChatRepository?,
    peerRepository: PeerRepository,
    onOpenChat: (String) -> Unit,
    onOpenDeviceChat: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false
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
    val bleIdentityStatuses by (bleTransport?.identityStatus
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

    // A first-contact Nearby connect in flight: which row is waiting
    // for its identity exchange to complete so the chat can open.
    // (BLE first contact no longer waits here at all — tapping a BLE
    // device opens its device chat immediately; see the row below.)
    var connectingEndpointId by remember { mutableStateOf<String?>(null) }
    var knownIdsAtConnectStart by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun openChat(peerId: String, displayName: String) {
        if (chatRepository == null) return
        scope.launch {
            chatRepository.ensureConversation(peerId, displayName)
            onOpenChat(peerId)
        }
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
            if (!embedded) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Column {
                Text(
                    text = "People nearby",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap a name to start chatting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (bleTransport == null && nearbyTransport == null) {
            Text(
                text = "Finish setting up first — finding people nearby " +
                    "starts right after that.",
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
                    text = "Faster chats and voice calls need one permission.",
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
                text = "Looking for people nearby…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (knownPeers.isNotEmpty()) {
                item {
                    SectionTitle("People you know")
                }
                items(knownPeers, key = { "known-${it.peerId}" }) { peer ->
                    ContactRow(
                        name = peer.displayName,
                        peerId = peer.peerId,
                        subtitle = "Tap to chat",
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
                val linkStatus = bleIdentityStatuses[peer.deviceAddress]
                val linkFailed = linkStatus?.phase ==
                    com.p2pchat.messenger.ble.model.IdentityPhase.FAILED
                val connecting = !linkFailed &&
                    (linkStatus?.phase ==
                        com.p2pchat.messenger.ble.model.IdentityPhase.RESOLVING ||
                        state == ConnState.CONNECTING || state == ConnState.DISCOVERING)
                ContactRow(
                    name = peer.displayName ?: "P2P Chat device",
                    peerId = peer.peerId ?: peer.deviceAddress,
                    subtitle = when {
                        // The connection's own one-line status, in
                        // plain words — including what went wrong once
                        // the attempts are used up.
                        linkStatus != null && peer.peerId == null -> linkStatus.message
                        peer.peerId != null -> "Nearby · tap to chat"
                        else -> "Tap to chat"
                    },
                    connecting = connecting,
                    onClick = {
                        val resolved = peer.peerId
                        if (resolved != null) {
                            openChat(resolved, peer.displayName ?: resolved.take(8))
                        } else if (bleTransport != null && peer.connectable) {
                            // Chat opens FIRST (never a waiting room):
                            // connect in the background; the exchange
                            // continues inside the device chat under
                            // the bounded resolver.
                            bleTransport.connect(peer.deviceAddress)
                            onOpenDeviceChat(peer.deviceAddress)
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
                        connecting -> "Connecting — the chat opens in a moment"
                        matchedPeer != null -> "Nearby · tap to chat"
                        else -> "Tap to chat"
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
                            "Bluetooth is off — turn it on to find people nearby."
                        } else {
                            "No one found yet. Ask the other person to open " +
                                "P2P Chat and turn Bluetooth on."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }

        Text(
            text = "Only people really near you are listed. A name appears " +
                "once the two phones have safely introduced themselves — " +
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

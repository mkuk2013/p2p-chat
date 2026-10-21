package com.p2pchat.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.ble.BleTransport
import com.p2pchat.messenger.data.entity.MessageEntity
import com.p2pchat.messenger.data.repository.ChatRepository
import com.p2pchat.messenger.data.repository.PeerRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * One-to-one conversation. Messages come from Room via [ChatRepository];
 * sending seals + signs + queues via the repository and the mesh router.
 *
 * Delivery labels are deliberately exact:
 *  - Queued   — still in the local outbox, nobody has accepted it yet;
 *  - Relayed  — a neighbour accepted it for forwarding (NOT delivered);
 *  - Delivered — the recipient device's own ACK came back;
 *  - Expired / Failed — it will not be delivered by this node.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerId: String,
    chatRepository: ChatRepository,
    peerRepository: PeerRepository,
    bleTransport: BleTransport?,
    onMessageInfo: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val peer by peerRepository.observePeer(peerId).collectAsState(initial = null)
    val messages by remember(peerId) { chatRepository.messages(peerId) }
        .collectAsState(initial = emptyList())

    // Live link state (recomputed whenever the connections map changes).
    val connectionsState = bleTransport?.connections?.collectAsState()
    val connections = connectionsState?.value ?: emptyMap()
    val peerConnected = remember(connections, bleTransport, peerId) {
        bleTransport?.readyPeerIds()?.values?.contains(peerId) == true
    }

    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(peerId) {
        chatRepository.markConversationRead(peerId)
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(peer?.displayName ?: peerId.take(8))
                        Text(
                            text = if (peerConnected) {
                                "Connected (direct Bluetooth link)"
                            } else {
                                "Not directly connected — mesh relay may still reach them"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!peerConnected) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Peer offline — messages will queue on this phone " +
                            "and relay through other P2P Chat phones when a route " +
                            "is available, until they expire.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.messageId }) { message ->
                    MessageBubble(
                        message = message,
                        onInfo = { onMessageInfo(message.messageId) }
                    )
                }
            }

            if (sendError != null) {
                Text(
                    text = sendError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = draft.trim()
                        if (text.isEmpty()) return@Button
                        scope.launch {
                            try {
                                chatRepository.sendText(peerId, text)
                                draft = ""
                                sendError = null
                            } catch (e: Exception) {
                                // e.g. identity exchange incomplete — the
                                // repository's message is the honest reason.
                                sendError = e.message ?: "Could not queue the message."
                            }
                        }
                    },
                    enabled = draft.isNotBlank()
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    onInfo: () -> Unit
) {
    val bubbleColor = if (message.isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = message.plaintext,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatBubbleTime(message.timestampMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (message.isMine) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = deliveryLabel(message.deliveryState),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.deliveryState == "DELIVERED") {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                TextButton(onClick = onInfo) {
                    Text("Details", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

internal fun deliveryLabel(state: String): String = when (state) {
    "QUEUED" -> "Queued"
    "RELAYED" -> "Relayed (not delivered yet)"
    "DELIVERED" -> "Delivered"
    "EXPIRED" -> "Expired"
    "FAILED" -> "Failed"
    else -> state
}

private fun formatBubbleTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))

package com.p2pchat.messenger.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.AppContainer
import com.p2pchat.messenger.ble.model.IdentityPhase
import com.p2pchat.messenger.data.repository.ChatRepository
import com.p2pchat.messenger.data.repository.PeerRepository
import com.p2pchat.messenger.ui.components.PeerAvatar
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * First-contact chat for a device whose identity is not resolved yet.
 *
 * The v1.1.0 flow stranded the user on the Select-contact list with an
 * unbounded "Connecting — the chat opens as soon as the identity is
 * read" spinner. Here the chat opens FIRST: the screen is chat-shaped,
 * typing is allowed immediately, and the identity exchange continues
 * inside it under the transport's bounded resolver (timeout + retries
 * + a full link reset for the final attempt). When the identity lands,
 * this screen hands over to the real [ChatScreen] in place — draft
 * included. If the bounded budget is exhausted, the failure banner
 * says why in plain words and offers a retry that fully resets the
 * link. There is no state in this screen that can spin forever.
 */
@Composable
fun DeviceChatScreen(
    address: String,
    container: AppContainer,
    chatRepository: ChatRepository,
    peerRepository: PeerRepository,
    onMessageInfo: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val bleTransport = container.bleTransport

    val blePeers by (bleTransport?.peers ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val identityStatuses by (bleTransport?.identityStatus ?: flowOf(emptyMap()))
        .collectAsState(initial = emptyMap())

    val peer = blePeers.firstOrNull { it.deviceAddress == address }
    val status = identityStatuses[address]
    val resolvedPeerId = peer?.peerId

    var draft by remember(address) { mutableStateOf("") }
    var conversationReady by remember(address) { mutableStateOf(false) }

    // Opening this screen IS the connect action (idempotent in the
    // transport). Resolution then runs under the bounded resolver.
    LaunchedEffect(address) {
        bleTransport?.connect(address)
    }

    LaunchedEffect(resolvedPeerId) {
        val peerId = resolvedPeerId ?: return@LaunchedEffect
        chatRepository.ensureConversation(peerId, peer?.displayName ?: peerId.take(8))
        conversationReady = true
    }

    if (resolvedPeerId != null && conversationReady) {
        ChatScreen(
            peerId = resolvedPeerId,
            container = container,
            chatRepository = chatRepository,
            peerRepository = peerRepository,
            onMessageInfo = onMessageInfo,
            onBack = onBack,
            modifier = modifier,
            initialDraft = draft
        )
        return
    }

    val failed = status?.phase == IdentityPhase.FAILED

    Column(modifier = modifier.fillMaxSize()) {
        // -- Header (mirrors the chat screen's identity block).
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                PeerAvatar(name = "P2P Chat device", peerId = address, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = peer?.displayName ?: "P2P Chat device",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when {
                            failed -> "Couldn't connect"
                            status != null -> status.message
                            else -> "Connecting…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // -- Status banner: progress (bounded) or failure + retry.
        if (failed) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = status?.message
                            ?: "Couldn't connect to this phone. Tap Try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { bleTransport?.resetLink(address) }) {
                        Text("Try again")
                    }
                }
            }
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = status?.message
                        ?: "Connecting — you can type now. Sending starts " +
                            "when you are connected.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // -- Empty conversation area.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No messages yet.\n" +
                    "Your chat opens fully as soon as you are connected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // -- Input bar: typing allowed now; sending unlocks on identity.
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message") },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { }, enabled = false) {
                    Icon(Icons.Filled.Send, contentDescription = "Send (locked until connected)")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

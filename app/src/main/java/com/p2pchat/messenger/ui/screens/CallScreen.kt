package com.p2pchat.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.AppContainer
import com.p2pchat.messenger.nearby.CallState
import com.p2pchat.messenger.ui.components.MicIcon
import com.p2pchat.messenger.ui.components.PeerAvatar
import com.p2pchat.messenger.ui.components.SpeakerIcon
import com.p2pchat.messenger.ui.components.formatDuration
import kotlinx.coroutines.delay

/**
 * Full-screen voice-call overlay for the experimental Nearby direct-link
 * calls. Renders the call controller's state EXACTLY — including failure
 * reasons — because on an untested radio path an honest error beats a
 * spinner that never ends.
 */
@Composable
fun CallOverlay(container: AppContainer, modifier: Modifier = Modifier) {
    val controller = container.callController ?: return
    val state by controller.state.collectAsState()

    when (val s = state) {
        is CallState.Idle -> Unit
        is CallState.Linking -> CallSurface(
            name = s.peerName ?: "P2P Chat user",
            peerId = s.peerId,
            status = "Connecting…",
            modifier = modifier
        ) {
            ControlRow {
                EndButton(onClick = { controller.end() })
            }
        }
        is CallState.Outgoing -> CallSurface(
            name = s.peerName ?: "P2P Chat user",
            peerId = s.peerId,
            status = "Ringing…",
            modifier = modifier
        ) {
            ControlRow {
                EndButton(onClick = { controller.end() })
            }
        }
        is CallState.Incoming -> CallSurface(
            name = s.peerName ?: "P2P Chat user",
            peerId = s.peerId,
            status = "Incoming call",
            modifier = modifier
        ) {
            ControlRow {
                DeclineButton(onClick = { controller.reject() })
                Spacer(Modifier.size(48.dp))
                AcceptButton(onClick = { controller.accept() })
            }
        }
        is CallState.Active -> {
            var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(s.connectedSinceMs) {
                while (true) {
                    nowMs = System.currentTimeMillis()
                    delay(500)
                }
            }
            CallSurface(
                name = s.peerName ?: "P2P Chat user",
                peerId = s.peerId,
                status = formatDuration(nowMs - s.connectedSinceMs),
                modifier = modifier
            ) {
                ControlRow {
                    ToggleButton(
                        label = if (s.muted) "Unmute" else "Mute",
                        active = s.muted,
                        onClick = { controller.toggleMute() }
                    ) {
                        MicIcon(
                            tint = if (s.muted) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color.White
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.size(20.dp))
                    EndButton(onClick = { controller.end() })
                    Spacer(Modifier.size(20.dp))
                    ToggleButton(
                        label = "Speaker",
                        active = s.speakerOn,
                        onClick = { controller.toggleSpeaker() }
                    ) {
                        SpeakerIcon(tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        is CallState.Ended -> AlertDialog(
            onDismissRequest = { controller.dismissTerminal() },
            title = { Text("Call ended") },
            text = {
                Text(
                    com.p2pchat.messenger.core.call.CallReasons.plainReason(s.reason)
                        ?: "The call is over."
                )
            },
            confirmButton = {
                TextButton(onClick = { controller.dismissTerminal() }) { Text("OK") }
            }
        )
        is CallState.Failed -> AlertDialog(
            onDismissRequest = { controller.dismissTerminal() },
            title = { Text("Couldn't call") },
            text = {
                Text(
                    com.p2pchat.messenger.core.call.CallReasons.plainReason(s.reason)
                        ?: "Something went wrong."
                )
            },
            confirmButton = {
                // Ask, never auto-switch: the only choices are trying
                // the same nearby call again, or cancelling.
                val retryPeerId = container.lastCallPeerId()
                if (retryPeerId != null) {
                    TextButton(onClick = {
                        controller.dismissTerminal()
                        container.startVoiceCall(retryPeerId)
                    }) { Text("Try again") }
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.dismissTerminal() }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CallSurface(
    name: String,
    peerId: String,
    status: String,
    modifier: Modifier = Modifier,
    controls: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(72.dp))
        PeerAvatar(name = name, peerId = peerId, size = 96.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "Nearby call · no internet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        controls()
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun ControlRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun EndButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0xFFD93025))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.Call,
            contentDescription = "End call",
            tint = Color.White,
            modifier = Modifier
                .size(28.dp)
                .rotate(135f)
        )
    }
}

@Composable
private fun DeclineButton(onClick: () -> Unit) = EndButton(onClick)

@Composable
private fun AcceptButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Filled.Call,
            contentDescription = "Answer call",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun ToggleButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

package com.p2pchat.messenger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.p2pchat.messenger.AppContainer
import com.p2pchat.messenger.ble.model.IdentityPhase
import com.p2pchat.messenger.data.entity.MessageEntity
import com.p2pchat.messenger.data.entity.VoiceTransferEntity
import com.p2pchat.messenger.data.repository.ChatRepository
import com.p2pchat.messenger.data.repository.PeerRepository
import com.p2pchat.messenger.nearby.NearbyPermissions
import com.p2pchat.messenger.ui.components.DeliveryTicks
import com.p2pchat.messenger.ui.components.MicIcon
import com.p2pchat.messenger.ui.components.PauseIcon
import com.p2pchat.messenger.ui.components.PeerAvatar
import com.p2pchat.messenger.ui.components.formatBubbleTime
import com.p2pchat.messenger.ui.components.formatDaySeparator
import com.p2pchat.messenger.ui.components.formatDuration
import com.p2pchat.messenger.ui.components.isSameDay
import com.p2pchat.messenger.ui.voice.VoicePlayer
import com.p2pchat.messenger.ui.voice.VoiceRecorder
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * One-to-one conversation, WhatsApp-style. Opening a chat immediately
 * starts connecting (BLE + Nearby direct link) in the background; when
 * the peer's identity is not resolved yet the screen shows a
 * "Connecting…" state instead of gating the user elsewhere. Delivery
 * ticks are honest: the mesh has no read receipts, so DELIVERED (✓✓,
 * azure) is only ever the recipient device's own ACK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerId: String,
    container: AppContainer,
    chatRepository: ChatRepository,
    peerRepository: PeerRepository,
    onMessageInfo: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Text typed on the pre-identity device chat screen, carried over. */
    initialDraft: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val peer by peerRepository.observePeer(peerId).collectAsState(initial = null)
    val messages by remember(peerId) { chatRepository.messages(peerId) }
        .collectAsState(initial = emptyList())
    val transfers by remember(peerId) { chatRepository.observeVoiceTransfers(peerId) }
        .collectAsState(initial = emptyList())

    // -- Live link state -------------------------------------------------
    val bleConnections by (container.bleTransport?.connections ?: flowOf(emptyMap()))
        .collectAsState(initial = emptyMap())
    val nearbySessions by (container.nearbyTransport?.sessions ?: flowOf(emptyMap()))
        .collectAsState(initial = emptyMap())
    val directBle = remember(bleConnections, peerId) {
        container.bleTransport?.readyPeerIds()?.values?.contains(peerId) == true
    }
    val directNearby = remember(nearbySessions, peerId) {
        container.isNearbyConnected(peerId)
    }
    val livePeer = remember(messages.size, bleConnections, nearbySessions, peerId) {
        container.peerTable.get(peerId)
    }
    // Identity-exchange diagnostics for this peer's BLE link (plain
    // words from the transport's bounded resolver).
    val bleIdentityStatuses by (container.bleTransport?.identityStatus ?: flowOf(emptyMap()))
        .collectAsState(initial = emptyMap())
    val bleAddress = remember(peerId, bleIdentityStatuses) {
        container.bleAddressForPeer(peerId)
    }
    val bleStatus = bleAddress?.let { bleIdentityStatuses[it] }
    val identityReady = peer != null
    // One short line a child could read — the technical detail lives
    // on the Connection details screen, never here.
    val statusText = when {
        !identityReady && bleStatus?.phase == IdentityPhase.FAILED -> "Couldn't connect"
        !identityReady -> "Connecting…"
        directBle || directNearby -> "Nearby now"
        else -> "Not nearby"
    }

    // Opening the chat IS the connect action; re-kick while unresolved.
    // A FAILED identity exchange is NOT auto-re-kicked: the bounded
    // resolver already spent its budget (including a full link reset),
    // so recovery is the user's explicit retry — no reset storms.
    LaunchedEffect(peerId) {
        container.startDirectLink(peerId)
        while (true) {
            delay(15_000)
            // peer is a State snapshot inside this effect's first frame;
            // read the table instead for liveness.
            if (container.peerTable.get(peerId) == null) {
                val phase = container.bleAddressForPeer(peerId)?.let { addr ->
                    container.bleTransport?.identityStatus?.value?.get(addr)?.phase
                }
                if (phase != IdentityPhase.FAILED) {
                    container.startDirectLink(peerId)
                }
            }
        }
    }
    LaunchedEffect(peerId) { chatRepository.markConversationRead(peerId) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) chatRepository.markConversationRead(peerId)
    }

    var draft by remember(peerId) { mutableStateOf(initialDraft) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // -- Voice recording --------------------------------------------------
    val recorder = remember { VoiceRecorder(context) }
    val player = remember { VoicePlayer() }
    val playerState by player.state.collectAsState()
    var recording by remember { mutableStateOf(false) }
    var recordElapsedMs by remember { mutableStateOf(0L) }
    var recordSizeBytes by remember { mutableStateOf(0L) }
    var recordLimitNote by remember { mutableStateOf<String?>(null) }

    fun finishRecording(send: Boolean) {
        if (!recording) return
        recording = false
        val durationMs = recorder.stop()
        val file = File(context.cacheDir, "voice-out.m4a")
        if (!send) {
            runCatching { file.delete() }
            return
        }
        if (durationMs <= 0 || !file.exists()) {
            sendError = "Recording failed — please try again."
            return
        }
        scope.launch {
            try {
                chatRepository.sendVoiceNote(peerId, file, durationMs.toInt())
                sendError = recordLimitNote
                recordLimitNote = null
            } catch (e: Exception) {
                sendError = e.message ?: "Could not send the voice note."
            }
        }
    }

    recorder.onLimitReached = {
        if (recording) {
            recordLimitNote = "Voice note reached the 600 KB limit and was sent."
            finishRecording(send = true)
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            recordElapsedMs = recorder.elapsedMs()
            recordSizeBytes = recorder.sizeBytes()
            if (recordSizeBytes >= SIZE_LIMIT_BYTES) {
                recordLimitNote = "Voice note reached the 600 KB limit and was sent."
                finishRecording(send = true)
                break
            }
            delay(200)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val file = File(context.cacheDir, "voice-out.m4a")
            recording = recorder.start(file)
            if (!recording) sendError = "Could not start recording on this device."
        } else {
            sendError = "Microphone permission is needed for voice notes."
        }
    }

    fun startRecording() {
        if (!identityReady) {
            sendError = "Still connecting — you can send in a moment."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val file = File(context.cacheDir, "voice-out.m4a")
            recording = recorder.start(file)
            if (!recording) sendError = "Could not start recording on this device."
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            player.release()
        }
    }

    // -- Voice call -------------------------------------------------------
    // The call button first opens the mode picker (below): the user
    // chooses how to call and consents to that mode — the app never
    // switches a call to another mode on its own.
    var showCallPicker by remember { mutableStateOf(false) }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micOk = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micOk && NearbyPermissions.hasAll(context)) {
            if (container.callController != null) {
                // Permissions just landed: start listening too, so this
                // phone can also RECEIVE calls from now on.
                container.ensureNearbyStarted()
                container.startVoiceCall(peerId)
            } else {
                sendError = "Calls are not ready yet — finish setup first."
            }
        } else {
            sendError = "Voice calls need the microphone permission."
        }
    }

    fun placeNearbyCall() {
        // No identityReady gate (v1.2.1): the chat flag could leave the
        // call button silently doing nothing on a phone whose BLE
        // identity exchange was still pending, even though the call
        // itself binds identity over its own link while "Connecting…".
        if (container.callController == null) {
            sendError = "Calls are not ready yet — finish setup first."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED && NearbyPermissions.hasAll(context)
        ) {
            container.ensureNearbyStarted()
            container.startVoiceCall(peerId)
        } else {
            callPermissionLauncher.launch(
                NearbyPermissions.requiredPermissions() + Manifest.permission.RECORD_AUDIO
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PeerAvatar(
                            name = peer?.displayName ?: "?",
                            peerId = peerId,
                            size = 36.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(peer?.displayName ?: peerId.take(8))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (!identityReady) {
                            sendError = "Still connecting — try the call in a moment."
                        } else {
                            showCallPicker = true
                        }
                    }) {
                        Icon(
                            Icons.Filled.Call,
                            contentDescription = "Voice call"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!identityReady && bleStatus?.phase == IdentityPhase.FAILED) {
                // Bounded exchange exhausted: truthful reason + retry.
                // The retry is a FULL link reset, not a bare re-read.
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = bleStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { container.retryBleLinkForPeer(peerId) }) {
                            Text("Try again")
                        }
                    }
                }
            } else if (!identityReady) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = bleStatus?.message
                            ?: "Connecting — you can type now. Sending starts " +
                                "when you are connected.",
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
                    val index = messages.indexOf(message)
                    val previous = messages.getOrNull(index - 1)
                    val showSeparator = previous == null || !isSameDay(
                        Calendar.getInstance().apply { timeInMillis = message.timestampMs },
                        Calendar.getInstance().apply { timeInMillis = previous.timestampMs }
                    )
                    Column {
                        if (showSeparator) {
                            DateSeparator(message.timestampMs)
                        }
                        if (message.kind == MessageEntity.KIND_VOICE) {
                            VoiceBubble(
                                message = message,
                                transfer = transfers.firstOrNull { it.blobId == message.blobId },
                                player = player,
                                playerState = playerState,
                                scope = scope,
                                onRetry = {
                                    scope.launch {
                                        runCatching {
                                            chatRepository.retryVoiceNote(
                                                message.blobId ?: message.messageId
                                            )
                                        }
                                    }
                                },
                                onInfo = { onMessageInfo(message.messageId) }
                            )
                        } else {
                            MessageBubble(
                                message = message,
                                onInfo = { onMessageInfo(message.messageId) }
                            )
                        }
                    }
                }

                // Incoming voice notes still arriving (no message row yet —
                // a note appears in the conversation only when complete).
                val receiving = transfers.filter {
                    !it.isMine && it.state == VoiceTransferEntity.STATE_ACTIVE &&
                        messages.none { m -> m.blobId == it.blobId }
                }
                items(receiving, key = { "recv-${it.blobId}" }) { transfer ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = "🎤 Receiving voice message… " +
                                    "${transfer.completedChunks}/${transfer.totalChunks}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    }
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

            if (recording) {
                RecordingBar(
                    elapsedMs = recordElapsedMs,
                    sizeBytes = recordSizeBytes,
                    onCancel = { finishRecording(send = false) },
                    onSend = { finishRecording(send = true) }
                )
            } else {
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
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    if (draft.isNotBlank()) {
                        RoundActionButton(
                            onClick = {
                                val text = draft.trim()
                                if (text.isEmpty()) return@RoundActionButton
                                scope.launch {
                                    try {
                                        chatRepository.sendText(peerId, text)
                                        draft = ""
                                        sendError = null
                                    } catch (e: Exception) {
                                        sendError = e.message
                                            ?: "Could not queue the message."
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = "Send",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        RoundActionButton(onClick = { startRecording() }) {
                            MicIcon(
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    // -- Call mode picker: the user picks how to call, every time.
    // Nearby is the only working mode today; Internet calling needs a
    // call server (WebRTC) and is shown disabled with the reason —
    // never faked, never switched to silently.
    if (showCallPicker) {
        ModalBottomSheet(onDismissRequest = { showCallPicker = false }) {
            Column(
                modifier = Modifier.padding(
                    start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp
                )
            ) {
                Text(
                    text = "Call ${peer?.displayName ?: "this person"}",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showCallPicker = false
                            placeNearbyCall()
                        }
                        .padding(vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Nearby call",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Free · works without internet · only " +
                                "when you are close",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                ) {
                    Spacer(Modifier.width(42.dp))
                    Column {
                        Text(
                            text = "Internet call",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Needs internet on both phones",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Coming in the next update — needs a " +
                                "call server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateSeparator(timestampMs: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = formatDaySeparator(timestampMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun RoundActionButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun RecordingBar(
    elapsedMs: Long,
    sizeBytes: Long,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Recording… ${formatDuration(elapsedMs)}",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "${sizeBytes / 1024} KB of 600 KB max",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "Cancel",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable(onClick = onCancel)
                .padding(8.dp)
        )
        Spacer(Modifier.width(4.dp))
        RoundActionButton(onClick = onSend) {
            Icon(
                Icons.Filled.Send,
                contentDescription = "Send voice note",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp)
            )
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
                .clickable(onClick = onInfo)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = message.plaintext,
                style = MaterialTheme.typography.bodyLarge
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatBubbleTime(message.timestampMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.isMine) {
                    Spacer(Modifier.width(4.dp))
                    DeliveryTicks(state = message.deliveryState, size = 14.dp)
                }
            }
        }
        if (message.isMine && message.deliveryState in setOf("EXPIRED", "FAILED")) {
            Text(
                text = deliveryLabel(message.deliveryState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
    }
}

@Composable
private fun VoiceBubble(
    message: MessageEntity,
    transfer: VoiceTransferEntity?,
    player: VoicePlayer,
    playerState: VoicePlayer.State,
    scope: kotlinx.coroutines.CoroutineScope,
    onRetry: () -> Unit,
    onInfo: () -> Unit
) {
    val bubbleColor = if (message.isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val path = message.mediaPath
    val isThisPlaying = playerState.path == path && playerState.playing
    val failed = message.isMine &&
        (message.deliveryState == "EXPIRED" || message.deliveryState == "FAILED" ||
            transfer?.state == VoiceTransferEntity.STATE_FAILED)
    val sending = message.isMine && transfer != null &&
        transfer.state == VoiceTransferEntity.STATE_ACTIVE
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bubbleColor)
                .clickable { if (failed) onRetry() else onInfo() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = path != null && !failed) {
                        if (path != null) player.toggle(scope, path)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isThisPlaying) {
                    PauseIcon(
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play voice message",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.widthIn(min = 120.dp)) {
                val progress = when {
                    isThisPlaying && playerState.durationMs > 0 ->
                        playerState.positionMs.toFloat() / playerState.durationMs
                    sending && transfer != null && transfer.totalChunks > 0 ->
                        transfer.completedChunks.toFloat() / transfer.totalChunks
                    else -> 0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isThisPlaying) {
                            formatDuration(playerState.positionMs.toLong())
                        } else {
                            formatDuration(message.durationMs.toLong())
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = formatBubbleTime(message.timestampMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (message.isMine) {
                        Spacer(Modifier.width(4.dp))
                        DeliveryTicks(state = message.deliveryState, size = 14.dp)
                    }
                }
                if (sending && transfer != null) {
                    Text(
                        text = "Sending ${transfer.completedChunks}/${transfer.totalChunks} parts…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (failed) {
            Text(
                text = "Not delivered — tap to retry",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 2.dp, end = 4.dp)
                    .clickable(onClick = onRetry)
            )
        }
    }
}

internal fun deliveryLabel(state: String): String = when (state) {
    "QUEUED" -> "Sending…"
    "RELAYED" -> "On its way"
    "DELIVERED" -> "Delivered"
    "EXPIRED" -> "Not delivered"
    "FAILED" -> "Not delivered"
    else -> state
}

private const val SIZE_LIMIT_BYTES = 590_000L

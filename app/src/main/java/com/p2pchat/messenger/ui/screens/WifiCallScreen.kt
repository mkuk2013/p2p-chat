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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.p2pchat.messenger.AppContainer
import com.p2pchat.messenger.ui.components.MicIcon
import com.p2pchat.messenger.ui.components.PeerAvatar
import com.p2pchat.messenger.ui.components.SpeakerIcon
import com.p2pchat.messenger.ui.components.formatDuration
import com.p2pchat.messenger.wifidirect.WifiCallState
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Full-screen overlay for Wi-Fi Direct WebRTC calls (voice + video).
 * Renders [WifiCallState] exactly — an "active" call screen exists only
 * after the WebRTC connection itself reports CONNECTED.
 */
@Composable
fun WifiCallOverlay(container: AppContainer, modifier: Modifier = Modifier) {
    val controller = container.wifiCallController ?: return
    val state by controller.state.collectAsState()
    val context = LocalContext.current

    val acceptPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        fun granted(perm: String) = result[perm] == true ||
            ContextCompat.checkSelfPermission(context, perm) ==
            PackageManager.PERMISSION_GRANTED
        val s = controller.state.value as? WifiCallState.Incoming
        if (granted(Manifest.permission.RECORD_AUDIO) &&
            (s?.video != true || granted(Manifest.permission.CAMERA))
        ) {
            controller.accept()
        }
    }

    fun acceptWithPermissions() {
        val s = controller.state.value as? WifiCallState.Incoming ?: return
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (s.video) perms += Manifest.permission.CAMERA
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            controller.accept()
        } else {
            acceptPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    when (val s = state) {
        is WifiCallState.Idle -> Unit
        is WifiCallState.Preparing -> SimpleCallSurface(
            name = s.peerName ?: "P2P Chat user",
            peerId = s.peerId,
            status = if (s.video) "Starting video call…" else "Starting call…",
            modifier = modifier,
        ) { EndControl { controller.end() } }
        is WifiCallState.Ringing -> SimpleCallSurface(
            name = s.peerName ?: "P2P Chat user",
            peerId = s.peerId,
            status = "Ringing…",
            modifier = modifier,
        ) { EndControl { controller.end() } }
        is WifiCallState.Connecting -> SimpleCallSurface(
            name = s.peerName ?: "P2P Chat user",
            peerId = s.peerId,
            status = "Connecting…",
            modifier = modifier,
        ) { EndControl { controller.end() } }
        is WifiCallState.Incoming -> SimpleCallSurface(
            name = s.peerName ?: "P2P Chat user",
            peerId = s.peerId,
            status = if (s.video) "Incoming video call" else "Incoming voice call",
            modifier = modifier,
        ) {
            Row(horizontalArrangement = Arrangement.Center) {
                EndControl { controller.reject() }
                Spacer(Modifier.size(48.dp))
                AcceptControl { acceptWithPermissions() }
            }
        }
        is WifiCallState.Active -> {
            var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(s.connectedSinceMs) {
                while (true) {
                    nowMs = System.currentTimeMillis()
                    delay(500)
                }
            }
            if (s.video) {
                VideoCallSurface(
                    container = container,
                    name = s.peerName ?: "P2P Chat user",
                    status = formatDuration(nowMs - s.connectedSinceMs),
                    cameraOn = s.cameraOn,
                    modifier = modifier,
                ) {
                    CallControls(
                        muted = s.muted,
                        speakerOn = s.speakerOn,
                        video = true,
                        cameraOn = s.cameraOn,
                        onMute = { controller.toggleMute() },
                        onCamera = { controller.toggleCamera() },
                        onSwitch = { controller.switchCamera() },
                        onSpeaker = { controller.toggleSpeaker() },
                        onEnd = { controller.end() },
                    )
                }
            } else {
                SimpleCallSurface(
                    name = s.peerName ?: "P2P Chat user",
                    peerId = s.peerId,
                    status = formatDuration(nowMs - s.connectedSinceMs),
                    modifier = modifier,
                ) {
                    CallControls(
                        muted = s.muted,
                        speakerOn = s.speakerOn,
                        video = false,
                        cameraOn = false,
                        onMute = { controller.toggleMute() },
                        onCamera = {},
                        onSwitch = {},
                        onSpeaker = { controller.toggleSpeaker() },
                        onEnd = { controller.end() },
                    )
                }
            }
        }
        is WifiCallState.Ended -> AlertDialog(
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
        is WifiCallState.Failed -> AlertDialog(
            onDismissRequest = { controller.dismissTerminal() },
            title = { Text("Couldn't call") },
            text = {
                Text(
                    com.p2pchat.messenger.core.call.CallReasons.plainReason(s.reason)
                        ?: "Something went wrong."
                )
            },
            confirmButton = {
                val retryPeerId = container.lastWifiCallPeerId()
                if (retryPeerId != null) {
                    TextButton(onClick = {
                        controller.dismissTerminal()
                        container.startWifiCall(retryPeerId, container.lastWifiCallVideo())
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
private fun VideoSurface(
    track: VideoTrack?,
    eglContext: EglBase.Context,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    if (track == null) {
        Box(modifier = modifier.background(Color.Black))
        return
    }
    key(track) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(eglContext, null)
                    setEnableHardwareScaler(true)
                    setMirror(mirror)
                    track.addSink(this)
                }
            },
            onRelease = { view ->
                runCatching { track.removeSink(view) }
                runCatching { view.release() }
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun VideoCallSurface(
    container: AppContainer,
    name: String,
    status: String,
    cameraOn: Boolean,
    modifier: Modifier = Modifier,
    controls: @Composable () -> Unit,
) {
    val controller = container.wifiCallController ?: return
    val remoteTrack by controller.remoteVideo.collectAsState()
    val localTrack by controller.localVideo.collectAsState()
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(
            track = remoteTrack,
            eglContext = controller.eglContext,
            mirror = false,
            modifier = Modifier.fillMaxSize(),
        )
        if (cameraOn) {
            VideoSurface(
                track = localTrack,
                eglContext = controller.eglContext,
                mirror = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(width = 110.dp, height = 150.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = name, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Direct · offline — Wi-Fi between the phones",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(20.dp))
            controls()
        }
    }
}

@Composable
private fun SimpleCallSurface(
    name: String,
    peerId: String,
    status: String,
    modifier: Modifier = Modifier,
    controls: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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
            text = "Direct · offline — Wi-Fi between the phones",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        controls()
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun CallControls(
    muted: Boolean,
    speakerOn: Boolean,
    video: Boolean,
    cameraOn: Boolean,
    onMute: () -> Unit,
    onCamera: () -> Unit,
    onSwitch: () -> Unit,
    onSpeaker: () -> Unit,
    onEnd: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundControl(
            label = if (muted) "Unmute" else "Mute",
            active = muted,
            onClick = onMute,
        ) {
            MicIcon(
                tint = if (muted) Color.White.copy(alpha = 0.55f) else Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        if (video) {
            Spacer(Modifier.size(16.dp))
            RoundControl(
                label = if (cameraOn) "Camera off" else "Camera on",
                active = !cameraOn,
                onClick = onCamera,
            ) {
                Icon(
                    imageVector = Icons.Filled.Face,
                    contentDescription = "Camera",
                    tint = if (cameraOn) Color.White else Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.size(16.dp))
            RoundControl(label = "Flip", active = false, onClick = onSwitch) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Switch camera",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        EndControl(onClick = onEnd)
        Spacer(Modifier.size(16.dp))
        RoundControl(
            label = "Speaker",
            active = speakerOn,
            onClick = onSpeaker,
        ) {
            SpeakerIcon(tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun RoundControl(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EndControl(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0xFFD93025))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Call,
            contentDescription = "End call",
            tint = Color.White,
            modifier = Modifier.size(28.dp).rotate(135f),
        )
    }
}

@Composable
private fun AcceptControl(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Call,
            contentDescription = "Answer call",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(28.dp),
        )
    }
}

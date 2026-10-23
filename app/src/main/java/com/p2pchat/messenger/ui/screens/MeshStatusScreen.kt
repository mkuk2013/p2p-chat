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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.AppContainer
import com.p2pchat.messenger.core.transport.LinkTestMath
import com.p2pchat.messenger.core.transport.LinkTestResult
import com.p2pchat.messenger.service.MeshForegroundService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * "Connection details": a plain summary first, the connection-speed
 * check, then the technical truth for troubleshooting (live transport
 * state, the router's peer table, the Room outbox). Every value comes
 * from real state — no sample peers or invented counts anywhere.
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
                text = "Connection details",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))

        // Plain summary first — the one line most people need.
        Text(
            text = when {
                !container.isBluetoothEnabled() ->
                    "Bluetooth is off — turn it on so people nearby can find you."
                connectedCount > 0 ->
                    "You are connected to $connectedCount " +
                        (if (connectedCount == 1) "person" else "people") + " nearby."
                else ->
                    "No one is connected right now. Open People and tap " +
                        "a name to connect."
            },
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "The details below are for troubleshooting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        LinkSpeedCheck(container = container, transport = transport)

        Spacer(Modifier.height(20.dp))
        StatusRow("Bluetooth", if (container.isBluetoothEnabled()) "On" else "Off")
        StatusRow("Looking for people", if (scanningState?.value == true) "Yes" else "No")
        StatusRow("Visible to others", if (advertisingState?.value == true) "Yes" else "No")
        StatusRow("Connected now", connectedCount.toString())
        StatusRow("Messages waiting to send", outboxPending.toString())
        StatusRow("People you know (saved)", knownPeers.size.toString())

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Help pass messages in the background",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Pass other people's messages along even with " +
                        "the screen locked. Android shows a small notice " +
                        "while this is on; turn it off any time.",
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
            text = "Who this phone has heard from",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        if (tablePeers.isEmpty()) {
            Text(
                text = "No one yet. People appear here once your phones " +
                    "have introduced themselves, or when a message passes " +
                    "through this phone.",
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
                        text = peer.displayName ?: "P2P Chat user",
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
            text = "How to read this: a phone can only ever list people it " +
                "really heard from. “Hops away 1” is someone right next to " +
                "you; larger numbers are people whose messages arrived " +
                "through other phones in between.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Call diary (v1.3.1): every call tap and its outcome, written
        // by the call paths themselves — the on-device evidence when a
        // call "does nothing", so the failing branch names itself.
        val callDebug by container.callDebug.collectAsState()
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Call diary",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(6.dp))
        if (callDebug.isEmpty()) {
            Text(
                text = "No call attempts yet in this session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            callDebug.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

/** UI state of the connection-speed check. */
private sealed interface LinkTestUiState {
    data object Idle : LinkTestUiState
    data object Running : LinkTestUiState
    data object Failed : LinkTestUiState
    data class Done(val result: LinkTestResult, val peerName: String) : LinkTestUiState
}

/**
 * The Bluetooth capability test (master spec §7): real timed test data
 * over the live link to a connected phone — measured throughput and
 * round-trip time, plus a plain verdict. When no link is up, the
 * honest instruction is shown instead of any number.
 */
@Composable
private fun LinkSpeedCheck(
    container: AppContainer,
    transport: com.p2pchat.messenger.ble.BleTransport?
) {
    val scope = rememberCoroutineScope()
    var testState by remember { mutableStateOf<LinkTestUiState>(LinkTestUiState.Idle) }
    val readyAddress = transport?.readyAddresses()?.firstOrNull()

    Text(
        text = "Connection speed",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Sends real test data to a connected phone and times it. " +
            "Nothing here is guessed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    if (transport == null || readyAddress == null) {
        Text(
            text = "Connect to a person first — open People nearby, tap " +
                "their name, then come back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val peerName = remember(readyAddress) {
        container.peerTable.all().firstOrNull { it.address == readyAddress }
            ?.displayName ?: "the connected phone"
    }

    when (val state = testState) {
        LinkTestUiState.Idle -> Button(onClick = {
            testState = LinkTestUiState.Running
            scope.launch {
                val result = runCatching { transport.runLinkTest(readyAddress) }
                    .getOrNull()
                testState = if (result != null) {
                    LinkTestUiState.Done(result, peerName)
                } else {
                    LinkTestUiState.Failed
                }
            }
        }) { Text("Check connection speed") }

        LinkTestUiState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 12.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = "Measuring — sending test data to $peerName…",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        LinkTestUiState.Failed -> Column {
            Text(
                text = "Couldn't measure — the connection was lost. " +
                    "Connect again and try once more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = { testState = LinkTestUiState.Idle }) {
                Text("Try again")
            }
        }

        is LinkTestUiState.Done -> Column {
            val result = state.result
            Text(
                text = "Speed: ${formatSpeed(result.throughputKBps)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Answer time: about ${result.averageRttMs.roundToInt()} ms",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (result.verdict) {
                    LinkTestMath.VoiceVerdict.VOICE_OK ->
                        "Voice calls could work on a link like this."
                    LinkTestMath.VoiceVerdict.TOO_SLOW ->
                        "Too slow for live voice — voice notes will " +
                            "still get through."
                    LinkTestMath.VoiceVerdict.UNSTEADY ->
                        "Fast enough, but answers come back slowly — " +
                            "live voice would keep pausing."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Video calls need internet — Bluetooth is too " +
                    "slow for video.",
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = { testState = LinkTestUiState.Idle }) {
                Text("Measure again")
            }
        }
    }
}

private fun formatSpeed(kBps: Double): String =
    if (kBps >= 1024.0) {
        String.format(Locale.getDefault(), "%.1f MB/s", kBps / 1024.0)
    } else {
        "${kBps.roundToInt()} KB/s"
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

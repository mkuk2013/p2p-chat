package com.p2pchat.messenger.wifidirect

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/** Honest states for a Wi-Fi Direct WebRTC call — the UI shows exactly
 *  these. Active is reached ONLY when the WebRTC connection itself
 *  reports CONNECTED; nothing here fakes a call. */
sealed interface WifiCallState {
    data object Idle : WifiCallState
    /** Caller: creating the Wi-Fi group. */
    data class Preparing(val peerId: String, val peerName: String?, val video: Boolean) : WifiCallState
    /** Caller: group up, invite sent, waiting for the callee to accept. */
    data class Ringing(val peerId: String, val peerName: String?, val video: Boolean) : WifiCallState
    /** Callee: an invite arrived; waiting for the user. */
    data class Incoming(val peerId: String, val peerName: String?, val video: Boolean) : WifiCallState
    /** Either side: joining / signalling / ICE negotiation in flight. */
    data class Connecting(val peerId: String, val peerName: String?, val video: Boolean) : WifiCallState
    data class Active(
        val peerId: String,
        val peerName: String?,
        val video: Boolean,
        val connectedSinceMs: Long,
        val muted: Boolean,
        val cameraOn: Boolean,
        val speakerOn: Boolean,
    ) : WifiCallState
    data class Ended(val reason: String) : WifiCallState
    data class Failed(val reason: String) : WifiCallState
}

private data class Invite(
    val callId: String,
    val fromPeerId: String,
    val video: Boolean,
    val ssid: String,
    val passphrase: String,
    val ownerIp: String,
    val port: Int,
)

/**
 * Orchestrates one offline voice/video call:
 *
 * ```
 * mesh (encrypted)          Wi-Fi Direct group         TCP + WebRTC
 * INVITE ───────────────►   callee joins as client ──► hello
 * ACCEPT ◄───────────────                             offer/answer/ICE
 * REJECT / BYE / CANCEL (any phase, either channel ends the call)
 * ```
 *
 * The mesh carries only the invitation (SSID + per-call passphrase of
 * the caller's fresh group). Everything else stays on the local link.
 */
class WifiCallController(
    context: Context,
    private val engine: RtcEngine,
    private val myPeerId: () -> String?,
    private val sendSignal: (peerId: String, text: String) -> Unit,
    private val nameForPeer: (peerId: String) -> String?,
    private val isBusyElsewhere: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val groupOwner = WifiDirectGroupOwner(appContext)
    private val joiner = WifiDirectJoiner(appContext)

    private val _state = MutableStateFlow<WifiCallState>(WifiCallState.Idle)
    val state: StateFlow<WifiCallState> = _state.asStateFlow()

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()
    val eglContext get() = engine.eglContext

    @Volatile
    private var callId: String? = null
    @Volatile
    private var invite: Invite? = null
    @Volatile
    private var isOwner = false
    @Volatile
    private var channel: SignalChannel? = null
    @Volatile
    private var session: RtcSession? = null
    @Volatile
    private var boundNetwork: Network? = null
    @Volatile
    private var remoteDescriptionSet = false
    private val pendingIce = mutableListOf<IceCandidate>()
    @Volatile
    private var phaseJob: Job? = null
    @Volatile
    private var disconnectJob: Job? = null

    // ------------------------------------------------------------- UI API

    fun startCall(peerId: String, video: Boolean) {
        if (_state.value !is WifiCallState.Idle) return
        if (myPeerId() == null) {
            _state.value = WifiCallState.Failed("Still setting up — try the call in a moment")
            return
        }
        if (!groupOwner.supported) {
            _state.value = WifiCallState.Failed("Wi-Fi Direct is not available on this phone")
            return
        }
        val id = UUID.randomUUID().toString()
        callId = id
        isOwner = true
        _state.value = WifiCallState.Preparing(peerId, nameForPeer(peerId), video)
        scope.launch {
            val group = groupOwner.createGroup()
            if (callId != id || _state.value !is WifiCallState.Preparing) return@launch
            if (group == null) {
                teardown()
                _state.value = WifiCallState.Failed(
                    groupOwner.lastError ?: "Couldn't start the Wi-Fi group"
                )
                return@launch
            }
            val ch = SignalChannel()
            channel = ch
            ch.listen(SignalChannel.PORT, tcpListener) {}
            val inviteJson = JSONObject()
                .put("k", "invite")
                .put("callId", id)
                .put("video", video)
                .put("ssid", group.ssid)
                .put("pass", group.passphrase)
                .put("ip", group.ownerIp)
                .put("port", SignalChannel.PORT)
            sendSignal(peerId, PREFIX + inviteJson.toString())
            _state.value = WifiCallState.Ringing(peerId, nameForPeer(peerId), video)
            armPhaseTimeout(RING_TIMEOUT_MS, id) {
                sendSignal(peerId, signalJson("cancel", id))
                teardown()
                _state.value = WifiCallState.Ended("No answer")
            }
        }
    }

    fun accept() {
        val s = _state.value as? WifiCallState.Incoming ?: return
        val inv = invite ?: return
        _state.value = WifiCallState.Connecting(s.peerId, s.peerName, s.video)
        sendSignal(s.peerId, signalJson("accept", inv.callId))
        armPhaseTimeout(CONNECT_TIMEOUT_MS, inv.callId) {
            teardown()
            _state.value = WifiCallState.Failed(
                "Couldn't connect — the call link didn't come up in time"
            )
        }
        joiner.join(
            ssid = inv.ssid,
            passphrase = inv.passphrase,
            onReady = { network ->
                if (callId != inv.callId) return@join
                boundNetwork = network
                if (network != null) {
                    runCatching { connectivity.bindProcessToNetwork(network) }
                }
                val ch = SignalChannel()
                channel = ch
                ch.connect(network, inv.ownerIp, inv.port, tcpListener) {
                    // Connected: identify ourselves, then build the
                    // answering session and wait for the offer.
                    ch.send(
                        JSONObject()
                            .put("t", "hello")
                            .put("peerId", myPeerId() ?: "")
                            .put("callId", inv.callId)
                    )
                    startSession(asOfferer = false, video = inv.video)
                }
            },
            onFailure = { reason ->
                if (callId != inv.callId) return@join
                sendSignal(s.peerId, signalJson("bye", inv.callId))
                teardown()
                _state.value = WifiCallState.Failed(reason)
            },
        )
    }

    fun reject() {
        val s = _state.value as? WifiCallState.Incoming ?: return
        invite?.let { sendSignal(s.peerId, signalJson("reject", it.callId)) }
        teardown()
        _state.value = WifiCallState.Idle
    }

    fun end() {
        val peer = activePeerId()
        val id = callId
        if (peer != null && id != null) {
            sendSignal(peer, signalJson("bye", id))
        }
        channel?.send(JSONObject().put("t", "bye"))
        teardown()
        _state.value = WifiCallState.Ended("Call ended")
    }

    fun dismissTerminal() {
        if (_state.value is WifiCallState.Ended || _state.value is WifiCallState.Failed) {
            _state.value = WifiCallState.Idle
        }
    }

    fun toggleMute() {
        val s = _state.value as? WifiCallState.Active ?: return
        val muted = !s.muted
        session?.setAudioEnabled(!muted)
        _state.value = s.copy(muted = muted)
    }

    fun toggleCamera() {
        val s = _state.value as? WifiCallState.Active ?: return
        val on = !s.cameraOn
        session?.setVideoEnabled(on)
        _state.value = s.copy(cameraOn = on)
    }

    fun switchCamera() {
        session?.switchCamera {}
    }

    fun toggleSpeaker() {
        val s = _state.value as? WifiCallState.Active ?: return
        val on = !s.speakerOn
        applySpeaker(on)
        _state.value = s.copy(speakerOn = on)
    }

    // ------------------------------------------------- mesh signal intake

    fun onMeshSignal(fromPeerId: String, text: String) {
        if (!text.startsWith(PREFIX)) return
        val json = runCatching { JSONObject(text.removePrefix(PREFIX)) }.getOrNull() ?: return
        val kind = json.optString("k")
        val id = json.optString("callId")
        when (kind) {
            "invite" -> {
                val busy = _state.value !is WifiCallState.Idle || isBusyElsewhere()
                if (busy) {
                    sendSignal(fromPeerId, signalJson("reject", id))
                    return
                }
                val inv = Invite(
                    callId = id,
                    fromPeerId = fromPeerId,
                    video = json.optBoolean("video", false),
                    ssid = json.optString("ssid"),
                    passphrase = json.optString("pass"),
                    ownerIp = json.optString("ip"),
                    port = json.optInt("port", SignalChannel.PORT),
                )
                if (inv.ssid.isEmpty() || inv.ownerIp.isEmpty()) return
                invite = inv
                callId = id
                isOwner = false
                _state.value = WifiCallState.Incoming(
                    fromPeerId, nameForPeer(fromPeerId), inv.video
                )
            }
            "accept" -> {
                val s = _state.value
                if ((s is WifiCallState.Ringing || s is WifiCallState.Preparing) &&
                    id == callId && fromPeerId == activePeerId()
                ) {
                    val peerName = nameForPeer(fromPeerId)
                    val video = when (s) {
                        is WifiCallState.Ringing -> s.video
                        is WifiCallState.Preparing -> s.video
                        else -> false
                    }
                    _state.value = WifiCallState.Connecting(fromPeerId, peerName, video)
                    armPhaseTimeout(CONNECT_TIMEOUT_MS, id) {
                        teardown()
                        _state.value = WifiCallState.Failed(
                            "Couldn't connect — the other phone didn't join the call link"
                        )
                    }
                }
            }
            "reject" -> {
                if (id == callId && fromPeerId == activePeerId() &&
                    _state.value !is WifiCallState.Idle
                ) {
                    teardown()
                    _state.value = WifiCallState.Ended("Declined")
                }
            }
            "cancel" -> {
                if (_state.value is WifiCallState.Incoming && id == callId) {
                    teardown()
                    _state.value = WifiCallState.Ended("No answer")
                }
            }
            "bye" -> {
                if (fromPeerId == activePeerId() && _state.value !is WifiCallState.Idle &&
                    _state.value !is WifiCallState.Ended && _state.value !is WifiCallState.Failed
                ) {
                    teardown()
                    _state.value = WifiCallState.Ended("Call ended")
                }
            }
        }
    }

    // ------------------------------------------------------- session glue

    private fun startSession(asOfferer: Boolean, video: Boolean) {
        val s = engine.newSession(sessionListener)
        session = s
        try {
            s.start(video)
        } catch (e: Exception) {
            Log.w(TAG, "rtc session start failed", e)
            teardown()
            _state.value = WifiCallState.Failed("The call engine couldn't start on this phone")
            return
        }
        _localVideo.value = s.localVideoTrack
        if (asOfferer) s.createOffer()
    }

    private val sessionListener = object : RtcSession.Listener {
        override fun onLocalDescription(description: SessionDescription) {
            val type = if (description.type == SessionDescription.Type.OFFER) "offer" else "answer"
            channel?.send(JSONObject().put("t", type).put("sdp", description.description))
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            channel?.send(
                JSONObject()
                    .put("t", "ice")
                    .put("mid", candidate.sdpMid ?: "")
                    .put("idx", candidate.sdpMLineIndex)
                    .put("cand", candidate.sdp)
            )
        }

        override fun onRemoteVideoTrack(track: VideoTrack) {
            _remoteVideo.value = track
        }

        override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> enterActive()
                PeerConnection.PeerConnectionState.FAILED -> {
                    if (_state.value is WifiCallState.Active) {
                        teardown()
                        _state.value = WifiCallState.Ended("Connection lost")
                    } else if (_state.value is WifiCallState.Connecting) {
                        teardown()
                        _state.value = WifiCallState.Failed(
                            "Couldn't connect — the phones couldn't agree on a link"
                        )
                    }
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    if (_state.value is WifiCallState.Active) {
                        disconnectJob?.cancel()
                        disconnectJob = scope.launch {
                            delay(DISCONNECT_GRACE_MS)
                            if (_state.value is WifiCallState.Active) {
                                teardown()
                                _state.value = WifiCallState.Ended("Connection lost")
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private val tcpListener = object : SignalChannel.Listener {
        override fun onMessage(json: JSONObject) {
            when (json.optString("t")) {
                "hello" -> {
                    // Caller side only: verify, then offer.
                    val s = _state.value
                    if (!isOwner || s !is WifiCallState.Connecting) return
                    if (json.optString("callId") != callId) return
                    if (json.optString("peerId") != s.peerId) return
                    startSession(asOfferer = true, video = s.video)
                }
                "offer" -> {
                    val s = session ?: return
                    s.setRemoteDescription(
                        SessionDescription(SessionDescription.Type.OFFER, json.optString("sdp"))
                    )
                    onRemoteDescriptionSet()
                    s.createAnswer()
                }
                "answer" -> {
                    val s = session ?: return
                    s.setRemoteDescription(
                        SessionDescription(SessionDescription.Type.ANSWER, json.optString("sdp"))
                    )
                    onRemoteDescriptionSet()
                }
                "ice" -> {
                    val candidate = IceCandidate(
                        json.optString("mid"),
                        json.optInt("idx"),
                        json.optString("cand"),
                    )
                    if (remoteDescriptionSet) {
                        session?.addIceCandidate(candidate)
                    } else {
                        synchronized(pendingIce) { pendingIce.add(candidate) }
                    }
                }
                "bye" -> {
                    if (_state.value !is WifiCallState.Idle &&
                        _state.value !is WifiCallState.Ended &&
                        _state.value !is WifiCallState.Failed
                    ) {
                        teardown()
                        _state.value = WifiCallState.Ended("Call ended")
                    }
                }
            }
        }

        override fun onClosed(reason: String) {
            val s = _state.value
            if (s is WifiCallState.Connecting || s is WifiCallState.Active) {
                teardown()
                _state.value = WifiCallState.Ended("Connection lost")
            }
        }
    }

    private fun onRemoteDescriptionSet() {
        remoteDescriptionSet = true
        val queued = synchronized(pendingIce) { pendingIce.toList().also { pendingIce.clear() } }
        queued.forEach { session?.addIceCandidate(it) }
    }

    private fun enterActive() {
        val s = _state.value
        val peer: Triple<String, String?, Boolean> = when (s) {
            is WifiCallState.Connecting -> Triple(s.peerId, s.peerName, s.video)
            is WifiCallState.Active -> return
            else -> return
        }
        phaseJob?.cancel()
        disconnectJob?.cancel()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val speaker = peer.third // video calls start on speaker; voice on earpiece
        applySpeaker(speaker)
        _state.value = WifiCallState.Active(
            peerId = peer.first,
            peerName = peer.second,
            video = peer.third,
            connectedSinceMs = System.currentTimeMillis(),
            muted = false,
            cameraOn = peer.third,
            speakerOn = speaker,
        )
    }

    // ------------------------------------------------------------ internals

    private fun activePeerId(): String? = when (val s = _state.value) {
        is WifiCallState.Preparing -> s.peerId
        is WifiCallState.Ringing -> s.peerId
        is WifiCallState.Incoming -> s.peerId
        is WifiCallState.Connecting -> s.peerId
        is WifiCallState.Active -> s.peerId
        else -> null
    }

    private fun signalJson(kind: String, id: String): String =
        PREFIX + JSONObject().put("k", kind).put("callId", id).toString()

    private fun armPhaseTimeout(ms: Long, id: String, onTimeout: () -> Unit) {
        phaseJob?.cancel()
        phaseJob = scope.launch {
            delay(ms)
            if (callId == id) onTimeout()
        }
    }

    private fun applySpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val target = if (on) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (target != null) {
                audioManager.setCommunicationDevice(target)
            } else if (!on) {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    private fun teardown() {
        phaseJob?.cancel()
        disconnectJob?.cancel()
        runCatching { session?.dispose() }
        session = null
        runCatching { channel?.close() }
        channel = null
        if (isOwner) {
            groupOwner.removeGroup()
        } else {
            joiner.leave()
        }
        if (boundNetwork != null) {
            runCatching { connectivity.bindProcessToNetwork(null) }
            boundNetwork = null
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        _localVideo.value = null
        _remoteVideo.value = null
        invite = null
        callId = null
        isOwner = false
        remoteDescriptionSet = false
        synchronized(pendingIce) { pendingIce.clear() }
    }

    companion object {
        private const val TAG = "WifiCall"
        const val PREFIX = "WIFICALL:"
        const val RING_TIMEOUT_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 45_000L
        const val DISCONNECT_GRACE_MS = 6_000L
    }
}

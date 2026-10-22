package com.p2pchat.messenger.nearby

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.nearby.connection.Payload
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Honest call states — the UI renders exactly these, nothing rosier. */
sealed interface CallState {
    data object Idle : CallState
    /** Trying to establish the direct Nearby link + identity binding. */
    data class Linking(val peerId: String, val peerName: String?) : CallState
    /** Link bound, CALL:OFFER sent, waiting for the peer to answer. */
    data class Outgoing(val peerId: String, val peerName: String?) : CallState
    /** CALL:OFFER received from a bound peer; waiting for the user. */
    data class Incoming(val peerId: String, val peerName: String?) : CallState
    data class Active(
        val peerId: String,
        val peerName: String?,
        val connectedSinceMs: Long,
        val muted: Boolean,
        val speakerOn: Boolean,
    ) : CallState
    /** Terminal (until dismissed): carries the real reason. */
    data class Ended(val reason: String) : CallState
    data class Failed(val reason: String) : CallState
}

/**
 * Experimental 1:1 voice call over a Google Nearby Connections direct
 * link. Media format: raw PCM, 16 kHz mono 16-bit (~256 kbit/s) streamed
 * via Payload.Type.STREAM — no Opus codec exists in this app's dependency
 * graph, so PCM is what honestly fits; quality is voice-grade and the
 * bandwidth is fine for a point-to-point Wi-Fi link.
 *
 * A call can only target a peer whose Nearby endpoint is BOUND to its
 * peerId through the verified identity-payload exchange (the app
 * supplies the binding lookups). Endpoint names are never trusted.
 * Signalling is tiny BYTES payloads: CALL:OFFER / ACCEPT / REJECT / END.
 */
class NearbyCallController(
    context: Context,
    private val transport: NearbyTransport,
    private val boundEndpointForPeer: (peerId: String) -> String?,
    private val peerForEndpoint: (endpointId: String) -> String?,
    private val requestLink: (peerId: String) -> Unit,
    private val nameForPeer: (peerId: String) -> String?,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    @Volatile
    private var currentEndpointId: String? = null

    @Volatile
    private var phaseJob: Job? = null

    // --- audio engine (plain threads: blocking IO loops) ---
    @Volatile
    private var audioRunning = false
    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var playbackInput: InputStream? = null

    init {
        // If the direct link dies mid-call, the call is over — say so.
        scope.launch {
            transport.sessions.collect { sessions ->
                val endpoint = currentEndpointId ?: return@collect
                val s = _state.value
                if (s is CallState.Linking || s is CallState.Outgoing || s is CallState.Active) {
                    if (sessions[endpoint] == NearbyLinkState.DISCONNECTED ||
                        sessions[endpoint] == NearbyLinkState.FAILED
                    ) {
                        finishCall(notifyPeer = false, endState = CallState.Ended("Connection lost"))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- UI API

    fun startCall(peerId: String) {
        if (_state.value !is CallState.Idle) return
        if (!transport.available) {
            _state.value = CallState.Failed("Google Play services is not available on this device")
            return
        }
        _state.value = CallState.Linking(peerId, nameForPeer(peerId))
        requestLink(peerId)
        phaseJob?.cancel()
        phaseJob = scope.launch {
            // Wait for the identity-bound endpoint to appear.
            val deadline = System.currentTimeMillis() + LINK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val s = _state.value
                if (s !is CallState.Linking) return@launch
                val endpoint = boundEndpointForPeer(peerId)
                if (endpoint != null) {
                    currentEndpointId = endpoint
                    if (sendControl(endpoint, OFFER)) {
                        _state.value = CallState.Outgoing(peerId, nameForPeer(peerId))
                        startRingTimeout(peerId)
                    } else {
                        _state.value = CallState.Failed("Could not reach the peer on the direct link")
                    }
                    return@launch
                }
                delay(300)
            }
            if (_state.value is CallState.Linking) {
                _state.value = CallState.Failed(
                    "Peer not reachable on the direct link — they may be out of range, " +
                        "or Nearby is unavailable on their device",
                )
            }
        }
    }

    fun accept() {
        val s = _state.value as? CallState.Incoming ?: return
        val endpoint = boundEndpointForPeer(s.peerId) ?: currentEndpointId ?: run {
            _state.value = CallState.Failed("Direct link was lost")
            return
        }
        currentEndpointId = endpoint
        if (!sendControl(endpoint, ACCEPT)) {
            _state.value = CallState.Failed("Could not answer — direct link failed")
            return
        }
        enterActive(s.peerId, endpoint)
    }

    fun reject() {
        val endpoint = currentEndpointId
        if (endpoint != null) sendControl(endpoint, REJECT)
        finishCall(notifyPeer = false, endState = CallState.Idle)
    }

    fun end() {
        val endpoint = currentEndpointId
        if (endpoint != null) sendControl(endpoint, END)
        finishCall(notifyPeer = false, endState = CallState.Ended("Call ended"))
    }

    fun dismissTerminal() {
        if (_state.value is CallState.Ended || _state.value is CallState.Failed) {
            _state.value = CallState.Idle
        }
    }

    fun toggleMute() {
        val s = _state.value as? CallState.Active ?: return
        _state.value = s.copy(muted = !s.muted)
    }

    fun toggleSpeaker() {
        val s = _state.value as? CallState.Active ?: return
        val on = !s.speakerOn
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = on
        _state.value = s.copy(speakerOn = on)
    }

    // ------------------------------------------------- transport callbacks

    /** Called by the app layer for every non-mesh, non-identity BYTES
     *  payload that looks like call signalling. */
    fun onControlBytes(endpointId: String, text: String) {
        val peerId = peerForEndpoint(endpointId)
        when (text) {
            OFFER -> {
                if (peerId == null) return // unbound endpoint: never trust it
                if (_state.value is CallState.Idle) {
                    currentEndpointId = endpointId
                    _state.value = CallState.Incoming(peerId, nameForPeer(peerId))
                } else {
                    sendControl(endpointId, REJECT) // busy
                }
            }
            ACCEPT -> {
                val s = _state.value
                if (s is CallState.Outgoing && peerForEndpoint(endpointId) == s.peerId) {
                    currentEndpointId = endpointId
                    enterActive(s.peerId, endpointId)
                }
            }
            REJECT -> {
                val s = _state.value
                if ((s is CallState.Outgoing || s is CallState.Linking) &&
                    peerForEndpoint(endpointId) == activePeerId()
                ) {
                    finishCall(notifyPeer = false, endState = CallState.Ended("Declined"))
                }
            }
            END -> {
                val s = _state.value
                if (s !is CallState.Idle && peerForEndpoint(endpointId) == activePeerId()) {
                    finishCall(notifyPeer = false, endState = CallState.Ended("Call ended"))
                }
            }
        }
    }

    /** Called by the app layer when a STREAM payload arrives. */
    fun onAudioStream(endpointId: String, input: InputStream) {
        if (endpointId != currentEndpointId || _state.value !is CallState.Active) {
            runCatching { input.close() }
            return
        }
        playbackInput = input
        startPlayback(input)
    }

    // ------------------------------------------------------------ internals

    private fun activePeerId(): String? = when (val s = _state.value) {
        is CallState.Linking -> s.peerId
        is CallState.Outgoing -> s.peerId
        is CallState.Incoming -> s.peerId
        is CallState.Active -> s.peerId
        else -> null
    }

    private fun startRingTimeout(peerId: String) {
        phaseJob?.cancel()
        phaseJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            val s = _state.value
            if (s is CallState.Outgoing && s.peerId == peerId) {
                currentEndpointId?.let { sendControl(it, END) }
                finishCall(notifyPeer = false, endState = CallState.Ended("No answer"))
            }
        }
    }

    private fun enterActive(peerId: String, endpointId: String) {
        phaseJob?.cancel()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        _state.value = CallState.Active(
            peerId = peerId,
            peerName = nameForPeer(peerId),
            connectedSinceMs = System.currentTimeMillis(),
            muted = false,
            speakerOn = false,
        )
        startAudio(endpointId)
    }

    private fun sendControl(endpointId: String, message: String): Boolean =
        transport.sendBytes(endpointId, message.toByteArray(Charsets.UTF_8))

    @SuppressLint("MissingPermission") // RECORD_AUDIO is gated by the UI flow.
    private fun startAudio(endpointId: String) {
        audioRunning = true
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            pipeWrite = pipe[1]
            if (!transport.sendPayload(endpointId, Payload.fromStream(readSide))) {
                runCatching { readSide.close() }
                throw IllegalStateException("stream payload rejected")
            }
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "no microphone buffer available" }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_BYTES * 8),
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "microphone init failed" }
            val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            recordThread = Thread({
                val buffer = ByteArray(FRAME_BYTES)
                try {
                    recorder.startRecording()
                    while (audioRunning) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val muted = (_state.value as? CallState.Active)?.muted == true
                            if (!muted) out.write(buffer, 0, read)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "record loop ended", t)
                } finally {
                    runCatching { recorder.stop() }
                    runCatching { recorder.release() }
                    runCatching { out.close() }
                }
            }, "p2p-call-record").apply { isDaemon = true; start() }
        } catch (t: Throwable) {
            Log.w(TAG, "startAudio failed", t)
            currentEndpointId?.let { sendControl(it, END) }
            finishCall(notifyPeer = false, endState = CallState.Failed("Microphone unavailable on this device"))
        }
    }

    private fun startPlayback(input: InputStream) {
        playThread?.interrupt()
        playThread = Thread({
            var track: AudioTrack? = null
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, FRAME_BYTES * 8))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                val buffer = ByteArray(FRAME_BYTES)
                while (audioRunning) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) track.write(buffer, 0, read)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "playback loop ended", t)
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
            }
        }, "p2p-call-play").apply { isDaemon = true; start() }
    }

    private fun finishCall(notifyPeer: Boolean, endState: CallState) {
        if (notifyPeer) currentEndpointId?.let { sendControl(it, END) }
        phaseJob?.cancel()
        audioRunning = false
        recordThread?.interrupt()
        playThread?.interrupt()
        recordThread = null
        playThread = null
        runCatching { pipeWrite?.close() }
        pipeWrite = null
        runCatching { playbackInput?.close() }
        playbackInput = null
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        currentEndpointId = null
        _state.value = endState
    }

    companion object {
        private const val TAG = "NearbyCall"
        const val OFFER = "CALL:OFFER"
        const val ACCEPT = "CALL:ACCEPT"
        const val REJECT = "CALL:REJECT"
        const val END = "CALL:END"
        const val PREFIX = "CALL:"

        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 640 // 20 ms of 16 kHz mono PCM16
        const val LINK_TIMEOUT_MS = 45_000L
        const val RING_TIMEOUT_MS = 45_000L
    }
}

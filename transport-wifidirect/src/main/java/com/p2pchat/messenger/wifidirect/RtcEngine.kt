package com.p2pchat.messenger.wifidirect

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRTC media engine for offline calls (Google's WebRTC, the same
 * real-time stack used by mainstream calling apps).
 *
 * Offline by construction: the [PeerConnection] is created with an
 * EMPTY ICE server list — no STUN, no TURN, no cloud. ICE gathers host
 * candidates on the phone's own interfaces and the only candidate pair
 * that can ever connect is the Wi-Fi Direct group link, because that is
 * the only network the two phones share. Media is DTLS-SRTP encrypted
 * by WebRTC itself.
 *
 * One factory + EglBase per process, created lazily; one [RtcSession]
 * per call.
 */
class RtcEngine(context: Context) {
    private val appContext = context.applicationContext

    val eglBase: EglBase = EglBase.create()
    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    @Volatile
    private var factory: PeerConnectionFactory? = null

    @Synchronized
    fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        val audioModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val created = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
        factory = created
        return created
    }

    fun newSession(listener: RtcSession.Listener): RtcSession =
        RtcSession(appContext, factory(), eglContext, listener)

    companion object {
        private const val TAG = "RtcEngine"
    }
}

class RtcSession(
    private val appContext: Context,
    private val factory: PeerConnectionFactory,
    private val eglContext: EglBase.Context,
    private val listener: Listener,
) {
    interface Listener {
        /** Local offer/answer is set and ready to send to the peer. */
        fun onLocalDescription(description: SessionDescription)
        fun onIceCandidate(candidate: IceCandidate)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onConnectionState(state: PeerConnection.PeerConnectionState)
    }

    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    val localVideoTrack: VideoTrack? get() = videoTrack

    @Volatile
    var isOfferer: Boolean = false
        private set

    /**
     * Create the peer connection and local media. Video is captured
     * only when [video] is true (a voice call never opens the camera).
     */
    fun start(video: Boolean, frontCamera: Boolean = true) {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val pc = factory.createPeerConnection(config, observer)
            ?: throw IllegalStateException("WebRTC could not create a connection")
        peerConnection = pc

        // -- audio (always) --
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("p2p-audio", audioSource)
        audioTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }

        // -- video (only when asked) --
        if (video) {
            startVideoCapture(frontCamera)
            videoTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }
        }
    }

    private fun startVideoCapture(frontCamera: Boolean) {
        val enumerator = Camera2Enumerator(appContext)
        val deviceName = enumerator.deviceNames.firstOrNull {
            if (frontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: enumerator.deviceNames.firstOrNull() ?: return
        val cap = enumerator.createCapturer(deviceName, null) ?: return
        capturer = cap
        val source = factory.createVideoSource(cap.isScreencast)
        videoSource = source
        surfaceHelper = SurfaceTextureHelper.create("p2p-capture", eglContext)
        cap.initialize(surfaceHelper, appContext, source.capturerObserver)
        cap.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
        videoTrack = factory.createVideoTrack("p2p-video", source)
    }

    fun createOffer() {
        isOfferer = true
        peerConnection?.createOffer(sdpObserver, MediaConstraints())
    }

    fun createAnswer() {
        isOfferer = false
        peerConnection?.createAnswer(sdpObserver, MediaConstraints())
    }

    fun setRemoteDescription(description: SessionDescription) {
        peerConnection?.setRemoteDescription(quietSdpObserver, description)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

    fun setVideoEnabled(enabled: Boolean) {
        videoTrack?.setEnabled(enabled)
    }

    fun switchCamera(onDone: (Boolean) -> Unit) {
        val cap = capturer as? CameraVideoCapturer
        if (cap == null) {
            onDone(false)
            return
        }
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) = onDone(true)
            override fun onCameraSwitchError(errorDescription: String) = onDone(false)
        })
    }

    fun hasVideo(): Boolean = videoTrack != null

    fun dispose() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching { videoTrack?.dispose() }
        videoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
    }

    // ------------------------------------------------------------ webrtc glue

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) =
            listener.onIceCandidate(candidate)

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) =
            listener.onConnectionState(newState)

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            val track = receiver.track()
            if (track is VideoTrack) listener.onRemoteVideoTrack(track)
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    /** createOffer/createAnswer path: set local, THEN tell the listener. */
    private val sdpObserver = object : org.webrtc.SdpObserver {
        private var pendingLocal: SessionDescription? = null

        override fun onCreateSuccess(description: SessionDescription) {
            pendingLocal = description
            peerConnection?.setLocalDescription(this, description)
        }

        override fun onSetSuccess() {
            pendingLocal?.let { listener.onLocalDescription(it) }
            pendingLocal = null
        }

        override fun onCreateFailure(error: String) {
            Log.w(TAG, "SDP create failed: $error")
        }

        override fun onSetFailure(error: String) {
            Log.w(TAG, "SDP set failed: $error")
        }
    }

    private val quietSdpObserver = object : org.webrtc.SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {
            Log.w(TAG, "remote SDP set failed: $error")
        }
    }

    companion object {
        private const val TAG = "RtcSession"
        private const val STREAM_ID = "p2p-stream"
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
        private const val CAPTURE_FPS = 30
    }
}

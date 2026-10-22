package com.p2pchat.messenger.ui.voice

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One shared player for all voice bubbles: starting a note stops whatever
 * was playing, and the UI observes [state] for play/pause + progress.
 */
class VoicePlayer {

    data class State(
        val path: String? = null,
        val playing: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    fun toggle(scope: CoroutineScope, path: String) {
        val current = _state.value
        if (current.path == path && player != null) {
            if (current.playing) pause() else resume(scope)
            return
        }
        start(scope, path)
    }

    private fun start(scope: CoroutineScope, path: String) {
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(path)
            mp.setOnCompletionListener {
                _state.value = _state.value.copy(playing = false, positionMs = 0)
                stopTicker()
            }
            mp.prepare()
            mp.start()
            _state.value = State(
                path = path,
                playing = true,
                positionMs = 0,
                durationMs = mp.duration.coerceAtLeast(0)
            )
            startTicker(scope)
        } catch (t: Throwable) {
            releasePlayer()
            _state.value = State()
        }
    }

    private fun pause() {
        val mp = player ?: return
        runCatching { mp.pause() }
        _state.value = _state.value.copy(
            playing = false,
            positionMs = runCatching { mp.currentPosition }.getOrDefault(0)
        )
        stopTicker()
    }

    private fun resume(scope: CoroutineScope) {
        val mp = player ?: return
        runCatching { mp.start() }
        _state.value = _state.value.copy(playing = true)
        startTicker(scope)
    }

    private fun startTicker(scope: CoroutineScope) {
        stopTicker()
        ticker = scope.launch {
            while (true) {
                val mp = player ?: break
                if (_state.value.playing) {
                    _state.value = _state.value.copy(
                        positionMs = runCatching { mp.currentPosition }.getOrDefault(0),
                        durationMs = runCatching { mp.duration }.getOrDefault(0).coerceAtLeast(0)
                    )
                }
                delay(200)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    fun release() {
        releasePlayer()
        _state.value = State()
    }

    private fun releasePlayer() {
        stopTicker()
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }
}

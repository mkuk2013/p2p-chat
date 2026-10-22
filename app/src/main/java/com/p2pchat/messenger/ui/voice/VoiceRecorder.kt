package com.p2pchat.messenger.ui.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * Records a voice note: AAC in an .m4a container, mono, 32 kbit/s at
 * 22,050 Hz — about 4 KB/s, so the app's 600 KB transfer cap allows
 * roughly 150 s. MediaRecorder's own max-duration (5 min) and
 * max-file-size guards back the UI's size polling; [onLimitReached]
 * fires when either guard trips so the UI can stop + send cleanly.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAtMs: Long = 0L

    var onLimitReached: (() -> Unit)? = null

    val isRecording: Boolean get() = recorder != null

    fun start(target: File): Boolean {
        stopInternal(discard = true)
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(SAMPLE_RATE)
            rec.setAudioEncodingBitRate(BIT_RATE)
            rec.setMaxDuration(MAX_DURATION_MS)
            rec.setMaxFileSize(MAX_FILE_BYTES)
            rec.setOutputFile(target.absolutePath)
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) {
                    onLimitReached?.invoke()
                }
            }
            rec.prepare()
            rec.start()
            recorder = rec
            output = target
            startedAtMs = SystemClock.elapsedRealtime()
            true
        } catch (t: Throwable) {
            runCatching { rec.release() }
            false
        }
    }

    fun elapsedMs(): Long =
        if (recorder != null) SystemClock.elapsedRealtime() - startedAtMs else 0L

    fun sizeBytes(): Long = output?.length() ?: 0L

    /** Stop and keep the file. Returns the measured duration, or -1 when
     *  the recording is unusable (too short / recorder error). */
    fun stop(): Long {
        val rec = recorder ?: return -1
        val file = output
        recorder = null
        output = null
        return try {
            rec.stop()
            rec.release()
            if (file == null || !file.exists() || file.length() == 0L) return -1
            durationOf(file)
        } catch (t: Throwable) {
            runCatching { rec.release() }
            -1
        }
    }

    fun cancel() = stopInternal(discard = true)

    private fun stopInternal(discard: Boolean) {
        val rec = recorder
        recorder = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        if (discard) output?.let { runCatching { it.delete() } }
        output = null
    }

    private fun durationOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: -1
        } catch (t: Throwable) {
            -1
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        const val SAMPLE_RATE = 22_050
        const val BIT_RATE = 32_000
        const val MAX_DURATION_MS = 300_000
        const val MAX_FILE_BYTES = 620_000L
    }
}

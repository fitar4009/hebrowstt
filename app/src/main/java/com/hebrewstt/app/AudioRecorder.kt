package com.hebrewstt.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

/**
 * Thin wrapper around [AudioRecord].
 * Reads PCM-16 mono audio at 16 kHz in 30 ms chunks and delivers each
 * chunk to [onChunk] on a dedicated background thread.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** 30 ms worth of samples at 16 kHz = 480 samples */
        const val CHUNK_SAMPLES = SAMPLE_RATE * 30 / 1000
    }

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")          // permission is checked in hasPermission()
    fun start(onChunk: (ShortArray) -> Unit) {
        if (running || !hasPermission()) return

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBuf, CHUNK_SAMPLES * 2 * 8) // at least 8 chunks of headroom

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufSize,
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        audioRecord = record
        running = true
        record.startRecording()

        thread = Thread({
            val buf = ShortArray(CHUNK_SAMPLES)
            while (running) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) onChunk(buf.copyOf(read))
            }
        }, "AudioRecorder").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        audioRecord?.apply { stop(); release() }
        audioRecord = null
    }
}

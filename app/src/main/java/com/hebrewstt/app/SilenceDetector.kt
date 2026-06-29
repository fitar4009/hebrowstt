package com.hebrewstt.app

import kotlin.math.sqrt

/**
 * Stateful silence detector that accumulates speech into a buffer and emits
 * a [FloatArray] (normalised to ±1.0) whenever a pause long enough to mark
 * end-of-utterance is detected.
 *
 * All calls to [process] must come from the same thread.
 *
 * @param silenceThreshold  RMS amplitude below which a chunk is "silent" (0–1).
 * @param silenceDurationMs Consecutive silence that triggers end-of-utterance (ms).
 */
class SilenceDetector(
    var silenceThreshold: Float = 0.020f,
    var silenceDurationMs: Int  = 1_500,
) {

    private companion object {
        /** Hard cap: never accumulate more than 30 s before forcing a flush. */
        const val MAX_SAMPLES = AudioRecorder.SAMPLE_RATE * 30
    }

    private val buffer = mutableListOf<ShortArray>()
    private var bufferedSamples = 0

    private var isSpeaking = false
    private var silenceStartMs = -1L

    val isCurrentlySpeaking: Boolean get() = isSpeaking

    /**
     * Feed one audio chunk. Returns a ready-to-transcribe [FloatArray] when
     * end-of-utterance is detected; returns `null` otherwise.
     */
    fun process(chunk: ShortArray): FloatArray? {
        val rms = rms(chunk)
        val nowMs = System.currentTimeMillis()

        return if (rms > silenceThreshold) {
            // ── speech ───────────────────────────────────────────────────
            isSpeaking = true
            silenceStartMs = -1L
            accumulateChunk(chunk)
            null
        } else {
            // ── silence ──────────────────────────────────────────────────
            if (!isSpeaking) return null                    // waiting for speech to start

            accumulateChunk(chunk)                          // keep trailing silence for context

            if (silenceStartMs < 0L) silenceStartMs = nowMs

            val silenceElapsed = nowMs - silenceStartMs
            val forceFlush = bufferedSamples >= MAX_SAMPLES

            if (silenceElapsed >= silenceDurationMs || forceFlush) {
                val result = flatten()
                reset()
                result
            } else {
                null
            }
        }
    }

    fun reset() {
        buffer.clear()
        bufferedSamples = 0
        isSpeaking = false
        silenceStartMs = -1L
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun accumulateChunk(chunk: ShortArray) {
        buffer.add(chunk.copyOf())
        bufferedSamples += chunk.size
    }

    private fun flatten(): FloatArray {
        val out = FloatArray(bufferedSamples)
        var offset = 0
        for (chunk in buffer) {
            for (s in chunk) out[offset++] = s / 32768.0f
        }
        return out
    }

    private fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return (sqrt(sum / samples.size) / 32768.0).toFloat()
    }
}

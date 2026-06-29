package com.k2fsa.sherpa.onnx

/**
 * Wraps the native offline-recogniser stream pointer.
 * [ptr] is package-visible so that [OfflineRecognizer] can pass it to JNI
 * calls without an extra accessor method.
 */
class OfflineStream(var ptr: Long = 0) {

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        acceptWaveform(ptr, samples, sampleRate)
    }

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    @Suppress("ProtectedInFinal")
    protected fun finalize() {
        release()
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun delete(ptr: Long)
}

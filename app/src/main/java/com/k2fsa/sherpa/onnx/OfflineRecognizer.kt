package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

// ---------------------------------------------------------------------------
// Config data classes – ALL field names must exactly match the JNI C++ accessors
// ---------------------------------------------------------------------------

data class OfflineLMConfig(
    var model: String = "",
    var scale: Float = 1.0f,
)

data class OfflineWhisperModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "en",
    var task: String = "transcribe",
    var tailPaddings: Int = 1000,
)

data class OfflineModelConfig(
    var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    var tokens: String = "",
    var modelType: String = "",
    var numThreads: Int = 2,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    var lmConfig: OfflineLMConfig = OfflineLMConfig(),
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

data class OfflineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
    val lang: String = "",
    val emotion: String = "",
    val event: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OfflineRecognizerResult
        return text == other.text && tokens.contentEquals(other.tokens)
    }

    override fun hashCode(): Int = 31 * text.hashCode() + tokens.contentHashCode()
}

// ---------------------------------------------------------------------------
// Main recogniser
// ---------------------------------------------------------------------------

class OfflineRecognizer(
    assetManager: AssetManager? = null,
    config: OfflineRecognizerConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    fun createStream(): OfflineStream {
        val streamPtr = createStream(ptr)
        return OfflineStream(streamPtr)
    }

    fun decode(stream: OfflineStream) {
        decode(ptr, stream.ptr)
    }

    fun getResult(stream: OfflineStream): OfflineRecognizerResult {
        val arr = getResult(ptr, stream.ptr)
        return OfflineRecognizerResult(
            text       = arr[0] as String,
            tokens     = arr[1] as Array<String>,
            timestamps = arr[2] as FloatArray,
            lang       = arr[3] as String,
            emotion    = arr[4] as String,
            event      = arr[5] as String,
        )
    }

    fun setConfig(config: OfflineRecognizerConfig) {
        setConfig(ptr, config)
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

    private external fun newFromAsset(assetManager: AssetManager, config: OfflineRecognizerConfig): Long
    private external fun newFromFile(config: OfflineRecognizerConfig): Long
    private external fun delete(ptr: Long)
    private external fun createStream(ptr: Long): Long
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun getResult(ptr: Long, streamPtr: Long): Array<Any>
    private external fun setConfig(ptr: Long, config: OfflineRecognizerConfig)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}

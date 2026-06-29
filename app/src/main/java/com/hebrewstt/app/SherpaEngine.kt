package com.hebrewstt.app

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps [OfflineRecognizer] for the app.
 *
 * The model directory must be placed at:
 *   <filesDir>/sherpa-onnx-whisper-tiny/
 *
 * Required files (quantised variant):
 *   tiny-encoder.int8.onnx
 *   tiny-decoder.int8.onnx
 *   tiny-tokens.txt
 *
 * Required native libs (place in app/src/main/jniLibs/<abi>/):
 *   libsherpa-onnx-jni.so
 *   libonnxruntime.so
 */
class SherpaEngine(private val context: Context) {

    enum class Variant { QUANTIZED, STANDARD }

    private var recognizer: OfflineRecognizer? = null
    private var currentConfig: OfflineRecognizerConfig? = null

    val modelDir: String
        get() = "${context.filesDir.absolutePath}/sherpa-onnx-whisper-tiny"

    /** Returns true if all required files for [variant] are present on disk. */
    fun modelFilesExist(variant: Variant = Variant.QUANTIZED): Boolean {
        val dir = File(modelDir)
        val (enc, dec) = encoderDecoder(variant)
        return dir.isDirectory &&
                File(dir, enc).exists() &&
                File(dir, dec).exists() &&
                File(dir, "tiny-tokens.txt").exists()
    }

    /**
     * Initialises the recogniser on [Dispatchers.IO]. Safe to call from Main.
     * Returns true on success, false if files are missing or initialisation fails.
     */
    suspend fun initialize(
        variant: Variant = Variant.QUANTIZED,
        language: String = "he",
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            recognizer?.release()
            recognizer = null

            if (!modelFilesExist(variant)) {
                Log.e(TAG, "Model files missing in $modelDir for variant $variant")
                return@withContext false
            }

            val cfg = buildConfig(variant, language)
            currentConfig = cfg
            recognizer = OfflineRecognizer(assetManager = null, config = cfg)
            Log.i(TAG, "OfflineRecognizer initialised (language=$language, variant=$variant)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Initialisation failed: ${e.message}", e)
            false
        }
    }

    /**
     * Transcribes [samples] (FloatArray, 16 kHz, mono, ±1.0) on [Dispatchers.IO].
     * Returns the recognised text, or an empty string on error.
     */
    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.IO) {
        val rec = recognizer ?: return@withContext ""
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples = samples, sampleRate = AudioRecorder.SAMPLE_RATE)
            rec.decode(stream)
            rec.getResult(stream).text.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.message}", e)
            ""
        } finally {
            stream.release()
        }
    }

    /**
     * Updates language/task without rebuilding the full recogniser (fast path).
     * Only call after a successful [initialize].
     */
    fun setLanguage(language: String) {
        val rec = recognizer ?: return
        val cfg = currentConfig ?: return
        val updated = cfg.copy(
            modelConfig = cfg.modelConfig.copy(
                whisper = cfg.modelConfig.whisper.copy(language = language)
            )
        )
        currentConfig = updated
        rec.setConfig(updated)
    }

    /** Must be called in onDestroy to free native memory. */
    fun release() {
        recognizer?.release()
        recognizer = null
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun buildConfig(variant: Variant, language: String): OfflineRecognizerConfig {
        val (enc, dec) = encoderDecoder(variant)
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$modelDir/$enc",
                    decoder = "$modelDir/$dec",
                    language = language,
                    task = "transcribe",
                    tailPaddings = 1_000,
                ),
                tokens = "$modelDir/tiny-tokens.txt",
                modelType = "whisper",
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
    }

    private fun encoderDecoder(variant: Variant): Pair<String, String> = when (variant) {
        Variant.QUANTIZED -> "tiny-encoder.int8.onnx" to "tiny-decoder.int8.onnx"
        Variant.STANDARD  -> "tiny-encoder.onnx"      to "tiny-decoder.onnx"
    }

    companion object {
        private const val TAG = "SherpaEngine"
    }
}

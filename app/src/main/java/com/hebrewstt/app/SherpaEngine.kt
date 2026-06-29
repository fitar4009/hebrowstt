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

class SherpaEngine(private val context: Context) {

    enum class Variant { QUANTIZED, STANDARD }

    private var recognizer: OfflineRecognizer? = null
    private var currentConfig: OfflineRecognizerConfig? = null

    val modelDir: String
        get() = "${context.filesDir.absolutePath}/sherpa-onnx-whisper-tiny"

    fun modelFilesExist(variant: Variant = Variant.QUANTIZED): Boolean {
        val dir = File(modelDir)
        val (enc, dec) = encoderDecoder(variant)
        return dir.isDirectory &&
                File(dir, enc).exists() &&
                File(dir, dec).exists() &&
                File(dir, "tiny-tokens.txt").exists()
    }

    suspend fun initialize(
        variant: Variant = Variant.QUANTIZED,
        language: String = "he",
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Always ensure the directory exists so the user can push model
            // files into it via adb without extra manual mkdir steps.
            val dir = File(modelDir)
            if (!dir.exists()) {
                val created = dir.mkdirs()
                Log.i(TAG, "Model directory created at $modelDir: $created")
            }

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

    fun release() {
        recognizer?.release()
        recognizer = null
    }

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

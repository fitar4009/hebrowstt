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

    sealed class InitResult {
        object Success : InitResult()
        data class ModelMissing(val diagnosis: String) : InitResult()
        data class NativeLibError(val message: String) : InitResult()
        data class UnknownError(val message: String) : InitResult()
    }

    private var recognizer: OfflineRecognizer? = null
    private var currentConfig: OfflineRecognizerConfig? = null

    /**
     * Resolved once and cached — guarantees the same path is used for directory
     * creation, file existence checks, and config building throughout the app's
     * lifetime, even if external-storage state fluctuates between calls.
     */
    val modelDir: String by lazy {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        "${base.absolutePath}/sherpa-onnx-whisper-tiny".also {
            Log.i(TAG, "modelDir resolved to: $it")
        }
    }

    fun modelFilesExist(variant: Variant = Variant.QUANTIZED): Boolean {
        val (enc, dec) = encoderDecoder(variant)
        return File(modelDir).isDirectory &&
                File(modelDir, enc).exists() &&
                File(modelDir, dec).exists() &&
                File(modelDir, "tiny-tokens.txt").exists()
    }

    /** Returns a human-readable list of which files are present / absent. */
    fun diagnose(variant: Variant = Variant.QUANTIZED): String {
        val (enc, dec) = encoderDecoder(variant)
        val expected = listOf(enc, dec, "tiny-tokens.txt")
        val dir = File(modelDir)
        val sb = StringBuilder()
        sb.appendLine("נתיב: $modelDir")
        sb.appendLine("תיקייה קיימת: ${dir.isDirectory}")
        for (name in expected) {
            val exists = File(dir, name).exists()
            sb.appendLine("  ${if (exists) "✓" else "✗"}  $name")
        }
        if (dir.isDirectory) {
            val found = dir.listFiles()?.map { it.name } ?: emptyList()
            if (found.isNotEmpty()) sb.appendLine("קבצים בתיקייה: ${found.joinToString()}")
        }
        return sb.toString().trimEnd()
    }

    suspend fun initialize(
        variant: Variant = Variant.QUANTIZED,
        language: String = "he",
    ): InitResult = withContext(Dispatchers.IO) {
        // Ensure directory exists so the user can push files into it
        File(modelDir).mkdirs()

        if (!modelFilesExist(variant)) {
            Log.e(TAG, "Model files missing.\n${diagnose(variant)}")
            return@withContext InitResult.ModelMissing(diagnose(variant))
        }

        recognizer?.release()
        recognizer = null

        try {
            val cfg = buildConfig(variant, language)
            currentConfig = cfg
            recognizer = OfflineRecognizer(assetManager = null, config = cfg)
            Log.i(TAG, "OfflineRecognizer ready (lang=$language, variant=$variant)")
            InitResult.Success
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library missing: ${e.message}")
            InitResult.NativeLibError(e.message ?: "UnsatisfiedLinkError")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            InitResult.UnknownError(e.message ?: e.javaClass.simpleName)
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

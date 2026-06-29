package com.hebrewstt.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.hebrewstt.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var silenceDetector: SilenceDetector
    private lateinit var sherpaEngine: SherpaEngine

    @Volatile private var modelReady = false
    @Volatile private var recording = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) initModel() else showStatus(Status.PERMISSION_DENIED)
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        audioRecorder   = AudioRecorder(this)
        silenceDetector = SilenceDetector()
        sherpaEngine    = SherpaEngine(this)

        binding.btnClear.setOnClickListener {
            binding.tvTranscript.text = ""
        }

        binding.btnRetry.setOnClickListener {
            binding.btnRetry.visibility = View.GONE
            initModel()
        }

        checkPermissionAndInit()
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        if (modelReady && !recording) startRecording()
    }

    override fun onPause() {
        super.onPause()
        stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        sherpaEngine.release()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── init flow ────────────────────────────────────────────────────────────

    private fun checkPermissionAndInit() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            initModel()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun initModel() {
        showStatus(Status.INITIALIZING)
        binding.btnRetry.visibility = View.GONE

        lifecycleScope.launch {
            when (val result = sherpaEngine.initialize()) {
                is SherpaEngine.InitResult.Success -> {
                    modelReady = true
                    startRecording()
                }
                is SherpaEngine.InitResult.ModelMissing -> {
                    showStatus(Status.MODEL_MISSING)
                    binding.tvTranscript.text =
                        getString(R.string.model_missing_instructions, result.diagnosis)
                    binding.btnRetry.visibility = View.VISIBLE
                }
                is SherpaEngine.InitResult.NativeLibError -> {
                    showStatus(Status.MODEL_MISSING)
                    binding.tvTranscript.text =
                        getString(R.string.native_lib_error, result.message)
                    binding.btnRetry.visibility = View.GONE   // retrying won't help
                }
                is SherpaEngine.InitResult.UnknownError -> {
                    showStatus(Status.MODEL_MISSING)
                    binding.tvTranscript.text =
                        getString(R.string.unknown_init_error, result.message)
                    binding.btnRetry.visibility = View.VISIBLE
                }
            }
        }
    }

    // ── recording pipeline ───────────────────────────────────────────────────

    private fun startRecording() {
        if (recording || !modelReady) return
        recording = true
        silenceDetector.reset()
        showStatus(Status.LISTENING)

        var wasSpeaking = false

        audioRecorder.start { chunk ->
            val utterance = silenceDetector.process(chunk)

            if (utterance != null) {
                wasSpeaking = false
                runOnUiThread { showStatus(Status.TRANSCRIBING) }

                lifecycleScope.launch(Dispatchers.IO) {
                    val text = sherpaEngine.transcribe(utterance)
                    withContext(Dispatchers.Main) {
                        if (text.isNotBlank()) appendTranscript(text)
                        if (recording) showStatus(Status.LISTENING)
                    }
                }
            } else {
                val speaking = silenceDetector.isCurrentlySpeaking
                if (speaking != wasSpeaking) {
                    wasSpeaking = speaking
                    runOnUiThread {
                        showStatus(if (speaking) Status.RECORDING_SPEECH else Status.LISTENING)
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        audioRecorder.stop()
        silenceDetector.reset()
        if (modelReady) showStatus(Status.IDLE)
    }

    // ── settings ─────────────────────────────────────────────────────────────

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        silenceDetector.silenceThreshold = prefs.getInt("silence_threshold", 20) / 1000f
        silenceDetector.silenceDurationMs = prefs.getInt("silence_duration_ms", 1500)
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    private fun appendTranscript(text: String) {
        val current = binding.tvTranscript.text.toString()
        val joined  = if (current.isNotEmpty()) "$current\n$text" else text
        binding.tvTranscript.text = joined
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showStatus(s: Status) {
        val (labelRes, colorRes) = when (s) {
            Status.IDLE              -> R.string.status_idle              to R.color.status_idle
            Status.INITIALIZING      -> R.string.status_initializing      to R.color.status_idle
            Status.LISTENING         -> R.string.status_listening         to R.color.status_listening
            Status.RECORDING_SPEECH  -> R.string.status_recording         to R.color.status_recording
            Status.TRANSCRIBING      -> R.string.status_transcribing      to R.color.status_transcribing
            Status.MODEL_MISSING     -> R.string.status_model_missing     to R.color.status_error
            Status.PERMISSION_DENIED -> R.string.status_permission_denied to R.color.status_error
        }
        binding.tvStatus.text = getString(labelRes)
        binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, colorRes))
    }

    private enum class Status {
        IDLE, INITIALIZING, LISTENING, RECORDING_SPEECH, TRANSCRIBING,
        MODEL_MISSING, PERMISSION_DENIED
    }
}

package dev.pegoku.androiduse.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.pegoku.androiduse.device.Device
import dev.pegoku.androiduse.shell.ShizukuShell
import dev.pegoku.androiduse.shell.ShizukuState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * The assistant overlay, opened by the system's assist gesture (long-press home / power) once
 * Android Use is the default digital assistant. It floats over the current app like Gemini.
 */
class AssistantActivity : ComponentActivity() {

    private var screen by mutableStateOf<SharedScreen?>(null)
    private var revealed by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var partialSpeech by mutableStateOf("")
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { ChatEngine.minimize.collect { finish() } }
        if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_RESUMED, false)) captureThenReveal() else reveal()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * Grabs what is on screen before drawing anything, so the user can share it with the first
     * question. The window stays empty (and invisible) until then.
     */
    private fun captureThenReveal() {
        if (ShizukuShell.state.value != ShizukuState.Ready) return reveal()
        lifecycleScope.launch {
            val capture = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { runCatching { Device(this@AssistantActivity).captureScreen() }.getOrNull() }
            if (capture != null) {
                val (bitmap, jpeg) = capture
                screen = SharedScreen(thumbnail = bitmap, jpegBase64 = jpeg, text = "", app = null)
            }
            reveal()
        }
    }

    private fun reveal() {
        if (revealed) return
        revealed = true
        setContent {
            AssistantOverlay(
                screen = screen,
                listening = listening,
                partialSpeech = partialSpeech,
                onSend = { text, share -> ChatEngine.send(this, text, if (share) screen else null) },
                onMic = ::toggleListening,
                onDismiss = ::finish,
                onCopy = { text ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Android Use", text))
                },
                onSpeak = ::speak,
            )
        }
    }

    private fun toggleListening() {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            partialSpeech = "Speech recognition isn't available on this device"
            return
        }
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; partialSpeech = "" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false; partialSpeech = "" }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partialSpeech = it }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                partialSpeech = ""
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ChatEngine.send(this@AssistantActivity, it, null) }
            }
        })
        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleListening()
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine != null) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
            return
        }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
            }
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESUMED = "resumed"
        private const val REQUEST_AUDIO = 7
        private const val CAPTURE_TIMEOUT_MS = 1_500L

        fun intent(context: Context): Intent =
            Intent(context, AssistantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RESUMED, true)
    }
}

package dev.pegoku.androiduse.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Voice input for the chat's mic button, shared by the app and the overlay. */
class SpeechInput(private val activity: ComponentActivity) {
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    private var recognizer: SpeechRecognizer? = null
    private var pending: ((String) -> Unit)? = null

    fun toggle(onResult: (String) -> Unit) {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pending = onResult
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            partial = "Speech recognition isn't available on this device"
            return
        }
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(activity).also { recognizer = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; partial = "" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false; partial = "" }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                partial = ""
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(onResult)
            }
        })
        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
    }

    /** Call from the activity's onRequestPermissionsResult; starts listening once the mic is allowed. */
    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_AUDIO) return
        val onResult = pending ?: return
        pending = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggle(onResult)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private companion object {
        const val REQUEST_AUDIO = 7
    }
}

/** Reads a reply aloud with the system's text-to-speech engine. */
class Speaker(private val activity: ComponentActivity) {
    private var tts: TextToSpeech? = null

    fun speak(text: String) {
        val engine = tts
        if (engine != null) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
            return
        }
        tts = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
            }
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}

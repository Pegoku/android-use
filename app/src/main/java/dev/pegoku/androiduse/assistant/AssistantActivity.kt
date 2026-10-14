package dev.pegoku.androiduse.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.pegoku.androiduse.agent.TaskRunner
import dev.pegoku.androiduse.device.Device
import dev.pegoku.androiduse.shell.ShizukuShell
import dev.pegoku.androiduse.shell.ShizukuState
import dev.pegoku.androiduse.ui.MainActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The assistant overlay, opened by the system's assist gesture (long-press home / power) once
 * Android Use is the default digital assistant. It floats over the current app like Gemini.
 */
class AssistantActivity : ComponentActivity() {

    private var screen by mutableStateOf<SharedScreen?>(null)
    private var revealed by mutableStateOf(false)
    private val speech = SpeechInput(this)
    private val speaker = Speaker(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent bars whose icon color follows the system theme, like the overlay itself.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
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
                listening = speech.listening,
                partialSpeech = speech.partial,
                onSend = { text, share -> ChatEngine.send(this, text, if (share) screen else null) },
                onMic = { speech.toggle { ChatEngine.send(this, it, null) } },
                onDismiss = ::finish,
                onOpenApp = {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                },
                onCopy = { text ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Android Use", text))
                },
                onSpeak = speaker::speak,
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        speech.onPermissionResult(requestCode, grantResults)
    }

    // The overlay covers the screen the agent works on, so a running task waits while it's in front.
    override fun onResume() {
        super.onResume()
        TaskRunner.setPaused(true)
    }

    override fun onPause() {
        TaskRunner.setPaused(false)
        super.onPause()
    }

    override fun onDestroy() {
        speech.destroy()
        speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESUMED = "resumed"
        private const val CAPTURE_TIMEOUT_MS = 1_500L

        fun intent(context: Context): Intent =
            Intent(context, AssistantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RESUMED, true)
    }
}

package dev.pegoku.androiduse.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pegoku.androiduse.R
import dev.pegoku.androiduse.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

data class QuestionRequest(val id: Int, val question: String, val options: List<String>)

/**
 * Lets the agent ask the owner something mid-task (missing information, a choice, something that
 * isn't working). Answer from the notification (inline reply or a quick option), the app, or the
 * assistant overlay.
 */
object Questions {
    private const val CHANNEL = "questions"
    private const val ACTION = "dev.pegoku.androiduse.ANSWER"
    private const val EXTRA_ID = "id"
    private const val EXTRA_OPTION = "option"
    private const val KEY_REPLY = "reply"
    const val TIMEOUT_MS = 10 * 60_000L

    private val ids = AtomicInteger(500)
    private val pending = mutableMapOf<Int, CompletableDeferred<String>>()
    private val _current = MutableStateFlow<QuestionRequest?>(null)
    val current: StateFlow<QuestionRequest?> = _current.asStateFlow()

    /** Suspends until the owner answers; null if nobody answers in time. */
    suspend fun ask(context: Context, question: String, options: List<String>): String? {
        val request = QuestionRequest(ids.incrementAndGet(), question, options.filter { it.isNotBlank() }.take(4))
        val answer = CompletableDeferred<String>()
        synchronized(pending) { pending[request.id] = answer }
        _current.value = request
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Questions from tasks", NotificationManager.IMPORTANCE_HIGH))
        notifications.notify(request.id, notification(context, request))
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { answer.await() }
        } finally {
            synchronized(pending) { pending.remove(request.id) }
            if (_current.value?.id == request.id) _current.value = null
            notifications.cancel(request.id)
        }
    }

    fun answer(id: Int, text: String) {
        if (text.isBlank()) return
        synchronized(pending) { pending[id] }?.complete(text.trim())
    }

    private fun notification(context: Context, request: QuestionRequest): Notification {
        val reply = Notification.Action.Builder(
            null, "Reply",
            PendingIntent.getBroadcast(
                context, request.id,
                Intent(context, Receiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, request.id),
                // Mutable so the system can attach the typed reply.
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Your answer").build()).build()
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Android Use has a question")
            .setContentText(request.question)
            .setStyle(Notification.BigTextStyle().bigText(request.question))
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
        // Notifications show at most three actions: up to two quick options plus the free-text reply.
        request.options.take(2).forEachIndexed { index, option ->
            builder.addAction(
                Notification.Action.Builder(
                    null, option,
                    PendingIntent.getBroadcast(
                        context, request.id * 10 + index + 1,
                        Intent(context, Receiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, request.id).putExtra(EXTRA_OPTION, option),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build(),
            )
        }
        builder.addAction(reply)
        return builder.build()
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val id = intent.getIntExtra(EXTRA_ID, -1)
            val text = intent.getStringExtra(EXTRA_OPTION)
                ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                ?: return
            answer(id, text)
        }
    }
}

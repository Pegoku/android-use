package dev.pegoku.androiduse.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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

data class ApprovalRequest(val id: Int, val title: String, val detail: String)

/** Asks the owner to approve a sensitive action, via a notification and the in-app card. */
object Approvals {
    private const val CHANNEL = "approvals"
    private const val ACTION = "dev.pegoku.androiduse.APPROVAL"
    private const val EXTRA_ID = "id"
    private const val EXTRA_APPROVED = "approved"
    private const val TIMEOUT_MS = 3 * 60_000L

    private val ids = AtomicInteger(100)
    private val pending = mutableMapOf<Int, CompletableDeferred<Boolean>>()
    private val _current = MutableStateFlow<ApprovalRequest?>(null)
    val current: StateFlow<ApprovalRequest?> = _current.asStateFlow()

    /** Suspends until the owner answers; unanswered requests are denied after a few minutes. */
    suspend fun ask(context: Context, title: String, detail: String): Boolean {
        val request = ApprovalRequest(ids.incrementAndGet(), title, detail)
        val answer = CompletableDeferred<Boolean>()
        synchronized(pending) { pending[request.id] = answer }
        _current.value = request
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Approval requests", NotificationManager.IMPORTANCE_HIGH))
        notifications.notify(request.id, notification(context, request))
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { answer.await() } ?: false
        } finally {
            synchronized(pending) { pending.remove(request.id) }
            if (_current.value?.id == request.id) _current.value = null
            notifications.cancel(request.id)
        }
    }

    fun answer(id: Int, approved: Boolean) {
        synchronized(pending) { pending[id] }?.complete(approved)
    }

    private fun notification(context: Context, request: ApprovalRequest): Notification {
        fun action(approved: Boolean) = PendingIntent.getBroadcast(
            context, request.id * 2 + if (approved) 1 else 0,
            Intent(context, Receiver::class.java).setAction(ACTION)
                .putExtra(EXTRA_ID, request.id).putExtra(EXTRA_APPROVED, approved),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(request.title)
            .setContentText(request.detail)
            .setStyle(Notification.BigTextStyle().bigText(request.detail))
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "Approve", action(true)).build())
            .addAction(Notification.Action.Builder(null, "Deny", action(false)).build())
            .setOngoing(true)
            .build()
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            answer(intent.getIntExtra(EXTRA_ID, -1), intent.getBooleanExtra(EXTRA_APPROVED, false))
        }
    }
}

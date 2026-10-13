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
import dev.pegoku.androiduse.assistant.AssistantActivity
import dev.pegoku.androiduse.assistant.ChatEngine

/**
 * The notification that replaces the live update when a task ends. It says what happened and,
 * like a messaging app, lets the owner reply straight from it: a follow-up goes to the assistant
 * chat (which then opens with the answer), or they can start a fresh chat.
 */
object ResultNotification {
    const val CHANNEL = "result"
    const val ID = 2
    private const val ACTION_REPLY = "dev.pegoku.androiduse.RESULT_REPLY"
    private const val ACTION_NEW_CHAT = "dev.pegoku.androiduse.RESULT_NEW_CHAT"
    private const val KEY_REPLY = "reply"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Task results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(context: Context, task: String, result: AgentResult) {
        createChannel(context)
        context.getSystemService(NotificationManager::class.java).notify(ID, build(context, task, result))
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun build(context: Context, task: String, result: AgentResult): Notification {
        val title = if (result.success) "Done: ${clip(task, 60)}" else "Couldn't finish: ${clip(task, 60)}"
        val reply = Notification.Action.Builder(
            null, "Reply",
            PendingIntent.getBroadcast(
                context, 20,
                Intent(context, Receiver::class.java).setAction(ACTION_REPLY),
                // Mutable so the system can attach the typed reply.
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Follow up or give feedback").build()).build()
        val newChat = Notification.Action.Builder(
            null, "New chat",
            PendingIntent.getBroadcast(
                context, 21,
                Intent(context, Receiver::class.java).setAction(ACTION_NEW_CHAT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(result.summary)
            .setStyle(Notification.BigTextStyle().bigText(result.summary))
            .setCategory(Notification.CATEGORY_MESSAGE)
            // Opens the chat where this task lives, with the outcome and an input for follow-ups.
            .setContentIntent(PendingIntent.getActivity(context, 22, AssistantActivity.intent(context), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .addAction(reply)
            .addAction(newChat)
            .build()
    }

    private fun clip(text: String, max: Int) = text.replace('\n', ' ').let { if (it.length > max) it.take(max - 1) + "…" else it }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_REPLY -> {
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
                    if (text.isEmpty()) return
                    ChatEngine.send(context, text, null)
                }
                ACTION_NEW_CHAT -> ChatEngine.newChat()
                else -> return
            }
            dismiss(context)
            // Bring the chat up so the answer (or the empty new chat) is in front of the user.
            runCatching { context.startActivity(AssistantActivity.intent(context)) }
        }
    }
}

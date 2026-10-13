package dev.pegoku.androiduse.agent

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import dev.pegoku.androiduse.R
import dev.pegoku.androiduse.llm.CodexUsage
import dev.pegoku.androiduse.ui.MainActivity
import dev.pegoku.androiduse.assistant.AssistantActivity

/**
 * The ongoing notification for a running task. On Android 16+ it asks to be promoted to a
 * Live Update: a status-bar pill with a short status that expands into these details when tapped.
 */
object LiveNotification {
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun build(
        context: Context,
        channel: String,
        state: RunState,
        approval: ApprovalRequest?,
        question: QuestionRequest?,
        usage: CodexUsage?,
        stopIntent: PendingIntent,
    ): Notification {
        val status = when {
            question != null -> "Question: ${question.question}"
            TaskRunner.paused.value -> "Paused while you're in the assistant"
            approval != null -> "Waiting for your approval: ${approval.detail.substringBefore('\n')}"
            state.lastAction != null -> describe(state.lastAction)
            else -> state.phase.ifBlank { "Starting…" }
        }
        // The pill: step and how much of the tightest Codex limit is left, e.g. "S4 · 99%".
        val left = usage?.tightest?.leftPercent
        val chip = when {
            question != null -> "Reply"
            approval != null -> "Approve"
            left != null -> "S${state.steps} · $left%"
            state.steps > 0 -> "Step ${state.steps}"
            else -> "Starting"
        }
        // Header line next to the app name: kept short so it isn't cut off; the step is in the pill.
        val details = usage?.shortSummary?.takeIf { it.isNotBlank() }
            ?: "Step ${state.steps}".takeIf { state.steps > 0 }
            ?: state.phase

        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.task?.let { clip(it, 80) } ?: "Android Use is working")
            .setContentText(status)
            .setSubText(details.ifBlank { null })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(
                // Tasks started from the assistant lead back to that chat; others to the app's log.
                if (state.fromAssistant) {
                    PendingIntent.getActivity(context, 2, AssistantActivity.intent(context), PendingIntent.FLAG_IMMUTABLE)
                } else {
                    PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                },
            )
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
        state.screen?.let { builder.setLargeIcon(Icon.createWithBitmap(thumbnail(it))) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder
                .setShortCriticalText(chip)
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
            // The setter only exists from Android 16 QPR2 (36.1); on 36.0 set the same extra directly.
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                builder.setRequestPromotedOngoing(true)
            } else {
                builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
            }
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(listOf(status, details).filter { it.isNotBlank() }.joinToString("\n")))
        }
        return builder.build()
    }

    /** Turns an action log line ("tap element=7 …") into something a person would say. */
    fun describe(action: String): String {
        val name = action.substringBefore(' ')
        fun arg(key: String) = Regex("""$key="([^"]*)""").find(action)?.groupValues?.get(1)
        return when (name) {
            "launch_app" -> "Opening ${arg("app") ?: "an app"}"
            "open_uri" -> "Opening ${arg("uri")?.substringAfter("://")?.substringBefore('/') ?: "a link"}"
            "open_settings" -> "Opening ${arg("page")?.replace('_', ' ') ?: ""} settings"
            "tap" -> if ("long_press=true" in action) "Long-pressing" else "Tapping"
            "type_text" -> arg("text")?.let { "Typing “${clip(it, 40)}”" } ?: "Typing"
            "fill_form" -> "Filling in a form"
            "scroll" -> "Scrolling"
            "scroll_to" -> "Looking for “${arg("text") ?: ""}”"
            "swipe" -> "Swiping"
            "press_key" -> "Pressing ${arg("key") ?: "a key"}"
            "compose_sms" -> "Writing a text to ${arg("number") ?: "someone"}"
            "compose_email" -> "Writing an email to ${arg("to") ?: "someone"}"
            "shell_read" -> "Checking the device"
            "shell" -> "Running a command"
            "wait" -> "Waiting for the screen"
            "save_note" -> "Noting what it found"
            "list_apps" -> "Looking through apps"
            "batch" -> describeBatch(action)
            "finish" -> "Wrapping up"
            else -> clip(action, 60)
        }
    }

    /** "tap → type → tap" style summary of a batch's actions. */
    private fun describeBatch(action: String): String {
        val steps = Regex(""""action":"(\w+)"(?:[^}]*?"text":"([^"]*)")?""").findAll(action).map { m ->
            val text = m.groupValues[2]
            when (m.groupValues[1]) {
                "tap" -> "Tapping"
                "long_press" -> "Long-pressing"
                "type_text" -> if (text.isNotEmpty()) "Typing \u201c${clip(text, 24)}\u201d" else "Typing"
                "press_key" -> "Pressing a key"
                "scroll", "scroll_to" -> "Scrolling"
                "launch_app" -> if (text.isNotEmpty()) "Opening $text" else "Opening an app"
                else -> m.groupValues[1]
            }
        }.toList()
        if (steps.isEmpty()) return "Doing several steps"
        return steps.take(3).joinToString(" → ") + if (steps.size > 3) " …" else ""
    }

    private fun thumbnail(screen: Bitmap): Bitmap {
        val height = 256
        val width = (screen.width * height / screen.height.toFloat()).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(screen, width, height, true)
    }

    private fun clip(text: String, max: Int) = text.replace('\n', ' ').let { if (it.length > max) it.take(max - 1) + "…" else it }
}

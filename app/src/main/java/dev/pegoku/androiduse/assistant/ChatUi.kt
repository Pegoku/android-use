package dev.pegoku.androiduse.assistant

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pegoku.androiduse.agent.LiveNotification
import dev.pegoku.androiduse.agent.QuestionRequest
import dev.pegoku.androiduse.agent.TaskRunner
import dev.pegoku.androiduse.ui.success

/*
 * The conversation pieces shared by the app's chat screen and the assistant overlay: messages,
 * task rows, follow-up chips, the question card and the input bar.
 */

/** The messages of a chat, with thinking dots while a reply is on its way and copy/read-aloud under the last one. */
internal fun LazyListScope.chatItems(
    messages: List<ChatMessage>,
    busy: Boolean,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
) {
    items(messages, key = { it.id }) { message ->
        when (message.role) {
            ChatRole.User -> UserMessage(message)
            ChatRole.Assistant -> AssistantMessage(message)
            ChatRole.Task -> TaskRow(message)
        }
    }
    item {
        val last = messages.lastOrNull()
        when {
            busy && last?.role == ChatRole.User -> ThinkingDots()
            !busy && last?.role == ChatRole.Assistant -> Row(verticalAlignment = Alignment.CenterVertically) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                IconButton(onClick = { onCopy(last.text) }) { Icon(Icons.Outlined.ContentCopy, "Copy", tint = muted, modifier = Modifier.size(20.dp)) }
                IconButton(onClick = { onSpeak(last.text) }) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Read aloud", tint = muted, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
internal fun AssistantMessage(message: ChatMessage) {
    Text(
        markdown(message.text + if (message.streaming) " ▍" else ""),
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
internal fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp)) {
        repeat(3) { index ->
            val distance = minOf(kotlin.math.abs(phase - index), kotlin.math.abs(phase - 3 - index))
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(0.35f + 0.65f * (1f - distance.coerceIn(0f, 1f)))
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

@Composable
internal fun UserMessage(message: ChatMessage) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        message.image?.let {
            Image(
                it.asImageBitmap(), "Shared screen",
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(bottom = 6.dp).size(width = 54.dp, height = 96.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
        Text(
            message.text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onPrimaryContainer,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp))
                .background(colors.primaryContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/**
 * A task in the conversation. While it runs: a spinner, the current step and a stop button.
 * Afterwards: the outcome; tap to read what the agent reported.
 */
@Composable
internal fun TaskRow(message: ChatMessage) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val running = message.success == null
    val run by TaskRunner.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surfaceContainerHighest)
            .clickable(enabled = message.detail != null) { expanded = !expanded }
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (message.success) {
                null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = colors.primary)
                true -> Icon(Icons.Outlined.CheckCircle, null, tint = colors.success, modifier = Modifier.size(22.dp))
                false -> Icon(Icons.Outlined.ErrorOutline, null, tint = colors.error, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when (message.success) { null -> "Working on your phone"; true -> "Done on your phone"; false -> "Couldn't finish" },
                    style = MaterialTheme.typography.titleSmall, color = colors.onSurface,
                )
                Text(message.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 3)
            }
            when {
                running -> IconButton(onClick = ChatEngine::stop) { Icon(Icons.Default.Stop, "Stop", tint = colors.onSurface) }
                message.detail != null -> Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Hide details" else "Show details",
                    tint = colors.onSurfaceVariant, modifier = Modifier.padding(8.dp),
                )
                else -> Spacer(Modifier.width(8.dp))
            }
        }
        if (running && run.running) {
            // Live progress: what the agent is doing right now and how far it is.
            Column(Modifier.padding(start = 34.dp, end = 8.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = colors.primary, trackColor = colors.surfaceContainer)
                Text(
                    buildString {
                        append(run.lastAction?.let(LiveNotification::describe) ?: run.phase.ifBlank { "Starting" })
                        if (run.steps > 0) append(" · step ${run.steps}")
                    },
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (expanded && message.detail != null) {
            Text(
                message.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 34.dp, end = 8.dp, top = 10.dp),
            )
        }
    }
}

/**
 * One-tap follow-ups once a task has ended and the user hasn't said anything since: retry or ask
 * what happened after a failure, or move on to a new chat.
 */
@Composable
internal fun FollowUps(messages: List<ChatMessage>, onSend: (String) -> Unit) {
    val lastTask = messages.lastOrNull { it.role == ChatRole.Task } ?: return
    if (lastTask.success == null) return
    if (messages.takeLastWhile { it.id != lastTask.id }.any { it.role == ChatRole.User }) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (lastTask.success == false) {
            Suggestion("Try again") { onSend("Try again.") }
            Suggestion("What went wrong?") { onSend("What went wrong?") }
        } else {
            Suggestion("What did you do?") { onSend("What exactly did you do?") }
        }
        Suggestion("New chat", onClick = ChatEngine::newChat)
    }
}

@Composable
internal fun Suggestion(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}

/** A question the running task is waiting on, with its quick options. */
@Composable
internal fun QuestionCard(question: QuestionRequest, shape: RoundedCornerShape, onAnswer: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = shape, color = colors.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("The task needs your answer", style = MaterialTheme.typography.labelLarge, color = colors.onTertiaryContainer.copy(alpha = 0.75f))
            Text(question.question, style = MaterialTheme.typography.bodyLarge, color = colors.onTertiaryContainer)
            if (question.options.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    question.options.forEach { option ->
                        Surface(shape = RoundedCornerShape(50), color = colors.surface, onClick = { onAnswer(option) }) {
                            Text(
                                option,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A saved conversation in a list: title, when it was last active, and a delete button. */
@Composable
internal fun RecentChatRow(chat: SavedChat, selected: Boolean = false, onOpen: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ChatBubbleOutline, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(chat.title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                DateUtils.getRelativeTimeSpanString(chat.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, "Delete chat", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** The pill-shaped composer: attach screen, text, mic or send. */
@Composable
internal fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    busy: Boolean,
    answering: Boolean,
    afterTask: Boolean,
    listening: Boolean,
    partialSpeech: String,
    screen: SharedScreen?,
    canShare: Boolean,
    onToggleScreen: () -> Unit,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask Android Use",
) {
    val colors = MaterialTheme.colorScheme
    fun submit() {
        if (text.isNotBlank() && !busy) {
            onSend(text)
            onTextChange("")
        }
    }
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = colors.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (screen != null) {
                // Attached screen: thumbnail with a tap-to-remove affordance.
                Box(Modifier.padding(start = 8.dp).clickable(onClick = onToggleScreen)) {
                    Image(
                        screen.thumbnail.asImageBitmap(), "Attached screen",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 28.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Icon(
                        Icons.Default.Close, "Remove screen", tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).size(14.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    )
                }
            } else if (canShare) {
                IconButton(onClick = onToggleScreen) { Icon(Icons.Outlined.AddAPhoto, "Attach screen", tint = colors.onSurface) }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            TextField(
                value = if (listening && partialSpeech.isNotEmpty()) partialSpeech else text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        when {
                            listening -> "Listening…"
                            busy -> "Working…"
                            answering -> "Type your answer"
                            screen != null -> "Ask about this screen"
                            afterTask -> "Follow up or give feedback"
                            else -> placeholder
                        },
                        fontSize = 17.sp, color = colors.onSurfaceVariant,
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, color = colors.onSurface),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = colors.primary,
                ),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    // Enter sends (as in chat apps); Shift+Enter still inserts a line break.
                    if (event.key == Key.Enter && !event.isShiftPressed) {
                        if (event.type == KeyEventType.KeyDown) submit()
                        true
                    } else false
                },
            )
            if (text.isNotBlank() && !listening) {
                Surface(onClick = ::submit, enabled = !busy, shape = CircleShape, color = if (busy) colors.surfaceContainerHighest else colors.primary) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, "Send",
                        tint = if (busy) colors.onSurfaceVariant else colors.onPrimary,
                        modifier = Modifier.padding(10.dp).size(20.dp),
                    )
                }
                Spacer(Modifier.width(2.dp))
            } else {
                Surface(onClick = onMic, shape = CircleShape, color = if (listening) colors.errorContainer else Color.Transparent) {
                    Icon(
                        Icons.Default.Mic, "Speak",
                        tint = if (listening) colors.onErrorContainer else colors.onSurface,
                        modifier = Modifier.padding(10.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

/** Renders **bold**, *italic* and `code` spans; turns list markers into bullets and drops headings and link syntax. */
internal fun markdown(text: String): AnnotatedString = buildAnnotatedString {
    val cleaned = text
        .replace(Regex("""^#+\s*""", RegexOption.MULTILINE), "")
        .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        .replace(Regex("""^\s*[-*]\s+""", RegexOption.MULTILINE), "•  ")
    val token = Regex("""\*\*(.+?)\*\*|`([^`]+)`|(?<![*\w])\*(?!\s)([^*\n]+?)\*(?![*\w])""")
    var index = 0
    for (match in token.findAll(cleaned)) {
        append(cleaned.substring(index, match.range.first))
        val (bold, code, italic) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp)) { append(code) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        index = match.range.last + 1
    }
    append(cleaned.substring(index))
}

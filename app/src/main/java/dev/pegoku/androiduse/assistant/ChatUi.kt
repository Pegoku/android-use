@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.pegoku.androiduse.assistant

import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledIconButton
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import dev.pegoku.androiduse.ui.Eyebrow
import dev.pegoku.androiduse.ui.Mono
import dev.pegoku.androiduse.ui.TouchMark
import dev.pegoku.androiduse.ui.card
import dev.pegoku.androiduse.ui.hairline
import dev.pegoku.androiduse.ui.isDark
import dev.pegoku.androiduse.ui.success

/*
 * The conversation pieces shared by the app's chat screen and the assistant overlay: messages,
 * task cards, follow-up chips, the question card and the composer.
 */

/**
 * The messages of a chat, with the thinking mark while a reply is on its way and copy/read-aloud
 * under the last one. [onShowSteps] adds a "Steps" button to the newest task's card.
 */
internal fun LazyListScope.chatItems(
    messages: List<ChatMessage>,
    busy: Boolean,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onShowSteps: (() -> Unit)? = null,
) {
    val latestTask = messages.lastOrNull { it.role == ChatRole.Task }?.id
    items(messages, key = { it.id }) { message ->
        when (message.role) {
            ChatRole.User -> UserMessage(message)
            ChatRole.Assistant -> AssistantMessage(message)
            ChatRole.Task -> TaskRow(message, latest = message.id == latestTask, onShowSteps = onShowSteps)
        }
    }
    item {
        val last = messages.lastOrNull()
        val run by TaskRunner.state.collectAsStateWithLifecycle()
        when {
            // While a task runs its card shows the progress (and messages sent then steer it). Otherwise
            // the model is working until its reply starts to show: before the first words, during a
            // web search, or when it picks up after a task.
            busy && !run.running && !(last?.role == ChatRole.Assistant && last.streaming && last.text.isNotEmpty()) -> Thinking()
            !busy && last?.role == ChatRole.Assistant -> Row(Modifier.offset(x = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                IconButton(shapes = IconButtonDefaults.shapes(), onClick = { onCopy(last.text) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.ContentCopy, "Copy", tint = muted, modifier = Modifier.size(18.dp)) }
                IconButton(shapes = IconButtonDefaults.shapes(), onClick = { onSpeak(last.text) }, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Read aloud", tint = muted, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
internal fun AssistantMessage(message: ChatMessage) {
    val base = markdown(message.text)
    val text = if (message.streaming) {
        // A softly pulsing touch dot where the next words will land.
        val cursor by rememberInfiniteTransition(label = "cursor").animateFloat(
            initialValue = 1f, targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
            label = "cursor",
        )
        val color = MaterialTheme.colorScheme.tertiary.copy(alpha = cursor)
        buildAnnotatedString {
            append(base)
            withStyle(SpanStyle(color = color)) { append(" ●") }
        }
    } else {
        base
    }
    Text(
        text,
        inlineContent = swatches(base),
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Shown while the model works on a reply: the touch mark rippling and a shimmering label. */
@Composable
internal fun Thinking() {
    val colors = MaterialTheme.colorScheme
    val sweep by rememberInfiniteTransition(label = "thinking").animateFloat(
        initialValue = -150f, targetValue = 450f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing)),
        label = "sweep",
    )
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TouchMark(size = 22.dp, active = true)
        Spacer(Modifier.width(10.dp))
        Text(
            "Thinking",
            style = MaterialTheme.typography.bodyLarge.copy(
                // A highlight that sweeps across the label; past its edges the text keeps the muted color.
                brush = Brush.linearGradient(
                    listOf(colors.onSurfaceVariant, colors.onSurface, colors.onSurfaceVariant),
                    start = Offset(sweep - 100f, 0f),
                    end = Offset(sweep + 100f, 0f),
                ),
            ),
        )
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
                modifier = Modifier.padding(bottom = 6.dp).size(width = 54.dp, height = 96.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        if (message.attachments.isNotEmpty()) {
            Row(
                Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                message.attachments.forEach { AttachmentChip(it, height = 96.dp) }
            }
        }
        if (message.text.isNotBlank()) {
            val text = withSwatches(message.text)
            Text(
                text,
                inlineContent = swatches(text),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onPrimaryContainer,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp))
                    .background(colors.primaryContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * A task in the conversation. While it runs: the rippling touch mark, the step it is on, the
 * phone's screen as the agent sees it, and a stop button. Afterwards: the outcome and the screen
 * it left the phone on; tap to read what the agent reported.
 *
 * [latest] marks the newest task of the chat, the only one the live run state can describe.
 */
@Composable
internal fun TaskRow(message: ChatMessage, latest: Boolean = false, onShowSteps: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val running = message.success == null
    val run by TaskRunner.state.collectAsStateWithLifecycle()
    // The run state belongs to this card only if it is the chat's newest task and the same task.
    val mine = latest && run.task == message.text
    val live = running && run.running
    val screen = run.screen.takeIf { mine }
    val steps = run.steps.takeIf { mine && it > 0 }
    val accent = when (message.success) { null -> colors.tertiary; true -> colors.success; false -> colors.error }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.card,
        border = hairline(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val showSteps = onShowSteps.takeIf { mine && run.log.isNotEmpty() }
        val hasActions = running || showSteps != null || message.detail != null
        Column(
            Modifier
                .clickable(enabled = message.detail != null) { expanded = !expanded }
                // The action buttons bring their own breathing room at the bottom.
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = if (hasActions) 2.dp else 14.dp)
                .animateContentSize(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (message.success) {
                            null -> TouchMark(size = 18.dp, active = live)
                            true -> Icon(Icons.Outlined.CheckCircle, null, tint = accent, modifier = Modifier.size(18.dp))
                            false -> Icon(Icons.Outlined.ErrorOutline, null, tint = accent, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Eyebrow(
                            buildString {
                                append(when (message.success) { null -> "Working"; true -> "Done"; false -> "Couldn't finish" })
                                if (steps != null) append(if (running) " · step $steps" else if (steps == 1) " · 1 step" else " · $steps steps")
                            },
                            color = accent,
                        )
                    }
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyLarge, color = colors.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    )
                    if (live) {
                        // What the agent is doing right now.
                        Text(
                            run.lastAction?.let(LiveNotification::describe) ?: run.phase.ifBlank { "Starting" },
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        LinearWavyProgressIndicator(
                            Modifier.padding(top = 2.dp).fillMaxWidth(),
                            color = colors.tertiary, trackColor = colors.tertiary.copy(alpha = 0.16f),
                        )
                    }
                }
                if (screen != null) {
                    Image(
                        screen.asImageBitmap(), if (running) "The phone's screen" else "Where the task left the phone",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(52.dp)
                            .aspectRatio((screen.width / screen.height.toFloat()).coerceIn(0.4f, 1f))
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp)),
                    )
                }
            }
            if (expanded && message.detail != null) {
                Text(message.detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            }
            if (hasActions) {
                Row(Modifier.padding(top = 2.dp).offset(x = (-10).dp), verticalAlignment = Alignment.CenterVertically) {
                    if (running) CardAction("Stop", Icons.Default.Stop, colors.error, onClick = ChatEngine::stop)
                    if (showSteps != null) CardAction("Steps", Icons.Outlined.Timeline, colors.primary, onClick = showSteps)
                    if (message.detail != null) {
                        CardAction(
                            if (expanded) "Hide report" else "Report",
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            colors.primary,
                        ) { expanded = !expanded }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardAction(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    TextButton(shapes = ButtonDefaults.shapes(), onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp), colors = ButtonDefaults.textButtonColors(contentColor = tint)) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
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
    Surface(onClick = onClick, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.card, border = hairline()) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** A question the running task is waiting on, with its quick options. */
@Composable
internal fun QuestionCard(question: QuestionRequest, shape: Shape, onAnswer: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = shape, color = colors.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TouchMark(size = 18.dp, color = colors.onTertiaryContainer, active = true)
                Spacer(Modifier.width(8.dp))
                Eyebrow("The task needs your answer", color = colors.onTertiaryContainer)
            }
            Text(question.question, style = MaterialTheme.typography.bodyLarge, color = colors.onTertiaryContainer)
            if (question.options.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    question.options.forEach { option ->
                        Surface(shape = MaterialTheme.shapes.small, color = colors.surface, onClick = { onAnswer(option) }) {
                            Text(
                                option,
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurface,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                chat.title, style = MaterialTheme.typography.bodyLarge,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                DateUtils.getRelativeTimeSpanString(chat.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
        }
        IconButton(shapes = IconButtonDefaults.shapes(), onClick = onDelete) {
            Icon(Icons.Outlined.Delete, "Delete chat", tint = colors.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
    }
}

/** The composer: attach files or the screen, text, mic or send. */
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
    /** Files picked for the next message; the caller takes them in [onSend]. Null hides the attach button. */
    attachments: PendingAttachments? = null,
) {
    val colors = MaterialTheme.colorScheme
    // While a task runs, the composer steers it instead of waiting for it to end.
    val run by TaskRunner.state.collectAsStateWithLifecycle()
    val steering = run.running && !answering
    val blocked = busy && !steering
    // Attachments go with a new message, not with steering or an answer to the task.
    val canAttach = attachments != null && !steering && !answering
    val attached = canAttach && attachments!!.items.isNotEmpty()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> attachments?.add(uris) }
    // A new chat starts with the cursor in the composer and the keyboard up.
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        ChatEngine.newChats.collect {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    fun submit() {
        if ((text.isNotBlank() || attached) && !blocked && attachments?.loading.let { it == null || it == 0 }) {
            onSend(text)
            onTextChange("")
        }
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.card,
        // Pink while the composer talks to a running task, so it is clear who is listening.
        border = if (steering || answering) BorderStroke(1.5.dp, colors.tertiary.copy(alpha = 0.7f)) else hairline(),
        shadowElevation = if (colors.isDark) 0.dp else 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            if (canAttach && (attached || attachments!!.loading > 0)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    attachments!!.items.forEach { attachment ->
                        Box(Modifier.clickable { attachments.remove(attachment) }) {
                            AttachmentChip(attachment, height = 56.dp)
                            Icon(
                                Icons.Default.Close, "Remove ${attachment.name}", tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape),
                            )
                        }
                    }
                    if (attachments.loading > 0) LoadingIndicator(Modifier.size(40.dp))
                }
            }
            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canAttach) {
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.AttachFile, "Attach files", tint = colors.onSurfaceVariant) }
                }
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
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = onToggleScreen) { Icon(Icons.Outlined.AddAPhoto, "Attach screen", tint = colors.onSurfaceVariant) }
                } else if (!canAttach) {
                    Spacer(Modifier.width(8.dp))
                }
                TextField(
                    value = if (listening && partialSpeech.isNotEmpty()) partialSpeech else text,
                    onValueChange = onTextChange,
                    placeholder = {
                        Text(
                            when {
                                listening -> "Listening…"
                                blocked -> "Working…"
                                answering -> "Type your answer"
                                steering -> "Tell the agent what to change"
                                screen != null -> "Ask about this screen"
                                attached -> "Ask about ${if (attachments!!.items.size == 1) "this file" else "these files"}"
                                afterTask -> "Follow up or give feedback"
                                else -> placeholder
                            },
                            style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = colors.primary,
                    ),
                    maxLines = 5,
                    visualTransformation = HexColorHighlight,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                        // Enter sends (as in chat apps); Shift+Enter still inserts a line break.
                        if (event.key == Key.Enter && !event.isShiftPressed) {
                            if (event.type == KeyEventType.KeyDown) submit()
                            true
                        } else false
                    },
                )
                if ((text.isNotBlank() || attached) && !listening) {
                    FilledIconButton(onClick = ::submit, shapes = IconButtonDefaults.shapes(), enabled = !blocked) {
                        Icon(Icons.Default.ArrowUpward, "Send", modifier = Modifier.size(22.dp))
                    }
                } else {
                    FilledIconButton(
                        onClick = onMic,
                        shapes = IconButtonDefaults.shapes(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (listening) colors.tertiary else Color.Transparent,
                            contentColor = if (listening) colors.onTertiary else colors.onSurfaceVariant,
                        ),
                    ) {
                        Icon(Icons.Default.Mic, if (listening) "Stop listening" else "Speak", modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

/** An attachment as a picture thumbnail, or a file card with its icon and name. */
@Composable
internal fun AttachmentChip(attachment: Attachment, height: androidx.compose.ui.unit.Dp) {
    val colors = MaterialTheme.colorScheme
    val thumbnail = attachment.thumbnail
    if (thumbnail != null && attachment.isImage) {
        Image(
            thumbnail.asImageBitmap(), attachment.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(height).clip(RoundedCornerShape(10.dp)),
        )
        return
    }
    Row(
        Modifier
            .height(height.coerceAtMost(56.dp))
            .widthIn(max = 200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceContainerHigh)
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) {
            Image(
                thumbnail.asImageBitmap(), null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 32.dp, height = 44.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Icon(
                when {
                    attachment.isImage -> Icons.Outlined.Image
                    attachment.mime == "application/pdf" -> Icons.Outlined.PictureAsPdf
                    else -> Icons.Outlined.Description
                },
                null, tint = colors.primary, modifier = Modifier.padding(start = 4.dp).size(24.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(attachment.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Renders **bold**, *italic* and `code` spans; turns list markers into bullets and drops headings and link syntax.
 * Hex colors get a swatch, so show the result with [swatches] as the Text's inlineContent.
 */
internal fun markdown(text: String): AnnotatedString = buildAnnotatedString {
    val cleaned = text
        .replace(Regex("""^#+\s*""", RegexOption.MULTILINE), "")
        .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        .replace(Regex("""^\s*[-*]\s+""", RegexOption.MULTILINE), "•  ")
    val token = Regex("""\*\*(.+?)\*\*|`([^`]+)`|(?<![*\w])\*(?!\s)([^*\n]+?)\*(?![*\w])""")
    var index = 0
    for (match in token.findAll(cleaned)) {
        appendWithSwatches(cleaned.substring(index, match.range.first))
        val (bold, code, italic) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendWithSwatches(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = Mono, fontSize = 14.sp)) { appendWithSwatches(code) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendWithSwatches(italic) }
        }
        index = match.range.last + 1
    }
    appendWithSwatches(cleaned.substring(index))
}

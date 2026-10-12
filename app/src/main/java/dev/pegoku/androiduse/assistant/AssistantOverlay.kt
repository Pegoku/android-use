package dev.pegoku.androiduse.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val CardColor = Color(0xFF131314)
private val BarColor = Color(0xFF1E1F20)
private val TextColor = Color(0xFFE3E3E3)
private val MutedColor = Color(0xFF9AA0A6)
private val AccentColor = Color(0xFFA8C7FA)
private val UserBubble = Color(0xFF2B2C2F)

@Composable
fun AssistantOverlay(
    screen: SharedScreen?,
    listening: Boolean,
    partialSpeech: String,
    onSend: (String, Boolean) -> Unit,
    onMic: () -> Unit,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
) {
    val messages by ChatEngine.messages.collectAsStateWithLifecycle()
    val busy by ChatEngine.busy.collectAsStateWithLifecycle()
    var shareScreen by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    MaterialTheme(colorScheme = darkColorScheme(primary = AccentColor, surface = CardColor)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            AnimatedVisibility(
                visible = shown,
                enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(200)),
                // Insets bound the whole area, so the panel can never extend under the keyboard or bars.
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                ) {
                    if (messages.isNotEmpty()) {
                        Pill(Modifier.align(Alignment.End), Icons.Default.CloseFullscreen, "Minimize", onDismiss)
                        // Takes only the space left above the input bar, scrolling when the chat is longer.
                        ChatCard(messages, busy, onCopy, onSpeak, Modifier.weight(1f, fill = false))
                    } else if (screen != null) {
                        ScreenChip(screen, shareScreen) { shareScreen = !shareScreen }
                    }
                    InputBar(
                        busy = busy,
                        listening = listening,
                        partialSpeech = partialSpeech,
                        screen = screen?.takeIf { shareScreen },
                        canShare = screen != null,
                        onToggleScreen = { if (screen != null) shareScreen = !shareScreen },
                        onSend = { text ->
                            onSend(text, shareScreen)
                            shareScreen = false
                        },
                        onMic = onMic,
                    )
                }
            }
        }
    }
}

@Composable
private fun Pill(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50), color = CardColor) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = TextColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = TextColor, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ScreenChip(screen: SharedScreen, selected: Boolean, onToggle: () -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = if (selected) AccentColor.copy(alpha = 0.25f) else CardColor, onClick = onToggle) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                screen.thumbnail.asImageBitmap(), null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 22.dp, height = 36.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Default.ScreenShare, null, tint = if (selected) AccentColor else TextColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (selected) "Screen will be shared" else "Ask about screen", color = TextColor, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ChatCard(
    messages: List<ChatMessage>,
    busy: Boolean,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        listState.animateScrollToItem(maxOf(0, messages.size))
    }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = CardColor,
        // Swallow taps on the card so they don't fall through and dismiss the overlay.
        modifier = modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = maxHeight).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(messages, key = { it.id }) { message ->
                when (message.role) {
                    ChatRole.User -> UserMessage(message)
                    ChatRole.Assistant -> Text(markdown(message.text + if (message.streaming) " ▍" else ""), color = TextColor, fontSize = 17.sp, lineHeight = 24.sp)
                    ChatRole.Task -> TaskRow(message)
                }
            }
            item {
                val last = messages.lastOrNull()
                when {
                    busy && last?.role == ChatRole.User -> Text("Thinking…", color = MutedColor, fontSize = 15.sp)
                    !busy && last?.role == ChatRole.Assistant -> Row {
                        IconButton(onClick = { onCopy(last.text) }) { Icon(Icons.Default.ContentCopy, "Copy", tint = MutedColor) }
                        IconButton(onClick = { onSpeak(last.text) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Read aloud", tint = MutedColor) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = ChatEngine::newChat) { Icon(Icons.Default.Refresh, "New chat", tint = MutedColor) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun UserMessage(message: ChatMessage) {
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
            color = TextColor,
            fontSize = 16.sp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp))
                .background(UserBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun TaskRow(message: ChatMessage) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BarColor).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (message.success) {
            null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentColor)
            true -> Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF81C995), modifier = Modifier.size(22.dp))
            false -> Icon(Icons.Default.Error, null, tint = Color(0xFFF28B82), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (message.success) { null -> "Working on your phone…"; true -> "Done on your phone"; false -> "Couldn't finish" },
                color = TextColor, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            Text(message.text, color = MutedColor, fontSize = 13.sp, maxLines = 3)
        }
        if (message.success == null) {
            IconButton(onClick = ChatEngine::stop) { Icon(Icons.Default.Stop, "Stop", tint = TextColor) }
        }
    }
}

@Composable
private fun InputBar(
    busy: Boolean,
    listening: Boolean,
    partialSpeech: String,
    screen: SharedScreen?,
    canShare: Boolean,
    onToggleScreen: () -> Unit,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank() && !busy) {
            onSend(text)
            text = ""
        }
    }
    Surface(
        shape = RoundedCornerShape(36.dp),
        color = BarColor,
        modifier = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (screen != null) {
                // Attached screen: thumbnail with a tap-to-remove affordance.
                Box(Modifier.padding(start = 8.dp).clickable(onClick = onToggleScreen)) {
                    Image(
                        screen.thumbnail.asImageBitmap(), "Attached screen",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 28.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Icon(
                        Icons.Default.Close, "Remove screen", tint = TextColor,
                        modifier = Modifier.align(Alignment.TopEnd).size(14.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)),
                    )
                }
            } else {
                IconButton(onClick = onToggleScreen, enabled = canShare) {
                    Icon(Icons.Default.Add, "Share screen", tint = if (canShare) TextColor else MutedColor)
                }
            }
            TextField(
                value = if (listening && partialSpeech.isNotEmpty()) partialSpeech else text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        when {
                            listening -> "Listening…"
                            busy -> "Working…"
                            screen != null -> "Ask about this screen"
                            else -> "Ask Android Use"
                        },
                        fontSize = 18.sp, color = MutedColor,
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, color = TextColor),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = AccentColor,
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
                IconButton(onClick = ::submit, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = AccentColor) }
            } else {
                IconButton(onClick = onMic) {
                    Icon(Icons.Default.Mic, "Speak", tint = if (listening) Color(0xFFF28B82) else TextColor)
                }
            }
        }
    }
}

/** Renders **bold** spans and drops other common markdown markers. */
private fun markdown(text: String): AnnotatedString = buildAnnotatedString {
    val cleaned = text.replace(Regex("""^#+\s*""", RegexOption.MULTILINE), "").replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
    var bold = false
    cleaned.split("**").forEachIndexed { index, part ->
        if (index > 0) bold = !bold
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
    }
}

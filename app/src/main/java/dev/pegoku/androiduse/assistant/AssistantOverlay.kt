package dev.pegoku.androiduse.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pegoku.androiduse.agent.Questions
import dev.pegoku.androiduse.ui.AndroidUseTheme

/**
 * The assistant sheet that floats over the current app. It follows the system theme and the
 * wallpaper colors, so it looks like part of the phone rather than a second app.
 */
@Composable
fun AssistantOverlay(
    screen: SharedScreen?,
    listening: Boolean,
    partialSpeech: String,
    onSend: (String, Boolean) -> Unit,
    onMic: () -> Unit,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
) {
    val messages by ChatEngine.messages.collectAsStateWithLifecycle()
    val busy by ChatEngine.busy.collectAsStateWithLifecycle()
    val question by Questions.current.collectAsStateWithLifecycle()
    var shareScreen by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    AndroidUseTheme {
        val dark = isSystemInDarkTheme()
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = if (dark) 0.6f else 0.35f)))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            AnimatedVisibility(
                visible = shown,
                enter = slideInVertically(tween(280)) { it / 4 } + fadeIn(tween(220)),
                // Insets bound the whole area, so the sheet can never extend under the keyboard or bars.
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                ) {
                    if (messages.isNotEmpty()) {
                        Header(onDismiss, onOpenApp)
                        // Takes only the space left above the input bar, scrolling when the chat is longer.
                        ChatCard(messages, busy, onCopy, onSpeak, Modifier.weight(1f, fill = false))
                        if (!busy && question == null) FollowUps(messages) { onSend(it, false) }
                    } else {
                        Welcome(
                            screen = screen,
                            sharing = shareScreen,
                            onToggleScreen = { shareScreen = !shareScreen },
                            onSuggestion = { suggestion, withScreen ->
                                onSend(suggestion, withScreen && screen != null)
                                shareScreen = false
                            },
                        )
                    }
                    question?.let { q -> Box(Modifier.swallowTaps()) { QuestionCard(q, SheetShape) { ChatEngine.answerQuestion(q.id, it) } } }
                    InputBar(
                        text = text,
                        onTextChange = { text = it },
                        // A pending question takes the input, even while the task it belongs to is running.
                        busy = busy && question == null,
                        answering = question != null,
                        afterTask = messages.lastOrNull { it.role == ChatRole.Task }?.success != null,
                        listening = listening,
                        partialSpeech = partialSpeech,
                        screen = screen?.takeIf { shareScreen },
                        canShare = screen != null,
                        onToggleScreen = { if (screen != null) shareScreen = !shareScreen },
                        onSend = { sent ->
                            val q = question
                            if (q != null) {
                                ChatEngine.answerQuestion(q.id, sent)
                            } else {
                                onSend(sent, shareScreen)
                                shareScreen = false
                            }
                        },
                        onMic = onMic,
                        modifier = Modifier.swallowTaps(),
                    )
                }
            }
        }
    }
}

private val SheetShape = RoundedCornerShape(28.dp)

/** Keeps taps inside a sheet from falling through to the scrim and closing the overlay. */
private fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures {} }

@Composable
private fun Header(onDismiss: () -> Unit, onOpenApp: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Brand()
        Spacer(Modifier.weight(1f))
        SmallAction(Icons.Outlined.OpenInFull, "Open in app", onClick = onOpenApp)
        Spacer(Modifier.width(6.dp))
        SmallAction(Icons.Outlined.Refresh, "New chat", onClick = ChatEngine::newChat)
        Spacer(Modifier.width(6.dp))
        SmallAction(Icons.Outlined.KeyboardArrowDown, "Minimize", onClick = onDismiss)
    }
}

@Composable
private fun Brand() {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Android Use", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(9.dp).size(20.dp))
    }
}

/** First thing you see: what the assistant can do, one-tap starters and the last few chats. */
@Composable
private fun Welcome(
    screen: SharedScreen?,
    sharing: Boolean,
    onToggleScreen: () -> Unit,
    onSuggestion: (String, Boolean) -> Unit,
) {
    val chats by ChatStore.chats.collectAsStateWithLifecycle()
    Surface(shape = SheetShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().swallowTaps()) {
        Column(Modifier.padding(top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Ask anything, or say what to do on your phone.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (screen != null) {
                    ScreenChip(screen, sharing, onToggleScreen)
                    Suggestion("What's on my screen?") { onSuggestion("What's on my screen?", true) }
                }
                Suggestion("Set a timer for 10 minutes") { onSuggestion("Set a timer for 10 minutes", false) }
                Suggestion("What's the weather today?") { onSuggestion("What's the weather today?", false) }
                Suggestion("Turn on do not disturb") { onSuggestion("Turn on do not disturb", false) }
            }
            if (chats.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    Text(
                        "Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    )
                    chats.take(4).forEach { chat ->
                        RecentChatRow(chat, onOpen = { ChatEngine.open(chat.id) }, onDelete = { ChatEngine.delete(chat.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenChip(screen: SharedScreen, selected: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) colors.primaryContainer else colors.surfaceContainerHighest,
        onClick = onToggle,
    ) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                screen.thumbnail.asImageBitmap(), null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 22.dp, height = 34.dp).clip(RoundedCornerShape(5.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (selected) "Screen attached" else "Attach screen",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            )
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
        shape = SheetShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().swallowTaps(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = maxHeight).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(Modifier.height(6.dp)) }
            chatItems(messages, busy, onCopy, onSpeak)
            item { Spacer(Modifier.height(6.dp)) }
        }
    }
}

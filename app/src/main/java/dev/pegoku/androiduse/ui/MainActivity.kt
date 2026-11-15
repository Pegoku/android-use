@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.pegoku.androiduse.ui

import androidx.compose.material3.IconButtonDefaults
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.IntentCompat
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import dev.pegoku.androiduse.agent.RunState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pegoku.androiduse.Settings
import dev.pegoku.androiduse.agent.Approvals
import dev.pegoku.androiduse.agent.Questions
import dev.pegoku.androiduse.agent.TaskRunner
import dev.pegoku.androiduse.assistant.ChatEngine
import dev.pegoku.androiduse.assistant.ChatRole
import dev.pegoku.androiduse.assistant.ChatStore
import dev.pegoku.androiduse.assistant.FollowUps
import dev.pegoku.androiduse.assistant.InputBar
import dev.pegoku.androiduse.assistant.QuestionCard
import dev.pegoku.androiduse.assistant.RecentChatRow
import dev.pegoku.androiduse.assistant.Speaker
import dev.pegoku.androiduse.assistant.SpeechInput
import dev.pegoku.androiduse.assistant.chatItems
import dev.pegoku.androiduse.auth.CodexAuth
import dev.pegoku.androiduse.auth.CodexTokens
import dev.pegoku.androiduse.shell.ShizukuShell
import dev.pegoku.androiduse.shell.ShizukuState
import kotlinx.coroutines.launch

/** The app: a chat with the assistant, a drawer of recent chats, and settings. */
class MainActivity : ComponentActivity() {
    private val sharedTask = mutableStateOf<String?>(null)
    private val sharedFiles = mutableStateOf<List<Uri>>(emptyList())
    private val speech = SpeechInput(this)
    private val speaker = Speaker(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        handleShare(intent)
        setContent {
            AndroidUseTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(
                        sharedTask = sharedTask,
                        sharedFiles = sharedFiles,
                        listening = speech.listening,
                        partialSpeech = speech.partial,
                        onMic = { speech.toggle { ChatEngine.send(this, it, null) } },
                        onCopy = { text -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Android Use", text)) },
                        onSpeak = speaker::speak,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        ShizukuShell.refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        speech.onPermissionResult(requestCode, grantResults)
    }

    override fun onDestroy() {
        speech.destroy()
        speaker.shutdown()
        super.onDestroy()
    }

    /** Text shared from another app becomes the draft; shared pictures and files are attached to it. */
    private fun handleShare(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedTask.value = it }
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { sharedFiles.value = listOf(it) }
            }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { sharedFiles.value = it }
        }
    }
}

@Composable
private fun Root(
    sharedTask: MutableState<String?>,
    sharedFiles: MutableState<List<Uri>>,
    listening: Boolean,
    partialSpeech: String,
    onMic: () -> Unit,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { Settings(context) }
    var showSettings by remember { mutableStateOf(false) }
    // A shared task always lands in the chat.
    LaunchedEffect(sharedTask.value, sharedFiles.value) { if (sharedTask.value != null || sharedFiles.value.isNotEmpty()) showSettings = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    AnimatedContent(
        targetState = showSettings,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
            }
        },
        label = "screen",
    ) { inSettings ->
        if (inSettings) {
            SettingsScreen(settings, onBack = { showSettings = false })
        } else {
            ChatScreen(sharedTask, sharedFiles, listening, partialSpeech, onMic, onCopy, onSpeak, onOpenSettings = { showSettings = true })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    sharedTask: MutableState<String?>,
    sharedFiles: MutableState<List<Uri>>,
    listening: Boolean,
    partialSpeech: String,
    onMic: () -> Unit,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by ChatEngine.messages.collectAsStateWithLifecycle()
    val busy by ChatEngine.busy.collectAsStateWithLifecycle()
    val currentChat by ChatEngine.currentChat.collectAsStateWithLifecycle()
    val chats by ChatStore.chats.collectAsStateWithLifecycle()
    val run by TaskRunner.state.collectAsStateWithLifecycle()
    val shizuku by ShizukuShell.state.collectAsStateWithLifecycle()
    val tokens by CodexAuth.tokens.collectAsStateWithLifecycle()
    val approval by Approvals.current.collectAsStateWithLifecycle()
    val question by Questions.current.collectAsStateWithLifecycle()
    val ready = shizuku == ShizukuState.Ready && tokens != null
    val settings = remember { Settings(context) }

    var text by remember { mutableStateOf("") }
    val attachments = dev.pegoku.androiduse.assistant.rememberPendingAttachments()
    var showSteps by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()

    LaunchedEffect(sharedTask.value) {
        sharedTask.value?.let {
            text = it
            sharedTask.value = null
        }
    }
    LaunchedEffect(sharedFiles.value) {
        if (sharedFiles.value.isNotEmpty()) {
            attachments.add(sharedFiles.value)
            sharedFiles.value = emptyList()
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    fun send(message: String) {
        val q = question
        // Steering a running task takes text only; anything attached waits for the next message.
        if (q != null) Questions.answer(q.id, message) else ChatEngine.send(context, message, null, if (run.running) emptyList() else attachments.take())
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
                Drawer(
                    chats = chats,
                    currentChat = currentChat,
                    chatEmpty = messages.isEmpty(),
                    tokens = tokens,
                    onNewChat = { ChatEngine.newChat(); scope.launch { drawer.close() } },
                    onOpenChat = { ChatEngine.open(it); scope.launch { drawer.close() } },
                    onDeleteChat = ChatEngine::delete,
                    onSettings = { scope.launch { drawer.close() }; onOpenSettings() },
                    onClose = { scope.launch { drawer.close() } },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "Chats") }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        // The mark ripples for as long as the agent has its finger on the phone.
                        Wordmark(active = run.running)
                        Text(
                            settings.fastModel.takeIf { it.isNotBlank() } ?: settings.model,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 30.dp),
                        )
                    }
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = ChatEngine::newChat, enabled = messages.isNotEmpty() && !run.running) { Icon(Icons.Outlined.Edit, "New chat") }
                    Avatar(tokens, onClick = onOpenSettings)
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().imePadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        Greeting(tokens, onSuggestion = ::send)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            chatItems(messages, busy, onCopy, onSpeak, onShowSteps = { showSteps = true })
                            item { Spacer(Modifier.height(4.dp)) }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!ready) SetupCard(shizuku, tokens)
                    approval?.let { ApprovalCard(it) }
                    question?.let { q -> QuestionCard(q, MaterialTheme.shapes.large) { Questions.answer(q.id, it) } }
                    if (!busy && question == null) FollowUps(messages, ::send)
                    InputBar(
                        text = text,
                        onTextChange = { text = it },
                        busy = busy && question == null,
                        answering = question != null,
                        afterTask = messages.lastOrNull { it.role == ChatRole.Task }?.success != null,
                        listening = listening,
                        partialSpeech = partialSpeech,
                        screen = null,
                        canShare = false,
                        onToggleScreen = {},
                        onSend = ::send,
                        onMic = onMic,
                        placeholder = if (ready) "Ask, or say what to do" else "Finish setup first",
                        attachments = attachments,
                    )
                }
            }
        }
    }

    if (showSteps) {
        ModalBottomSheet(onDismissRequest = { showSteps = false }, containerColor = MaterialTheme.colorScheme.background) {
            StepLog(run)
        }
    }
}

/** What the agent did on the current task: the phone's last screen, then every step in order. */
@Composable
private fun StepLog(run: RunState) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    // Opens on the newest step and follows along while the task runs.
    LaunchedEffect(run.log.size) { if (run.log.isNotEmpty()) listState.scrollToItem(run.log.size) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PhoneFrame(run)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (run.running) {
                            TouchMark(size = 18.dp, active = true)
                            Spacer(Modifier.width(8.dp))
                        }
                        Eyebrow(
                            (if (run.running) run.phase.ifBlank { "Starting" } else run.phase) + " · " + (if (run.steps == 1) "1 step" else "${run.steps} steps"),
                            color = when {
                                run.running -> colors.tertiary
                                run.result?.success == true -> colors.success
                                run.result != null -> colors.error
                                else -> colors.onSurfaceVariant
                            },
                        )
                    }
                    run.task?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 5, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        items(run.log) { line -> LogRow(line) }
    }
}

/** The empty chat: the question the app exists to ask, and a few things to say back. */
@Composable
private fun Greeting(tokens: CodexTokens?, onSuggestion: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val name = tokens?.email?.substringBefore('@')?.substringBefore('.')?.replaceFirstChar(Char::uppercase)
    // Sits just above the composer, within thumb reach, and scrolls when the keyboard leaves little room.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)) {
            Text(if (name != null) "Hi $name." else "Hi.", style = MaterialTheme.typography.displaySmall, color = colors.onSurfaceVariant)
            Text("What should I do\non your phone?", style = MaterialTheme.typography.displaySmall, color = colors.onSurface)
            Spacer(Modifier.height(28.dp))
            Starter(Icons.Outlined.Timer, "Set a timer for 10 minutes", onSuggestion)
            HorizontalDivider(color = colors.hairline)
            Starter(Icons.Outlined.DoNotDisturbOn, "Turn on do not disturb", onSuggestion)
            HorizontalDivider(color = colors.hairline)
            Starter(Icons.Outlined.Sms, "Text Anna I'm running late", onSuggestion)
            HorizontalDivider(color = colors.hairline)
            Starter(Icons.Outlined.WbSunny, "What's the weather today?", onSuggestion)
        }
    }
}

/** One thing to try, sent as typed when tapped. */
@Composable
private fun Starter(icon: ImageVector, text: String, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onPick(text) }.heightIn(min = 54.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ArrowOutward, null, tint = colors.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
    }
}

/** The side drawer: new chat, the saved chats by day, and the account row with settings. */
@Composable
private fun Drawer(
    chats: List<dev.pegoku.androiduse.assistant.SavedChat>,
    currentChat: Long,
    chatEmpty: Boolean,
    tokens: CodexTokens?,
    onNewChat: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onDeleteChat: (Long) -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(Modifier.weight(1f))
            IconButton(shapes = IconButtonDefaults.shapes(), onClick = onClose) { Icon(Icons.Outlined.Close, "Close") }
        }
        Surface(
            onClick = onNewChat,
            shape = MaterialTheme.shapes.medium,
            color = colors.primary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Edit, null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("New chat", style = MaterialTheme.typography.titleSmall, color = colors.onPrimary)
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (chats.isEmpty()) {
                item {
                    Text(
                        "Chats you start are kept here, so you can pick one up later.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
            // Newest first, so the day groups come out in order: today, yesterday, earlier.
            chats.groupBy { dayOf(it.updatedAt) }.forEach { (day, group) ->
                item(key = day) { Eyebrow(day, Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp)) }
                items(group, key = { it.id }) { chat ->
                    RecentChatRow(chat, selected = chat.id == currentChat && !chatEmpty, onOpen = { onOpenChat(chat.id) }, onDelete = { onDeleteChat(chat.id) })
                }
            }
        }
        HorizontalDivider(color = colors.hairline)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(start = 12.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(tokens, onClick = onSettings)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(tokens?.email ?: "Not signed in", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    tokens?.plan?.let { "${it.replaceFirstChar(Char::uppercase)} plan" } ?: "Settings",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.Settings, "Settings", tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

private fun dayOf(time: Long): String = when {
    DateUtils.isToday(time) -> "Today"
    DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> "Earlier"
}

/** A round initial from the signed-in email, as the account button. */
@Composable
private fun Avatar(tokens: CodexTokens?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val initial = tokens?.email?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    IconButton(shapes = IconButtonDefaults.shapes(), onClick = onClick) {
        Box(
            Modifier.size(32.dp).background(if (tokens != null) colors.primaryContainer else colors.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initial, style = MaterialTheme.typography.titleSmall, color = if (tokens != null) colors.onPrimaryContainer else colors.onSurfaceVariant)
        }
    }
}

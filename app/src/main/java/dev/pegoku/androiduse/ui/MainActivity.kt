package dev.pegoku.androiduse.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ListAlt
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
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.pegoku.androiduse.assistant.Suggestion
import dev.pegoku.androiduse.assistant.chatItems
import dev.pegoku.androiduse.auth.CodexAuth
import dev.pegoku.androiduse.auth.CodexTokens
import dev.pegoku.androiduse.shell.ShizukuShell
import dev.pegoku.androiduse.shell.ShizukuState
import kotlinx.coroutines.launch

/** The app: a chat with the assistant, a drawer of recent chats, and settings. */
class MainActivity : ComponentActivity() {
    private val sharedTask = mutableStateOf<String?>(null)
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

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedTask.value = it }
    }
}

@Composable
private fun Root(
    sharedTask: MutableState<String?>,
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
    LaunchedEffect(sharedTask.value) { if (sharedTask.value != null) showSettings = false }
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
            ChatScreen(sharedTask, listening, partialSpeech, onMic, onCopy, onSpeak, onOpenSettings = { showSettings = true })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    sharedTask: MutableState<String?>,
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
    var showSteps by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()

    LaunchedEffect(sharedTask.value) {
        sharedTask.value?.let {
            text = it
            sharedTask.value = null
        }
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    fun send(message: String) {
        val q = question
        if (q != null) Questions.answer(q.id, message) else ChatEngine.send(context, message, null)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
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
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "Chats") } },
                    title = {
                        Column {
                            Text("Android Use", style = MaterialTheme.typography.titleMedium)
                            Text(
                                settings.fastModel.takeIf { it.isNotBlank() } ?: settings.model,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (run.log.isNotEmpty()) IconButton(onClick = { showSteps = true }) { Icon(Icons.Outlined.ListAlt, "Task steps") }
                        IconButton(onClick = ChatEngine::newChat, enabled = messages.isNotEmpty() && !run.running) { Icon(Icons.Outlined.Edit, "New chat") }
                        Avatar(tokens, onClick = onOpenSettings)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
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
                            chatItems(messages, busy, onCopy, onSpeak)
                            item { Spacer(Modifier.height(4.dp)) }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!ready) SetupCard(shizuku, tokens)
                    approval?.let { ApprovalCard(it) }
                    question?.let { q -> QuestionCard(q, RoundedCornerShape(24.dp)) { Questions.answer(q.id, it) } }
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
                        placeholder = if (ready) "Ask anything, or say what to do" else "Finish setup first",
                    )
                }
            }
        }
    }

    if (showSteps) {
        ModalBottomSheet(onDismissRequest = { showSteps = false }) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PhoneFrame(run)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (run.running) run.phase.ifBlank { "Starting" } else run.phase, style = MaterialTheme.typography.titleMedium)
                    run.task?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    Hint(if (run.steps == 1) "1 step" else "${run.steps} steps")
                }
            }
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(run.log) { line -> LogRow(line) }
            }
        }
    }
}

/** The empty chat: a greeting and a few starters, like the first screen of Gemini. */
@Composable
private fun Greeting(tokens: CodexTokens?, onSuggestion: (String) -> Unit) {
    val name = tokens?.email?.substringBefore('@')?.substringBefore('.')?.replaceFirstChar(Char::uppercase)
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            if (name != null) "Hi, $name.\nWhat's the plan?" else "Hi.\nWhat's the plan?",
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Suggestion("Set a timer for 10 minutes") { onSuggestion("Set a timer for 10 minutes") }
            Suggestion("What's the weather today?") { onSuggestion("What's the weather today?") }
            Suggestion("Turn on do not disturb") { onSuggestion("Turn on do not disturb") }
            Suggestion("Text Anna I'm running late") { onSuggestion("Text Anna I'm running late") }
        }
    }
}

/** The side drawer: new chat, recent chats, and the account row with settings. */
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
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Android Use", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close") }
        }
        Spacer(Modifier.height(8.dp))
        NavigationDrawerItem(
            label = { Text("New chat") },
            icon = { Icon(Icons.Outlined.Edit, null) },
            selected = chatEmpty,
            onClick = onNewChat,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (chats.isNotEmpty()) {
                item {
                    Text(
                        "Recent", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
                    )
                }
                items(chats, key = { it.id }) { chat ->
                    RecentChatRow(chat, selected = chat.id == currentChat && !chatEmpty, onOpen = { onOpenChat(chat.id) }, onDelete = { onDeleteChat(chat.id) })
                }
            } else {
                item {
                    Text(
                        "Your chats will show up here.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(tokens, onClick = onSettings)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tokens?.email ?: "Not signed in", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                tokens?.plan?.let { Text("${it.replaceFirstChar(Char::uppercase)} plan", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
        }
    }
}

/** A round initial from the signed-in email, as the account button. */
@Composable
private fun Avatar(tokens: CodexTokens?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val initial = tokens?.email?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    IconButton(onClick = onClick) {
        Box(Modifier.size(32.dp).background(if (tokens != null) colors.primary else colors.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
            Text(initial, style = MaterialTheme.typography.titleSmall, color = if (tokens != null) colors.onPrimary else colors.onSurface)
        }
    }
}

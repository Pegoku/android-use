package dev.pegoku.androiduse.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pegoku.androiduse.Settings
import dev.pegoku.androiduse.agent.ApprovalRequest
import dev.pegoku.androiduse.assistant.AssistantActivity
import androidx.lifecycle.repeatOnLifecycle
import dev.pegoku.androiduse.agent.Approvals
import dev.pegoku.androiduse.agent.Guard
import dev.pegoku.androiduse.agent.LogKind
import dev.pegoku.androiduse.agent.LogLine
import dev.pegoku.androiduse.agent.TaskRunner
import dev.pegoku.androiduse.auth.CodexAuth
import dev.pegoku.androiduse.llm.CodexClient
import dev.pegoku.androiduse.shell.ShizukuShell
import dev.pegoku.androiduse.shell.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val sharedTask = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        handleShare(intent)
        setContent {
            val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(this) else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { Screen(sharedTask) }
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

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedTask.value = it }
    }
}

@Composable
private fun Screen(sharedTask: androidx.compose.runtime.MutableState<String?>) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val run by TaskRunner.state.collectAsStateWithLifecycle()
    val shizuku by ShizukuShell.state.collectAsStateWithLifecycle()
    val tokens by CodexAuth.tokens.collectAsStateWithLifecycle()
    val approval by Approvals.current.collectAsStateWithLifecycle()
    var task by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    val ready = shizuku == ShizukuState.Ready && tokens != null
    // Settings are plain prefs; re-read them whenever the settings card closes.
    val reportChat = remember(showSettings) { settings.reportChat.takeIf { settings.reportEnabled && it.isNotBlank() } }
    val listState = rememberLazyListState()
    LaunchedEffect(sharedTask.value) {
        sharedTask.value?.let {
            task = it
            sharedTask.value = null
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(run.log.size) { if (run.log.isNotEmpty()) listState.animateScrollToItem(run.log.size + HEADER_ITEMS - 1) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Android Use", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Close settings" else "Settings") }
            }
        }
        item { approval?.let { ApprovalCard(it) } }
        item { SetupCard(shizuku, tokens) }
        item { AssistantCard(shizuku) }
        item { if (showSettings) SettingsCard(settings) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = task,
                        onValueChange = { task = it },
                        label = { Text("What should I do?") },
                        placeholder = { Text("Open Revolut, get my EUR balance and email it to me@example.com") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !run.running,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (run.running) {
                            Button(onClick = TaskRunner::stop) { Text("Stop") }
                        } else {
                            Button(onClick = { TaskRunner.start(context, task.trim()) }, enabled = ready && task.isNotBlank()) { Text("Run") }
                        }
                        Text(
                            run.phase.ifEmpty { if (ready) "Ready" else "Finish setup above" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (reportChat != null) {
                        Text(
                            "Result will be reported to WhatsApp chat \"$reportChat\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            run.screen?.let { bitmap ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(bitmap.asImageBitmap(), contentDescription = "Last screen", modifier = Modifier.width(120.dp))
                    run.result?.let { result ->
                        Column {
                            Text(if (result.success) "Done" else "Failed", fontWeight = FontWeight.Bold)
                            Text(result.summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        items(run.log) { LogRow(it) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private const val HEADER_ITEMS = 7

/**
 * Opens [url] in the user's browser explicitly. A plain VIEW intent for auth.openai.com is captured by
 * the ChatGPT app (it claims those links), which shows "Your session has ended" instead of the login page.
 */
private fun openInBrowser(context: android.content.Context, url: String) {
    val pm = context.packageManager
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
    val browsers = pm.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }.distinct()
    val default = pm.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    val browser = default?.takeIf { it in browsers } ?: browsers.firstOrNull()
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    if (browser != null) intent.setPackage(browser)
    context.startActivity(intent)
}

private const val POWER_LONG_PRESS = "power_button_long_press"

private fun powerOpensAssistant(context: android.content.Context): Boolean =
    android.provider.Settings.Global.getInt(context.contentResolver, POWER_LONG_PRESS, 1) == 5

private fun isDefaultAssistant(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
    } else {
        android.provider.Settings.Secure.getString(context.contentResolver, "assistant")?.startsWith(context.packageName) == true
    }

@Composable
private fun AssistantCard(shizuku: ShizukuState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDefault by remember { mutableStateOf(isDefaultAssistant(context)) }
    var powerOpens by remember { mutableStateOf(powerOpensAssistant(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            isDefault = isDefaultAssistant(context)
            powerOpens = powerOpensAssistant(context)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Assistant", fontWeight = FontWeight.Bold)
            Text(
                if (isDefault) "Android Use is your default assistant. Long-press Home (or the power button, if set up) to chat or ask it to do things."
                else "Make Android Use your default assistant to open it over any app with long-press Home or the power button.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isDefault) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val role = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && shizuku == ShizukuState.Ready) {
                                runCatching {
                                    ShizukuShell.run("cmd role add-role-holder --user 0 android.app.role.ASSISTANT ${context.packageName}")
                                }.getOrNull()
                            } else null
                            isDefault = isDefaultAssistant(context)
                            if (!isDefault) {
                                message = role?.output?.takeIf { it.isNotBlank() }?.let { "Couldn't set it automatically ($it). Pick Android Use as the digital assistant app." }
                                    ?: "Pick Android Use as the digital assistant app."
                                context.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } else {
                                message = null
                            }
                        }
                    }) { Text("Make default assistant") }
                }
                OutlinedButton(onClick = { context.startActivity(AssistantActivity.intent(context).putExtra(AssistantActivity.EXTRA_RESUMED, false)) }) {
                    Text("Try it")
                }
            }
            if (isDefault && shizuku == ShizukuState.Ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Open with long-press power")
                        Text(
                            "Instead of the power menu. Some phone makers use their own setting for this.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = powerOpens, onCheckedChange = { enable ->
                        scope.launch {
                            // AOSP/Pixel: 5 = digital assistant, 1 = power menu.
                            runCatching { ShizukuShell.run("settings put global $POWER_LONG_PRESS ${if (enable) 5 else 1}") }
                            powerOpens = powerOpensAssistant(context)
                        }
                    })
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ApprovalCard(request: ApprovalRequest) {
    Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Approval needed", fontWeight = FontWeight.Bold)
            Text(request.detail, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Approvals.answer(request.id, true) }) { Text("Approve") }
                OutlinedButton(onClick = { Approvals.answer(request.id, false) }) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val (prefix, color) = when (line.kind) {
        LogKind.Info -> "•" to MaterialTheme.colorScheme.onSurfaceVariant
        LogKind.Thought -> "💭" to MaterialTheme.colorScheme.onSurfaceVariant
        LogKind.Action -> "▶" to MaterialTheme.colorScheme.primary
        LogKind.Result -> "↳" to MaterialTheme.colorScheme.onSurfaceVariant
        LogKind.Error -> "✗" to MaterialTheme.colorScheme.error
        LogKind.Done -> "✓" to Color(0xFF1B7F3B)
    }
    Row {
        Text(prefix, color = color, modifier = Modifier.width(22.dp))
        Text(
            line.text,
            color = color,
            fontSize = 13.sp,
            fontFamily = if (line.kind == LogKind.Action || line.kind == LogKind.Result) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun SetupCard(shizuku: ShizukuState, tokens: dev.pegoku.androiduse.auth.CodexTokens?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authMessage by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf<CodexAuth.Login?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        authMessage = runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            CodexAuth.importAuthJson(text)
            "Imported auth.json."
        }.getOrElse { "Import failed: ${it.message}" }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Shizuku", fontWeight = FontWeight.Bold)
            when (shizuku) {
                ShizukuState.Ready -> Text("Connected — shell access granted.")
                ShizukuState.PermissionNeeded -> {
                    Text("Shizuku is running but Android Use isn't authorized.")
                    FilledTonalButton(onClick = ShizukuShell::requestPermission) { Text("Grant Shizuku access") }
                }
                ShizukuState.NotRunning -> {
                    Text("Shizuku isn't running. Install Shizuku and start it (wireless debugging or root), then come back.")
                    FilledTonalButton(onClick = {
                        val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                        context.startActivity(launch)
                    }) { Text("Open Shizuku") }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("Codex (ChatGPT subscription)", fontWeight = FontWeight.Bold)
            if (tokens != null) {
                Text("Signed in as ${tokens.email ?: "unknown"}${tokens.plan?.let { " · $it plan" }.orEmpty()}")
                OutlinedButton(onClick = CodexAuth::signOut) { Text("Sign out") }
            } else if (login != null) {
                Text("Finish signing in in the browser…")
                Text(
                    "If you see \"Your session has ended\" or the ChatGPT app opens instead, copy the link and open it in your browser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        login?.url?.let { url ->
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Sign-in link", url))
                            authMessage = "Link copied. Paste it into your browser's address bar."
                        }
                    }) { Text("Copy link") }
                    OutlinedButton(onClick = { login?.cancel() }) { Text("Cancel") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        val pending = runCatching { CodexAuth.startLogin() }.getOrElse {
                            authMessage = "Could not start sign-in: ${it.message}"
                            return@FilledTonalButton
                        }
                        login = pending
                        authMessage = null
                        openInBrowser(context, pending.url)
                        scope.launch {
                            authMessage = runCatching { pending.complete(); "Signed in." }.getOrElse { it.message }
                            login = null
                        }
                    }) { Text("Sign in with ChatGPT") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text("Import auth.json")
                    }
                }
                Text(
                    "Importing ~/.codex/auth.json shares the desktop session; the phone refreshing it may sign the desktop Codex out. Signing in here is preferred.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            authMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SettingsCard(settings: Settings) {
    var model by remember { mutableStateOf(settings.model) }
    var fastModel by remember { mutableStateOf(settings.fastModel) }
    var effort by remember { mutableStateOf(settings.effort) }
    var maxSteps by remember { mutableStateOf(settings.maxSteps.toString()) }
    var reportEnabled by remember { mutableStateOf(settings.reportEnabled) }
    var approvalMode by remember { mutableStateOf(settings.approvalMode) }
    var reviewerModel by remember { mutableStateOf(settings.reviewerModel) }
    var shellEnabled by remember { mutableStateOf(settings.shellEnabled) }
    var reportChat by remember { mutableStateOf(settings.reportChat) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        models = withContext(Dispatchers.IO) { runCatching { CodexClient.models() }.getOrDefault(emptyList()) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; settings.model = it },
                    label = { Text("Main model") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Column {
                    TextButton(onClick = { menuOpen = true }, enabled = models.isNotEmpty()) { Text("Pick") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        models.forEach { slug ->
                            DropdownMenuItem(text = { Text(slug) }, onClick = {
                                model = slug
                                settings.model = slug
                                menuOpen = false
                            })
                        }
                    }
                }
            }
            OutlinedTextField(
                value = fastModel,
                onValueChange = { fastModel = it; settings.fastModel = it },
                label = { Text("Fast model for routine steps (blank = off)") },
                supportingText = { Text("The main model takes over when the fast one gets stuck.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Reasoning effort", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.EFFORTS.forEach { level ->
                    FilterChip(selected = effort == level, onClick = { effort = level; settings.effort = level }, label = { Text(level) })
                }
            }
            OutlinedTextField(
                value = maxSteps,
                onValueChange = { value ->
                    maxSteps = value.filter(Char::isDigit)
                    maxSteps.toIntOrNull()?.takeIf { it in 5..300 }?.let { settings.maxSteps = it }
                },
                label = { Text("Max steps per task") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Sensitive actions", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = approvalMode == Guard.MODE_AUTO,
                    onClick = { approvalMode = Guard.MODE_AUTO; settings.approvalMode = Guard.MODE_AUTO },
                    label = { Text("Auto-review") },
                )
                FilterChip(
                    selected = approvalMode == Guard.MODE_ASK,
                    onClick = { approvalMode = Guard.MODE_ASK; settings.approvalMode = Guard.MODE_ASK },
                    label = { Text("Always ask me") },
                )
            }
            Text(
                if (approvalMode == Guard.MODE_AUTO) {
                    "A reviewer model checks each shell command or message to someone the task didn't name against your task. It approves clear cases, blocks unrelated or injected ones and asks you when unsure."
                } else "You approve every shell command and every message to someone the task didn't name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (approvalMode == Guard.MODE_AUTO) {
                OutlinedTextField(
                    value = reviewerModel,
                    onValueChange = { reviewerModel = it; settings.reviewerModel = it },
                    label = { Text("Reviewer model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow shell commands")
                    Text(
                        "Read-only queries are always allowed. This lets the agent run other commands after review; destructive ones are always refused.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = shellEnabled, onCheckedChange = { shellEnabled = it; settings.shellEnabled = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Report result to WhatsApp", modifier = Modifier.weight(1f))
                Switch(checked = reportEnabled, onCheckedChange = { reportEnabled = it; settings.reportEnabled = it })
            }
            OutlinedTextField(
                value = reportChat,
                onValueChange = { reportChat = it; settings.reportChat = it },
                label = { Text("WhatsApp chat name") },
                singleLine = true,
                enabled = reportEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

package dev.pegoku.androiduse.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowRightAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.pegoku.androiduse.Settings
import dev.pegoku.androiduse.agent.ApprovalRequest
import dev.pegoku.androiduse.agent.Approvals
import dev.pegoku.androiduse.agent.LogKind
import dev.pegoku.androiduse.agent.LogLine
import dev.pegoku.androiduse.agent.RunState
import dev.pegoku.androiduse.assistant.AssistantActivity
import dev.pegoku.androiduse.auth.CodexAuth
import dev.pegoku.androiduse.auth.CodexTokens
import dev.pegoku.androiduse.llm.CodexUsageStore
import dev.pegoku.androiduse.llm.UsageWindow
import dev.pegoku.androiduse.shell.ShizukuShell
import dev.pegoku.androiduse.shell.ShizukuState
import kotlinx.coroutines.launch

/*
 * Setup and account pieces: Shizuku, the ChatGPT sign-in, Codex usage, the default-assistant role,
 * approvals, and the step log of the current task. Shown in Settings and, until setup is done, in the chat.
 */

@Composable
internal fun CardTitle(text: String, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
internal fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** The last screenshot, framed like the phone it came from. */
@Composable
internal fun PhoneFrame(run: RunState) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(width = 84.dp, height = 168.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerHighest)
            .border(2.dp, colors.outlineVariant, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = run.screen
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), "Last screen", modifier = Modifier.fillMaxSize().padding(2.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            Icon(Icons.Outlined.TouchApp, null, tint = colors.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
internal fun LogRow(line: LogLine) {
    val colors = MaterialTheme.colorScheme
    val (icon, color) = when (line.kind) {
        LogKind.Info -> Icons.Outlined.Circle to colors.onSurfaceVariant
        LogKind.Thought -> Icons.Outlined.Lightbulb to colors.onSurfaceVariant
        LogKind.Action -> Icons.Outlined.TouchApp to colors.primary
        LogKind.Result -> Icons.AutoMirrored.Outlined.ArrowRightAlt to colors.onSurfaceVariant
        LogKind.Error -> Icons.Outlined.ErrorOutline to colors.error
        LogKind.Done -> Icons.Outlined.CheckCircle to colors.success
    }
    val monospace = line.kind == LogKind.Action || line.kind == LogKind.Result
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.padding(top = 2.dp).size(if (line.kind == LogKind.Info) 10.dp else 16.dp).let {
            if (line.kind == LogKind.Info) it.padding(top = 3.dp) else it
        })
        Spacer(Modifier.width(12.dp))
        Text(
            line.text,
            color = if (line.kind == LogKind.Thought) colors.onSurfaceVariant else colors.onSurface.takeIf { line.kind == LogKind.Info } ?: color,
            style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp) else MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun ApprovalCard(request: ApprovalRequest) {
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.errorContainer), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Approval needed", style = MaterialTheme.typography.labelLarge, color = colors.onErrorContainer.copy(alpha = 0.75f))
            Text(request.title, style = MaterialTheme.typography.titleMedium, color = colors.onErrorContainer)
            Text(request.detail, style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Approvals.answer(request.id, true) }) { Text("Approve") }
                OutlinedButton(onClick = { Approvals.answer(request.id, false) }) { Text("Deny") }
            }
        }
    }
}

// ----- Setup, account and usage -----

/**
 * Opens [url] in the user's browser explicitly. A plain VIEW intent for auth.openai.com is captured by
 * the ChatGPT app (it claims those links), which shows "Your session has ended" instead of the login page.
 */
internal fun openInBrowser(context: Context, url: String) {
    val pm = context.packageManager
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
    val browsers = pm.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }.distinct()
    val default = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    val browser = default?.takeIf { it in browsers } ?: browsers.firstOrNull()
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    if (browser != null) intent.setPackage(browser)
    context.startActivity(intent)
}

/** Shown until both Shizuku and the ChatGPT sign-in are in place. */
@Composable
internal fun SetupCard(shizuku: ShizukuState, tokens: CodexTokens?) {
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.primaryContainer), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Two things before the first task", style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
                Text(
                    "Shizuku lets the agent touch the screen. Your ChatGPT plan is the model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Surface(shape = MaterialTheme.shapes.medium, color = colors.surface) {
                Column {
                    ShizukuStep(shizuku)
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    SignInStep(tokens)
                }
            }
        }
    }
}

@Composable
internal fun StepRow(done: Boolean, title: String, status: String, actions: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done) Icons.Outlined.CheckCircle else Icons.Outlined.Circle,
                null,
                tint = if (done) colors.success else colors.outline,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Hint(status)
            }
        }
        if (actions != null && !done) {
            Row(Modifier.padding(start = 34.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@Composable
internal fun ShizukuStep(shizuku: ShizukuState) {
    val context = LocalContext.current
    when (shizuku) {
        ShizukuState.Ready -> StepRow(done = true, title = "Shizuku", status = "Connected.")
        ShizukuState.PermissionNeeded -> StepRow(done = false, title = "Shizuku", status = "Running, but Android Use isn't allowed to use it yet.") {
            FilledTonalButton(onClick = ShizukuShell::requestPermission) { Text("Allow") }
        }
        ShizukuState.NotRunning -> StepRow(done = false, title = "Shizuku", status = "Not running. Start it with wireless debugging or root, then come back.") {
            FilledTonalButton(onClick = {
                val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                context.startActivity(launch)
            }) { Text("Open Shizuku") }
        }
    }
}

@Composable
internal fun SignInStep(tokens: CodexTokens?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf<CodexAuth.Login?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            CodexAuth.importAuthJson(text)
            "Imported."
        }.getOrElse { "Import failed: ${it.message}" }
    }

    val pending = login
    when {
        tokens != null -> StepRow(done = true, title = "ChatGPT", status = "Signed in as ${tokens.email ?: "unknown"}.")
        pending != null -> StepRow(done = false, title = "ChatGPT", status = "Finish signing in in the browser. If the ChatGPT app opens instead or says your session ended, copy the link into a browser.") {
            OutlinedButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Sign-in link", pending.url))
                message = "Link copied."
            }) { Text("Copy link") }
            TextButton(onClick = { pending.cancel() }) { Text("Cancel") }
        }
        else -> StepRow(done = false, title = "ChatGPT", status = "Sign in with the account whose plan should be used.") {
            FilledTonalButton(onClick = {
                val started = runCatching { CodexAuth.startLogin() }.getOrElse {
                    message = "Couldn't start sign-in: ${it.message}"
                    return@FilledTonalButton
                }
                login = started
                message = null
                openInBrowser(context, started.url)
                scope.launch {
                    message = runCatching { started.complete(); null }.getOrElse { it.message }
                    login = null
                }
            }) { Text("Sign in") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import auth.json") }
        }
    }
    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if ("failed" in it || "Couldn't" in it) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
    }
}

/** Who is signed in and how much of the ChatGPT plan is left for Codex. */
@Composable
internal fun AccountCard(tokens: CodexTokens) {
    val colors = MaterialTheme.colorScheme
    val usage by CodexUsageStore.usage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { CodexUsageStore.refresh() }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Codex usage", style = MaterialTheme.typography.titleMedium)
                    Hint(listOfNotNull(tokens.email, tokens.plan?.let { "${it.replaceFirstChar(Char::uppercase)} plan" }).joinToString(" · "))
                }
                TextButton(
                    onClick = { scope.launch { refreshing = true; CodexUsageStore.refresh(); refreshing = false } },
                    enabled = !refreshing,
                ) { Text(if (refreshing) "Refreshing…" else "Refresh") }
            }
            val current = usage
            if (current == null) {
                Hint("Not loaded yet.")
            } else {
                listOfNotNull(current.primary, current.secondary).forEach { window -> UsageBar(window) }
                if (current.limitReached) Hint("Limit reached. Tasks will fail until it resets.", color = colors.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { CodexAuth.signOut(); CodexUsageStore.clear() }) { Text("Sign out") }
            }
        }
    }
}

@Composable
internal fun UsageBar(window: UsageWindow) {
    val colors = MaterialTheme.colorScheme
    val low = window.leftPercent <= 15
    val tint = if (low) colors.error else colors.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (window.label == "Weekly") "This week" else "Next ${window.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${window.leftPercent}% left", style = MaterialTheme.typography.titleSmall, color = tint)
            Spacer(Modifier.width(8.dp))
            Hint("resets in ${window.resetsIn()}")
        }
        LinearProgressIndicator(
            progress = { window.leftPercent / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            color = tint,
            trackColor = colors.surfaceContainerHighest,
            drawStopIndicator = {},
        )
    }
}

@Composable
internal fun ShizukuChip() {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(colors.success, CircleShape))
        Spacer(Modifier.width(10.dp))
        Hint("Shizuku connected")
    }
}

// ----- Assistant -----

internal const val POWER_LONG_PRESS = "power_button_long_press"

internal fun powerLongPress(context: Context): Int =
    android.provider.Settings.Global.getInt(context.contentResolver, POWER_LONG_PRESS, 1)

internal fun powerOpensAssistant(context: Context): Boolean = powerLongPress(context) == 5

/** Opens the system's "Digital assistant app" picker, falling back to the default-apps page. */
internal fun openAssistantSettings(context: Context) {
    val intents = listOf(
        Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    val intent = intents.first { it.resolveActivity(context.packageManager) != null || it == intents.last() }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun isDefaultAssistant(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
    } else {
        android.provider.Settings.Secure.getString(context.contentResolver, "assistant")?.startsWith(context.packageName) == true
    }

@Composable
internal fun AssistantCard(shizuku: ShizukuState) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var isDefault by remember { mutableStateOf(isDefaultAssistant(context)) }
    var powerOpens by remember { mutableStateOf(powerOpensAssistant(context)) }
    val settings = remember { Settings(context) }
    var message by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            isDefault = isDefaultAssistant(context)
            powerOpens = powerOpensAssistant(context)
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle("Assistant", Icons.Outlined.AutoAwesome) {
                TextButton(onClick = { context.startActivity(AssistantActivity.intent(context).putExtra(AssistantActivity.EXTRA_RESUMED, false)) }) {
                    Text("Try it")
                }
            }
            Text(
                if (isDefault) "Long-press Home to chat or ask for something over any app."
                else "Replace Gemini: open Android Use over any app with long-press Home.",
                style = MaterialTheme.typography.bodyMedium,
            )
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
                            openAssistantSettings(context)
                        } else {
                            message = null
                        }
                    }
                }) { Text("Make default assistant") }
            }
            AnimatedVisibility(visible = isDefault && shizuku == ShizukuState.Ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Long-press power opens it too", style = MaterialTheme.typography.bodyLarge)
                        Hint("Instead of the power menu. Some phone makers have their own setting for this.")
                    }
                    Spacer(Modifier.width(16.dp))
                    Switch(checked = powerOpens, onCheckedChange = { enable ->
                        scope.launch {
                            // AOSP/Pixel: 5 = digital assistant, 1 = power menu. Turning it off restores what was there before.
                            if (enable) settings.previousPowerLongPress = powerLongPress(context).takeIf { it != 5 }
                            val value = if (enable) 5 else settings.previousPowerLongPress ?: 1
                            runCatching { ShizukuShell.run("settings put global $POWER_LONG_PRESS $value") }
                            powerOpens = powerOpensAssistant(context)
                        }
                    })
                }
            }
            // Android's own page for picking the digital assistant (switch back to Gemini, etc.).
            OutlinedButton(onClick = { openAssistantSettings(context) }) { Text("Change assistant") }
            message?.let { Hint(it) }
        }
    }
}

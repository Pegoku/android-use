package dev.pegoku.androiduse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pegoku.androiduse.Settings
import dev.pegoku.androiduse.agent.Guard
import dev.pegoku.androiduse.llm.CodexClient
import dev.pegoku.androiduse.memory.Memory
import dev.pegoku.androiduse.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Full-screen settings: models, agent limits, safety, reporting and the agent's memory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item { ModelsSection(settings) }
            item { AgentSection(settings) }
            item { SafetySection(settings) }
            item { ReportingSection(settings) }
            item { MemorySection() }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun Section(title: String, description: String? = null, last: Boolean = false, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
    if (!last) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(title: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Hint(description)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsSection(settings: Settings) {
    var model by remember { mutableStateOf(settings.model) }
    var fastModel by remember { mutableStateOf(settings.fastModel) }
    var effort by remember { mutableStateOf(settings.effort) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        models = withContext(Dispatchers.IO) { runCatching { CodexClient.models() }.getOrDefault(emptyList()) }
    }

    Section("Models", "Both come from your ChatGPT plan.") {
        ExposedDropdownMenuBox(expanded = menuOpen && models.isNotEmpty(), onExpandedChange = { menuOpen = it }) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; settings.model = it },
                label = { Text("Main model") },
                supportingText = { Text("Takes over when a step fails or nothing changes. The only one allowed to finish a task.") },
                trailingIcon = { if (models.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            )
            ExposedDropdownMenu(expanded = menuOpen && models.isNotEmpty(), onDismissRequest = { menuOpen = false }) {
                models.forEach { slug ->
                    DropdownMenuItem(
                        text = { Text(slug) },
                        onClick = { model = slug; settings.model = slug; menuOpen = false },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
        OutlinedTextField(
            value = fastModel,
            onValueChange = { fastModel = it; settings.fastModel = it },
            label = { Text("Fast model") },
            placeholder = { Text("Leave empty to use the main model for everything") },
            supportingText = { Text("Runs routine steps. The assistant chat also uses it.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reasoning effort", style = MaterialTheme.typography.bodyLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Settings.EFFORTS.forEachIndexed { index, level ->
                    SegmentedButton(
                        selected = effort == level,
                        onClick = { effort = level; settings.effort = level },
                        shape = SegmentedButtonDefaults.itemShape(index, Settings.EFFORTS.size),
                        label = { Text(level.replaceFirstChar(Char::uppercase)) },
                    )
                }
            }
            Hint("Low is fastest. Higher effort thinks longer on every step.")
        }
    }
}

@Composable
private fun AgentSection(settings: Settings) {
    var maxSteps by remember { mutableStateOf(settings.maxSteps.toFloat()) }
    Section("Task limits") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Max steps per task", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(maxSteps.roundToInt().toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = maxSteps,
                onValueChange = { maxSteps = (it / 5f).roundToInt() * 5f },
                onValueChangeFinished = { settings.maxSteps = maxSteps.roundToInt() },
                valueRange = 5f..300f,
            )
            Hint("A step is one action on the phone. The task fails once this many are used.")
        }
    }
}

@Composable
private fun SafetySection(settings: Settings) {
    var approvalMode by remember { mutableStateOf(settings.approvalMode) }
    var reviewerModel by remember { mutableStateOf(settings.reviewerModel) }
    var shellEnabled by remember { mutableStateOf(settings.shellEnabled) }
    Section("Sensitive actions", "Shell commands and messages to people the task didn't name.") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = approvalMode == Guard.MODE_AUTO,
                onClick = { approvalMode = Guard.MODE_AUTO; settings.approvalMode = Guard.MODE_AUTO },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                label = { Text("Review automatically") },
            )
            SegmentedButton(
                selected = approvalMode == Guard.MODE_ASK,
                onClick = { approvalMode = Guard.MODE_ASK; settings.approvalMode = Guard.MODE_ASK },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                label = { Text("Always ask me") },
            )
        }
        Hint(
            if (approvalMode == Guard.MODE_AUTO) {
                "A reviewer model checks each one against your task. Clear cases go through, unrelated or injected ones are blocked, and you're asked when it's unsure."
            } else {
                "You get a notification for every one and nothing happens until you answer."
            },
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
        SwitchRow(
            title = "Allow shell commands",
            description = "Read-only queries are always allowed. This lets the agent change things with the shell after review. Destructive commands are always refused.",
            checked = shellEnabled,
            onCheckedChange = { shellEnabled = it; settings.shellEnabled = it },
        )
    }
}

@Composable
private fun ReportingSection(settings: Settings) {
    var reportEnabled by remember { mutableStateOf(settings.reportEnabled) }
    var reportChat by remember { mutableStateOf(settings.reportChat) }
    Section("Report on WhatsApp") {
        SwitchRow(
            title = "Send the outcome to a chat",
            description = "After each task, the agent opens WhatsApp and posts \"Task done\" or \"Task failed\" with a summary.",
            checked = reportEnabled,
            onCheckedChange = { reportEnabled = it; settings.reportEnabled = it },
        )
        OutlinedTextField(
            value = reportChat,
            onValueChange = { reportChat = it; settings.reportChat = it },
            label = { Text("Chat name") },
            singleLine = true,
            enabled = reportEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Long-term memory: what the agent learned, editable and deletable by the owner. */
@Composable
private fun MemorySection() {
    val memories by MemoryStore.memories.collectAsStateWithLifecycle()
    var newMemory by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    Section(
        "Memory",
        "Things the agent learned: where settings live, how a task is done, what you told it. Every task and chat sees all of it.",
        last = true,
    ) {
        OutlinedTextField(
            value = newMemory,
            onValueChange = { newMemory = it },
            placeholder = { Text("Something to remember, e.g. \"My work email is on the Outlook app\"") },
            trailingIcon = {
                IconButton(onClick = { MemoryStore.add(newMemory); newMemory = "" }, enabled = newMemory.isNotBlank()) {
                    Icon(Icons.Default.Add, "Add memory")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (memories.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Lightbulb, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Hint("Nothing yet. Tasks save what worked, and what you tell them, for next time.")
            }
        } else {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    memories.asReversed().forEachIndexed { index, memory ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        MemoryRow(memory)
                    }
                }
            }
            TextButton(onClick = { confirmClear = true }) { Text("Forget everything", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Forget everything?") },
            text = { Text("All ${memories.size} memories will be deleted. Tasks start learning again from scratch.") },
            confirmButton = { TextButton(onClick = { MemoryStore.clear(); confirmClear = false }) { Text("Forget", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun MemoryRow(memory: Memory) {
    var editing by remember(memory.id) { mutableStateOf(false) }
    var text by remember(memory.id) { mutableStateOf(memory.text) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        if (editing) {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = { MemoryStore.update(memory.id, text); editing = false }, enabled = text.isNotBlank()) { Text("Save") }
                TextButton(onClick = { text = memory.text; editing = false }) { Text("Cancel") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    memory.app?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    Text(memory.text, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { MemoryStore.remove(memory.id) }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

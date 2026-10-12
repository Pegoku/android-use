package dev.pegoku.androiduse.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import dev.pegoku.androiduse.Settings
import dev.pegoku.androiduse.device.Device
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LogLine(val kind: LogKind, val text: String, val time: Long = System.currentTimeMillis())

data class RunState(
    val running: Boolean = false,
    val task: String? = null,
    val phase: String = "",
    val log: List<LogLine> = emptyList(),
    val screen: Bitmap? = null,
    val result: AgentResult? = null,
)

/** Process-wide holder for the current task, shared between [AgentService] and the UI. */
object TaskRunner {
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    @Volatile internal var job: Job? = null

    fun start(context: Context, task: String) {
        if (_state.value.running) return
        _state.value = RunState(running = true, task = task, phase = "Starting")
        context.startForegroundService(Intent(context, AgentService::class.java).putExtra(AgentService.EXTRA_TASK, task))
    }

    fun stop() {
        job?.cancel()
    }

    private fun log(kind: LogKind, text: String) {
        Log.i("AndroidUse", "$kind: $text")
        _state.update { it.copy(log = (it.log + LogLine(kind, text)).takeLast(500)) }
    }

    private fun phase(text: String) = _state.update { it.copy(phase = text) }

    /** Runs the task and the optional WhatsApp report. Called from the service's coroutine. */
    suspend fun execute(context: Context, task: String, onProgress: (String) -> Unit): AgentResult {
        val settings = Settings(context)
        val device = Device(context)
        fun agent(maxSteps: Int, task: String) = Agent(
            device = device,
            model = settings.model,
            fastModel = settings.fastModel.takeIf { it.isNotBlank() && it != settings.model },
            effort = settings.effort,
            maxSteps = maxSteps,
            log = { kind, text ->
                log(kind, text)
                if (kind == LogKind.Action) onProgress(text)
            },
            onScreen = { obs -> _state.update { it.copy(screen = obs.bitmap) } },
            guard = Guard(
                context = context,
                task = task,
                mode = settings.approvalMode,
                reviewerModel = settings.reviewerModel,
                fallbackModel = settings.model,
                log = ::log,
            ),
            shellEnabled = settings.shellEnabled,
        )

        val result = try {
            phase("Preparing device")
            device.prepare()?.let { error(it) }
            device.pressKey("home")
            delay(600)
            phase("Working on task")
            log(LogKind.Info, "Task: $task")
            agent(settings.maxSteps, task).run(task)
        } catch (e: CancellationException) {
            log(LogKind.Error, "Stopped by user.")
            _state.update { it.copy(running = false, phase = "Stopped", result = AgentResult(false, "Stopped by user")) }
            throw e
        } catch (e: Exception) {
            AgentResult(false, e.message ?: e.javaClass.simpleName)
        }
        log(if (result.success) LogKind.Done else LogKind.Error, (if (result.success) "Done: " else "Failed: ") + result.summary)

        if (settings.reportEnabled && settings.reportChat.isNotBlank()) {
            phase("Reporting to WhatsApp")
            log(LogKind.Info, "Reporting result to WhatsApp chat \"${settings.reportChat}\"")
            val message = buildString {
                append(if (result.success) "Task done: " else "Task failed: ")
                append(task.replace('\n', ' ').take(200))
                append("\n").append(result.summary.take(600))
            }
            val reportTask = """
                Open WhatsApp and send a message to the chat named "${settings.reportChat}" (it is a group chat; use WhatsApp's search if it isn't visible).
                The message text must be exactly:
                $message
                Then confirm the message appears as sent in that chat. Do nothing else.
            """.trimIndent()
            val report = runCatching { agent(30, reportTask).run(reportTask) }
                .getOrElse { if (it is CancellationException) throw it else AgentResult(false, it.message ?: "error") }
            log(if (report.success) LogKind.Done else LogKind.Error, "WhatsApp report: " + report.summary)
        }

        _state.update { it.copy(running = false, phase = if (result.success) "Done" else "Failed", result = result) }
        return result
    }

    internal fun markFinished() {
        _state.update { if (it.running) it.copy(running = false, phase = "Stopped") else it }
    }
}

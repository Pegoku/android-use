package dev.pegoku.androiduse.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.pegoku.androiduse.llm.CodexUsage
import dev.pegoku.androiduse.llm.CodexUsageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import dev.pegoku.androiduse.shell.ShizukuShell

/** Foreground service that keeps the agent alive while other apps are in front. */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifications: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_RUN, "Running task", NotificationManager.IMPORTANCE_LOW))
        ResultNotification.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TaskRunner.stop()
            return START_NOT_STICKY
        }
        val task = intent?.getStringExtra(EXTRA_TASK)
        if (task == null || TaskRunner.job?.isActive == true) return START_NOT_STICKY

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_RUN, liveNotification(TaskRunner.state.value, null, null, CodexUsageStore.usage.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_RUN, liveNotification(TaskRunner.state.value, null, null, CodexUsageStore.usage.value))
        }
        @Suppress("DEPRECATION")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "android-use:task")
            .apply { acquire(60 * 60_000L) }

        scope.launch { CodexUsageStore.refresh() }
        // Keep the live notification in step with the task, at most a couple of updates per second.
        val updates = scope.launch {
            combine(TaskRunner.state, Approvals.current, Questions.current, CodexUsageStore.usage, TaskRunner.paused) { state, approval, question, usage, _ ->
                { if (state.running) notifications.notify(ID_RUN, liveNotification(state, approval, question, usage)) }
            }
                .sample(500)
                .collect { it() }
        }
        TaskRunner.job = scope.launch {
            try {
                val result = TaskRunner.execute(this@AgentService, task, intent.getBooleanExtra(EXTRA_FROM_HOME, true))
                ResultNotification.show(this@AgentService, task, result)
            } finally {
                withContext(NonCancellable) {
                    // Deliver the result first: the assistant reopens itself through the shell service.
                    runCatching { TaskRunner.markFinished() }
                    runCatching { ShizukuShell.release() }
                }
                updates.cancel()
                wakeLock?.takeIf { it.isHeld }?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun liveNotification(state: RunState, approval: ApprovalRequest?, question: QuestionRequest?, usage: CodexUsage?): Notification =
        LiveNotification.build(
            this, CHANNEL_RUN, state, approval, question, usage,
            PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE),
        )

    companion object {
        const val EXTRA_TASK = "task"
        const val EXTRA_FROM_HOME = "from_home"
        private const val ACTION_STOP = "dev.pegoku.androiduse.STOP"
        private const val CHANNEL_RUN = "run"
        private const val ID_RUN = 1
    }
}

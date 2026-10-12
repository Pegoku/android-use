package dev.pegoku.androiduse.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import dev.pegoku.androiduse.R
import dev.pegoku.androiduse.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_RESULT, "Task results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TaskRunner.stop()
            return START_NOT_STICKY
        }
        val task = intent?.getStringExtra(EXTRA_TASK)
        if (task == null || TaskRunner.job?.isActive == true) return START_NOT_STICKY

        startForeground(ID_RUN, progress("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        @Suppress("DEPRECATION")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "android-use:task")
            .apply { acquire(60 * 60_000L) }

        TaskRunner.job = scope.launch {
            try {
                val result = TaskRunner.execute(this@AgentService, task) { notifications.notify(ID_RUN, progress(it)) }
                notifications.notify(ID_RESULT, resultNotification(if (result.success) "Task done" else "Task failed", result.summary))
            } finally {
                withContext(NonCancellable) { runCatching { ShizukuShell.release() } }
                TaskRunner.markFinished()
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

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progress(text: String): Notification = Notification.Builder(this, CHANNEL_RUN)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Android Use is working")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(openApp())
        .addAction(
            Notification.Action.Builder(
                null, "Stop",
                PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE),
            ).build(),
        )
        .build()

    private fun resultNotification(title: String, text: String): Notification = Notification.Builder(this, CHANNEL_RESULT)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(openApp())
        .setAutoCancel(true)
        .build()

    companion object {
        const val EXTRA_TASK = "task"
        private const val ACTION_STOP = "dev.pegoku.androiduse.STOP"
        private const val CHANNEL_RUN = "run"
        private const val CHANNEL_RESULT = "result"
        private const val ID_RUN = 1
        private const val ID_RESULT = 2
    }
}

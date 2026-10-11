package dev.pegoku.androiduse.shell

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dev.pegoku.androiduse.BuildConfig
import dev.pegoku.androiduse.IShellService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

data class ShellResult(val exitCode: Int, val output: String) {
    val ok get() = exitCode == 0
}

enum class ShizukuState { NotRunning, PermissionNeeded, Ready }

/** App-side handle to the Shizuku-hosted [ShellService]. */
object ShizukuShell {
    private const val PERMISSION_REQUEST = 4217

    private val _state = MutableStateFlow(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile private var service: IShellService? = null
    @Volatile private var pending: CompletableDeferred<IShellService>? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val shell = IShellService.Stub.asInterface(binder)
            service = shell
            pending?.complete(shell)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun refresh() {
        _state.value = when {
            !Shizuku.pingBinder() -> ShizukuState.NotRunning
            Shizuku.isPreV11() -> ShizukuState.NotRunning
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.PermissionNeeded
            else -> ShizukuState.Ready
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(PERMISSION_REQUEST)
        }
    }

    private suspend fun connect(): IShellService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        refresh()
        check(_state.value == ShizukuState.Ready) {
            when (_state.value) {
                ShizukuState.NotRunning -> "Shizuku is not running. Start it from the Shizuku app."
                else -> "Shizuku permission not granted to Android Use."
            }
        }
        val deferred = CompletableDeferred<IShellService>()
        pending = deferred
        withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
        return withTimeout(15_000) { deferred.await() }
    }

    suspend fun run(command: String, timeoutMs: Long = 30_000): ShellResult = withContext(Dispatchers.IO) {
        val raw = connect().run(command, timeoutMs)
        val newline = raw.indexOf('\n')
        ShellResult(raw.substring(0, newline).toInt(), raw.substring(newline + 1))
    }

    suspend fun bytes(command: String): ByteArray = withContext(Dispatchers.IO) {
        val pfd = connect().stream(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}

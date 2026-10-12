package dev.pegoku.androiduse

import android.app.Application
import dev.pegoku.androiduse.auth.CodexAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.pegoku.androiduse.shell.ShizukuShell

class App : Application() {
    companion object {
        /** Work that must outlive any one screen, such as an assistant reply still streaming in. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onCreate() {
        super.onCreate()
        CodexAuth.init(this)
        ShizukuShell.init()
    }
}

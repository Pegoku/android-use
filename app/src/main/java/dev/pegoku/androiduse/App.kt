package dev.pegoku.androiduse

import android.app.Application
import dev.pegoku.androiduse.auth.CodexAuth
import dev.pegoku.androiduse.shell.ShizukuShell

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CodexAuth.init(this)
        ShizukuShell.init()
    }
}

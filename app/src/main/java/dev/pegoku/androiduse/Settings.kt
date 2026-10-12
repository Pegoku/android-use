package dev.pegoku.androiduse

import android.content.Context
import android.content.SharedPreferences

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    /** Cheaper, lower-latency model used for routine steps; blank disables it. */
    var fastModel: String
        get() = prefs.getString("fast_model", DEFAULT_FAST_MODEL) ?: DEFAULT_FAST_MODEL
        set(value) = prefs.edit().putString("fast_model", value.trim()).apply()

    var effort: String
        get() = prefs.getString("effort", "low") ?: "low"
        set(value) = prefs.edit().putString("effort", value).apply()

    var maxSteps: Int
        get() = prefs.getInt("max_steps", 60)
        set(value) = prefs.edit().putInt("max_steps", value).apply()

    var reportEnabled: Boolean
        get() = prefs.getBoolean("report_enabled", true)
        set(value) = prefs.edit().putBoolean("report_enabled", value).apply()

    var reportChat: String
        get() = prefs.getString("report_chat", "Notes") ?: "Notes"
        set(value) = prefs.edit().putString("report_chat", value.trim()).apply()

    companion object {
        const val DEFAULT_MODEL = "gpt-6.1-sol"
        const val DEFAULT_FAST_MODEL = "gpt-6-luna"
        val EFFORTS = listOf("low", "medium", "high")
    }
}

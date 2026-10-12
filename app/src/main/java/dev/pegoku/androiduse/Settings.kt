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

    /** "auto": a reviewer model approves clear cases and asks you otherwise; "ask": you approve everything. */
    var approvalMode: String
        get() = prefs.getString("approval_mode", "auto") ?: "auto"
        set(value) = prefs.edit().putString("approval_mode", value).apply()

    var reviewerModel: String
        get() = prefs.getString("reviewer_model", DEFAULT_REVIEWER_MODEL) ?: DEFAULT_REVIEWER_MODEL
        set(value) = prefs.edit().putString("reviewer_model", value.trim()).apply()

    /** Lets the agent run arbitrary shell commands (after approval). Read-only queries are always allowed. */
    var shellEnabled: Boolean
        get() = prefs.getBoolean("shell_enabled", false)
        set(value) = prefs.edit().putBoolean("shell_enabled", value).apply()

    var reportEnabled: Boolean
        get() = prefs.getBoolean("report_enabled", true)
        set(value) = prefs.edit().putBoolean("report_enabled", value).apply()

    var reportChat: String
        get() = prefs.getString("report_chat", "Notes") ?: "Notes"
        set(value) = prefs.edit().putString("report_chat", value.trim()).apply()

    companion object {
        const val DEFAULT_MODEL = "gpt-6.1-sol"
        const val DEFAULT_FAST_MODEL = "gpt-6-luna"
        const val DEFAULT_REVIEWER_MODEL = "codex-auto-review"
        val EFFORTS = listOf("low", "medium", "high")
    }
}

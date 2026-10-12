package dev.pegoku.androiduse.llm

import dev.pegoku.androiduse.Http
import dev.pegoku.androiduse.auth.CodexAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.Request
import kotlin.math.roundToInt

/** One rate-limit window of the ChatGPT plan (the 5-hour one or the weekly one). */
data class UsageWindow(val usedPercent: Double, val windowMinutes: Long, val resetAtEpochSeconds: Long) {
    val leftPercent: Int get() = (100 - usedPercent).roundToInt().coerceIn(0, 100)

    /** "5h" / "Weekly" style label for the window length. */
    val label: String
        get() = when {
            windowMinutes >= 7 * 24 * 60 - 60 -> "Weekly"
            windowMinutes % 60 == 0L -> "${windowMinutes / 60}h"
            else -> "${windowMinutes}m"
        }

    fun resetsIn(nowMillis: Long = System.currentTimeMillis()): String {
        val minutes = ((resetAtEpochSeconds * 1000 - nowMillis) / 60_000).coerceAtLeast(0)
        return when {
            minutes >= 24 * 60 -> "${minutes / (24 * 60)}d ${minutes % (24 * 60) / 60}h"
            minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
            else -> "${minutes}m"
        }
    }
}

data class CodexUsage(
    val primary: UsageWindow?,
    val secondary: UsageWindow?,
    val limitReached: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** The window closest to running out, which is what actually limits the next request. */
    val tightest: UsageWindow? get() = listOfNotNull(primary, secondary).minByOrNull { it.leftPercent }

    /** E.g. "5h 99% · wk 70% left", short enough for a notification header. */
    val shortSummary: String
        get() = listOfNotNull(primary, secondary)
            .joinToString(" · ") { "${if (it.label == "Weekly") "wk" else it.label} ${it.leftPercent}%" }
            .let { if (it.isEmpty()) it else "$it left" }

    /** E.g. "5h 99% left · Weekly 70% left". */
    val summary: String
        get() = listOfNotNull(primary, secondary).joinToString(" · ") { "${it.label} ${it.leftPercent}% left" }
}

/**
 * Remaining ChatGPT-plan usage for Codex. Every Codex response carries the current limits in
 * x-codex-* headers, so this stays fresh as a side effect of normal use; [refresh] asks the same
 * usage endpoint Codex's /status uses when nothing has been sent yet.
 */
object CodexUsageStore {
    private val _usage = MutableStateFlow<CodexUsage?>(null)
    val usage: StateFlow<CodexUsage?> = _usage.asStateFlow()

    fun updateFromHeaders(headers: Headers) {
        fun window(prefix: String): UsageWindow? {
            val used = headers["x-codex-$prefix-used-percent"]?.toDoubleOrNull() ?: return null
            val minutes = headers["x-codex-$prefix-window-minutes"]?.toLongOrNull() ?: return null
            val resetAt = headers["x-codex-$prefix-reset-at"]?.toLongOrNull()
                ?: headers["x-codex-$prefix-reset-after-seconds"]?.toLongOrNull()?.let { System.currentTimeMillis() / 1000 + it }
                ?: return null
            return UsageWindow(used, minutes, resetAt)
        }
        val primary = window("primary")
        val secondary = window("secondary")
        if (primary != null || secondary != null) {
            _usage.value = CodexUsage(primary, secondary, limitReached = listOfNotNull(primary, secondary).any { it.usedPercent >= 100 })
        }
    }

    suspend fun refresh(): CodexUsage? = withContext(Dispatchers.IO) {
        runCatching {
            val tokens = CodexAuth.fresh()
            val request = Request.Builder()
                .url("https://chatgpt.com/backend-api/wham/usage")
                .header("Authorization", "Bearer ${tokens.accessToken}")
                .header("chatgpt-account-id", tokens.accountId)
                .header("originator", "codex_cli_rs")
                .header("Accept", "application/json")
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parse(Json.parseToJsonElement(response.body.string()).jsonObject)
            }
        }.getOrNull()?.also { _usage.value = it }
    }

    internal fun parse(root: JsonObject): CodexUsage? {
        val limits = root["rate_limit"] as? JsonObject ?: return null
        fun window(key: String): UsageWindow? {
            val w = limits[key] as? JsonObject ?: return null
            val used = w["used_percent"]?.jsonPrimitive?.doubleOrNull ?: return null
            val seconds = w["limit_window_seconds"]?.jsonPrimitive?.longOrNull ?: return null
            val resetAt = w["reset_at"]?.jsonPrimitive?.longOrNull
                ?: w["reset_after_seconds"]?.jsonPrimitive?.longOrNull?.let { System.currentTimeMillis() / 1000 + it }
                ?: return null
            return UsageWindow(used, seconds / 60, resetAt)
        }
        return CodexUsage(
            primary = window("primary_window"),
            secondary = window("secondary_window"),
            limitReached = limits["limit_reached"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    fun clear() {
        _usage.value = null
    }
}

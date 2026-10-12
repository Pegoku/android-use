package dev.pegoku.androiduse.llm

import dev.pegoku.androiduse.BuildConfig
import dev.pegoku.androiduse.Http
import dev.pegoku.androiduse.auth.CodexAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

class CodexException(message: String, val retryable: Boolean = false) : Exception(message)

/** Minimal client for the ChatGPT-subscription Codex Responses endpoint. */
class CodexClient(private val sessionId: String = UUID.randomUUID().toString()) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Sends one turn and returns the completed output items (reasoning, messages, function calls). */
    suspend fun respond(
        model: String,
        effort: String,
        instructions: String,
        input: List<JsonElement>,
        tools: JsonArray,
        toolChoice: JsonElement = kotlinx.serialization.json.JsonPrimitive("auto"),
        parallelToolCalls: Boolean = true,
        /** Called with each streamed chunk of reply text; a null signals a retry, so drop what was received. */
        onTextDelta: ((String?) -> Unit)? = null,
    ): List<JsonObject> {
        val body = buildJsonObject {
            put("model", model)
            put("instructions", instructions)
            put("input", JsonArray(input))
            put("tools", tools)
            put("tool_choice", toolChoice)
            put("parallel_tool_calls", parallelToolCalls)
            put("store", false)
            put("stream", true)
            putJsonObject("reasoning") {
                put("effort", effort)
                put("summary", "auto")
            }
            put("include", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("reasoning.encrypted_content")) })
            put("prompt_cache_key", sessionId)
        }.toString()

        var attempt = 0
        var refreshed = false
        while (true) {
            try {
                if (attempt > 0 || refreshed) onTextDelta?.invoke(null)
                return send(body, onTextDelta)
            } catch (_: UnauthorizedException) {
                if (refreshed) throw CodexException("Codex rejected the credentials. Sign in again.")
                refreshed = true
                CodexAuth.fresh(force = true)
            } catch (e: CodexException) {
                if (!e.retryable || ++attempt > MAX_RETRIES) throw e
                delay(backoff(attempt))
            } catch (e: IOException) {
                if (++attempt > MAX_RETRIES) throw CodexException("Network error: ${e.message}")
                delay(backoff(attempt))
            }
        }
    }

    private fun backoff(attempt: Int) = minOf(30_000L, 2_000L shl (attempt - 1))

    private class UnauthorizedException : Exception()

    private suspend fun send(body: String, onTextDelta: ((String?) -> Unit)?): List<JsonObject> {
        val tokens = CodexAuth.fresh()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("chatgpt-account-id", tokens.accountId)
            .header("OpenAI-Beta", "responses=experimental")
            .header("originator", "codex_cli_rs")
            .header("session_id", sessionId)
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val call = Http.client.newCall(request)
        return coroutineScope {
            val canceller = launch {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                withContext(Dispatchers.IO) { readStream(call.execute(), onTextDelta) }
            } finally {
                canceller.cancel()
            }
        }
    }

    private fun readStream(response: okhttp3.Response, onTextDelta: ((String?) -> Unit)?): List<JsonObject> = response.use {
        // Every response (including 429s) reports the plan's remaining usage.
        CodexUsageStore.updateFromHeaders(response.headers)
        if (response.code == 401) throw UnauthorizedException()
        if (!response.isSuccessful) {
            val text = response.body.string().take(800)
            val detail = runCatching {
                val obj = json.parseToJsonElement(text).jsonObject
                (obj["detail"] ?: obj["error"]?.jsonObject?.get("message"))?.jsonPrimitive?.content
            }.getOrNull() ?: text
            throw CodexException(
                "Codex request failed (${response.code}): $detail",
                retryable = response.code >= 500 || response.code == 408,
            )
        }
        val items = mutableListOf<JsonObject>()
        val source = response.body.source()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val event = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (event["type"]?.jsonPrimitive?.content) {
                "response.output_item.done" -> event["item"]?.jsonObject?.let(items::add)
                "response.output_text.delta" -> event["delta"]?.jsonPrimitive?.content?.let { onTextDelta?.invoke(it) }
                "response.completed" -> {
                    if (BuildConfig.DEBUG) android.util.Log.d("AndroidUseTiming", "usage ${event["response"]?.jsonObject?.get("usage")?.toString()?.take(300)}")
                    return items
                }
                "response.failed", "response.incomplete" ->
                    throw streamError(event["response"]?.jsonObject?.get("error") as? JsonObject, "response did not complete")
                "error" -> throw streamError(event["error"] as? JsonObject ?: event, data.take(300))
            }
        }
        if (items.isEmpty()) throw CodexException("Codex stream ended unexpectedly", retryable = true)
        items
    }

    private fun streamError(error: JsonObject?, fallback: String): CodexException {
        fun field(key: String) = (error?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
        val message = field("message") ?: fallback
        val code = field("code") ?: field("type")
        val retryable = code in RETRYABLE_CODES || "overloaded" in message.lowercase() || "try again" in message.lowercase()
        return CodexException("Codex: $message", retryable)
    }

    companion object {
        private const val MAX_RETRIES = 5
        private val RETRYABLE_CODES = setOf("server_error", "server_is_overloaded", "slow_down", "service_unavailable_error")
        private const val ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"
        private const val MODELS_ENDPOINT = "https://chatgpt.com/backend-api/codex/models?client_version=0.200.0"

        /** Model slugs available to the signed-in subscription. */
        suspend fun models(): List<String> = withContext(Dispatchers.IO) {
            val tokens = CodexAuth.fresh()
            val request = Request.Builder()
                .url(MODELS_ENDPOINT)
                .header("Authorization", "Bearer ${tokens.accessToken}")
                .header("chatgpt-account-id", tokens.accountId)
                .header("originator", "codex_cli_rs")
                .build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("models request failed (${response.code})")
                Json.parseToJsonElement(response.body.string()).jsonObject["models"]?.jsonArray.orEmpty()
                    .mapNotNull { (it as? JsonObject)?.get("slug")?.jsonPrimitive?.content }
            }
        }
    }
}

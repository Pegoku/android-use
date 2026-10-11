package dev.pegoku.androiduse.agent

import android.os.Build
import dev.pegoku.androiduse.device.Device
import dev.pegoku.androiduse.device.Observation
import dev.pegoku.androiduse.llm.CodexClient
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AgentResult(val success: Boolean, val summary: String)

/** Observe → ask the model → act loop driving the phone through [Device]. */
class Agent(
    private val device: Device,
    private val model: String,
    private val effort: String,
    private val maxSteps: Int,
    private val log: (LogKind, String) -> Unit,
    private val onScreen: (Observation) -> Unit,
) {
    private val client = CodexClient()
    private val notes = mutableListOf<String>()

    /** Conversation items; screens are kept separately so older screenshots can be elided. */
    private sealed interface Item
    private class Raw(val json: JsonElement) : Item
    private class Screen(val text: String, val imageBase64: String, val app: String?) : Item

    private val history = mutableListOf<Item>()

    suspend fun run(task: String): AgentResult {
        history += Raw(userMessage("Task: $task"))
        var nudges = 0
        for (step in 1..maxSteps) {
            history += observe(step)
            val output = client.respond(model, effort, instructions(), render(), Tools.definitions)
            output.forEach { history += Raw(it) }

            output.filter { it.type == "reasoning" }.forEach { item ->
                item["summary"]?.jsonArray?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }
                    ?.joinToString("\n")?.takeIf { it.isNotBlank() }?.let { log(LogKind.Thought, it) }
            }
            output.filter { it.type == "message" }.forEach { item ->
                val text = item["content"]?.jsonArray?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }
                    ?.joinToString("\n").orEmpty()
                if (text.isNotBlank()) log(LogKind.Thought, text)
            }

            val calls = output.filter { it.type == "function_call" }
            if (calls.isEmpty()) {
                if (++nudges > 3) return AgentResult(false, "The model stopped without finishing the task.")
                history += Raw(userMessage("Keep going using the tools. Do not ask me questions; if the task is complete or impossible, call finish."))
                continue
            }
            nudges = 0
            for (call in calls) {
                val name = call["name"]!!.jsonPrimitive.content
                val callId = call["call_id"]!!.jsonPrimitive.content
                val args = runCatching { Json.parseToJsonElement(call["arguments"]?.jsonPrimitive?.content ?: "{}").jsonObject }
                    .getOrDefault(JsonObject(emptyMap()))
                log(LogKind.Action, "$name ${describeArgs(args)}")
                if (name == "finish") {
                    val success = args["success"]?.jsonPrimitive?.booleanOrNull ?: false
                    val summary = args["summary"]?.jsonPrimitive?.content.orEmpty()
                    return AgentResult(success, summary)
                }
                val result = runCatching { execute(name, args) }.getOrElse { "Error: ${it.message}" }
                log(LogKind.Result, result)
                history += Raw(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", result)
                })
            }
            delay(SETTLE_MS)
        }
        return AgentResult(false, "Gave up after $maxSteps steps without finishing.")
    }

    private suspend fun execute(name: String, a: JsonObject): String = when (name) {
        "tap" -> {
            val long = a.bool("long_press")
            val element = a.int("element")
            if (element != null) device.tapElement(element, long)
            else device.tapPoint(a.num("x") ?: error("x is required"), a.num("y") ?: error("y is required"), long)
        }
        "type_text" -> device.typeText(a.str("text").orEmpty(), a.bool("clear_first"), a.int("element"), a.bool("submit"))
        "scroll" -> device.scroll(a.str("direction") ?: "down", a.int("element"))
        "swipe" -> device.swipe(a.num("x1")!!, a.num("y1")!!, a.num("x2")!!, a.num("y2")!!, a.int("duration_ms") ?: 300)
        "press_key" -> device.pressKey(a.str("key") ?: error("key is required"))
        "launch_app" -> device.launchApp(a.str("app") ?: error("app is required"))
        "list_apps" -> {
            val filter = a.str("filter")?.lowercase()
            device.installedApps()
                .filter { filter == null || filter in it.label.lowercase() || filter in it.packageName.lowercase() }
                .joinToString("\n") { "${it.label} — ${it.packageName}" }
                .ifEmpty { "No matching apps." }
        }
        "open_uri" -> device.openUri(a.str("uri") ?: error("uri is required"), a.str("package"))
        "compose_email" -> device.composeEmail(a.str("to").orEmpty(), a.str("subject").orEmpty(), a.str("body").orEmpty(), a.str("package"))
        "wait" -> {
            val seconds = (a.num("seconds") ?: 2.0).coerceIn(0.0, 30.0)
            delay((seconds * 1000).toLong())
            "Waited ${seconds}s"
        }
        "save_note" -> {
            a.str("note")?.let(notes::add)
            "Noted."
        }
        else -> "Unknown tool $name"
    }

    private suspend fun observe(step: Int): Screen {
        val obs = device.observe()
        onScreen(obs)
        val app = obs.tree?.packageName
        val text = buildString {
            append("Step $step/$maxSteps. Foreground app: ${app ?: "unknown"}\n")
            append("Screenshot is ${obs.bitmap.width}x${obs.bitmap.height} px; all coordinates are in these pixels.\n")
            if (notes.isNotEmpty()) append("Your notes:\n").append(notes.joinToString("\n") { "- $it" }).append('\n')
            if (obs.tree != null) {
                val listing = obs.tree.describe(obs.scale)
                append("UI elements:\n").append(listing.ifEmpty { "(none listed — rely on the screenshot)\n" })
            } else {
                append("UI element list unavailable: ${obs.treeError}. Use screenshot coordinates.\n")
            }
        }
        return Screen(text, obs.jpegBase64, app)
    }

    /** Builds the request input, keeping only the latest screenshot and element list in full. */
    private fun render(): List<JsonElement> {
        val lastScreen = history.indexOfLast { it is Screen }
        return history.mapIndexed { index, item ->
            when (item) {
                is Raw -> item.json
                is Screen -> if (index == lastScreen) {
                    buildJsonObject {
                        put("type", "message")
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "input_text"); put("text", item.text) })
                            add(buildJsonObject {
                                put("type", "input_image")
                                put("image_url", "data:image/jpeg;base64,${item.imageBase64}")
                                put("detail", "high")
                            })
                        })
                    }
                } else {
                    userMessage("(Earlier screen in ${item.app ?: "unknown app"}; details omitted.)")
                }
            }
        }
    }

    private fun instructions(): String {
        val now = SimpleDateFormat("EEEE yyyy-MM-dd HH:mm zzz", Locale.US).format(Date())
        return """
            You are Android Use, an agent that operates an Android phone (${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}) on behalf of its owner to complete the task they give you. Current time: $now. Device locale: ${Locale.getDefault().toLanguageTag()}.

            After every action you receive a fresh screenshot and a list of on-screen UI elements, each with an [id], class, text, flags and its centre @(x,y) in screenshot pixels. Work step by step:
            - Prefer acting on elements by [id]. Use raw x,y coordinates only for things missing from the list (e.g. canvas, web content, games).
            - Use launch_app to open apps rather than hunting on the home screen; use list_apps if unsure of an app's name.
            - After each action, check the new screen to confirm it had the intended effect before moving on. If something did not work, try another approach (scroll, search, back, wait for loading).
            - To type, focus the field with type_text's element argument. Dismiss pop-ups, permission prompts and onboarding screens only in the way that least changes the user's settings.
            - When you find information you need later (values, names, numbers), call save_note with the exact value.
            - For email, compose_email opens a prefilled draft; then press the app's Send button and confirm the draft was sent. Never leave a requested message unsent.
            - In messaging apps, open the exact chat requested (use the app's search when it is not visible), type the message, tap Send, and confirm it appears in the conversation.
            - Stay within the task: don't buy anything, delete data, change settings or contact people unless the task asks for it. If you hit a login wall, CAPTCHA, payment or a decision only the owner can make, stop.
            - Do not interact with "Android Use" (this automation app) or its notification.
            - Finish with finish(success, summary). The summary must state the concrete result (e.g. the value found and who it was sent to) or exactly why it failed. Only report success when you verified it.
        """.trimIndent()
    }

    private fun userMessage(text: String) = buildJsonObject {
        put("type", "message")
        put("role", "user")
        put("content", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", text) }) })
    }

    private fun describeArgs(args: JsonObject) = args.entries
        .filter { it.value !is JsonNull }
        .joinToString(" ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { "\"${it.take(80)}\"" } ?: v}" }

    private val JsonObject.type get() = this["type"]?.jsonPrimitive?.content
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    companion object {
        private const val SETTLE_MS = 1200L
    }
}

enum class LogKind { Info, Thought, Action, Result, Error, Done }

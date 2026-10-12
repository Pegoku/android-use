package dev.pegoku.androiduse.agent

import dev.pegoku.androiduse.device.Device

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Function tool definitions exposed to the model (strict JSON schemas). */
object Tools {
    private class Param(val name: String, val type: Any, val description: String, val enum: List<String>? = null)

    private fun tool(name: String, description: String, vararg params: Param): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", name)
        put("description", description)
        put("strict", true)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                for (p in params) putJsonObject(p.name) {
                    when (val t = p.type) {
                        is String -> put("type", t)
                        is List<*> -> putJsonArray("type") { t.forEach { add(JsonPrimitive(it as String)) } }
                    }
                    put("description", p.description)
                    p.enum?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
                }
            }
            putJsonArray("required") { params.forEach { add(JsonPrimitive(it.name)) } }
            put("additionalProperties", false)
        }
    }

    private val optInt = listOf("integer", "null")
    private val optNumber = listOf("number", "null")
    private val optString = listOf("string", "null")

    private val fillForm = buildJsonObject {
        put("type", "function")
        put("name", "fill_form")
        put("description", "Fill several text fields on the current screen in one call. Each field is tapped, its content replaced with the given text, then the next one is filled.")
        put("strict", true)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("fields") {
                    put("type", "array")
                    put("description", "Fields to fill, in order.")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("element") { put("type", "integer"); put("description", "Editable element id.") }
                            putJsonObject("text") { put("type", "string"); put("description", "Text to put in the field.") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("element")); add(JsonPrimitive("text")) }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("fields")) }
            put("additionalProperties", false)
        }
    }

    private fun nullable(type: String, description: String) = buildJsonObject {
        putJsonArray("type") { add(JsonPrimitive(type)); add(JsonPrimitive("null")) }
        put("description", description)
    }

    val BATCH_ACTIONS = listOf("tap", "long_press", "type_text", "press_key", "scroll", "scroll_to", "launch_app")

    private val batch = buildJsonObject {
        put("type", "function")
        put("name", "batch")
        put(
            "description",
            "Run several actions in sequence in ONE turn, without a new screenshot in between. Element ids refer to the current screen " +
                "and are re-mapped if it shifts; the batch stops at the first failure. Fields not used by an action must be null.",
        )
        put("strict", true)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("actions") {
                    put("type", "array")
                    put("description", "Actions to run in order.")
                    putJsonObject("items") {
                        put("type", "object")
                        val fields = linkedMapOf(
                            "element" to nullable("integer", "Element id (tap, long_press, type_text, scroll, scroll_to)."),
                            "x" to nullable("number", "Screenshot x when no element (tap, long_press)."),
                            "y" to nullable("number", "Screenshot y when no element (tap, long_press)."),
                            "text" to nullable("string", "Text to type (type_text), item to find (scroll_to), or app name (launch_app)."),
                            "clear_first" to nullable("boolean", "type_text: replace the field's content."),
                            "submit" to nullable("boolean", "type_text: press Enter afterwards."),
                            "key" to nullable("string", "press_key: key name."),
                            "direction" to nullable("string", "scroll / scroll_to: up, down, left or right."),
                        )
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                putJsonArray("enum") { BATCH_ACTIONS.forEach { add(JsonPrimitive(it)) } }
                            }
                            fields.forEach { (name, schema) -> put(name, schema) }
                        }
                        putJsonArray("required") { add(JsonPrimitive("action")); fields.keys.forEach { add(JsonPrimitive(it)) } }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("actions")) }
            put("additionalProperties", false)
        }
    }

    private val shellRead = tool(
        "shell_read",
        "Run a read-only device query as the shell user and get its output, e.g. `getprop ro.build.version.release`, " +
            "`dumpsys battery`, `settings get system screen_brightness`, `pm list packages`, `wm size`. Much faster than reading it from the UI. " +
            "Only allowlisted read-only commands are accepted; no pipes or redirection.",
        Param("command", "string", "The command line."),
    )

    private val shell = tool(
        "shell",
        "Run a shell command (adb shell, as the shell user) to change something quickly, e.g. `cmd uimode night yes` or `svc wifi enable`. " +
            "Sensitive: it is checked against the owner's task and may need their approval. Destructive commands are always refused. " +
            "Prefer UI actions or other tools when they are just as quick.",
        Param("command", "string", "The command line (sh syntax)."),
        Param("reason", "string", "Why this command is needed for the owner's task."),
    )

    /** All tools; the free-form shell tool is only offered when the owner enabled it. */
    fun definitions(shellEnabled: Boolean): JsonArray = buildJsonArray {
        base.forEach { add(it) }
        add(shellRead)
        if (shellEnabled) add(shell)
    }

    private val base: JsonArray = buildJsonArray {
        add(batch)
        add(tool(
            "tap",
            "Tap an element by its [id] from the UI element list (preferred), or tap screenshot coordinates x,y when the target is not listed.",
            Param("element", optInt, "Element id from the UI list, or null to use x/y."),
            Param("x", optNumber, "X in screenshot pixels (only when element is null)."),
            Param("y", optNumber, "Y in screenshot pixels (only when element is null)."),
            Param("long_press", "boolean", "Long-press instead of a normal tap."),
        ))
        add(tool(
            "type_text",
            "Type text into the focused field, or first tap the given element to focus it.",
            Param("text", "string", "Text to type. May be empty when only clearing."),
            Param("element", optInt, "Editable element to tap first, or null to type into the currently focused field."),
            Param("clear_first", "boolean", "Delete the field's existing content before typing."),
            Param("submit", "boolean", "Press Enter after typing, for search boxes only. Never use it to send a chat message; tap Send instead."),
        ))
        add(fillForm)
        add(tool(
            "scroll",
            "Scroll by whole pages. Direction is where you want to move in the content: 'down' shows content further below. Prefer scroll_to when you know what you are looking for.",
            Param("direction", "string", "Scroll direction.", listOf("up", "down", "left", "right")),
            Param("element", optInt, "Scrollable element to scroll inside, or null for the main screen area."),
            Param("pages", "integer", "How many pages to scroll in one go (1-10). Use more than 1 to jump far, e.g. to the bottom of a long page."),
        ))
        add(tool(
            "scroll_to",
            "Scroll repeatedly until an element whose text or description contains the query is on screen, in a single call. Much faster than scrolling step by step. Falls back to reporting the end of the list if not found.",
            Param("text", "string", "Text (case-insensitive substring) of the item to bring into view, e.g. \"About phone\"."),
            Param("direction", "string", "Direction to search in.", listOf("up", "down", "left", "right")),
            Param("element", optInt, "Scrollable container to scroll inside, or null for the main screen area."),
        ))
        add(tool(
            "swipe",
            "Raw swipe gesture between two screenshot-pixel points.",
            Param("x1", "number", "Start x."),
            Param("y1", "number", "Start y."),
            Param("x2", "number", "End x."),
            Param("y2", "number", "End y."),
            Param("duration_ms", "integer", "Gesture duration, 300 is typical."),
        ))
        add(tool(
            "press_key",
            "Press a system key: back, home, enter, recents, delete, search, tab, escape, space, page_up, page_down, menu, notifications, quick_settings, or any Android KEYCODE_* name.",
            Param("key", "string", "Key name."),
        ))
        add(tool(
            "launch_app",
            "Launch an installed app by its name (e.g. \"WhatsApp\", \"Gmail\") or package name.",
            Param("app", "string", "App label or package name."),
        ))
        add(tool(
            "list_apps",
            "List installed launchable apps (label and package), optionally filtered by a substring.",
            Param("filter", optString, "Case-insensitive substring to filter by, or null for all."),
        ))
        add(tool(
            "open_uri",
            "Open a URI with an ACTION_VIEW intent (web URL, deep link, tel:, geo:, etc.).",
            Param("uri", "string", "The URI to open."),
            Param("package", optString, "Package to open it with, or null for the default handler."),
        ))
        add(tool(
            "open_settings",
            "Jump straight to a Settings page (much faster than navigating menus). about_phone shows the Android version, model, build number and more.",
            Param("page", "string", "Settings page.", Device.SETTINGS_PAGES.keys.toList()),
            Param("package", optString, "App package, required for app_details and app_notifications; otherwise null."),
        ))
        add(tool(
            "compose_sms",
            "Open the SMS app with the recipient and message already filled in. Then tap the Send button and confirm the message was sent.",
            Param("number", "string", "Recipient phone number."),
            Param("text", "string", "Message text."),
            Param("package", optString, "SMS app package, or null for the default."),
        ))
        add(tool(
            "compose_email",
            "Open the email app with a prefilled draft (mailto intent). You must then tap Send on screen and confirm it was sent.",
            Param("to", "string", "Recipient address(es), comma separated."),
            Param("subject", "string", "Subject line."),
            Param("body", "string", "Plain-text body."),
            Param("package", optString, "Email app package (e.g. com.google.android.gm), or null for the default."),
        ))
        add(tool(
            "wait",
            "Wait until the screen stops changing (e.g. a page still loading), up to the given number of seconds, then observe again.",
            Param("seconds", "number", "Maximum seconds to wait (max 30)."),
        ))
        add(tool(
            "save_note",
            "Remember a fact discovered during the task (e.g. a value you will need later). Notes are repeated with every screen.",
            Param("note", "string", "The fact to remember, including the exact value."),
        ))
        add(tool(
            "finish",
            "End the task. Call with success=true only after verifying everything requested was actually done.",
            Param("success", "boolean", "Whether the whole task was completed."),
            Param("summary", "string", "Short result for the user, including any values found or the reason for failure."),
        ))
    }
}

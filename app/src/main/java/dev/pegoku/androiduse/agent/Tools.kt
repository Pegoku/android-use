package dev.pegoku.androiduse.agent

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

    val definitions: JsonArray = buildJsonArray {
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
            Param("submit", "boolean", "Press Enter after typing (e.g. to search)."),
        ))
        add(tool(
            "scroll",
            "Scroll to reveal more content. Direction is where you want to move in the content: 'down' shows content further below.",
            Param("direction", "string", "Scroll direction.", listOf("up", "down", "left", "right")),
            Param("element", optInt, "Scrollable element to scroll inside, or null for the main screen area."),
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
            "compose_email",
            "Open the email app with a prefilled draft (mailto intent). You must then tap Send on screen and confirm it was sent.",
            Param("to", "string", "Recipient address(es), comma separated."),
            Param("subject", "string", "Subject line."),
            Param("body", "string", "Plain-text body."),
            Param("package", optString, "Email app package (e.g. com.google.android.gm), or null for the default."),
        ))
        add(tool(
            "wait",
            "Wait for the screen to settle or content to load, then observe again.",
            Param("seconds", "number", "Seconds to wait (max 30)."),
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

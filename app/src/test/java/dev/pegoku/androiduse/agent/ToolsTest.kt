package dev.pegoku.androiduse.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {
    private val tools = Tools.definitions(shellEnabled = true).map { it.jsonObject }

    @Test
    fun toolNamesAreUnique() {
        val names = tools.map { it["name"]!!.jsonPrimitive.content }
        assertEquals(names.size, names.toSet().size)
        assertTrue("finish" in names)
    }

    /** Strict function calling rejects a schema unless every object lists all properties as required and forbids extras. */
    @Test
    fun schemasSatisfyStrictMode() {
        for (tool in tools) {
            val name = tool["name"]!!.jsonPrimitive.content
            assertTrue("$name should be strict", tool["strict"]!!.jsonPrimitive.boolean)
            assertStrictObject(name, tool["parameters"]!!.jsonObject)
        }
    }

    @Test
    fun shellToolOnlyOfferedWhenEnabled() {
        fun names(enabled: Boolean) = Tools.definitions(enabled).map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("shell" in names(true))
        assertFalse("shell" in names(false))
        assertTrue("shell_read" in names(false))
    }

    @Test
    fun batchActionsAreAllExecutableTools() {
        val names = tools.map { it["name"]!!.jsonPrimitive.content }.toSet() + "long_press"
        assertTrue(Tools.BATCH_ACTIONS.all { it in names })
        assertFalse("finish must not be batchable" , "finish" in Tools.BATCH_ACTIONS)
    }

    private fun assertStrictObject(path: String, schema: JsonObject) {
        assertEquals("$path type", "object", schema["type"]!!.jsonPrimitive.content)
        assertEquals("$path additionalProperties", false, schema["additionalProperties"]!!.jsonPrimitive.boolean)
        val properties = schema["properties"]!!.jsonObject
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals("$path required", properties.keys, required)
        for ((key, value) in properties) {
            val property = value.jsonObject
            val types = when (val type = property["type"]) {
                is JsonPrimitive -> listOf(type.content)
                is JsonArray -> type.map { it.jsonPrimitive.content }
                else -> emptyList()
            }
            if ("object" in types) assertStrictObject("$path.$key", property)
            if ("array" in types) {
                val items = property["items"]!!.jsonObject
                if (items["type"]?.jsonPrimitive?.content == "object") assertStrictObject("$path.$key[]", items)
            }
        }
    }
}

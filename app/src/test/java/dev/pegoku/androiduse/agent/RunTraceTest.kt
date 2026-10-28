package dev.pegoku.androiduse.agent

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunTraceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun recordsOrderedEventsBeforeRunCompletes() {
        val file = File(folder.root, "run.jsonl")
        val trace = RunTrace(file)
        trace.event("action_start", "arguments" to "line one\nline two")
        trace.event("action_end", "result" to "ok")
        val events = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("1", "2"), events.map { it["sequence"]!!.jsonPrimitive.content })
        assertEquals("line one\nline two", events[0]["arguments"]!!.jsonPrimitive.content)
        assertEquals("action_end", events[1]["kind"]!!.jsonPrimitive.content)
    }

    @Test fun exportsRetainedRunsAndChatsAfterReinitialization() {
        RunTraceStore.init(folder.root)
        repeat(32) { RunTraceStore.start().event("run_start", "task" to "test $it") }
        RunTraceStore.init(folder.root)
        val bytes = ByteArrayOutputStream()
        RunTraceStore.export(bytes, emptyList())
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(30, entries.keys.count { it.startsWith("runs/") })
        assertEquals("[]", entries["chats.json"])
        assertTrue(entries.containsKey("README.txt"))
        assertTrue(entries.filterKeys { it.startsWith("runs/") }.values.all { it.contains("run_start") })
    }
}

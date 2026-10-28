package dev.pegoku.androiduse.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import dev.pegoku.androiduse.assistant.SavedChat

/** Append each event before continuing, so stopped or crashed runs remain inspectable. */
class RunTrace(private val file: File) {
    private val started = System.nanoTime()
    private var sequence = 0

    @Synchronized
    fun event(kind: String, vararg fields: Pair<String, String?>) {
        val event = buildJsonObject {
            put("sequence", ++sequence)
            put("time", System.currentTimeMillis())
            put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            put("kind", kind)
            fields.forEach { (key, value) -> put(key, value) }
        }
        // Diagnostics must never abort a phone action if storage is full.
        runCatching { file.appendText("$event\n") }
    }
}

object RunTraceStore {
    private lateinit var directory: File

    fun init(filesDir: File) { directory = File(filesDir, "traces").apply { mkdirs() } }

    @Synchronized
    fun start(): RunTrace {
        check(::directory.isInitialized)
        val files = directory.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedByDescending { it.name }
        files.drop(29).forEach { it.delete() }
        return RunTrace(File(directory, "${System.currentTimeMillis()}-${UUID.randomUUID()}.jsonl"))
    }

    /** ZIP entries are streamed, rather than loading large traces into memory. */
    @Synchronized
    fun export(output: OutputStream, chats: List<SavedChat>) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("README.txt", "Android Use diagnostics v1\n" +
                "chats.json contains saved conversations. runs/*.jsonl contains timestamped events for the last 30 runs since tracing was installed.\n" +
                "Old actions cannot be recovered. A run without run_end was interrupted. A live export may end with a partial JSON line; ignore that last line.\n" +
                "Includes private chat text, screen text and action arguments. No screenshots or authentication store are exported. Review before sharing.\n")
            entry("chats.json", Json.encodeToString(chats))
            directory.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }.forEach { file ->
                zip.putNextEntry(ZipEntry("runs/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}

package dev.pegoku.androiduse.memory

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Something worth knowing next time: how a task is done, where a setting lives, a preference. */
@Serializable
data class Memory(
    val id: Long,
    val text: String,
    /** App the memory is about (label or package), or null for general knowledge. */
    val app: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Long-term memory shared by the agent and the assistant, kept in a private file on the device.
 * Every memory is given to the model as context, so this stays small and human-editable.
 */
object MemoryStore {
    private const val MAX_MEMORIES = 200
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private lateinit var file: File

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "memories.json")
        _memories.value = runCatching { json.decodeFromString<List<Memory>>(file.readText()) }.getOrDefault(emptyList())
    }

    /** Adds a memory unless an identical one exists. Returns the stored memory. */
    @Synchronized
    fun add(text: String, app: String? = null): Memory {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Memory text is empty" }
        val normalizedApp = app?.trim()?.takeIf { it.isNotEmpty() }
        _memories.value.firstOrNull { it.text.equals(clean, ignoreCase = true) && it.app.equals(normalizedApp, ignoreCase = true) }
            ?.let { return it }
        val memory = Memory(id = (_memories.value.maxOfOrNull { it.id } ?: 0) + 1, text = clean, app = normalizedApp)
        save((_memories.value + memory).takeLast(MAX_MEMORIES))
        return memory
    }

    @Synchronized
    fun update(id: Long, text: String) {
        save(_memories.value.map { if (it.id == id) it.copy(text = text.trim()) else it }.filter { it.text.isNotEmpty() })
    }

    @Synchronized
    fun remove(id: Long): Boolean {
        val before = _memories.value
        save(before.filterNot { it.id == id })
        return before.size != _memories.value.size
    }

    @Synchronized
    fun clear() = save(emptyList())

    /** The memories as model context, newest last, e.g. "[3] (WhatsApp) The Notes group is pinned." */
    fun render(memories: List<Memory> = _memories.value): String =
        memories.joinToString("\n") { m -> "[${m.id}] " + (m.app?.let { "($it) " } ?: "") + m.text }

    private fun save(list: List<Memory>) {
        _memories.value = list
        if (::file.isInitialized) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(list))
            tmp.renameTo(file)
        }
    }
}

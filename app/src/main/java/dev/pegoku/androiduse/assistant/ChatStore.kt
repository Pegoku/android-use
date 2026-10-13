package dev.pegoku.androiduse.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/** A chat message without its bitmap, as kept on disk. */
@Serializable
data class SavedMessage(val id: Long, val role: ChatRole, val text: String, val success: Boolean? = null, val detail: String? = null)

/** A whole conversation: what the user saw plus the model transcript needed to continue it. */
@Serializable
data class SavedChat(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messages: List<SavedMessage>,
    val history: List<JsonElement>,
)

/**
 * Recent assistant conversations, kept in a private file so a chat can be picked up later, like
 * the chat list in Gemini or ChatGPT. Only the newest few are kept; screenshots are not saved.
 */
object ChatStore {
    private const val MAX_CHATS = 20
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var file: File

    private val _chats = MutableStateFlow<List<SavedChat>>(emptyList())
    /** Newest first. */
    val chats: StateFlow<List<SavedChat>> = _chats.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "chats.json")
        _chats.value = runCatching { json.decodeFromString<List<SavedChat>>(file.readText()) }.getOrDefault(emptyList())
    }

    fun get(id: Long): SavedChat? = _chats.value.firstOrNull { it.id == id }

    @Synchronized
    fun save(chat: SavedChat) {
        if (chat.messages.isEmpty()) return
        write((listOf(chat) + _chats.value.filterNot { it.id == chat.id }).sortedByDescending { it.updatedAt }.take(MAX_CHATS))
    }

    @Synchronized
    fun remove(id: Long) = write(_chats.value.filterNot { it.id == id })

    @Synchronized
    fun clear() = write(emptyList())

    private fun write(list: List<SavedChat>) {
        _chats.value = list
        if (::file.isInitialized) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(list))
            tmp.renameTo(file)
        }
    }
}

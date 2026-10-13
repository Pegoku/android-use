package dev.pegoku.androiduse.assistant

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChatStoreTest {
    @Before
    fun reset() = ChatStore.clear()

    private fun chat(id: Long, title: String, at: Long) = SavedChat(
        id = id, title = title, updatedAt = at,
        messages = listOf(SavedMessage(1, ChatRole.User, title), SavedMessage(2, ChatRole.Task, title, success = true, detail = "ok")),
        history = listOf(buildJsonObject { put("type", "message"); put("role", "user") }),
    )

    @Test
    fun keepsNewestFirstAndReplacesSameId() {
        ChatStore.save(chat(1, "Older", 100))
        ChatStore.save(chat(2, "Newer", 200))
        ChatStore.save(chat(1, "Older, updated", 300))
        assertEquals(listOf("Older, updated", "Newer"), ChatStore.chats.value.map { it.title })
        assertEquals(true, ChatStore.get(1)?.messages?.last()?.success)
    }

    @Test
    fun ignoresEmptyChatsAndRemoves() {
        ChatStore.save(SavedChat(5, "Empty", 1, emptyList(), emptyList()))
        assertNull(ChatStore.get(5))
        ChatStore.save(chat(6, "Keep", 1))
        ChatStore.remove(6)
        assertNull(ChatStore.get(6))
    }

    @Test
    fun capsAtTwentyNewest() {
        (1..25L).forEach { ChatStore.save(chat(it, "Chat $it", it)) }
        assertEquals(20, ChatStore.chats.value.size)
        assertEquals(25L, ChatStore.chats.value.first().id)
        assertEquals(6L, ChatStore.chats.value.last().id)
    }
}

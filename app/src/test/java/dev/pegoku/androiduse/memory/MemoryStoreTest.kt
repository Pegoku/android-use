package dev.pegoku.androiduse.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryStoreTest {
    @Before
    fun reset() = MemoryStore.clear()

    @Test
    fun addsRendersAndDeduplicates() {
        val a = MemoryStore.add("The Notes group is pinned at the top of Chats", "WhatsApp")
        val again = MemoryStore.add("the notes group is pinned at the top of chats", "whatsapp")
        MemoryStore.add("Dentist's number is 555-0177")
        assertEquals(a.id, again.id)
        assertEquals(2, MemoryStore.memories.value.size)
        assertEquals(
            "[1] (WhatsApp) The Notes group is pinned at the top of Chats\n[2] Dentist's number is 555-0177",
            MemoryStore.render(),
        )
    }

    @Test
    fun removesAndEdits() {
        val m = MemoryStore.add("Wrong fact")
        MemoryStore.update(m.id, "Right fact")
        assertEquals("Right fact", MemoryStore.memories.value.single().text)
        assertTrue(MemoryStore.remove(m.id))
        assertFalse(MemoryStore.remove(m.id))
        assertTrue(MemoryStore.memories.value.isEmpty())
    }
}

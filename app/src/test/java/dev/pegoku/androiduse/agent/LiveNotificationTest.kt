package dev.pegoku.androiduse.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveNotificationTest {
    @Test
    fun describesActionsInPlainWords() {
        assertEquals("Opening Clock", LiveNotification.describe("launch_app app=\"Clock\""))
        assertEquals("Tapping", LiveNotification.describe("tap element=7 long_press=false"))
        assertEquals("Writing a text to 555-0142", LiveNotification.describe("compose_sms number=\"555-0142\" text=\"hi\""))
        assertEquals("Opening about phone settings", LiveNotification.describe("open_settings page=\"about_phone\""))
    }

    @Test
    fun summarizesBatches() {
        val batch = """batch actions=[{"action":"tap","element":6,"x":null,"y":null,"text":null},""" +
            """{"action":"type_text","element":8,"x":null,"y":null,"text":"Eiffel Tower"},""" +
            """{"action":"tap","element":3,"x":null,"y":null,"text":null},{"action":"press_key","key":"back"}]"""
        assertEquals("Tapping → Typing “Eiffel Tower” → Tapping …", LiveNotification.describe(batch))
    }
}

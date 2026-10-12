package dev.pegoku.androiduse.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTest {
    @Test
    fun quoteSurvivesSingleQuotes() {
        assertEquals("'plain'", Device.quote("plain"))
        assertEquals("'it'\\''s'", Device.quote("it's"))
        assertEquals("'a; rm -rf / \$(x)'", Device.quote("a; rm -rf / \$(x)"))
    }

    @Test
    fun settingsPagesAreIntentActions() {
        Device.SETTINGS_PAGES.forEach { (page, action) ->
            assertEquals(page, true, action.startsWith("android.settings.") || action.startsWith("android.intent.action."))
        }
    }
}

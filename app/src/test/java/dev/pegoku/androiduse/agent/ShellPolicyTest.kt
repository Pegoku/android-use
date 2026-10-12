package dev.pegoku.androiduse.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellPolicyTest {
    private fun readOnly(command: String) = ShellPolicy.isReadOnly(ShellPolicy.tokenize(command))

    @Test
    fun tokenizesQuotesAndEscapes() {
        assertEquals(listOf("settings", "get", "system", "a b"), ShellPolicy.tokenize("settings get system 'a b'"))
        assertEquals(listOf("echo", "it's", "x\"y"), ShellPolicy.tokenize("""echo "it's" x\"y"""))
        assertEquals(listOf("getprop", ";", "rm", "-rf", "/"), ShellPolicy.tokenize("getprop ; rm -rf /"))
    }

    @Test
    fun allowsReadOnlyQueries() {
        assertTrue(readOnly("getprop ro.build.version.release"))
        assertTrue(readOnly("dumpsys battery"))
        assertTrue(readOnly("settings get system screen_brightness"))
        assertTrue(readOnly("pm list packages -3"))
        assertTrue(readOnly("wm size"))
        assertTrue(readOnly("cmd wifi status"))
    }

    @Test
    fun rejectsAnythingElseAsReadOnly() {
        assertFalse(readOnly("settings put system screen_brightness 10"))
        assertFalse(readOnly("dumpsys notification"))
        assertFalse(readOnly("content query --uri content://sms"))
        assertFalse(readOnly("cmd wifi set-wifi-enabled disabled"))
        assertFalse(readOnly("am start -a android.intent.action.VIEW"))
        // Chained commands become literal arguments, and getprop with extra args is not allowlisted.
        assertFalse(readOnly("getprop ; rm -rf /"))
    }

    @Test
    fun forbidsDestructiveCommands() {
        listOf(
            "rm -rf /sdcard/DCIM",
            "ls; rm -r /sdcard",
            "pm uninstall com.whatsapp",
            "pm clear com.whatsapp",
            "cmd package uninstall com.whatsapp",
            "reboot",
            "svc power shutdown",
            "content delete --uri content://sms",
            "settings delete global foo",
            "am broadcast -a android.intent.action.MASTER_CLEAR",
            "am force-stop moe.shizuku.privileged.api",
        ).forEach { assertNotNull(it, ShellPolicy.forbiddenReason(it)) }
    }

    @Test
    fun permitsOrdinaryChanges() {
        listOf("cmd uimode night yes", "svc wifi enable", "settings put system screen_brightness 120", "am start -a android.settings.SETTINGS")
            .forEach { assertNull(it, ShellPolicy.forbiddenReason(it)) }
    }
}

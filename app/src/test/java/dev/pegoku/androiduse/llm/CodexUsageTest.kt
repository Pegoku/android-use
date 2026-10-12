package dev.pegoku.androiduse.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodexUsageTest {
    /** Shape returned by chatgpt.com/backend-api/wham/usage. */
    private val sample = """
        {"plan_type":"plus","rate_limit":{"allowed":true,"limit_reached":false,
         "primary_window":{"used_percent":1,"limit_window_seconds":18000,"reset_after_seconds":17764,"reset_at":1791844862},
         "secondary_window":{"used_percent":30,"limit_window_seconds":604800,"reset_after_seconds":521459,"reset_at":1792348557}}}
    """.trimIndent()

    @Test
    fun parsesUsageEndpoint() {
        val usage = CodexUsageStore.parse(Json.parseToJsonElement(sample).jsonObject)!!
        assertEquals(99, usage.primary!!.leftPercent)
        assertEquals("5h", usage.primary!!.label)
        assertEquals(70, usage.secondary!!.leftPercent)
        assertEquals("Weekly", usage.secondary!!.label)
        assertEquals(70, usage.tightest!!.leftPercent)
        assertEquals("5h 99% · wk 70% left", usage.shortSummary)
        assertFalse(usage.limitReached)
    }

    @Test
    fun formatsResetTimes() {
        val now = 1_000_000_000_000L
        assertEquals("4h 56m", UsageWindow(1.0, 300, now / 1000 + (4 * 60 + 56) * 60).resetsIn(now))
        assertEquals("6d 0h", UsageWindow(30.0, 10080, now / 1000 + 6 * 24 * 3600 + 120).resetsIn(now))
    }
}

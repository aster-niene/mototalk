package dev.mototalk.diag

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DiagnosticsLogTest {

    @Test
    fun lineStartsWithBaseKeys() {
        val line = logLine(1000L, 20L, "SM-S918B-3fa2", "mark", mapOf("label" to "test 1"))
        assertEquals(
            """{"wallMs":1000,"monoMs":20,"phone":"SM-S918B-3fa2","event":"mark","label":"test 1"}""",
            Json.encode(line),
        )
    }

    @Test
    fun reservedKeysInFieldsAreRenamed() {
        val line = logLine(1L, 2L, "p", "e", mapOf("event" to "x", "phone" to "y"))
        assertEquals("e", line["event"])
        assertEquals("x", line["_event"])
        assertEquals("y", line["_phone"])
    }

    @Test
    fun fileNameIsSanitized() {
        assertEquals(
            "mototalk-2026-10-06-SM_S918B__x-3fa2.jsonl",
            logFileName(LocalDate.of(2026, 10, 6), "SM S918B/:x-3fa2"),
        )
    }
}

package com.clessira.jetbrains.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ElapsedFormatTest {

    @Test
    fun `formats like the VS Code status bar`() {
        assertEquals("<1m", ElapsedFormat.formatElapsed(0))
        assertEquals("<1m", ElapsedFormat.formatElapsed(59_999))
        assertEquals("1m", ElapsedFormat.formatElapsed(60_000))
        assertEquals("37m", ElapsedFormat.formatElapsed(37 * 60_000L))
        assertEquals("1h", ElapsedFormat.formatElapsed(60 * 60_000L))
        assertEquals("2h", ElapsedFormat.formatElapsed(2 * 60 * 60_000L))
        assertEquals("1h 5m", ElapsedFormat.formatElapsed(65 * 60_000L))
        assertEquals("26h 1m", ElapsedFormat.formatElapsed((26 * 60 + 1) * 60_000L))
    }
}

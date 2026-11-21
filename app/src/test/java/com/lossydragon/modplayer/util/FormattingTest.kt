package com.lossydragon.modplayer.util

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FormattingTest {

    @BeforeEach
    fun pinLocale() {
        // formatSize uses the default locale's decimal separator on purpose (it's UI text).
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `formatMs renders minutes and zero padded seconds`() {
        assertEquals("0:00", 0L.formatMs())
        assertEquals("0:09", 9_999L.formatMs())
        assertEquals("1:05", 65_000L.formatMs())
        assertEquals("12:34", (12 * 60_000L + 34_000L).formatMs())
        assertEquals("100:00", 6_000_000L.formatMs())
    }

    @Test
    fun `formatSize picks the unit by magnitude`() {
        assertEquals("0 B", 0L.formatSize())
        assertEquals("1023 B", 1_023L.formatSize())
        assertEquals("1.0 KB", 1_024L.formatSize())
        assertEquals("1.5 KB", 1_536L.formatSize())
        assertEquals("1.0 MB", 1_048_576L.formatSize())
        assertEquals("2.5 MB", (2_621_440L).formatSize())
    }
}

package com.lossydragon.modplayer.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LegacyPaletteStyleTest {

    @Test
    fun `stored ordinals map to the PaletteStyle names of that release`() {
        assertEquals("TonalSpot", legacyPaletteStyleName(0))
        assertEquals("Vibrant", legacyPaletteStyleName(2))
        assertEquals("FruitSalad", legacyPaletteStyleName(5))
        assertEquals("Content", legacyPaletteStyleName(8))
    }

    @Test
    fun `missing or out of range ordinals fall back to the default`() {
        assertEquals(AppPreferences.DEFAULT_THEME_STYLE, legacyPaletteStyleName(null))
        assertEquals(AppPreferences.DEFAULT_THEME_STYLE, legacyPaletteStyleName(-1))
        assertEquals(AppPreferences.DEFAULT_THEME_STYLE, legacyPaletteStyleName(99))
    }
}

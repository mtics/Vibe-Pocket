package au.edu.uts.vibepocket.ui.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateTest {
    @Test
    fun invalidStoredValuesFallBackToTheUpgradeDefaults() {
        assertEquals(Palette.LIGHT, palette(null))
        assertEquals(Palette.LIGHT, palette("SEPIA"))
        assertEquals(Hand.RIGHT, hand(null))
        assertEquals(Hand.RIGHT, hand("AUTO"))
    }

    @Test
    fun storedValuesRoundTripByStableEnumName() {
        Palette.entries.forEach { value -> assertEquals(value, palette(value.name)) }
        Hand.entries.forEach { value -> assertEquals(value, hand(value.name)) }
    }

    @Test
    fun systemPaletteIsTheOnlyPaletteThatFollowsTheDevice() {
        assertFalse(Palette.LIGHT.usesDark(systemDark = true))
        assertTrue(Palette.DARK.usesDark(systemDark = false))
        assertFalse(Palette.SYSTEM.usesDark(systemDark = false))
        assertTrue(Palette.SYSTEM.usesDark(systemDark = true))
    }
}

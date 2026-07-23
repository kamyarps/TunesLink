package com.kamyarps.tuneslink

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class TunesLinkAccessibilityTest {
    @Test
    fun semanticTextPairsMeetWcagAa() {
        listOf(
            TunesLinkPalettes.Dark,
            TunesLinkPalettes.Light,
            TunesLinkPalettes.HighContrastDark,
            TunesLinkPalettes.HighContrastLight,
        ).forEach { palette ->
            assertContrast(palette.primaryText, palette.canvas, 4.5, "primary on canvas")
            assertContrast(palette.primaryText, palette.surface, 4.5, "primary on surface")
            assertContrast(palette.secondaryText, palette.canvas, 4.5, "secondary on canvas")
            assertContrast(palette.secondaryText, palette.surface, 4.5, "secondary on surface")
            assertContrast(palette.accentText, palette.canvas, 4.5, "accent on canvas")
            assertContrast(palette.onBrandText, palette.brandStart, 4.5, "on-brand on start")
            assertContrast(palette.onBrandText, palette.brandEnd, 4.5, "on-brand on end")
            assertContrast(palette.danger, palette.canvas, 4.5, "danger on canvas")
            assertContrast(palette.success, palette.canvas, 4.5, "success on canvas")
        }
    }

    @Test
    fun manualAddressAcceptsOnlyPrivateIpv4AndOptionalPort() {
        assertNull(TunesLinkViewModel.validateManualAddress(" 192.168.1.20 "))
        assertNull(TunesLinkViewModel.validateManualAddress("10.0.0.8:45832"))
        assertEquals(R.string.address_not_private, TunesLinkViewModel.validateManualAddress("8.8.8.8"))
        assertEquals(R.string.address_invalid_port, TunesLinkViewModel.validateManualAddress("192.168.1.20:70000"))
        assertEquals(R.string.address_invalid_ipv4, TunesLinkViewModel.validateManualAddress("computer.local"))
    }

    private fun assertContrast(foreground: Int, background: Int, minimum: Double, label: String) {
        val first = luminance(foreground)
        val second = luminance(background)
        val ratio = (max(first, second) + 0.05) / (min(first, second) + 0.05)
        assertTrue("$label contrast was $ratio", ratio >= minimum)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 0xFF) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}

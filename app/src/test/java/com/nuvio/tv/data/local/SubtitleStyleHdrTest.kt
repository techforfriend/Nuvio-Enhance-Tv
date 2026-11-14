package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 62 H2 (D7): the HDR / Dolby Vision subtitle style and the controls dim. */
class SubtitleStyleHdrTest {

    private val style = SubtitleStyleSettings(
        textColor = 0xFFFFFFFF.toInt(),
        backgroundColor = 0x80646464.toInt(),
        outlineColor = 0xFFFFFFFF.toInt(),
        hdrStyleEnabled = true,
        hdrTextColor = 0xFFFFFF00.toInt(),
        hdrBrightnessPercent = 100
    )

    @Test
    fun `sdr output keeps the normal style`() {
        assertSame(style, style.forOutput(hdrOutput = false))
        assertSame(style, style.editableForOutput(hdrOutput = false))
        assertFalse(style.hdrStyleApplies(hdrOutput = false))
    }

    @Test
    fun `hdr output keeps the normal style while the hdr style is off`() {
        val off = style.copy(hdrStyleEnabled = false)
        assertSame(off, off.forOutput(hdrOutput = true))
        assertSame(off, off.editableForOutput(hdrOutput = true))
        assertFalse(off.hdrStyleApplies(hdrOutput = true))
    }

    @Test
    fun `full brightness swaps in the hdr text colour undimmed`() {
        val drawn = style.forOutput(hdrOutput = true)
        assertEquals(0xFFFFFF00.toInt(), drawn.textColor)
        assertEquals(style.backgroundColor, drawn.backgroundColor)
        assertEquals(style.outlineColor, drawn.outlineColor)
    }

    @Test
    fun `the default draws every colour at 60 percent and keeps alpha`() {
        val drawn = style.copy(hdrBrightnessPercent = SubtitleStyleSettings.HDR_BRIGHTNESS_DEFAULT)
            .forOutput(hdrOutput = true)
        assertEquals(0xFF999900.toInt(), drawn.textColor)
        assertEquals(0x803C3C3C.toInt(), drawn.backgroundColor)
        assertEquals(0xFF999999.toInt(), drawn.outlineColor)
    }

    @Test
    fun `brightness outside 30 to 100 is clamped`() {
        assertEquals(0xFF4C4C00.toInt(), style.copy(hdrBrightnessPercent = 0).forOutput(hdrOutput = true).textColor)
        assertEquals(0xFFFFFF00.toInt(), style.copy(hdrBrightnessPercent = 250).forOutput(hdrOutput = true).textColor)
    }

    @Test
    fun `the editor shows the undimmed hdr colour, opacity included`() {
        val translucent = style.copy(hdrTextColor = 0x80FFFF00.toInt(), hdrBrightnessPercent = 30)
        assertEquals(0x80FFFF00.toInt(), translucent.editableForOutput(hdrOutput = true).textColor)
        assertTrue(translucent.hdrStyleApplies(hdrOutput = true))
    }

    @Test
    fun `controls dim only on hdr output with both switches on`() {
        val dimming = style.copy(hdrDimPlayerControls = true, hdrBrightnessPercent = 60)
        assertEquals(0.6f, dimming.playerControlsBrightness(hdrOutput = true), 0.0001f)
        assertEquals(1f, dimming.playerControlsBrightness(hdrOutput = false), 0f)
        assertEquals(1f, dimming.copy(hdrStyleEnabled = false).playerControlsBrightness(hdrOutput = true), 0f)
        assertEquals(1f, style.copy(hdrBrightnessPercent = 60).playerControlsBrightness(hdrOutput = true), 0f)
    }

    @Test
    fun `stored brightness snaps to the 30 to 100 steps`() {
        assertEquals(30, SubtitleStyleSettings.coerceHdrBrightness(0))
        assertEquals(60, SubtitleStyleSettings.coerceHdrBrightness(58))
        assertEquals(70, SubtitleStyleSettings.coerceHdrBrightness(65))
        assertEquals(100, SubtitleStyleSettings.coerceHdrBrightness(250))
    }
}

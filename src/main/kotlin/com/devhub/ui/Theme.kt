package com.devhub.ui

import com.jakewharton.mosaic.ui.Color

/**
 * Mosaic's Color companion only exposes the 8 base ANSI colors, so the brighter
 * shades the UI wants are built from the Color(r, g, b) factory here, in one place.
 */
object Theme {
    val accent: Color = Color(0x3C, 0xC8, 0xE6)   // bright cyan — headers, selection marker
    val dim: Color = Color(0x80, 0x80, 0x80)      // gray — secondary text
    val ok: Color = Color(0x4C, 0xD1, 0x6E)       // bright green
    val bad: Color = Color(0xF2, 0x55, 0x55)      // bright red
    val warn: Color = Color(0xE6, 0xC2, 0x29)     // amber
    val text: Color = Color.White
    val selected: Color = Color(0xFF, 0xFF, 0xFF) // bright white
}

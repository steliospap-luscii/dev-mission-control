package com.devhub.ui

import com.jakewharton.mosaic.ui.Color

/**
 * "Mission control" palette based on Catppuccin Mocha. Mosaic's Color companion only exposes the
 * 8 base ANSI colors, so every shade here is built from the Color(r, g, b) factory. Terminals with
 * truecolor (most modern ones) render these exactly; 256-color terminals approximate.
 */
object Theme {
    val accent: Color = Color(0x89, 0xB4, 0xFA)    // blue — headers, selection marker
    val info: Color = Color(0x89, 0xDC, 0xEB)      // sky — in-progress / informational
    val highlight: Color = Color(0xCB, 0xA6, 0xF7) // mauve — title accent
    val ok: Color = Color(0xA6, 0xE3, 0xA1)        // green
    val warn: Color = Color(0xFA, 0xB3, 0x87)      // peach
    val bad: Color = Color(0xF3, 0x8B, 0xA8)       // red
    val dim: Color = Color(0x6C, 0x70, 0x86)       // overlay — secondary text
    val text: Color = Color(0xCD, 0xD6, 0xF4)      // primary text
    val selected: Color = Color(0xF5, 0xE0, 0xDC)  // rosewater — selected row pops
}

package com.devhub.ui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

/**
 * Mosaic renders inline (no alternate screen), so a frame taller than the terminal corrupts —
 * it redraws by moving the cursor up, and overflow leaves duplicated copies. To prevent that,
 * panels build a measurable list of [Seg] lines and [RenderLines] clips them to a row budget,
 * scrolling to keep the selected ("anchor") line visible.
 */
data class Seg(val text: String, val color: Color, val bold: Boolean = false, val bg: Color? = null)

class LineBuf {
    val lines = mutableListOf<List<Seg>>()
    var anchor = 0
        private set

    fun add(vararg segs: Seg) { lines.add(segs.toList()) }
    fun add(segs: List<Seg>) { lines.add(segs) }
    fun blank() { lines.add(emptyList()) }

    /** Mark that the next line added belongs to the selected item (kept on screen). */
    fun anchorHere() { anchor = lines.size }
}

fun seg(text: String, color: Color, bold: Boolean = false, bg: Color? = null) = Seg(text, color, bold, bg)

@Composable
fun RenderLines(buf: LineBuf, budget: Int) {
    val lines = buf.lines
    val n = lines.size
    if (n <= budget || budget < 3) {
        lines.forEach { RenderLine(it) }
        return
    }
    val inner = (budget - 1).coerceAtLeast(1)            // reserve one row for the scroll indicator
    val start = (buf.anchor - inner / 2).coerceIn(0, (n - inner).coerceAtLeast(0))
    val end = (start + inner).coerceAtMost(n)
    for (i in start until end) RenderLine(lines[i])
    RenderLine(listOf(seg("  … ${start + 1}–$end of $n   ↑/↓ to scroll", Theme.dim)))
}

@Composable
private fun RenderLine(line: List<Seg>) {
    if (line.isEmpty()) { Text(""); return }
    Row {
        line.forEach { s ->
            Text(
                s.text,
                color = s.color,
                background = s.bg ?: Color.Unspecified,
                textStyle = if (s.bold) TextStyle.Bold else TextStyle.Empty,
            )
        }
    }
}

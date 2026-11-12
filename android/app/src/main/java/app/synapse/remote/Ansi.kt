package app.synapse.remote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/** Colours for the 16 ANSI slots plus the terminal's default foreground/background. */
class AnsiPalette(val normal: List<Color>, val bright: List<Color>, val fg: Color, val bg: Color) {
    companion object {
        private fun hex(vararg v: Long) = v.map { Color(0xFF000000 or it) }

        fun of(dark: Boolean, fg: Color, bg: Color) = if (dark) {
            AnsiPalette(
                hex(0x45475A, 0xF38BA8, 0xA6E3A1, 0xF9E2AF, 0x89B4FA, 0xF5C2E7, 0x94E2D5, 0xBAC2DE),
                hex(0x6C7086, 0xF38BA8, 0xA6E3A1, 0xF9E2AF, 0x89B4FA, 0xF5C2E7, 0x94E2D5, 0xCDD6F4), fg, bg,
            )
        } else {
            AnsiPalette(
                hex(0x5C5F77, 0xD20F39, 0x40A02B, 0xDF8E1D, 0x1E66F5, 0xEA76CB, 0x179299, 0xACB0BE),
                hex(0x6C6F85, 0xD20F39, 0x40A02B, 0xDF8E1D, 0x1E66F5, 0xEA76CB, 0x179299, 0xBCC0CC), fg, bg,
            )
        }
    }
}

// Everything except SGR ("...m"): cursor moves, OSC titles/hyperlinks, charset selection.
private val NON_SGR = Regex("\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)|\u001b\\[[0-9;:?<>=]*[ -/]*[@-ln-~]|\u001b[()][A-Za-z0-9]")
private val SGR = Regex("\u001b\\[([0-9;:]*)m")
private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

private fun color256(n: Int, p: AnsiPalette): Color = when {
    n < 8 -> p.normal[n]
    n < 16 -> p.bright[n - 8]
    n < 232 -> {
        val i = n - 16
        Color(CUBE[i / 36], CUBE[(i / 6) % 6], CUBE[i % 6])
    }
    else -> {
        val g = 8 + (n - 232) * 10
        Color(g, g, g)
    }
}

private class Pen {
    var fg: Color? = null
    var bg: Color? = null
    var bold = false
    var dim = false
    var italic = false
    var underline = false
    var reverse = false

    fun reset() { fg = null; bg = null; bold = false; dim = false; italic = false; underline = false; reverse = false }

    fun style(p: AnsiPalette): SpanStyle {
        var f = fg ?: p.fg
        var b = bg
        if (reverse) { val t = f; f = b ?: p.bg; b = t }
        if (dim) f = f.copy(alpha = 0.6f)
        return SpanStyle(
            color = f,
            background = b ?: Color.Unspecified,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
    }

    fun apply(params: List<Int>, p: AnsiPalette) {
        var i = 0
        while (i < params.size) {
            when (val c = params[i]) {
                0 -> reset()
                1 -> bold = true
                2 -> dim = true
                3 -> italic = true
                4 -> underline = true
                7 -> reverse = true
                22 -> { bold = false; dim = false }
                23 -> italic = false
                24 -> underline = false
                27 -> reverse = false
                in 30..37 -> fg = p.normal[c - 30]
                in 40..47 -> bg = p.normal[c - 40]
                in 90..97 -> fg = p.bright[c - 90]
                in 100..107 -> bg = p.bright[c - 100]
                39 -> fg = null
                49 -> bg = null
                38, 48 -> {
                    val col: Color? = when (params.getOrNull(i + 1)) {
                        5 -> params.getOrNull(i + 2)?.let { i += 2; color256(it.coerceIn(0, 255), p) }
                        2 -> if (i + 4 < params.size) {
                            val r = params[i + 2]; val g = params[i + 3]; val b = params[i + 4]
                            i += 4
                            Color(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
                        } else null
                        else -> null
                    }
                    if (col != null) { if (c == 38) fg = col else bg = col }
                }
            }
            i++
        }
    }
}

/**
 * Turns tmux `capture-pane -e` output into styled text. [cursor] (row counted in the captured text, column in
 * cells) is drawn as an inverted block, padding past the end of the trimmed text when it sits below the content.
 */
fun parseAnsi(src: String, p: AnsiPalette, cursor: Cursor? = null): AnnotatedString {
    val clean = NON_SGR.replace(src, "")
    val pen = Pen()
    val cur = cursor?.takeIf { it.visible }
    val cursorStyle = SpanStyle(color = p.bg, background = p.fg)
    return buildAnnotatedString {
        var row = 0
        var col = 0
        var drawn = false

        fun segment(text: String) {
            val style = pen.style(p)
            var runStart = 0
            for (i in text.indices) {
                val ch = text[i]
                if (cur != null && !drawn && row == cur.y && col == cur.x) {
                    if (i > runStart) withStyle(style) { append(text, runStart, i) }
                    if (ch == '\n') {
                        withStyle(cursorStyle) { append(' ') } // cursor at end of line: block, then the newline
                        runStart = i
                    } else {
                        withStyle(cursorStyle) { append(ch) }
                        runStart = i + 1
                    }
                    drawn = true
                }
                if (ch == '\n') { row++; col = 0 } else col++
            }
            if (runStart < text.length) withStyle(style) { append(text, runStart, text.length) }
        }

        var last = 0
        for (m in SGR.findAll(clean)) {
            if (m.range.first > last) segment(clean.substring(last, m.range.first))
            val params = m.groupValues[1].split(';', ':').map { it.toIntOrNull() ?: 0 }.ifEmpty { listOf(0) }
            pen.apply(params, p)
            last = m.range.last + 1
        }
        if (last < clean.length) segment(clean.substring(last))

        if (cur != null && !drawn) { // below/after the trimmed text
            while (row < cur.y) { append('\n'); row++; col = 0 }
            while (col < cur.x) { append(' '); col++ }
            withStyle(cursorStyle) { append(' ') }
        }
    }
}

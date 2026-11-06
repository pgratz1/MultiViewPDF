package com.pgratz.multiviewpdf.model

import kotlin.math.max
import kotlin.math.min

/**
 * One line of a page's text layer. [quads] holds one quad per character of [text]
 * (same length), so any substring maps straight back onto the page.
 */
data class TextLine(val text: String, val quads: List<PQuad>) {
    init {
        require(text.length == quads.size) { "text and quads differ in length" }
    }

    val bounds: PRect get() = quads.unionBounds()
}

/** A paragraph-ish group of lines, as MuPDF's StructuredText reports them. */
data class TextBlock(val lines: List<TextLine>)

/** The text layer of one page, in MuPDF's reading order. */
data class PageText(val blocks: List<TextBlock>) {
    val isEmpty: Boolean get() = blocks.all { b -> b.lines.all { it.text.isBlank() } }
}

/** Smallest rect containing all the quads (a zero rect for none). */
fun List<PQuad>.unionBounds(): PRect {
    var r: PRect? = null
    for (q in this) r = r?.union(q.bounds) ?: q.bounds
    return r ?: PRect(0f, 0f, 0f, 0f)
}

fun PRect.union(o: PRect) = PRect(min(x0, o.x0), min(y0, o.y0), max(x1, o.x1), max(y1, o.y1))

/** Distance from [p] to the nearest point of this rect (0 inside). */
fun PRect.distanceTo(p: PPoint): Float {
    val dx = max(max(x0 - p.x, 0f), p.x - x1)
    val dy = max(max(y0 - p.y, 0f), p.y - y1)
    return kotlin.math.hypot(dx, dy)
}

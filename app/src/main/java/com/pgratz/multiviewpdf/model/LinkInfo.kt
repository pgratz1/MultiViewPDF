package com.pgratz.multiviewpdf.model

/**
 * One link on a page. [page] is the 0-based target page of an internal (GoTo) link;
 * null means [uri] points outside the document (web, mailto, ...).
 */
data class LinkInfo(val rect: PRect, val uri: String, val page: Int?) {
    val isExternal: Boolean get() = page == null

    /** Short text for the chip / hover tooltip. */
    val label: String
        get() = if (page != null) "Page ${page + 1}" else uri.forDisplay(MAX_LABEL)

    companion object {
        const val MAX_LABEL = 48
    }
}

/**
 * Link under [p]: an exact hit (topmost wins), else the nearest one within [slop] page
 * units. Link rects are only a line high, so a finger-sized slop would otherwise grab
 * the link on the line below.
 */
fun List<LinkInfo>.hitTest(p: PPoint, slop: Float): LinkInfo? =
    lastOrNull { it.rect.contains(p) }
        ?: filter { it.rect.contains(p, slop) }.minByOrNull { it.rect.distanceTo(p) }

/** Drops the scheme of web URLs and shortens the middle of long ones. */
private fun String.forDisplay(max: Int): String {
    val s = removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    if (s.length <= max) return s
    val head = max * 2 / 3
    return s.take(head) + "…" + s.takeLast(max - head - 1)
}

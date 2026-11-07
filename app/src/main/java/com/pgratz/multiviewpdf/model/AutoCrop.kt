package com.pgratz.multiviewpdf.model

import kotlin.math.max
import kotlin.math.min

/** Pure pixel math behind "Auto" crop; the renderer supplies low-resolution page bitmaps. */
object AutoCrop {
    /** Pixels with any channel darker than this count as content (scan noise is ~245+). */
    const val THRESHOLD = 235

    /** Ignore stray specks: a row/column needs this many content pixels to count. */
    const val MIN_PIXELS = 2

    /** Runs of ink closer together than this fraction of the page are one segment (line gaps, two-column gutters are wider). */
    const val MERGE_GAP = 0.02f
    /** A segment at least this wide/tall is content for sure. */
    const val MAIN_SIZE = 0.15f
    /** Across the page: a strip narrower than this outside the main content is margin matter (line numbers, side banners). */
    const val THIN_STRIP = 0.05f
    /** Down the page: a run shorter than this, within [EDGE_BAND] of the top or bottom, is a header, footer or page number. */
    const val SHORT_RUN = 0.02f
    const val EDGE_BAND = 0.12f

    /**
     * Margins of the page's main content in an ARGB pixel array, or null for a blank page.
     * Ink is grouped into runs along each axis; thin strips beside the text (submission
     * line numbers, "confidential" side banners) and single lines in the top/bottom bands
     * (running headers, page numbers) don't count, as long as there is a substantial block
     * to crop to. Pages that are all thin strips (drawings) keep every mark.
     */
    fun contentMargins(pixels: IntArray, width: Int, height: Int): CropMargins? {
        val rowCounts = IntArray(height)
        val colCounts = IntArray(width)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val c = pixels[row + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (min(r, min(g, b)) < THRESHOLD) {
                    rowCounts[y]++
                    colCounts[x]++
                }
            }
        }
        val xs = mainExtent(colCounts, dropMax = THIN_STRIP, band = 1f) ?: return null
        val ys = mainExtent(rowCounts, dropMax = SHORT_RUN, band = EDGE_BAND) ?: return null
        return CropMargins(
            left = xs.first.toFloat() / width,
            top = ys.first.toFloat() / height,
            right = (width - 1 - xs.last).toFloat() / width,
            bottom = (height - 1 - ys.last).toFloat() / height,
        )
    }

    /**
     * First..last index of the content along one axis. Marked indices (count ≥ [MIN_PIXELS])
     * form runs, merged across gaps under [MERGE_GAP]. When some run is at least [MAIN_SIZE]
     * long, runs shorter than [dropMax] that lie wholly within [band] of either end and
     * outside the span of the long runs are ignored. Null when nothing is marked.
     */
    internal fun mainExtent(counts: IntArray, dropMax: Float, band: Float): IntRange? {
        val n = counts.size
        val runs = ArrayList<IntRange>()
        val gap = max(1, (MERGE_GAP * n).toInt())
        var start = -1
        var last = -1
        for (i in 0 until n) {
            if (counts[i] < MIN_PIXELS) continue
            if (start < 0) { start = i } else if (i - last > gap) { runs += start..last; start = i }
            last = i
        }
        if (start < 0) return null
        runs += start..last
        val main = runs.filter { it.last - it.first + 1 >= MAIN_SIZE * n }
        if (main.isEmpty()) return runs.first().first..runs.last().last
        val lo = main.minOf { it.first }
        val hi = main.maxOf { it.last }
        val bandPx = band * n
        val keep = runs.filter { r ->
            val len = r.last - r.first + 1
            val inside = r.first >= lo && r.last <= hi
            val peripheral = r.last < bandPx || r.first > n - 1 - bandPx
            inside || len >= dropMax * n || !peripheral
        }
        return keep.minOf { it.first }..keep.maxOf { it.last }
    }

    /** Smallest margins over all pages (so no page loses content), minus [padding]. */
    fun union(pages: List<CropMargins>, padding: Float = 0.01f): CropMargins {
        if (pages.isEmpty()) return CropMargins.NONE
        return CropMargins(
            left = max(0f, pages.minOf { it.left } - padding),
            top = max(0f, pages.minOf { it.top } - padding),
            right = max(0f, pages.minOf { it.right } - padding),
            bottom = max(0f, pages.minOf { it.bottom } - padding),
        ).clamped()
    }
}

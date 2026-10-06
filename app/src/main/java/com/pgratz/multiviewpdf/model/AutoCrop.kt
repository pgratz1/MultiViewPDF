package com.pgratz.multiviewpdf.model

import kotlin.math.max
import kotlin.math.min

/** Pure pixel math behind "Auto" crop; the renderer supplies low-resolution page bitmaps. */
object AutoCrop {
    /** Pixels with any channel darker than this count as content (scan noise is ~245+). */
    const val THRESHOLD = 235

    /** Ignore stray specks: a row/column needs this many content pixels to count. */
    const val MIN_PIXELS = 2

    /**
     * Margins of the non-white content in an ARGB pixel array, or null for a blank page.
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
        val top = rowCounts.indexOfFirst { it >= MIN_PIXELS }
        if (top < 0) return null
        val bottom = rowCounts.indexOfLast { it >= MIN_PIXELS }
        val left = colCounts.indexOfFirst { it >= MIN_PIXELS }
        val right = colCounts.indexOfLast { it >= MIN_PIXELS }
        if (left < 0) return null
        return CropMargins(
            left = left.toFloat() / width,
            top = top.toFloat() / height,
            right = (width - 1 - right).toFloat() / width,
            bottom = (height - 1 - bottom).toFloat() / height,
        )
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

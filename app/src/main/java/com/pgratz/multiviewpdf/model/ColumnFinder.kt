package com.pgratz.multiviewpdf.model

import kotlin.math.abs

/**
 * Finds a column of content from a horizontal ink profile: profile[x] is how much ink lies
 * at x (dark pixels from a coarse render, so it works on scanned pages too). Columns are
 * runs where the profile is well above zero, separated by near-empty gutters. Sparse
 * gutter content, like the line numbers between a patent's columns, falls below the
 * threshold or is dropped as too narrow.
 */
object ColumnFinder {
    /** Fraction of a "typical full" ink level (90th percentile) below which x counts as gutter. */
    private const val GUTTER_LEVEL = 0.15f

    /**
     * The column (inclusive x range) containing [at], or the nearest one if [at] is in a
     * gutter or margin. Gaps narrower than [minGap] are merged (word gaps, ragged edges);
     * runs narrower than [minWidth] are ignored unless nothing wider exists. Null if the
     * profile is empty.
     */
    fun find(profile: FloatArray, at: Int, minGap: Int, minWidth: Int): IntRange? {
        val inked = profile.filter { it > 0f }.sorted()
        if (inked.isEmpty()) return null
        val threshold = inked[(inked.size * 0.9f).toInt().coerceAtMost(inked.size - 1)] * GUTTER_LEVEL

        val runs = mutableListOf<IntRange>()
        var start = -1
        for (x in profile.indices) {
            val on = profile[x] > threshold
            if (on && start < 0) start = x
            if (!on && start >= 0) {
                runs += start until x
                start = -1
            }
        }
        if (start >= 0) runs += start until profile.size
        if (runs.isEmpty()) return null

        val merged = mutableListOf<IntRange>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.last - 1 < minGap) merged[merged.lastIndex] = last.first..r.last
            else merged += r
        }
        val candidates = merged.filter { it.last - it.first + 1 >= minWidth }.ifEmpty { merged }
        return candidates.firstOrNull { at in it } ?: candidates.minBy { distance(at, it) }
    }

    private fun distance(x: Int, r: IntRange) = if (x < r.first) r.first - x else abs(x - r.last)
}

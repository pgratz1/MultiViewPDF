package com.pgratz.multiviewpdf

import com.pgratz.multiviewpdf.model.ColumnFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColumnFinderTest {
    /** A 600-wide page: margins, text at [a, b) with [level] ink, and optional extras. */
    private fun profile(vararg spans: Triple<Int, Int, Float>): FloatArray {
        val p = FloatArray(600)
        for ((a, b, level) in spans) for (x in a until b) p[x] = level
        return p
    }

    // Two columns 50..290 and 310..550, patent-style line numbers at 296..304.
    private val twoColumn = profile(
        Triple(50, 290, 60f),
        Triple(296, 304, 6f),
        Triple(310, 550, 60f),
    ).also {
        // Ragged word gaps inside the columns.
        it[120] = 0f; it[121] = 0f; it[400] = 0f
    }

    @Test fun findsLeftColumn() =
        assertEquals(50..289, ColumnFinder.find(twoColumn, 100, minGap = 6, minWidth = 48))

    @Test fun findsRightColumn() =
        assertEquals(310..549, ColumnFinder.find(twoColumn, 500, minGap = 6, minWidth = 48))

    @Test fun gutterPicksNearestColumn() =
        assertEquals(310..549, ColumnFinder.find(twoColumn, 306, minGap = 6, minWidth = 48))

    @Test fun marginPicksNearestColumn() =
        assertEquals(50..289, ColumnFinder.find(twoColumn, 5, minGap = 6, minWidth = 48))

    @Test fun denseLineNumbersAreTooNarrowToBeAColumn() {
        val p = twoColumn.copyOf().also { for (x in 296 until 304) it[x] = 40f }
        assertEquals(310..549, ColumnFinder.find(p, 300, minGap = 6, minWidth = 48))
    }

    @Test fun singleColumnPage() =
        assertEquals(72..539, ColumnFinder.find(profile(Triple(72, 540, 50f)), 300, minGap = 6, minWidth = 48))

    @Test fun blankPage() = assertNull(ColumnFinder.find(FloatArray(600), 300, minGap = 6, minWidth = 48))
}

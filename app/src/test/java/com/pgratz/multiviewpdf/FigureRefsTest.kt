package com.pgratz.multiviewpdf

import com.pgratz.multiviewpdf.model.FigureKey
import com.pgratz.multiviewpdf.model.FigureKind
import com.pgratz.multiviewpdf.model.FigureRefs
import org.junit.Assert.assertEquals
import org.junit.Test

class FigureRefsTest {
    private fun f(vararg n: Int) = n.map { FigureKey(FigureKind.FIGURE, it) }
    private fun t(vararg n: Int) = n.map { FigureKey(FigureKind.TABLE, it) }

    @Test fun singleReferences() {
        assertEquals(f(3), FigureRefs.find("As shown in Figure 3, prefetchers open rows."))
        assertEquals(f(8), FigureRefs.find("Figure 8 details the mechanism."))
        assertEquals(f(2), FigureRefs.find("Figure 2b shows the second scheme."))
        assertEquals(t(1), FigureRefs.find("Table 1 lists the system configuration."))
        assertEquals(f(3), FigureRefs.find("FIG. 3 is a block diagram of the detector."))
        assertEquals(f(5), FigureRefs.find("(see Fig. 5)"))
    }

    @Test fun listsAndRanges() {
        assertEquals(f(3, 4), FigureRefs.find("Figures 3 and 4 compare the two."))
        assertEquals(f(2, 3, 4), FigureRefs.find("as in Figs. 2–4 above"))
        assertEquals(f(3, 4, 5), FigureRefs.find("Figure 3, 4, and 5 agree."))
        assertEquals(f(10, 11, 12), FigureRefs.find("Figures 10 through 12 show speedups."))
        assertEquals(f(6, 8) + t(2), FigureRefs.find("Figure 6 and Figure 8, plus Table 2."))
    }

    @Test fun romanNumeralsAndAlgorithms() {
        assertEquals(t(1), FigureRefs.find("Table I shows how this extension works."))
        assertEquals(t(2, 4), FigureRefs.find("accesses shown in Table II are shown in Table IV, generated"))
        assertEquals(listOf(FigureKey(FigureKind.ALGORITHM, 1)), FigureRefs.find("applying Alg. 1 to a memory stream"))
        assertEquals(listOf(FigureKey(FigureKind.ALGORITHM, 4)), FigureRefs.find("Algorithm 4 shows how R-Max handles reordering."))
        assertEquals(f(2), FigureRefs.find("Fig. 2 shows a high level overview."))
        assertEquals(emptyList<FigureKey>(), FigureRefs.find("Table Isolation is not a thing")) // "Is" is not a numeral
    }

    @Test fun noFalsePositives() {
        assertEquals(emptyList<FigureKey>(), FigureRefs.find("Section 3 describes the design."))
        assertEquals(emptyList<FigureKey>(), FigureRefs.find("We configure 3 prefetchers per core."))
        assertEquals(emptyList<FigureKey>(), FigureRefs.find("The figure is large."))
        assertEquals(f(3), FigureRefs.find("Figure 3 and Figure 3 again")) // de-duplicated
    }
}

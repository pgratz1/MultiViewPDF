package com.pgratz.multiviewpdf

import com.pgratz.multiviewpdf.model.Body
import com.pgratz.multiviewpdf.model.Caption
import com.pgratz.multiviewpdf.model.Figure
import com.pgratz.multiviewpdf.model.FigureKey
import com.pgratz.multiviewpdf.model.FigureKind
import com.pgratz.multiviewpdf.model.FigureNote
import com.pgratz.multiviewpdf.model.PQuad
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.ReadingPlans
import com.pgratz.multiviewpdf.model.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPlanTest {
    private fun s(text: String, row: Int) = Sentence(text, listOf(PQuad.of(PRect(0f, row * 10f, 100f, row * 10f + 10f))))
    private val fig3 = Figure(
        FigureKey(FigureKind.FIGURE, 3), page = 2,
        Caption(FigureKind.FIGURE, 3, "Figure 3: Geomean increase.", emptyList(), PRect(318f, 230f, 560f, 260f), PRect(318f, 70f, 560f, 230f)),
    )
    private val table1 = Figure(
        FigureKey(FigureKind.TABLE, 1), page = 7,
        Caption(FigureKind.TABLE, 1, "Table 1: System configuration.", emptyList(), PRect(54f, 231f, 296f, 239f), PRect(54f, 70f, 296f, 231f)),
    )
    private val catalog = mapOf(fig3.key to fig3, table1.key to table1)

    private val sentences = listOf(
        s("Intro sentence.", 0),
        s("As shown in Figure 3 and Table 1, the rate rises.", 1),
        s("Figure 3 again confirms it.", 2),
        s("See Figure 9 for the ablation.", 3),
        s("Figure 3: Geomean increase in activations.", 4), // a caption that slipped through
    )

    @Test fun notesFollowFirstMentionOnly() {
        val plan = ReadingPlans.build(2, null, sentences) { catalog[it] }
        val kinds = plan.items.map { if (it is Body) "B" else (it as FigureNote).key.id }
        assertEquals(listOf("B", "B", "figure-3", "table-1", "B", "B", "figure-9", "B"), kinds)
        val note3 = plan.items[2] as FigureNote
        assertEquals(fig3, note3.figure)
        assertEquals(listOf(PQuad.of(fig3.caption.extent)), note3.quads) // same page: highlight the figure
        val noteT = plan.items[3] as FigureNote
        assertTrue(noteT.quads.isEmpty()) // other page: no highlight, no view change
        val note9 = plan.items[6] as FigureNote
        assertNull(note9.figure)
    }

    @Test fun bodyIndexMapsToItems() {
        val plan = ReadingPlans.build(2, null, sentences) { catalog[it] }
        assertEquals(5, plan.bodySentences.size)
        assertEquals(0, plan.bodyIndexToItem(0))
        assertEquals(1, plan.bodyIndexToItem(1))
        assertEquals(4, plan.bodyIndexToItem(2))
        assertEquals(7, plan.bodyIndexToItem(4))
        assertEquals(setOf(fig3.key, table1.key, FigureKey(FigureKind.FIGURE, 9)), plan.referencedKeys)
    }

    @Test fun referencedKeysSkipCaptionLikeSentences() {
        assertEquals(
            setOf(fig3.key, table1.key, FigureKey(FigureKind.FIGURE, 9)),
            ReadingPlans.referencedKeys(sentences),
        )
    }
}

package com.pgratz.multiviewpdf

import com.pgratz.multiviewpdf.model.PPoint
import com.pgratz.multiviewpdf.model.PQuad
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.PageText
import com.pgratz.multiviewpdf.model.SentenceSplitter
import com.pgratz.multiviewpdf.model.TextBlock
import com.pgratz.multiviewpdf.model.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {
    private val cw = 5f // char width
    private val lh = 10f // line height

    /** A line whose chars are [cw] wide at row [row] (y = row*lh), starting at [x0]. */
    private fun line(text: String, row: Int, x0: Float = 0f) = TextLine(
        text,
        text.indices.map { i ->
            PQuad.of(PRect(x0 + i * cw, row * lh, x0 + (i + 1) * cw, (row + 1) * lh))
        },
    )

    private fun page(vararg blocks: TextBlock) = PageText(blocks.toList())
    private fun block(vararg lines: TextLine) = TextBlock(lines.toList())
    private fun texts(page: PageText) = SentenceSplitter.split(page).map { it.text }

    @Test fun splitsOnTerminatorBeforeCapital() =
        assertEquals(
            listOf("The widget is round.", "It has a frame!", "Really?", "Yes."),
            texts(page(block(line("The widget is round. It has a frame! Really? Yes.", 0)))),
        )

    @Test fun figureReferencesDoNotSplit() =
        assertEquals(
            listOf("As shown in FIG. 3 the frame is bent.", "See FIGS. 4 and 5."),
            texts(page(block(line("As shown in FIG. 3 the frame is bent. See FIGS. 4 and 5.", 0)))),
        )

    @Test fun patentCitationsDoNotSplit() =
        assertEquals(
            listOf("See U.S. Pat. No. 5,123,456 to Smith et al. (2001) at col. 3, lines 4-5.", "The end."),
            texts(page(block(line("See U.S. Pat. No. 5,123,456 to Smith et al. (2001) at col. 3, lines 4-5. The end.", 0)))),
        )

    @Test fun latinAbbreviationsAndInitialsDoNotSplit() =
        assertEquals(
            listOf("Metals, e.g. Steel, are used by John A. Smith.", "Next."),
            texts(page(block(line("Metals, e.g. Steel, are used by John A. Smith. Next.", 0)))),
        )

    @Test fun lowercaseAfterPeriodDoesNotSplit() =
        assertEquals(
            listOf("Version 2.0 of the firmware v. 3 is here."),
            texts(page(block(line("Version 2.0 of the firmware v. 3 is here.", 0)))),
        )

    @Test fun hyphenationIsUndoneAcrossLines() {
        val s = SentenceSplitter.split(page(block(line("the inven-", 0), line("tion is new.", 1))))
        assertEquals(listOf("the invention is new."), s.map { it.text })
        assertEquals(2, s[0].quads.size)
        // The hyphen is dropped, so the first line's quad ends after "the inven" (9 chars).
        assertEquals(9 * cw, s[0].quads[0].bounds.x1, 1e-3f)
        assertEquals(0f, s[0].quads[0].bounds.y0, 1e-3f)
        assertEquals(lh, s[0].quads[1].bounds.y0, 1e-3f)
    }

    @Test fun hyphenBeforeCapitalIsKept() =
        assertEquals(
            listOf("a Smith- Jones coupling."),
            texts(page(block(line("a Smith-", 0), line("Jones coupling.", 1)))),
        )

    @Test fun sentenceSpanningLinesHasOneQuadPerLine() {
        val s = SentenceSplitter.split(page(block(line("The widget is round and", 0), line("very heavy. Next one.", 1))))
        assertEquals(listOf("The widget is round and very heavy.", "Next one."), s.map { it.text })
        assertEquals(2, s[0].quads.size)
        assertEquals(1, s[1].quads.size)
        // "very heavy." is 11 chars wide; "Next one." starts after "very heavy. " (12 chars).
        assertEquals(11 * cw, s[0].quads[1].bounds.x1, 1e-3f)
        assertEquals(12 * cw, s[1].quads[0].bounds.x0, 1e-3f)
    }

    @Test fun claimNumberMergesIntoItsClaim() =
        assertEquals(
            listOf("1. A widget comprising a frame.", "2. The widget of claim 1."),
            texts(page(block(line("1. A widget comprising a frame. 2. The widget of claim 1.", 0)))),
        )

    @Test fun sentencesDoNotCrossBlocks() =
        assertEquals(
            listOf("First part", "Second part."),
            texts(page(block(line("First part", 0)), block(line("Second part.", 5)))),
        )

    @Test fun lineNumberGutterIsDropped() =
        assertEquals(
            listOf("Real text."),
            texts(page(block(line("5", 0, 300f), line("10", 1, 300f), line("1045", 2, 300f)), block(line("Real text.", 0)))),
        )

    @Test fun longSentencesAreCutAtClauseBoundaries() {
        val text = (1..40).joinToString(", ") { "part $it" } + "."
        val s = SentenceSplitter.split(page(block(line(text, 0))), maxLen = 60)
        assertTrue(s.size > 3)
        assertTrue(s.all { it.text.length <= 60 })
        assertTrue(s.all { it.text.isNotBlank() && !it.text.startsWith(" ") })
        assertEquals(text.length * cw, s.last().quads.last().bounds.x1, 1e-3f)
        assertEquals(0f, s.first().quads.first().bounds.x0, 1e-3f)
        assertEquals(text.replace(", ", ",").replace(" ", ""), s.joinToString("") { it.text.replace(" ", "") })
    }

    @Test fun indexAtFindsTheSentenceUnderNearOrBelowThePoint() {
        val s = SentenceSplitter.split(page(block(line("First one.", 2), line("Second one.", 4))))
        assertEquals(2, s.size)
        assertEquals(1, SentenceSplitter.indexAt(s, PPoint(10f, 45f)))        // inside line 2
        assertEquals(0, SentenceSplitter.indexAt(s, PPoint(70f, 25f)))        // right of line 1, within 30
        assertEquals(0, SentenceSplitter.indexAt(s, PPoint(10f, 2f)))         // header: first below
        assertEquals(1, SentenceSplitter.indexAt(s, PPoint(10f, 200f)))       // footer: last
        assertEquals(0, SentenceSplitter.indexAt(emptyList(), PPoint(0f, 0f)))
    }

    @Test fun spokenExpandsAbbreviations() {
        assertEquals(
            "Figure 3 shows U.S. patent number 5,000 at column 2, for example the frame.",
            SentenceSplitter.spoken("FIG. 3 shows U.S. Pat. No. 5,000 at col. 2, e.g. the frame."),
        )
        assertEquals("Figures 1 and 2 per Smith et al", SentenceSplitter.spoken("FIGS. 1 and 2 per Smith et al."))
    }

    @Test fun ocrSpacedPunctuationIsHandled() {
        val s = texts(page(block(line("See FIG . 3 for details . The end .", 0))))
        assertEquals(listOf("See FIG . 3 for details .", "The end ."), s)
        assertEquals("See Figure 3 for details.", SentenceSplitter.spoken(s[0]))
        assertEquals("U.S. patent number 10,000,000 (2018).", SentenceSplitter.spoken("U . S . Pat . No . 10 , 000 , 000 ( 2018 ) ."))
    }

    @Test fun figureSheetsAreNotProse() {
        val labels = SentenceSplitter.split(page(block(line("121a", 0), line("AMP", 1), line("IMAGING TELESCOPE", 2), line("FIG . 1B", 3))))
        assertTrue(!SentenceSplitter.looksLikeProse(labels))
        val words = (1..SentenceSplitter.MIN_PROSE_WORDS).joinToString(" ") { "word$it" } + "."
        assertTrue(SentenceSplitter.looksLikeProse(SentenceSplitter.split(page(block(line(words, 0))))))
    }

    @Test fun emptyPageYieldsNothing() {
        assertTrue(SentenceSplitter.split(page()).isEmpty())
        assertTrue(SentenceSplitter.split(page(block(line("   ", 0)))).isEmpty())
    }
}

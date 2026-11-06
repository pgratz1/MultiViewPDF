package com.pgratz.multiviewpdf

import com.pgratz.multiviewpdf.model.BlockClass
import com.pgratz.multiviewpdf.model.FigureKind
import com.pgratz.multiviewpdf.model.Layout
import com.pgratz.multiviewpdf.model.PQuad
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.PageLayout
import com.pgratz.multiviewpdf.model.PageText
import com.pgratz.multiviewpdf.model.ReadingPlans
import com.pgratz.multiviewpdf.model.SentenceSplitter
import com.pgratz.multiviewpdf.model.TextBlock
import com.pgratz.multiviewpdf.model.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Golden tests on the text layer of a real two-column ACM paper (ISCA submission, 13
 * pages), dumped with `mutool draw -F stext` into src/test/resources/layout (see
 * CLAUDE.md for the one-liner). Each line carries its bbox and font size; characters get
 * equal-width quads. The dumps are git-ignored (confidential draft): without them these
 * tests are skipped.
 */
class RealPagesLayoutTest {
    private class Loaded(val text: PageText, val bounds: PRect)

    private fun load(name: String): Loaded {
        // The dumps come from a confidential draft and are not in git; skip (don't fail) without them.
        val stream = javaClass.getResourceAsStream("/layout/$name.txt")
        Assume.assumeNotNull(stream)
        val lines = stream!!.bufferedReader().readLines()
        var bounds = PRect(0f, 0f, 612f, 792f)
        val blocks = ArrayList<TextBlock>()
        var cur = ArrayList<TextLine>()
        fun flush() { if (cur.isNotEmpty()) { blocks += TextBlock(cur); cur = ArrayList() } }
        for (l in lines) {
            when {
                l.startsWith("#page") -> { val (w, h) = l.split(' ').drop(1).map { it.toFloat() }; bounds = PRect(0f, 0f, w, h) }
                l.startsWith("#block") -> flush()
                l.isBlank() -> {}
                else -> {
                    val bar = l.indexOf('|')
                    val (x0, y0, x1, _, size) = l.substring(0, bar).split(' ').map { it.toFloat() }
                    val text = l.substring(bar + 1)
                    if (text.isEmpty()) continue
                    val cw = (x1 - x0) / text.length
                    cur += TextLine(text, text.indices.map { i -> PQuad.of(PRect(x0 + i * cw, y0, x0 + (i + 1) * cw, y0 + size)) })
                }
            }
        }
        flush()
        return Loaded(PageText(blocks), bounds)
    }

    private fun analyze(name: String, page: Int): Pair<PageLayout, PageText> {
        val l = load(name)
        return Layout.analyze(page, l.text, l.bounds) to l.text
    }

    private fun PageLayout.firstLineOf(b: TextBlock) = b.lines.first().text.trim()
    private fun PageLayout.readableTexts() = blocks.filter { it.cls.isReadable }.map { firstLineOf(it.block) }
    private fun PageLayout.classOfBlockStarting(prefix: String) =
        blocks.first { firstLineOf(it.block).startsWith(prefix) }.cls

    @Test fun page3SkipsHeaderSubCaptionsAndFigures() {
        val (l, _) = analyze("isca_p3", 2)
        assertEquals(BlockClass.HEADER_FOOTER, l.classOfBlockStarting("Optimizing Hardware Prefetchers"))
        assertEquals(BlockClass.IN_FIGURE, l.classOfBlockStarting("(a) PRAC-based"))
        assertEquals(BlockClass.IN_FIGURE, l.classOfBlockStarting("(b) Statistical"))
        assertEquals(BlockClass.CAPTION, l.classOfBlockStarting("Figure 2:"))
        assertEquals(BlockClass.CAPTION, l.classOfBlockStarting("Figure 3:"))
        assertEquals(BlockClass.HEADING, l.classOfBlockStarting("2.4"))
        assertEquals(setOf(2, 3), l.captions.map { it.number }.toSet())
        val readable = l.readableTexts()
        assertEquals("PRAC, while PRAC-based mitigations [42, 50] attempt to fully lever-", readable.first())
        assertTrue(readable.none { it.matches(Regex("""\d{1,4}""")) })
        val f3 = l.captions.first { it.number == 3 }
        assertTrue(f3.region.y1 <= f3.bounds.y0 + 0.01f && f3.region.height > 100f)
    }

    @Test fun page3PlanNotesFigure3AfterItsFirstMention() {
        val (l, text) = analyze("isca_p3", 2)
        val sentences = SentenceSplitter.split(text, l::isReadable)
        val keys = ReadingPlans.referencedKeys(sentences)
        assertTrue(keys.any { it.kind == FigureKind.FIGURE && it.number == 3 })
        val first = sentences.indexOfFirst { it.text.contains("Figure 3") }
        assertTrue(first in 1..20) // the parenthetical "(Figure 3)" in the left column, not section 2.4.1
        assertTrue(sentences.none { it.text.startsWith("Figure 2:") || it.text.startsWith("Figure 3:") })
        assertTrue(sentences.none { it.text.contains("ISCA ’27") })
    }

    @Test fun page1KeepsTitleAndAbstractDropsCopyright() {
        val (l, _) = analyze("isca_p1", 0)
        assertEquals(BlockClass.HEADING, l.classOfBlockStarting("Optimizing Hardware Prefetchers"))
        assertEquals(BlockClass.HEADING, l.classOfBlockStarting("Abstract"))
        assertEquals(BlockClass.SMALL_PRINT, l.classOfBlockStarting("ISCA ’27, Atlanta"))
        assertEquals(listOf(1), l.captions.map { it.number })
        assertTrue(l.readableTexts().any { it.startsWith("Hardware prefetchers and Rowhammer") })
    }

    @Test fun page5StackedFigures() {
        val (l, _) = analyze("isca_p5", 4)
        assertEquals(setOf(6, 7, 8), l.captions.map { it.number }.toSet())
        val f7 = l.captions.first { it.number == 7 }
        val f8 = l.captions.first { it.number == 8 }
        assertEquals(f7.bounds.y1, f8.region.y0, 0.01f)
        assertTrue(l.readableTexts().any { it.startsWith("prefetcher. Figure 6 shows") })
    }

    @Test fun page7TablesAreSkippedWithCaptionsBelow() {
        val (l, _) = analyze("isca_p7", 6)
        assertEquals(setOf(FigureKind.TABLE), l.captions.map { it.kind }.toSet())
        assertEquals(setOf(1, 2), l.captions.map { it.number }.toSet())
        val small = l.blocks.filter { it.fontSize < 7.5f && it.lineCount >= 2 && it.cls != BlockClass.HEADER_FOOTER }
        assertTrue(small.isNotEmpty())
        assertTrue("table cells must be inside the table regions", small.all { it.cls == BlockClass.IN_FIGURE })
        val t1 = l.captions.first { it.number == 1 }
        assertTrue(t1.region.y1 <= t1.bounds.y0 + 0.01f) // caption below the table
        assertTrue(l.readableTexts().all { !it.startsWith("L1I") && !it.startsWith("Prefetch Buffer") })
    }

    @Test fun page11FullWidthFiguresAndTwoColumnText() {
        val (l, _) = analyze("isca_p11", 10)
        assertTrue(l.captions.size >= 2)
        val wide = l.captions.filter { it.bounds.width > 400f }
        assertTrue("expected a full-width caption on page 11", wide.isNotEmpty())
        assertTrue(l.readableTexts().isNotEmpty())
        assertTrue(l.blocks.filter { it.cls.isReadable }.all { it.fontSize >= 0.85f * l.bodySize })
    }

    @Test fun referencesPagesAreSilent() {
        val (l, _) = analyze("isca_p12", 11)
        assertTrue(l.captions.isEmpty())
        assertEquals(emptyList<String>(), l.readableTexts())
    }

    @Test fun bodySizeHintSilencesAnAllSmallPrintPage() {
        // Without the "[n]" cue a page of 7 pt text would measure itself as body text.
        val loaded = load("isca_p12")
        val stripped = PageText(loaded.text.blocks.map { b ->
            TextBlock(b.lines.map { l -> if (l.text.startsWith("[")) TextLine("  " + l.text.drop(2), l.quads) else l })
        })
        val without = Layout.analyze(11, stripped, loaded.bounds)
        assertTrue(without.readableTexts().isNotEmpty())
        val with = Layout.analyze(11, stripped, loaded.bounds, bodySizeHint = 9f)
        assertEquals(emptyList<String>(), with.readableTexts())
    }
}

package com.pgratz.multiviewpdf

import com.pgratz.multiviewpdf.model.BlockClass
import com.pgratz.multiviewpdf.model.FigureKind
import com.pgratz.multiviewpdf.model.Layout
import com.pgratz.multiviewpdf.model.PQuad
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.PageLayout
import com.pgratz.multiviewpdf.model.PageText
import com.pgratz.multiviewpdf.model.TextBlock
import com.pgratz.multiviewpdf.model.TextLine
import com.pgratz.multiviewpdf.model.center
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures mirror pages of the ISCA sample paper (612×792 pt, 9 pt body, columns
 * x 53–296 and 318–560) with the block positions measured by mutool.
 */
class LayoutTest {
    private val page = PRect(0f, 0f, 612f, 792f)

    /** A block at ([x0],[y0]) whose chars are [size] tall and 0.45·size wide, lines 1.2·size apart. */
    private fun blk(x0: Float, y0: Float, size: Float, lines: List<String>): TextBlock {
        val cw = size * 0.45f
        val pitch = size * 1.2f
        return TextBlock(lines.mapIndexed { k, text ->
            val y = y0 + k * pitch
            TextLine(text, text.indices.map { i -> PQuad.of(PRect(x0 + i * cw, y, x0 + (i + 1) * cw, y + size)) })
        })
    }

    private fun body(x0: Float, y0: Float, n: Int, size: Float = 9f) =
        blk(x0, y0, size, List(n) { "prefetchers open rows that the mitigation then closes early" })

    private fun PageLayout.cls(b: TextBlock) = classOf(b)
    private fun PageLayout.readable() = blocks.filter { it.cls.isReadable }.map { it.block }

    // ---- page 3: figures 2 (with sub-captions) and 3 (no text layer at all)

    private val header = blk(54f, 62f, 7f, listOf("Optimizing Hardware Prefetchers for Rowhammer-mitigated Memory ISCA 27"))
    private val gutter3 = blk(28f, 89f, 6f, listOf("233"))
    private val gutter4 = blk(28f, 100f, 6f, listOf("1045"))
    private val subA = blk(57f, 176f, 8f, listOf("(a) PRAC-based", "mitigation scheme with a thresh-", "old of 512."))
    private val subB = blk(175f, 176f, 8f, listOf("(b) Statistical-tracking-based", "mitigation scheme with a thresh-", "old of 16."))
    private val cap2 = blk(54f, 218f, 9f, listOf("Figure 2: Statistical-tracking-based and PRAC-based archi-", "tectures."))
    private val bodyL = body(53f, 296f, 38)
    private val cap3 = blk(318f, 230f, 9f, listOf("Figure 3: Geomean increase in activations per instruction", "(API) when adding prefetchers to a system with Rowhammer", "mitigations."))
    private val heading = blk(318f, 284f, 10.9f, listOf("2.4 Hardware Prefetching"))
    private val bodyR1 = body(318f, 299f, 19)
    private val bodyR2 = body(318f, 515f, 19)
    private val page3 = PageText(listOf(gutter3, gutter4, header, subA, subB, cap2, bodyL, cap3, heading, bodyR1, bodyR2))

    @Test fun page3Classification() {
        val l = Layout.analyze(3, page3, page)
        assertEquals(9f, l.bodySize, 0.01f)
        assertEquals(BlockClass.HEADER_FOOTER, l.cls(header))
        assertEquals(BlockClass.NUMERIC, l.cls(gutter3))
        assertEquals(BlockClass.NUMERIC, l.cls(gutter4))
        assertEquals(BlockClass.IN_FIGURE, l.cls(subA))
        assertEquals(BlockClass.IN_FIGURE, l.cls(subB))
        assertEquals(BlockClass.CAPTION, l.cls(cap2))
        assertEquals(BlockClass.CAPTION, l.cls(cap3))
        assertEquals(BlockClass.HEADING, l.cls(heading))
        assertEquals(BlockClass.BODY, l.cls(bodyL))
        assertEquals(listOf(bodyL, heading, bodyR1, bodyR2), l.readable())
    }

    @Test fun page3CaptionsAndRegions() {
        val l = Layout.analyze(3, page3, page)
        assertEquals(2, l.captions.size)
        val f2 = l.captions.first { it.number == 2 }
        assertEquals(FigureKind.FIGURE, f2.kind)
        assertEquals("Figure 2: Statistical-tracking-based and PRAC-based architectures.", f2.text)
        assertEquals(listOf("(a) PRAC-based mitigation scheme with a threshold of 512.", "(b) Statistical-tracking-based mitigation scheme with a threshold of 16."), f2.subCaptions)
        // Region: left column, from the header band down to the caption top.
        assertEquals(218f, f2.region.y1, 0.01f)
        assertEquals(792f * Layout.HEADER_BAND, f2.region.y0, 0.01f)
        assertTrue(f2.region.x0 <= 54f && f2.region.x1 >= 280f && f2.region.x1 < 318f)
        assertTrue(f2.extent.contains(subA.bounds.center) && f2.extent.y1 > 230f)

        val f3 = l.captions.first { it.number == 3 }
        assertEquals(230f, f3.region.y1, 0.01f)
        assertEquals(792f * Layout.HEADER_BAND, f3.region.y0, 0.01f)
        assertTrue(f3.region.x0 >= 300f && f3.region.x0 <= 318f && f3.region.x1 >= 540f)
        assertTrue(f3.subCaptions.isEmpty())
    }

    // ---- page 1: title, submission line, copyright block, Figure 1 bounded by the 12 pt line

    @Test fun page1HeadingsCopyrightAndFigure1() {
        val title = blk(66f, 84f, 17.2f, listOf("Optimizing Hardware Prefetchers for Rowhammer-mitigated", "Memory"))
        val submission = blk(137f, 131f, 12f, listOf("ISCA 2027 Submission #8 - Confidential Draft - Do NOT Distribute!!"))
        val abstractH = blk(54f, 149f, 10.9f, listOf("Abstract"))
        val abstractB = body(53f, 165f, 20)
        val copyright = blk(54f, 686f, 7f, listOf("ISCA '27, Atlanta, Georgia, USA", "2027. ACM ISBN 978-x-xxxx-xxxx-x/YY/MM", "https://doi.org/XXXXXXX.XXXXXXX"))
        val cap1 = blk(318f, 327f, 9f, listOf("Figure 1: Impact of Rowhammer mitigations on prefetcher", "speedup and DRAM activations."))
        val bodyR = body(318f, 378f, 24)
        val l = Layout.analyze(1, PageText(listOf(title, submission, abstractH, abstractB, copyright, cap1, bodyR)), page)
        assertEquals(BlockClass.HEADING, l.cls(title))
        assertEquals(BlockClass.HEADING, l.cls(submission))
        assertEquals(BlockClass.LABEL, l.cls(abstractH)) // a bare heading: read, but not a figure boundary
        assertEquals(BlockClass.SMALL_PRINT, l.cls(copyright))
        assertEquals(listOf(title, submission, abstractH, abstractB, bodyR), l.readable())
        val f1 = l.captions.single()
        assertEquals(submission.bounds.y1, f1.region.y0, 0.01f)
        assertEquals(327f, f1.region.y1, 0.01f)
    }

    // ---- page 5: two captions in the right column

    @Test fun page5StackedCaptions() {
        val cap6 = blk(54f, 228f, 9f, listOf("Figure 6: ORAP alongside a main prefetcher in the L2C or LLC.", "The two share the cache.", "Line three.", "Line four.", "Line five.", "Line six."))
        val bodyL1 = body(53f, 315f, 23)
        val h32 = blk(54f, 576f, 10.9f, listOf("3.2 Avoiding DRAM Activations"))
        val bodyL2 = body(54f, 591f, 11)
        val cap7 = blk(318f, 202f, 9f, listOf("Figure 7: Occurrences of stride access patterns from LLC", "misses in SPEC2017 workloads."))
        val cap8 = blk(318f, 429f, 9f, List(7) { "Figure 8: Next-Column example. CPU 0's miss to A0 opens a".takeIf { _ -> it == 0 } ?: "row; confidence permits a prefetch of the next column." })
        val bodyR = body(318f, 537f, 16)
        val l = Layout.analyze(5, PageText(listOf(cap6, bodyL1, h32, bodyL2, cap7, cap8, bodyR)), page)
        val f7 = l.captions.first { it.number == 7 }
        val f8 = l.captions.first { it.number == 8 }
        assertEquals(792f * Layout.HEADER_BAND, f7.region.y0, 0.01f)
        assertEquals(202f, f7.region.y1, 0.01f)
        assertEquals(cap7.bounds.y1, f8.region.y0, 0.01f) // stops at Figure 7's caption
        assertEquals(429f, f8.region.y1, 0.01f)
        assertEquals(listOf(bodyL1, h32, bodyL2, bodyR), l.readable())
    }

    // ---- page 7: table whose caption sits BELOW it

    @Test fun page7TableCaptionBelowTable() {
        val cells = blk(55f, 98f, 6.7f, List(16) { "L1I 32 KiB, 8-way, 4-cyc, 16 MSHRs Channels 2 (1c), 4 (8c)" })
        val capT1 = blk(54f, 231f, 9f, listOf("Table 1: System configuration."))
        val bodyL = body(53f, 296f, 37)
        val bodyR = body(318f, 300f, 36)
        val l = Layout.analyze(7, PageText(listOf(cells, capT1, bodyL, bodyR)), page)
        val t1 = l.captions.single()
        assertEquals(FigureKind.TABLE, t1.kind)
        assertEquals(1, t1.number)
        assertEquals(BlockClass.IN_FIGURE, l.cls(cells))
        assertEquals(231f, t1.region.y1, 0.01f) // chosen above on evidence, despite being a table
        assertTrue(t1.region.contains(cells.bounds.center))
        assertEquals(listOf(bodyL, bodyR), l.readable())
    }

    @Test fun tableCaptionAboveTableIsChosenBelow() {
        val capT2 = blk(318f, 90f, 9f, listOf("Table 2: Per-core state overhead of ORAP."))
        val cells = blk(320f, 110f, 6.3f, List(12) { "Prefetch Buffer 4 queues, 16 entries each 45 bits/entry" })
        val bodyR = body(318f, 260f, 30)
        val bodyL = body(53f, 90f, 40)
        val l = Layout.analyze(7, PageText(listOf(bodyL, capT2, cells, bodyR)), page)
        val t2 = l.captions.single()
        assertEquals(capT2.bounds.y1, t2.region.y0, 0.01f)
        assertEquals(260f, t2.region.y1, 0.01f)
        assertEquals(BlockClass.IN_FIGURE, l.cls(cells))
    }

    // ---- footnotes vs. boilerplate and bibliography

    @Test fun contentFootnotesAreReadButBoilerplateIsNot() {
        val bodyL = body(53f, 100f, 40)
        val footnote = blk(53f, 690f, 7f, listOf("1 This holds for every configuration we evaluated in this paper,", "because the prefetcher is disabled during the warm-up phase."))
        val refs = blk(318f, 89f, 7f, listOf("[12] Author Name. 2020. A Paper Title. In Proceedings of", "Something Important. doi:10.1145/1234567"))
        val pageNo = blk(304f, 715f, 9f, listOf("7"))
        val l = Layout.analyze(9, PageText(listOf(bodyL, footnote, refs, pageNo)), page)
        assertEquals(BlockClass.FOOTNOTE, l.cls(footnote))
        assertEquals(BlockClass.SMALL_PRINT, l.cls(refs))
        assertEquals(BlockClass.NUMERIC, l.cls(pageNo))
        assertEquals(listOf(bodyL, footnote), l.readable())
    }

    // ---- caption split across two blocks

    @Test fun splitCaptionMergesButBodyDoesNot() {
        val capA = blk(54f, 218f, 9f, listOf("Figure 2: Statistical-tracking-based and PRAC-based archi-"))
        val capB = blk(54f, 229f, 9f, listOf("tectures."))
        val bodyL = body(53f, 262f, 30)
        val bodyR = body(318f, 90f, 40)
        val l = Layout.analyze(3, PageText(listOf(capA, capB, bodyL, bodyR)), page)
        val f2 = l.captions.single()
        assertEquals("Figure 2: Statistical-tracking-based and PRAC-based architectures.", f2.text)
        assertEquals(BlockClass.CAPTION, l.cls(capB))
        assertEquals(BlockClass.BODY, l.cls(bodyL))
        assertEquals(capB.bounds.y1, f2.bounds.y1, 0.01f)
        assertEquals(listOf(bodyL, bodyR), l.readable())
    }

    // ---- IEEE conventions (MICRO / ISCA proceedings): "Figure 6." alone on a line, "Table I",
    // diagram labels at large font sizes, algorithm listings, a rotated side banner.

    @Test fun ieeeDiagramLabelsAreNotHeadings() {
        // Page 7 of the Page Size paper: a block diagram with 9–18 pt labels above a full-width caption.
        val l2c = blk(403f, 82f, 11.9f, listOf("L2C"))
        val pref = blk(97f, 108f, 11.6f, listOf("L2C Prefetcher", "L1D"))
        val bits = blk(171f, 76f, 8.9f, listOf("page size", "bit", "L1D MSHR"))
        val big = blk(293f, 103f, 17.9f, listOf(".", "Pref-PSA"))
        val stop = blk(401f, 125f, 7.1f, listOf("Stop @ 4KB Boundaries"))
        val cap6 = blk(224f, 167f, 8f, listOf("Figure 6.", "Page-size Propagation Module (PPM)."))
        val bodyL = body(54f, 200f, 21, 10f)
        val heading = blk(54f, 559f, 10f, listOf("D. Putting Everything Together"))
        val bodyL2 = body(54f, 579f, 12, 10f)
        val bodyR = body(315f, 200f, 16, 10f)
        val l = Layout.analyze(6, PageText(listOf(l2c, pref, bits, big, stop, cap6, bodyL, heading, bodyL2, bodyR)), page)
        for (b in listOf(l2c, pref, bits, big, stop)) assertEquals(b.lines[0].text, BlockClass.IN_FIGURE, l.cls(b))
        val f6 = l.captions.single()
        assertEquals(6, f6.number)
        assertEquals("Figure 6. Page-size Propagation Module (PPM).", f6.text)
        assertEquals(792f * Layout.HEADER_BAND, f6.region.y0, 0.01f)
        assertEquals(167f, f6.region.y1, 0.01f)
        assertEquals(BlockClass.HEADING, l.cls(heading))
        assertEquals(listOf(bodyL, heading, bodyL2, bodyR), l.readable())
    }

    @Test fun ieeeRomanTableCaptionBelowRows() {
        val rows = (0 until 9).map { k -> blk(315f, 75f + k * 17f, 8f, listOf("L1 DCache", "48KB, 12-way, 5-cycle, 16-entry MSHR")) }
        val capT = blk(392f, 265f, 8f, listOf("Table I", "SYSTEM CONFIGURATION."))
        val bodyR = body(315f, 319f, 21, 10f)
        val method = blk(395f, 581f, 9f, listOf("V. METHODOLOGY"))
        val bodyL = body(54f, 77f, 50, 10f)
        val l = Layout.analyze(8, PageText(listOf(bodyL) + rows + listOf(capT, bodyR, method)), page)
        val t1 = l.captions.single()
        assertEquals(FigureKind.TABLE, t1.kind)
        assertEquals(1, t1.number)
        assertEquals("Table I SYSTEM CONFIGURATION.", t1.text)
        assertTrue(rows.all { l.cls(it) == BlockClass.IN_FIGURE })
        assertEquals(265f, t1.region.y1, 0.01f)
        assertEquals(BlockClass.HEADING, l.cls(method))
        assertEquals(listOf(bodyL, bodyR, method), l.readable())
    }

    @Test fun algorithmListingIsOneBlockBelowItsTitle() {
        val title = blk(65f, 76f, 9.4f, listOf("Algorithm 1: Setup Memory Access List For Each Set"))
        val input = blk(69f, 89f, 9.4f, listOf("Input: mem: list of (address, timestamp) pairs of", "length x; w, y: number of cache sets and ways"))
        val lines = (0 until 12).map { k -> blk(62f, 122f + k * 12f, 9.4f, listOf("${k + 1}", "if mem[i] belongs to cache set s then")) }
        val para = body(60f, 300f, 11, 9.4f)
        val bodyR = body(314f, 76f, 50, 9.4f)
        val l = Layout.analyze(5, PageText(listOf(title, input) + lines + listOf(para, bodyR)), page)
        val a1 = l.captions.single()
        assertEquals(FigureKind.ALGORITHM, a1.kind)
        assertEquals(1, a1.number)
        assertEquals(title.bounds.y1, a1.region.y0, 0.01f)
        assertEquals(300f, a1.region.y1, 0.01f)
        assertEquals(BlockClass.IN_FIGURE, l.cls(input))
        assertTrue(lines.all { l.cls(it) == BlockClass.IN_FIGURE })
        assertEquals(listOf(para, bodyR), l.readable())
    }

    @Test fun rotatedSideBannerAndShortHeadingsOutsideFigures() {
        val bannerText = "2026 ACM/IEEE 53rd Annual International Symposium on Computer Architecture (ISCA)"
        val banner = TextBlock(listOf(TextLine(bannerText, bannerText.indices.map { i -> PQuad.of(PRect(11f, 174f + i * 7f, 18f, 181f + i * 7f)) })))
        val abstractH = blk(54f, 149f, 10.9f, listOf("Abstract"))
        val bodyL = body(53f, 165f, 40, 9.4f)
        val bodyR = body(314f, 100f, 50, 9.4f)
        val l = Layout.analyze(0, PageText(listOf(banner, abstractH, bodyL, bodyR)), page)
        assertEquals(BlockClass.SMALL_PRINT, l.cls(banner))
        assertEquals(BlockClass.LABEL, l.cls(abstractH))
        assertEquals(listOf(abstractH, bodyL, bodyR), l.readable())
    }

    @Test fun paragraphStartingWithFigureNumberIsNotACaption() {
        val para = blk(53f, 100f, 9f, listOf("Figure 4. We further break down their speedup across workloads and", "find that the gains concentrate in the memory-bound half of the suite,", "which is where the mitigation overhead is largest.", "More lines follow here.", "And here."))
        val bodyR = body(318f, 100f, 40)
        val l = Layout.analyze(0, PageText(listOf(para, bodyR)), page)
        assertTrue(l.captions.isEmpty())
        assertEquals(BlockClass.BODY, l.cls(para))
    }

    @Test fun listingStepsAreNotHeadings() {
        val step = blk(62f, 133f, 9.4f, listOf("2", "Acc = <empty list>;"))
        val step2 = blk(60f, 446f, 9.4f, listOf("21", "Find l in set with the largest timestamp;"))
        val heading = blk(54f, 498f, 10.9f, listOf("1", "Introduction"))
        val heading2 = blk(318f, 284f, 10.9f, listOf("2.4", "Hardware Prefetching"))
        val bodyL = body(53f, 520f, 20)
        val bodyR = body(318f, 300f, 30)
        val l = Layout.analyze(0, PageText(listOf(step, step2, heading, heading2, bodyL, bodyR)), page)
        assertEquals(BlockClass.HEADING, l.cls(heading))
        assertEquals(BlockClass.HEADING, l.cls(heading2))
        assertTrue(l.cls(step) != BlockClass.HEADING && l.cls(step2) != BlockClass.HEADING)
    }

    @Test fun pageWithoutCaptionsReadsEverythingButNumbers() {
        val l = Layout.analyze(0, PageText(listOf(body(53f, 90f, 40), blk(28f, 89f, 6f, listOf("12")))), page)
        assertTrue(l.captions.isEmpty())
        assertEquals(1, l.readable().size)
    }

    // ---- MICRO page 2 as the phone saw it: chart labels measured at body size from their glyph boxes

    @Test fun oneWordChartLabelLinesDoNotBoundTheFigureRegion() {
        val labels = blk(325f, 75f, 9.8f, listOf("SPP", "VLDP", "PPF", "BOP") + List(13) { "0.%02d".format(it * 5) })
        val cap = blk(318f, 184f, 8f, listOf("Figure 2. Probability distribution of discarded prefetches for", "four spatial cache prefetchers and 80 applications."))
        val bodyR = body(318f, 244f, 30)
        val l = Layout.analyze(2, PageText(listOf(body(53f, 77f, 40), labels, cap, bodyR)), page)
        val f2 = l.captions.single()
        assertEquals(BlockClass.IN_FIGURE, l.cls(labels))
        assertTrue("region $f2", f2.region.y1 <= f2.bounds.y0 + 0.01f && f2.region.height > 90f)
    }

    @Test fun lineSizesBeatGlyphHeightsWhenPresent() {
        // Same block, but the text layer says the labels are really 5.8 pt: small print, not body.
        val tall = blk(325f, 75f, 9.8f, listOf("SPP", "VLDP", "PPF", "BOP"))
        val sized = TextBlock(tall.lines.map { TextLine(it.text, it.quads, 5.8f) })
        val l = Layout.analyze(2, PageText(listOf(body(53f, 77f, 40), sized, body(318f, 244f, 30))), page)
        assertEquals(BlockClass.SMALL_PRINT, l.cls(sized))
    }
}

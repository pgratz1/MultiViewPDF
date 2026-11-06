package com.pgratz.multiviewpdf.model

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class BlockClass {
    BODY, HEADING, CAPTION, SUB_CAPTION, FOOTNOTE, SMALL_PRINT, HEADER_FOOTER, NUMERIC, IN_FIGURE, EMPTY,
    /** A few words at any size: a diagram label inside a figure, or a bare heading ("Abstract") outside one. */
    LABEL;

    /** Read aloud in place. Labels and sub-captions only when they sit outside every figure region. */
    val isReadable: Boolean get() = this == BODY || this == HEADING || this == FOOTNOTE || this == SUB_CAPTION || this == LABEL
}

enum class FigureKind(val label: String) { FIGURE("Figure"), TABLE("Table"), ALGORITHM("Algorithm") }

/** "IV" → 4; null when [s] is not a well-formed roman numeral. */
fun romanToInt(s: String): Int? {
    if (s.isEmpty() || !Regex("""M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})""").matches(s)) return null
    val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
    var total = 0
    for (i in s.indices) {
        val a = v[s[i]] ?: return null
        val b = if (i + 1 < s.length) v[s[i + 1]] ?: return null else 0
        total += if (a < b) -a else a
    }
    return total
}

/** Parses the number of a caption or reference: digits ("3", "2b" → 2) or a roman numeral ("IV"). */
fun figureNumber(token: String): Int? = token.trimEnd { it.isLowerCase() }.toIntOrNull() ?: romanToInt(token)

data class ClassifiedBlock(
    val block: TextBlock,
    val bounds: PRect,
    val fontSize: Float,
    val lineCount: Int,
    val cls: BlockClass,
)

/** A figure or table caption and the graphic it belongs to. */
data class Caption(
    val kind: FigureKind,
    val number: Int,
    /** Caption lines joined (hyphenation undone), e.g. "Figure 3: Geomean increase ...". */
    val text: String,
    /** "(a) ...", "(b) ..." blocks found inside the region, left to right. */
    val subCaptions: List<String>,
    /** The caption block(s). */
    val bounds: PRect,
    /** The graphic area, excluding the caption. */
    val region: PRect,
) {
    /** Graphic plus caption: what to render for a description and to highlight. */
    val extent: PRect get() = region.union(bounds)
}

/** Result of [Layout.analyze]: which blocks to read, and where the figures are. */
class PageLayout(
    val page: Int,
    val pageBounds: PRect,
    val bodySize: Float,
    val blocks: List<ClassifiedBlock>,
    val captions: List<Caption>,
) {
    private val readable: MutableSet<TextBlock> = Collections.newSetFromMap(IdentityHashMap())
    private val classes: MutableMap<TextBlock, BlockClass> = IdentityHashMap()

    /** Characters in BODY blocks: how much running text this page really has. */
    val bodyChars: Int

    init {
        var n = 0
        for (b in blocks) {
            classes[b.block] = b.cls
            if (b.cls.isReadable) readable += b.block
            if (b.cls == BlockClass.BODY) n += b.block.lines.sumOf { it.text.length }
        }
        bodyChars = n
    }

    /** Identity-based: pass the same [TextBlock] instances that were analysed. */
    fun isReadable(b: TextBlock): Boolean = b in readable

    fun classOf(b: TextBlock): BlockClass? = classes[b]
}

/**
 * Page layout analysis for read-aloud, from the text layer alone: running headers and
 * footers, small print, figure/table captions and the graphic each caption belongs to.
 * Tuned on two-column ACM papers (see LayoutTest for the measured fixtures).
 */
object Layout {
    const val HEADER_BAND = 0.09f
    const val FOOTER_BAND = 0.90f
    const val SMALL_PRINT_RATIO = 0.85f
    const val HEADING_RATIO = 1.1f
    const val MIN_REGION_HEIGHT = 15f
    const val TALL_REGION_HEIGHT = 40f

    /**
     * "Figure 3:", "Fig. 3.", "Table 1 –", "FIG. 2b:", "TABLE I", "Algorithm 1:" at the start
     * of a block. IEEE puts "Figure 2." or "TABLE I" alone on the first line, so the end of
     * the line counts as a separator too.
     */
    val CAPTION = Regex("""^(Figure|Fig\.|FIGURE|FIG\.|Table|TABLE|Algorithm|ALGORITHM|Listing)\s*(\d{1,3}[a-z]?|[IVXLC]{1,7})\s*([:.|–—\-]|$)""")

    /** The caption punctuation prose never uses; keeps caption-like sentences out of the reader. */
    val CAPTION_STRICT = Regex("""^(Figure|Fig\.|FIGURE|FIG\.|Table|TABLE|Algorithm|ALGORITHM|Listing)\s*(\d{1,3}[a-z]?|[IVXLC]{1,7})\s*[:|]""")

    private val NUMERIC = Regex("""[\d.,%−–\-\s]+""")
    private val SUB_CAPTION = Regex("""^\([a-z]\)\s""")
    private val FOOTNOTE_MARK = Regex("""^(\d{1,2}|[*†‡§¶])\s*\S""")
    private val BIB_ENTRY = Regex("""^\[\d{1,3}\]\s""")
    /** "2.4 Hardware Prefetching", "IV. DESIGN", "A. Methodology", "1) Design of …" (number, then the title). */
    private val NUMBERED_HEADING = Regex("""^(\d+(\.\d+)*|[IVXLC]{1,6}|[A-Z])[.)]?\s+([A-Z(].*)$""")
    private val CODE_CHARS = Regex("""[=;<>{}\[\]/\\|]""")
    private val LISTING_START = Regex("""^(\d{1,3}(\s|$)|(Input|Output|Require|Ensure|Data|Result)\s*:)""")

    /**
     * A section heading, not a numbered listing step ("2 Acc = <empty list>;"): the title has
     * no code punctuation and its longer words are mostly capitalised.
     */
    private fun isNumberedHeading(text: String): Boolean {
        val m = NUMBERED_HEADING.find(text) ?: return false
        val title = m.groupValues[3]
        if (CODE_CHARS.containsMatchIn(title)) return false
        val long = title.split(Regex("""\s+""")).filter { it.length >= 4 && it[0].isLetter() }
        if (long.isEmpty()) return true
        return long.count { it[0].isUpperCase() } >= 0.6f * long.size
    }
    /** Words in a block before it counts as a paragraph when it has fewer than three lines. */
    private const val PARAGRAPH_WORDS = 25
    /** Words in a one- or two-line block before it still bounds a figure region (a short paragraph). */
    private const val PROSE_FRAGMENT_WORDS = 12
    private const val LABEL_WORDS = 5
    private val BOILERPLATE = Regex(
        """ACM ISBN|\bdoi[:.]|doi\.org|©|Permission to make|licensed under|Copyright|^\s*\[\d+\]""",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
    )

    /**
     * [bodySizeHint] is the document's body font size seen on earlier pages; it takes over
     * when this page has no body text of its own to measure (a page of references).
     */
    fun analyze(
        page: Int,
        text: PageText,
        pageBounds: PRect,
        images: List<PRect> = emptyList(),
        bodySizeHint: Float? = null,
    ): PageLayout {
        val h = pageBounds.height
        val headerBottom = pageBounds.y0 + HEADER_BAND * h
        val footerTop = pageBounds.y0 + FOOTER_BAND * h
        val ms = text.blocks.map { Measured(it) }
        val estimate = bodySize(ms)
        val body = if (bodySizeHint != null && estimate < SMALL_PRINT_RATIO * bodySizeHint) bodySizeHint else estimate
        for (m in ms) m.cls = classify(m, body, pageBounds, headerBottom, footerTop)

        val caps = mergeCaptions(ms, body)
        val columns = columns(ms)
        for (cap in caps) {
            cap.column = columnFor(cap.bounds, columns)
            cap.region = region(cap, ms, images, headerBottom, footerTop)
        }
        // Everything that isn't a paragraph, heading or caption and sits inside a figure belongs to it.
        for (m in ms) {
            if (m.cls == BlockClass.CAPTION || m.cls == BlockClass.EMPTY || m.cls == BlockClass.HEADING) continue
            if (m.cls == BlockClass.BODY && m.isParagraph) continue
            val c = m.bounds.center
            val owner = caps.firstOrNull { it.region.contains(c) } ?: continue
            if (m.cls == BlockClass.SUB_CAPTION) owner.subCaptions += m
            m.cls = BlockClass.IN_FIGURE
        }
        val captions = caps.sortedWith(compareBy({ it.bounds.y0 }, { it.bounds.x0 })).map { cap ->
            Caption(
                kind = cap.kind,
                number = cap.number,
                text = joinLines(cap.parts.flatMap { it.lines }),
                subCaptions = cap.subCaptions.sortedWith(compareBy({ it.bounds.x0 }, { it.bounds.y0 })).map { joinLines(it.lines) },
                bounds = cap.bounds,
                region = cap.region,
            )
        }
        return PageLayout(
            page, pageBounds, body,
            ms.map { ClassifiedBlock(it.block, it.bounds, it.size, it.lines.size, it.cls) },
            captions,
        )
    }

    // ------------------------------------------------------------------ internals

    private class Measured(val block: TextBlock) {
        val lines: List<String> = block.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        val bounds: PRect = block.bounds
        val chars: Int = lines.sumOf { it.length }
        val size: Float = run {
            val heights = block.lines.flatMap { l -> l.quads.map { it.bounds.height } }.filter { it > 0f }.sorted()
            if (heights.isEmpty()) 0f else heights[heights.size / 2]
        }
        val words: Int = lines.sumOf { it.split(Regex("\\s+")).size }
        val wordsPerLine: Float = if (lines.isEmpty()) 0f else words.toFloat() / lines.size
        /** Running text: three or more lines, or a long one- or two-liner. */
        val isParagraph: Boolean get() = lines.size >= 3 || words >= PARAGRAPH_WORDS
        /** A one- or two-line sentence (bounds a figure region; a listing line doesn't). */
        val isProseFragment: Boolean get() = lines.size <= 2 && words >= PROSE_FRAGMENT_WORDS
        /** A pseudo-code line: step number, Input/Output, trailing ';' or code punctuation. */
        val isListingLine: Boolean get() {
            val first = lines.first()
            return LISTING_START.containsMatchIn(first) || text.trimEnd().endsWith(";") || CODE_CHARS.containsMatchIn(text)
        }
        /** Single rotated line (a side banner, a y-axis label). */
        val isRotated: Boolean get() = lines.size == 1 && bounds.height > 4f * bounds.width && lines[0].length > 3
        var cls: BlockClass = BlockClass.BODY
        val text: String get() = lines.joinToString("\n")
    }

    private class Cap(val primary: Measured, val kind: FigureKind, val number: Int) {
        val parts = mutableListOf(primary)
        val subCaptions = mutableListOf<Measured>()
        val bounds: PRect get() = parts.map { it.bounds }.reduce { a, b -> a.union(b) }
        var column: PRect = primary.bounds
        var region: PRect = PRect(0f, 0f, 0f, 0f)
    }

    /** Character-weighted median font size of multi-line blocks (the running text). */
    private fun bodySize(ms: List<Measured>): Float {
        var pool = ms.filter { it.lines.size >= 3 && it.size > 0f }
        if (pool.isEmpty()) pool = ms.filter { it.size > 0f }
        if (pool.isEmpty()) return 10f
        val sorted = pool.sortedBy { it.size }
        val half = sorted.sumOf { it.chars } / 2
        var acc = 0
        for (m in sorted) {
            acc += m.chars
            if (acc >= half) return m.size
        }
        return sorted.last().size
    }

    private fun classify(m: Measured, body: Float, pageBounds: PRect, headerBottom: Float, footerTop: Float): BlockClass {
        if (m.lines.isEmpty() || m.size <= 0f) return BlockClass.EMPTY
        if (m.lines.all { NUMERIC.matches(it) }) return BlockClass.NUMERIC
        if (m.isRotated) return BlockClass.SMALL_PRINT
        val cap = CAPTION.find(m.lines.first())
        if (cap != null && figureNumber(cap.groupValues[2]) != null) {
            // "Figure 2. We further…" can open a paragraph; real captions are at most body size,
            // or short. The ':' / '|' forms are never prose.
            val strict = CAPTION_STRICT.containsMatchIn(m.lines.first())
            if (strict || m.size < 0.97f * body || m.lines.size <= 4) return BlockClass.CAPTION
        }
        val bib = m.lines.count { BIB_ENTRY.containsMatchIn(it) }
        if (bib >= 2 || (bib >= 1 && m.lines.size <= 3)) return BlockClass.SMALL_PRINT // bibliography
        val fewLines = m.lines.size <= 2
        if (fewLines && m.size < 1.3f * body && (m.bounds.y1 <= headerBottom || m.bounds.y0 >= footerTop)) return BlockClass.HEADER_FOOTER
        // "2.4" may sit alone on the first line with the title on the second.
        if (m.lines.size <= 3 && m.words <= 12 && m.size >= SMALL_PRINT_RATIO * body &&
            isNumberedHeading(m.lines.joinToString(" "))
        ) return BlockClass.HEADING
        if (m.lines.size <= 3 && m.size >= HEADING_RATIO * body && m.words >= 6) return BlockClass.HEADING // a title
        if (m.size < SMALL_PRINT_RATIO * body) {
            val lowerHalf = m.bounds.y0 >= pageBounds.y0 + pageBounds.height / 2
            val prose = m.wordsPerLine >= 5f && (m.lines.size >= 2 || FOOTNOTE_MARK.containsMatchIn(m.lines.first()))
            return if (lowerHalf && prose && !BOILERPLATE.containsMatchIn(m.text)) BlockClass.FOOTNOTE else BlockClass.SMALL_PRINT
        }
        if (m.lines.size <= 4 && SUB_CAPTION.containsMatchIn(m.lines.first())) return BlockClass.SUB_CAPTION
        if (m.lines.size <= 3 && m.words <= LABEL_WORDS) return BlockClass.LABEL
        return BlockClass.BODY
    }

    /** Captions split across blocks: absorb the block that continues right below at the same size. */
    private fun mergeCaptions(ms: List<Measured>, body: Float): List<Cap> {
        val caps = ms.filter { it.cls == BlockClass.CAPTION }.mapNotNull { m ->
            val match = CAPTION.find(m.lines.first()) ?: return@mapNotNull null
            val number = figureNumber(match.groupValues[2]) ?: return@mapNotNull null
            val kind = when (match.groupValues[1].first().uppercaseChar()) {
                'T' -> FigureKind.TABLE
                'A', 'L' -> FigureKind.ALGORITHM
                else -> FigureKind.FIGURE
            }
            Cap(m, kind, number)
        }
        val taken: MutableSet<Measured> = Collections.newSetFromMap(IdentityHashMap())
        caps.forEach { taken += it.primary }
        val byTop = ms.sortedBy { it.bounds.y0 }
        for (cap in caps) {
            if (cap.kind == FigureKind.ALGORITHM) continue // a one-line title; the listing below is not caption text
            var last = cap.primary
            while (true) {
                // Only an unfinished caption ("…archi-", "…and") continues in the next block.
                val tail = last.lines.last().trimEnd()
                if (tail.isEmpty() || tail.last() in ".!?)]\"”") break
                val pitch = last.size * 1.3f
                val next = byTop.firstOrNull { n ->
                    n !in taken && n.cls != BlockClass.CAPTION && n.cls != BlockClass.HEADING && n.cls != BlockClass.NUMERIC &&
                        n.cls != BlockClass.EMPTY && n.cls != BlockClass.HEADER_FOOTER &&
                        xOverlapRatio(n.bounds, cap.bounds) >= 0.8f &&
                        n.bounds.y0 >= last.bounds.y1 - 2f && n.bounds.y0 - last.bounds.y1 <= 1.2f * pitch &&
                        abs(n.size - last.size) <= 0.5f
                } ?: break
                next.cls = BlockClass.CAPTION
                taken += next
                cap.parts += next
                last = next
            }
        }
        return caps
    }

    /** Overlap of the x ranges relative to the narrower rect. */
    private fun xOverlapRatio(a: PRect, b: PRect): Float {
        val w = min(a.width, b.width)
        return if (w <= 0f) 0f else a.overlapX(b) / w
    }

    /** Text columns: merged x spans of the multi-line body blocks. */
    private fun columns(ms: List<Measured>): List<PRect> {
        val spans = ms.filter { it.cls == BlockClass.BODY && it.lines.size >= 3 }.map { it.bounds }.sortedBy { it.x0 }
        val out = ArrayList<PRect>()
        for (s in spans) {
            val last = out.lastOrNull()
            if (last != null && s.x0 <= last.x1) out[out.lastIndex] = last.union(s) else out += s
        }
        return out
    }

    /** The column(s) a caption spans; a full-width caption covers both. */
    private fun columnFor(bounds: PRect, columns: List<PRect>): PRect {
        val hit = columns.filter { it.overlapsX(bounds) }
        if (hit.isEmpty()) return bounds
        val x0 = min(bounds.x0, hit.minOf { it.x0 })
        val x1 = max(bounds.x1, hit.maxOf { it.x1 })
        return PRect(x0, bounds.y0, x1, bounds.y1)
    }

    /**
     * The graphic belonging to [cap]: the gap above or below the caption, bounded by prose,
     * headings and other captions in the same column(s). Which side is decided by what is
     * in it (labels, numbers, images): this paper puts table captions below the tables too.
     */
    private fun region(cap: Cap, ms: List<Measured>, images: List<PRect>, headerBottom: Float, footerTop: Float): PRect {
        val col = cap.column
        val capBounds = cap.bounds
        val own: Set<Measured> = Collections.newSetFromMap<Measured>(IdentityHashMap()).apply { addAll(cap.parts) }
        // Prose bounds the gap; labels and listing lines don't. Algorithm listings are made of
        // one- and two-line fragments, so only real paragraphs bound those.
        val boundaries = ms.filter {
            it !in own && it.bounds.overlapsX(col) && when (it.cls) {
                BlockClass.HEADING, BlockClass.CAPTION -> true
                BlockClass.BODY -> it.isParagraph || (it.isProseFragment && (cap.kind != FigureKind.ALGORITHM || !it.isListingLine))
                else -> false
            }
        }
        val topLimit = boundaries.filter { it.bounds.y1 <= capBounds.y0 + 2f }.maxOfOrNull { it.bounds.y1 } ?: headerBottom
        val bottomLimit = boundaries.filter { it.bounds.y0 >= capBounds.y1 - 2f }.minOfOrNull { it.bounds.y0 } ?: footerTop
        val above = PRect(col.x0, min(topLimit, capBounds.y0), col.x1, capBounds.y0)
        val below = PRect(col.x0, capBounds.y1, col.x1, max(bottomLimit, capBounds.y1))

        fun score(r: PRect): Int {
            if (r.height < MIN_REGION_HEIGHT) return Int.MIN_VALUE
            var s = 0
            for (m in ms) {
                if (m in own) continue
                val filler = m.cls == BlockClass.SMALL_PRINT || m.cls == BlockClass.NUMERIC || m.cls == BlockClass.LABEL ||
                    m.cls == BlockClass.SUB_CAPTION || m.cls == BlockClass.FOOTNOTE || m.cls == BlockClass.IN_FIGURE ||
                    (m.cls == BlockClass.BODY && !m.isParagraph)
                if (filler && r.contains(m.bounds.center)) s += 2
            }
            for (img in images) {
                val inter = r.intersect(img) ?: continue
                if (inter.area >= 0.5f * img.area) s += 2
            }
            if (r.height >= TALL_REGION_HEIGHT) s += 1
            return s
        }

        val sa = score(above)
        val sb = score(below)
        val prefersBelow = cap.kind != FigureKind.FIGURE // tables and algorithms are usually titled above
        var chosen = when {
            sa == Int.MIN_VALUE && sb == Int.MIN_VALUE -> if (prefersBelow) below else above
            sa > sb -> above
            sb > sa -> below
            prefersBelow -> below
            else -> above
        }
        // Images that mostly fall inside the chosen side extend it (clamped to the column and the limits).
        for (img in images) {
            val inter = chosen.intersect(img) ?: continue
            if (inter.area >= 0.5f * img.area) {
                val u = chosen.union(img)
                chosen = PRect(max(u.x0, col.x0), max(u.y0, min(topLimit, chosen.y0)), min(u.x1, col.x1), min(u.y1, max(bottomLimit, chosen.y1)))
            }
        }
        return chosen
    }

    /** Lines joined into one string; a hyphen at a line end followed by lowercase is removed. */
    fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty()) {
                val last = sb[sb.length - 1]
                if ((last == '-' || last == '‐' || last == '­') && t[0].isLowerCase()) sb.setLength(sb.length - 1)
                else sb.append(' ')
            }
            sb.append(t)
        }
        return sb.toString()
    }
}

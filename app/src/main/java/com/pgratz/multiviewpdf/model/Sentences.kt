package com.pgratz.multiviewpdf.model

/** A sentence to read aloud, with one quad per text line it touches (for the on-page highlight). */
data class Sentence(val text: String, val quads: List<PQuad>) {
    val bounds: PRect get() = quads.unionBounds()
}

/**
 * Splits a page's text layer into sentences for text-to-speech. Pure Kotlin so it is
 * unit-testable; tuned for patents (lots of "FIG. 3", "U.S. Pat. No.", numbered claims
 * and two-column text whose gutter carries line numbers).
 */
object SentenceSplitter {
    /** Tokens (lower-case, trailing dot removed) after which a period is not a sentence end. */
    private val ABBREVIATIONS = setOf(
        "fig", "figs", "no", "nos", "u.s", "pat", "al", "e.g", "i.e", "col", "cols", "ser", "appl",
        "pub", "vs", "approx", "para", "eq", "eqs", "ref", "refs", "cf", "viz", "ex", "dept", "assn",
        "govt", "inc", "ltd", "co", "corp", "dr", "mr", "mrs", "ms", "prof", "st",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )

    /** Terminator (+ closing quote/bracket), whitespace, then something that starts a sentence. */
    private val BOUNDARY = Regex("""[.!?]+["')\]]*\s+(?=[A-Z0-9"(\[])""")

    /** "1." / "(12)" / "(a)" standing alone: the marker of a claim or list item. */
    private val LIST_MARKER = Regex("""^\(?\d{1,3}[.)]$|^\([a-zA-Z]\)$""")

    private val NUMERIC_LINE = Regex("""^\d{1,3}$""")

    /** Drops empty blocks and the patent gutter's line-number blocks (5, 10, 15, ...). */
    fun defaultFilter(block: TextBlock): Boolean {
        val lines = block.lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        return lines.isNotEmpty() && !lines.all { NUMERIC_LINE.matches(it) }
    }

    /**
     * Sentences in reading order. Lines of a block are joined (undoing end-of-line
     * hyphenation); sentences never cross blocks. Anything longer than [maxLen] is cut
     * at a clause boundary so it fits a TTS utterance.
     */
    fun split(
        page: PageText,
        blockFilter: (TextBlock) -> Boolean = ::defaultFilter,
        maxLen: Int = 1500,
    ): List<Sentence> {
        val out = ArrayList<Sentence>()
        for (block in page.blocks) {
            if (!blockFilter(block)) continue
            val para = Paragraph.of(block) ?: continue
            val ranges = mergeListMarkers(para.text, boundaries(para.text))
            for (r in ranges) for ((a, b) in softSplit(para.text, r, maxLen)) {
                out += Sentence(para.text.substring(a, b), para.quadsFor(a, b))
            }
        }
        return out
    }

    /**
     * Sentence to start reading from for a long-press at [p]: the one under the point,
     * else the nearest within [near] points, else the first one below it, else the last.
     */
    fun indexAt(sentences: List<Sentence>, p: PPoint, slop: Float = 0f, near: Float = 30f): Int {
        if (sentences.isEmpty()) return 0
        val hit = sentences.indexOfFirst { s -> s.quads.any { it.bounds.contains(p, slop) } }
        if (hit >= 0) return hit
        var best = -1
        var bestD = near
        for ((i, s) in sentences.withIndex()) {
            val d = s.bounds.distanceTo(p)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best >= 0) return best
        val below = sentences.indexOfFirst { it.bounds.y0 >= p.y }
        return if (below >= 0) below else sentences.lastIndex
    }

    /** Words of at least this many letters that a page needs before it counts as prose. */
    const val MIN_PROSE_WORDS = 30

    private val PROSE_WORD = Regex("""\p{L}{4,}""")

    /**
     * False for pages that are really drawings: OCR'd patent figure sheets carry only
     * reference numerals and short labels ("FIG. 1B", "AMP", "Sheet 2 of 6").
     */
    fun looksLikeProse(sentences: List<Sentence>): Boolean {
        var n = 0
        for (s in sentences) {
            n += PROSE_WORD.findAll(s.text).count()
            if (n >= MIN_PROSE_WORDS) return true
        }
        return false
    }

    /** OCR output often spaces punctuation out: "FIG . 3", "10 , 000", "( 2001 )". */
    private val OCR_SPACING = listOf(
        Regex("""(?<=\d)\s*,\s*(?=\d{3}(?!\d))""") to ",",          // 10 , 000 , 000
        Regex("""\b([A-Z])\s*\.\s*(?=[A-Z]\s*\.)""") to "$1.",   // U . S .
        Regex("""\s+([.,;:!?)\]])""") to "$1",
        Regex("""([(\[])\s+""") to "$1",
    )

    private val SPOKEN = listOf(
        Regex("""\bFIGS\.\s*""") to "Figures ",
        Regex("""\bFIG\.\s*""") to "Figure ",
        Regex("""\bU\.S\.\s+Pat\.\s+No\.\s*""") to "U.S. patent number ",
        Regex("""\bPat\.\s+No\.\s*""") to "patent number ",
        Regex("""\bNo\.\s*(?=\d)""") to "number ",
        Regex("""\bNos\.\s*(?=\d)""") to "numbers ",
        Regex("""\bcols?\.\s*(?=\d)""") to "column ",
        Regex("""\be\.g\.""") to "for example",
        Regex("""\bi\.e\.""") to "that is",
        Regex("""\bet al\.""") to "et al",
    )

    /** The text as it should be spoken: expands the abbreviations engines mangle. */
    fun spoken(text: String): String {
        var s = text
        for ((re, rep) in OCR_SPACING) s = re.replace(s, rep)
        for ((re, rep) in SPOKEN) s = re.replace(s, rep)
        return s.trim()
    }

    // ------------------------------------------------------------------ internals

    /** Exclusive end offsets of sentences inside [text] (the final one is implied). */
    private fun boundaries(text: String): List<Pair<Int, Int>> {
        val ends = ArrayList<Int>()
        for (m in BOUNDARY.findAll(text)) {
            val termStart = m.range.first
            var i = termStart
            while (i > 0 && text[i - 1] == ' ') i-- // OCR: "FIG . 3"
            val tokenEnd = i
            while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '.')) i--
            val token = text.substring(i, tokenEnd).trimEnd('.').lowercase()
            if (token in ABBREVIATIONS) continue
            if (token.length == 1 && token[0].isLetter()) continue // an initial: "John A. Smith"
            var end = termStart
            while (end <= m.range.last && !text[end].isWhitespace()) end++
            ends += end
        }
        val ranges = ArrayList<Pair<Int, Int>>()
        var start = 0
        for (e in ends) {
            trimmed(text, start, e)?.let { ranges += it }
            start = e
        }
        trimmed(text, start, text.length)?.let { ranges += it }
        return ranges
    }

    private fun mergeListMarkers(text: String, ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < ranges.size) {
            var (a, b) = ranges[i]
            while (i + 1 < ranges.size && LIST_MARKER.matches(text.substring(a, b))) {
                b = ranges[i + 1].second
                i++
            }
            out += a to b
            i++
        }
        return out
    }

    private val SOFT_SEPARATORS = listOf("; ", ", ", " ")

    private fun softSplit(text: String, range: Pair<Int, Int>, maxLen: Int): List<Pair<Int, Int>> {
        var (s, end) = range
        if (end - s <= maxLen) return listOf(range)
        val out = ArrayList<Pair<Int, Int>>()
        while (end - s > maxLen) {
            val limit = s + maxLen
            var cut = -1
            for (sep in SOFT_SEPARATORS) {
                val k = text.lastIndexOf(sep, limit - sep.length)
                if (k > s) { cut = k + sep.length - 1; break }
            }
            if (cut <= s) cut = limit
            trimmed(text, s, cut)?.let { out += it }
            s = cut
        }
        trimmed(text, s, end)?.let { out += it }
        return out
    }

    private fun trimmed(text: String, start: Int, end: Int): Pair<Int, Int>? {
        var a = start
        var b = end
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        return if (a < b) a to b else null
    }

    /** A block's lines joined into one string, remembering which page char each position came from. */
    private class Paragraph(val text: String, private val lineOf: IntArray, private val idxOf: IntArray, private val block: TextBlock) {
        /** One quad per line for the characters in [start, end). */
        fun quadsFor(start: Int, end: Int): List<PQuad> {
            val out = ArrayList<PQuad>()
            var curLine = -1
            var r: PRect? = null
            for (i in start until end) {
                val l = lineOf[i]
                if (l < 0) continue
                val q = block.lines[l].quads[idxOf[i]].bounds
                if (l != curLine) {
                    r?.let { out += PQuad.of(it) }
                    curLine = l
                    r = q
                } else {
                    r = r!!.union(q)
                }
            }
            r?.let { out += PQuad.of(it) }
            return out
        }

        companion object {
            private const val SEP = -1

            fun of(block: TextBlock): Paragraph? {
                val sb = StringBuilder()
                val lineOf = ArrayList<Int>()
                val idxOf = ArrayList<Int>()
                for ((li, line) in block.lines.withIndex()) {
                    val t = line.text
                    var s = 0
                    while (s < t.length && t[s].isWhitespace()) s++
                    var e = t.length
                    while (e > s && t[e - 1].isWhitespace()) e--
                    if (s >= e) continue
                    if (sb.isNotEmpty()) {
                        val last = sb[sb.length - 1]
                        val hyphen = last == '-' || last == '‐' || last == '­'
                        if (hyphen && t[s].isLowerCase()) {
                            // "inven-" / "tion": drop the hyphen and join directly.
                            sb.setLength(sb.length - 1)
                            lineOf.removeAt(lineOf.size - 1)
                            idxOf.removeAt(idxOf.size - 1)
                        } else {
                            sb.append(' ')
                            lineOf += SEP
                            idxOf += SEP
                        }
                    }
                    for (k in s until e) {
                        sb.append(t[k])
                        lineOf += li
                        idxOf += k
                    }
                }
                if (sb.isEmpty()) return null
                return Paragraph(sb.toString(), lineOf.toIntArray(), idxOf.toIntArray(), block)
            }
        }
    }
}

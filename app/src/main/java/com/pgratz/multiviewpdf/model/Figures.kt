package com.pgratz.multiviewpdf.model

/** "Figure 3" / "Table 1" as the text refers to it. */
data class FigureKey(val kind: FigureKind, val number: Int) {
    val spoken: String get() = "${kind.label} $number"

    /** Stable id for caches: "figure-3", "table-1". */
    val id: String get() = "${kind.name.lowercase()}-$number"
}

/** A caption found on [page], located so it can be rendered and highlighted. */
data class Figure(val key: FigureKey, val page: Int, val caption: Caption)

/** Finds figure/table references in running text. */
object FigureRefs {
    private val HEAD = Regex("""(?<![A-Za-z])(Figures?|Figs?\.|FIGS?\.|FIGURES?|Tables?|TABLES?)\s*~?(\d{1,3})[a-z]?""")
    private val TAIL = Regex("""^\s*(?:(–|—|-|to|through)|(,|and|&|,\s*and))\s*(\d{1,3})[a-z]?""")
    private const val MAX_RANGE = 10

    /** Keys in order of appearance, de-duplicated. Ranges ("Figs. 2–4") are expanded. */
    fun find(text: String): List<FigureKey> {
        val out = LinkedHashSet<FigureKey>()
        var m = HEAD.find(text)
        while (m != null) {
            val kind = if (m.groupValues[1].startsWith("T", ignoreCase = true)) FigureKind.TABLE else FigureKind.FIGURE
            var last = m.groupValues[2].toInt()
            out += FigureKey(kind, last)
            var pos = m.range.last + 1
            while (true) {
                val t = TAIL.find(text.substring(pos)) ?: break
                val n = t.groupValues[3].toInt()
                if (t.groupValues[1].isNotEmpty()) { // a range
                    if (n > last && n - last <= MAX_RANGE) for (k in last + 1..n) out += FigureKey(kind, k)
                    else out += FigureKey(kind, n)
                } else {
                    out += FigureKey(kind, n)
                }
                last = n
                pos += t.range.last + 1
            }
            m = HEAD.find(text, pos)
        }
        return out.toList()
    }
}

/** One step of a page's read-aloud: a body sentence or a figure note inserted after a reference. */
sealed interface ReadItem {
    val quads: List<PQuad>
}

data class Body(val sentence: Sentence) : ReadItem {
    override val quads: List<PQuad> get() = sentence.quads
}

/**
 * Spoken after the sentence that first mentions [key]. [figure] is null when the caption
 * hasn't been found yet (the reader re-resolves before speaking). [quads] outline the
 * figure when it is on this page, else empty.
 */
data class FigureNote(val key: FigureKey, val figure: Figure?, val page: Int, override val quads: List<PQuad>) : ReadItem

class ReadingPlan(val page: Int, val layout: PageLayout?, val items: List<ReadItem>) {
    val bodySentences: List<Sentence> = items.filterIsInstance<Body>().map { it.sentence }
    private val bodyToItem: IntArray = items.indices.filter { items[it] is Body }.toIntArray()

    /** Item index of the [i]-th body sentence. */
    fun bodyIndexToItem(i: Int): Int = if (bodyToItem.isEmpty()) 0 else bodyToItem[i.coerceIn(0, bodyToItem.lastIndex)]

    val referencedKeys: Set<FigureKey> get() = items.filterIsInstance<FigureNote>().map { it.key }.toSet()

    /** The body sentence a note at [itemIndex] follows, if any. */
    fun referencingSentence(itemIndex: Int): String? {
        var i = itemIndex - 1
        while (i >= 0) {
            val it = items[i]
            if (it is Body) return it.sentence.text
            i--
        }
        return null
    }
}

object ReadingPlans {
    /** Keys mentioned anywhere in [sentences]; lets the caller locate them before [build]. */
    fun referencedKeys(sentences: List<Sentence>): LinkedHashSet<FigureKey> {
        val out = LinkedHashSet<FigureKey>()
        for (s in sentences) if (!Layout.CAPTION_STRICT.containsMatchIn(s.text)) out += FigureRefs.find(s.text)
        return out
    }

    /** Body sentences in order, each followed by notes for the figures it mentions for the first time on this page. */
    fun build(page: Int, layout: PageLayout?, sentences: List<Sentence>, resolve: (FigureKey) -> Figure?): ReadingPlan {
        val items = ArrayList<ReadItem>(sentences.size + 4)
        val noted = HashSet<FigureKey>()
        for (s in sentences) {
            items += Body(s)
            if (Layout.CAPTION_STRICT.containsMatchIn(s.text)) continue
            for (key in FigureRefs.find(s.text)) {
                if (!noted.add(key)) continue
                val fig = resolve(key)
                val quads = if (fig != null && fig.page == page) listOf(PQuad.of(fig.caption.extent)) else emptyList()
                items += FigureNote(key, fig, page, quads)
            }
        }
        return ReadingPlan(page, layout, items)
    }
}

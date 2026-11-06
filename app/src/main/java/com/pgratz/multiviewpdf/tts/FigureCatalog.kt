package com.pgratz.multiviewpdf.tts

import com.pgratz.multiviewpdf.model.Figure
import com.pgratz.multiviewpdf.model.FigureKey
import com.pgratz.multiviewpdf.model.Layout
import com.pgratz.multiviewpdf.model.PageLayout
import com.pgratz.multiviewpdf.model.PageText
import com.pgratz.multiviewpdf.pdf.PdfSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Where the figures and tables of one document are. Layouts are kept for every page
 * analysed; page texts (large) only for the most recent few. Main-thread confined: call
 * from viewModelScope only.
 */
class FigureCatalog(private val session: PdfSession) {
    private val texts = object : LinkedHashMap<Int, PageText>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageText>): Boolean = size > TEXT_CACHE
    }
    private val layouts = HashMap<Int, PageLayout>()
    private val figures = HashMap<FigureKey, Figure>()
    /** Body font sizes of pages with real running text, for pages that have none. */
    private val bodySizes = ArrayList<Float>()

    /**
     * Text and layout of [page]. The layout is recomputed whenever the text had to be
     * re-fetched, so the layout's block identities always match the returned text.
     */
    suspend fun analysis(page: Int): Pair<PageText, PageLayout> {
        val t = texts[page]
        val l = layouts[page]
        if (t != null && l != null) return t to l
        val text = session.pageText(page)
        val bounds = session.bounds(page)
        val images = session.pageImages(page)
        val hint = bodySizes.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val layout = withContext(Dispatchers.Default) { Layout.analyze(page, text, bounds, images, hint) }
        texts[page] = text
        layouts[page] = layout
        if (layout.bodyChars >= MIN_BODY_CHARS) bodySizes += layout.bodySize
        for (c in layout.captions) {
            val key = FigureKey(c.kind, c.number)
            if (key !in figures) figures[key] = Figure(key, page, c)
        }
        return text to layout
    }

    suspend fun layout(page: Int): PageLayout = layouts[page] ?: analysis(page).second

    fun resolveCached(key: FigureKey): Figure? = figures[key]

    /** Finds [key] by scanning pages outward from [near], at most [budget] new pages. */
    suspend fun resolve(key: FigureKey, near: Int, budget: Int = 12): Figure? {
        figures[key]?.let { return it }
        val n = session.pageCount
        var tried = 0
        var d = 0
        while (tried < budget) {
            val candidates = if (d == 0) listOf(near) else listOf(near + d, near - d)
            var inRange = false
            for (p in candidates) {
                if (p !in 0 until n) continue
                inRange = true
                if (p !in layouts) {
                    layout(p)
                    tried++
                    figures[key]?.let { return it }
                }
            }
            if (!inRange) break
            d++
        }
        return null
    }

    /** Background pass over the whole document so cross-page references resolve. */
    suspend fun scanAll() {
        for (p in 0 until session.pageCount) {
            if (p !in layouts) layout(p)
            yield()
        }
    }

    companion object {
        private const val TEXT_CACHE = 6
        private const val MIN_BODY_CHARS = 300
    }
}

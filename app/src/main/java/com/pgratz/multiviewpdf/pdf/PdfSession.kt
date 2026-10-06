package com.pgratz.multiviewpdf.pdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.pgratz.multiviewpdf.model.AnnotInfo
import com.pgratz.multiviewpdf.model.AnnotType
import com.pgratz.multiviewpdf.model.AutoCrop
import com.pgratz.multiviewpdf.model.ColumnFinder
import com.pgratz.multiviewpdf.model.CropMargins
import com.pgratz.multiviewpdf.model.PPoint
import com.pgratz.multiviewpdf.model.PQuad
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.PageTransform
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

/**
 * One open PDF. MuPDF objects are not thread-safe, so every call that touches them runs
 * on the single [dispatcher] thread; callers just use the suspend functions.
 */
class PdfSession private constructor(private var doc: PDFDocument, val workFile: File) {

    val pageCount: Int = doc.countPages()

    private val bounds = HashMap<Int, PRect>()

    private val pages = object : LinkedHashMap<Int, PDFPage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PDFPage>): Boolean =
            (size > PAGE_CACHE).also { if (it) eldest.value.destroy() }
    }

    private val texts = object : LinkedHashMap<Int, StructuredText>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, StructuredText>): Boolean =
            (size > TEXT_CACHE).also { if (it) eldest.value.destroy() }
    }

    private fun page(i: Int): PDFPage = pages.getOrPut(i) { doc.loadPage(i) as PDFPage }

    private fun text(i: Int): StructuredText = texts.getOrPut(i) { page(i).toStructuredText() }

    suspend fun bounds(i: Int): PRect = withContext(dispatcher) {
        bounds.getOrPut(i) { page(i).bounds.toPRect() }
    }

    /**
     * Renders the [w]×[h] pixel region starting at ([x0], [y0]) of the page's content
     * image as defined by [t]. Annotations are drawn by MuPDF as part of the page.
     */
    suspend fun render(i: Int, t: PageTransform, x0: Int, y0: Int, w: Int, h: Int): Bitmap =
        withContext(dispatcher) {
            val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val a = t.affine()
            val dev = AndroidDrawDevice(bmp, x0, y0)
            try {
                page(i).run(dev, Matrix(a[0], a[1], a[2], a[3], a[4], a[5]), null)
                dev.close()
            } finally {
                dev.destroy()
            }
            bmp
        }

    suspend fun annotations(i: Int): List<AnnotInfo> = withContext(dispatcher) {
        page(i).annotations.orEmpty().mapIndexed { idx, annot -> annot.toInfo(idx) }
    }

    /** Text-selection quads between two page points (empty if there's no text there). */
    suspend fun highlightQuads(i: Int, a: PPoint, b: PPoint): List<PQuad> = withContext(dispatcher) {
        text(i).highlight(Point(a.x, a.y), Point(b.x, b.y)).orEmpty().map { it.toPQuad() }
    }

    suspend fun hasText(i: Int): Boolean = withContext(dispatcher) {
        text(i).blocks.orEmpty().any { b -> b.lines.orEmpty().any { it.chars.orEmpty().isNotEmpty() } }
    }

    /**
     * Selection end points spanning the word under [p], or null if there's no word
     * there. The points sit inside the first/last characters, which is what
     * [highlightQuads] needs to include them.
     */
    suspend fun wordAt(i: Int, p: PPoint): Pair<PPoint, PPoint>? = withContext(dispatcher) {
        for (block in text(i).blocks.orEmpty()) {
            for (line in block.lines.orEmpty()) {
                val chars = line.chars ?: continue
                if (chars.isEmpty() || !line.bbox.contains(p.x, p.y)) continue
                var idx = chars.indexOfFirst { it.quad.contains(p.x, p.y) }
                if (idx < 0) {
                    idx = chars.indices.minByOrNull { k ->
                        val r = chars[k].quad.toRect()
                        kotlin.math.abs((r.x0 + r.x1) / 2 - p.x)
                    } ?: continue
                }
                if (chars[idx].isWhitespace) continue
                var s = idx
                while (s > 0 && !chars[s - 1].isWhitespace) s--
                var e = idx
                while (e < chars.size - 1 && !chars[e + 1].isWhitespace) e++
                return@withContext chars[s].quad.across(0.25f) to chars[e].quad.across(0.75f)
            }
        }
        null
    }

    suspend fun addHighlight(i: Int, quads: List<PQuad>, color: Int, author: String) =
        withContext(dispatcher) {
            val pg = page(i)
            val annot = pg.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT)
            annot.setQuadPoints(quads.map { it.toQuad() }.toTypedArray())
            annot.setColor(color.toRgb())
            annot.stamp(author)
            annot.update()
            pg.update()
        }

    /** Adds a sticky note (PDF Text annotation) with its icon centred on [at]. */
    suspend fun addNote(i: Int, at: PPoint, contents: String, color: Int, author: String) =
        withContext(dispatcher) {
            val pg = page(i)
            val annot = pg.createAnnotation(PDFAnnotation.TYPE_TEXT)
            val h = NOTE_SIZE / 2
            annot.setRect(Rect(at.x - h, at.y - h, at.x + h, at.y + h))
            annot.setIcon("Note")
            annot.setColor(color.toRgb())
            annot.setContents(contents)
            annot.stamp(author)
            annot.update()
            pg.update()
        }

    suspend fun setContents(i: Int, index: Int, contents: String) = editAnnot(i, index) {
        it.setContents(contents)
    }

    suspend fun setColor(i: Int, index: Int, color: Int) = editAnnot(i, index) {
        it.setColor(color.toRgb())
    }

    suspend fun delete(i: Int, index: Int) = withContext(dispatcher) {
        val pg = page(i)
        val annot = pg.annotations.orEmpty().getOrNull(index) ?: return@withContext
        pg.deleteAnnotation(annot)
        pg.update()
    }

    private suspend fun editAnnot(i: Int, index: Int, f: (PDFAnnotation) -> Unit) =
        withContext(dispatcher) {
            val pg = page(i)
            val annot = pg.annotations.orEmpty().getOrNull(index) ?: return@withContext
            f(annot)
            annot.setModificationDate(Date())
            annot.update()
            pg.update()
        }

    /**
     * The first element of the trailer /ID array, as hex: by the PDF spec it identifies
     * the document permanently and survives incremental saves. Null if absent.
     */
    suspend fun permanentId(): String? = withContext(dispatcher) {
        runCatching {
            val id = doc.trailer.get("ID")
            if (!id.isArray || id.size() < 1) return@runCatching null
            id.get(0).asByteString()?.takeIf { it.isNotEmpty() }?.joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    suspend fun hasUnsavedChanges(): Boolean = withContext(dispatcher) { doc.hasUnsavedChanges() }

    /**
     * Writes pending changes into [workFile]. Incremental saves append the changes and
     * leave the original bytes untouched (how Okular/Acrobat save annotations); a full
     * rewrite is the fallback for files MuPDF had to repair. The document is reopened
     * afterwards so MuPDF's view of the file matches what is on disk.
     */
    suspend fun save() = withContext(dispatcher) {
        if (!doc.hasUnsavedChanges()) return@withContext
        try {
            doc.save(workFile.path, "incremental")
        } catch (e: RuntimeException) {
            val tmp = File(workFile.path + ".full")
            doc.save(tmp.path, "garbage")
            closeDoc()
            if (!tmp.renameTo(workFile)) throw java.io.IOException("Could not replace ${workFile.name}")
            doc = openPdf(workFile)
            return@withContext
        }
        closeDoc()
        doc = openPdf(workFile)
    }

    /** Content margins over up to [samples] evenly spaced pages, for "Auto" crop. */
    suspend fun autoCrop(samples: Int = 20): CropMargins = withContext(dispatcher) {
        val step = maxOf(1, pageCount / samples)
        val found = (0 until pageCount step step).take(samples).mapNotNull { i ->
            val b = bounds.getOrPut(i) { page(i).bounds.toPRect() }
            val t = PageTransform(b, 0, AUTOCROP_WIDTH / b.width)
            val w = t.contentWidth.toInt().coerceAtLeast(1)
            val h = t.contentHeight.toInt().coerceAtLeast(1)
            AutoCrop.contentMargins(pixels(i, t, w, h), w, h)
        }
        AutoCrop.union(found)
    }

    /**
     * The column of content around [at] (text or scanned ink alike), as a page-space rect
     * spanning [visible]'s height. Found from a coarse render's per-x ink profile near
     * [at]'s height, falling back to the whole page. Null if the page is blank.
     */
    suspend fun columnAt(i: Int, visible: PRect, at: PPoint): PRect? = withContext(dispatcher) {
        val scale = COLUMN_RENDER_WIDTH / visible.width
        val t = PageTransform(visible, 0, scale)
        val w = t.contentWidth.toInt().coerceAtLeast(1)
        val h = t.contentHeight.toInt().coerceAtLeast(1)
        val px = pixels(i, t, w, h)
        fun profile(y0: Int, y1: Int) = FloatArray(w).also { p ->
            for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(h)) {
                val row = y * w
                for (x in 0 until w) {
                    val c = px[row + x]
                    if (minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) < COLUMN_INK) p[x] += 1f
                }
            }
        }
        val ax = ((at.x - visible.x0) * scale).toInt().coerceIn(0, w - 1)
        val ay = ((at.y - visible.y0) * scale).toInt()
        val band = (h * 0.2f).toInt()
        val minGap = (6f * scale).toInt().coerceAtLeast(2) // gutters are wider than ~6 pt
        val minWidth = (w * 0.08f).toInt()
        val r = ColumnFinder.find(profile(ay - band, ay + band), ax, minGap, minWidth)
            ?: ColumnFinder.find(profile(0, h), ax, minGap, minWidth)
            ?: return@withContext null
        PRect(visible.x0 + r.first / scale, visible.y0, visible.x0 + (r.last + 1) / scale, visible.y1)
    }

    /** ARGB pixels of a [w]×[h] render of page [i] through [t] (MuPDF fills the background white). */
    private fun pixels(i: Int, t: PageTransform, w: Int, h: Int): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val a = t.affine()
        val dev = AndroidDrawDevice(bmp, 0, 0)
        try {
            page(i).run(dev, Matrix(a[0], a[1], a[2], a[3], a[4], a[5]), null)
            dev.close()
        } finally {
            dev.destroy()
        }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return px
    }

    suspend fun close() = withContext(dispatcher) { closeDoc() }

    private fun closeDoc() {
        texts.values.forEach { it.destroy() }
        texts.clear()
        pages.values.forEach { it.destroy() }
        pages.clear()
        doc.destroy()
    }

    companion object {
        private const val PAGE_CACHE = 8
        private const val TEXT_CACHE = 4
        private const val NOTE_SIZE = 20f
        private const val AUTOCROP_WIDTH = 240f
        private const val COLUMN_RENDER_WIDTH = 600f

        /** Darker than this (any channel) counts as ink for column finding; skips light highlights. */
        private const val COLUMN_INK = 160

        val dispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mupdf").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        /** Thrown for files MuPDF opens but that aren't PDFs (annotations need PDF). */
        class NotPdfException : Exception("Not a PDF file")

        class PasswordException : Exception("Password-protected PDFs aren't supported yet")

        suspend fun open(file: File): PdfSession = withContext(dispatcher) {
            PdfSession(openPdf(file), file)
        }

        private fun openPdf(file: File): PDFDocument {
            val d = Document.openDocument(file.path)
            if (d !is PDFDocument) {
                d.destroy()
                throw NotPdfException()
            }
            if (d.needsPassword()) {
                d.destroy()
                throw PasswordException()
            }
            return d
        }
    }
}

private fun Rect.toPRect() = PRect(x0, y0, x1, y1)

private fun Quad.toPQuad() = PQuad(
    PPoint(ul_x, ul_y), PPoint(ur_x, ur_y), PPoint(ll_x, ll_y), PPoint(lr_x, lr_y)
)

private fun PQuad.toQuad() = Quad(ul.x, ul.y, ur.x, ur.y, ll.x, ll.y, lr.x, lr.y)

/** Point [f] of the way from the middle of the quad's left edge to its right edge. */
private fun Quad.across(f: Float): PPoint {
    val lx = (ul_x + ll_x) / 2
    val ly = (ul_y + ll_y) / 2
    val rx = (ur_x + lr_x) / 2
    val ry = (ur_y + lr_y) / 2
    return PPoint(lx + (rx - lx) * f, ly + (ry - ly) * f)
}

private fun Int.toRgb() = floatArrayOf(
    ((this shr 16) and 0xFF) / 255f, ((this shr 8) and 0xFF) / 255f, (this and 0xFF) / 255f
)

private fun FloatArray?.toArgb(): Int {
    if (this == null || isEmpty()) return 0xFFFFFF00.toInt()
    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt()
    return when (size) {
        1 -> (0xFF shl 24) or (c(this[0]) * 0x010101)
        4 -> { // CMYK
            val k = this[3]
            (0xFF shl 24) or (c((1 - this[0]) * (1 - k)) shl 16) or
                (c((1 - this[1]) * (1 - k)) shl 8) or c((1 - this[2]) * (1 - k))
        }
        else -> (0xFF shl 24) or (c(this[0]) shl 16) or (c(this[1]) shl 8) or c(this[2])
    }
}

private fun PDFAnnotation.stamp(author: String) {
    if (author.isNotBlank()) setAuthor(author)
    setModificationDate(Date())
}

private fun PDFAnnotation.toInfo(index: Int): AnnotInfo {
    val t = when (type) {
        PDFAnnotation.TYPE_TEXT -> AnnotType.NOTE
        PDFAnnotation.TYPE_HIGHLIGHT -> AnnotType.HIGHLIGHT
        else -> AnnotType.OTHER
    }
    val quads = if (t == AnnotType.HIGHLIGHT) {
        runCatching { quadPoints.orEmpty().map { it.toPQuad() } }.getOrDefault(emptyList())
    } else emptyList()
    return AnnotInfo(
        index = index,
        type = t,
        rect = bounds.toPRect(),
        quads = quads,
        contents = contents.orEmpty(),
        color = runCatching { color.toArgb() }.getOrDefault(0xFFFFFF00.toInt()),
    )
}

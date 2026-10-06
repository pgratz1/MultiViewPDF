package com.pgratz.multiviewpdf

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pgratz.multiviewpdf.model.AnnotType
import com.pgratz.multiviewpdf.model.PPoint
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.PageTransform
import com.pgratz.multiviewpdf.pdf.PdfSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Round-trips real annotations through MuPDF: create, save incrementally, reopen. */
@RunWith(AndroidJUnit4::class)
class PdfSessionTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** A two-page PDF with a real text layer ("Claim 1 ..." at y≈100 on page 1). */
    private fun samplePdf(): File {
        val f = File(ctx.cacheDir, "sample-${System.nanoTime()}.pdf")
        val doc = PdfDocument()
        val paint = Paint().apply { textSize = 18f }
        for (i in 0 until 2) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, i + 1).create())
            page.canvas.drawText("Claim 1 recites a widget coupled to a frame", 72f, 100f, paint)
            page.canvas.drawText("FIG. ${i + 1}", 72f, 400f, paint)
            doc.finishPage(page)
        }
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    @Test
    fun highlightAndNoteSurviveSaveAndReopen() = runBlocking {
        val file = samplePdf()
        val originalSize = file.length()
        val s = PdfSession.open(file)
        assertEquals(2, s.pageCount)
        val id = s.permanentId()
        assertTrue(s.hasText(0))

        val word = s.wordAt(0, PPoint(140f, 95f))
        assertNotNull("expected a word under the point", word)
        val quads = s.highlightQuads(0, PPoint(72f, 95f), PPoint(300f, 95f))
        assertTrue("expected text selection quads", quads.isNotEmpty())

        s.addHighlight(0, quads, 0xFFFFFF00.toInt(), "tester")
        s.addNote(0, PPoint(500f, 120f), "Compare with FIG. 2", 0xFFFFD54F.toInt(), "tester")
        assertTrue(s.hasUnsavedChanges())
        s.save()
        s.close()

        // Incremental save appends to the original bytes.
        assertTrue(file.length() > originalSize)
        val head = file.readBytes().copyOf(originalSize.toInt())
        val reopened = PdfSession.open(file)
        // Per-document view state is keyed by this, so it must survive saving.
        assertEquals(id, reopened.permanentId())
        val annots = reopened.annotations(0)
        assertEquals(setOf(AnnotType.HIGHLIGHT, AnnotType.NOTE), annots.map { it.type }.toSet())
        val note = annots.first { it.type == AnnotType.NOTE }
        assertEquals("Compare with FIG. 2", note.contents)
        assertTrue(note.rect.contains(PPoint(500f, 120f)))
        assertTrue(annots.first { it.type == AnnotType.HIGHLIGHT }.quads.isNotEmpty())
        assertTrue(head.isNotEmpty())

        // Edit and delete also persist.
        reopened.setContents(0, note.index, "edited")
        reopened.delete(0, annots.first { it.type == AnnotType.HIGHLIGHT }.index)
        reopened.save()
        val after = reopened.annotations(0)
        assertEquals(listOf(AnnotType.NOTE), after.map { it.type })
        assertEquals("edited", after.single().contents)
        reopened.close()
    }

    @Test
    fun rendersRotatedAndCroppedRegions() = runBlocking {
        val s = PdfSession.open(samplePdf())
        val b = s.bounds(0)
        val crop = PRect(b.x0 + 50f, b.y0 + 60f, b.x1 - 50f, b.y1 - 60f)
        for (rot in listOf(0, 90, 180, 270)) {
            val t = PageTransform(crop, rot, 0.5f)
            val bmp = s.render(0, t, 0, 0, t.contentWidth.toInt(), t.contentHeight.toInt())
            assertEquals(t.contentWidth.toInt(), bmp.width)
            assertEquals(t.contentHeight.toInt(), bmp.height)
        }
        val margins = s.autoCrop()
        assertTrue("text sits well inside the page", margins.left > 0.05f && margins.bottom > 0.3f)
        s.close()
    }
}

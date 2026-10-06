package com.pgratz.multiviewpdf

import com.pgratz.multiviewpdf.model.AnnotInfo
import com.pgratz.multiviewpdf.model.AnnotType
import com.pgratz.multiviewpdf.model.AutoCrop
import com.pgratz.multiviewpdf.model.CropMargins
import com.pgratz.multiviewpdf.model.PPoint
import com.pgratz.multiviewpdf.model.PQuad
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.hitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoCropTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun page(w: Int, h: Int, ink: (Int, Int) -> Boolean) =
        IntArray(w * h) { i -> if (ink(i % w, i / w)) black else white }

    @Test
    fun findsContentBox() {
        val px = page(100, 200) { x, y -> x in 10..79 && y in 20..169 }
        val m = AutoCrop.contentMargins(px, 100, 200)!!
        assertEquals(0.10f, m.left, 1e-6f)
        assertEquals(0.10f, m.top, 1e-6f)
        assertEquals(0.20f, m.right, 1e-6f)
        assertEquals(0.15f, m.bottom, 1e-6f)
    }

    @Test
    fun blankPageAndSingleSpecksAreIgnored() {
        assertNull(AutoCrop.contentMargins(page(50, 50) { _, _ -> false }, 50, 50))
        assertNull(AutoCrop.contentMargins(page(50, 50) { x, y -> x == 3 && y == 3 }, 50, 50))
    }

    @Test
    fun unionKeepsEveryPagesContent() {
        val u = AutoCrop.union(
            listOf(CropMargins(0.1f, 0.2f, 0.1f, 0.1f), CropMargins(0.05f, 0.3f, 0.2f, 0.12f)),
            padding = 0f,
        )
        assertEquals(CropMargins(0.05f, 0.2f, 0.1f, 0.1f), u)
    }

    @Test
    fun hitTestPrefersTopmostAndRespectsSlop() {
        val note = AnnotInfo(0, AnnotType.NOTE, PRect(100f, 100f, 120f, 120f), emptyList(), "n", 0)
        val hl = AnnotInfo(
            1, AnnotType.HIGHLIGHT, PRect(50f, 105f, 300f, 118f),
            listOf(PQuad.of(PRect(50f, 105f, 300f, 118f))), "", 0,
        )
        val list = listOf(note, hl)
        assertEquals(hl, list.hitTest(PPoint(110f, 110f), 5f))
        assertEquals(note, list.hitTest(PPoint(110f, 97f), 5f))
        assertNull(list.hitTest(PPoint(400f, 400f), 5f))
    }
}

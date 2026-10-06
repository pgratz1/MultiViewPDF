package com.pgratz.multiviewpdf

import com.pgratz.multiviewpdf.model.CropMargins
import com.pgratz.multiviewpdf.model.PPoint
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.PageTransform
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTransformTest {
    private val page = PRect(0f, 0f, 612f, 792f)
    private val crop = CropMargins(left = 0.1f, top = 0.05f, right = 0.2f, bottom = 0.15f).applyTo(page)
    private val samples = listOf(PPoint(100f, 200f), PPoint(crop.x0, crop.y0), PPoint(crop.x1, crop.y1), PPoint(300f, 650f))

    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 1e-2f)

    @Test
    fun roundTripsForEveryRotationCropAndScale() {
        for (visible in listOf(page, crop)) for (rot in listOf(0, 90, 180, 270)) for (scale in listOf(0.5f, 1f, 2.75f)) {
            val t = PageTransform(visible, rot, scale)
            for (p in samples) {
                val back = t.contentToPage(t.pageToContent(p))
                near(p.x, back.x)
                near(p.y, back.y)
            }
        }
    }

    @Test
    fun affineMatrixMatchesPointMapping() {
        for (visible in listOf(page, crop)) for (rot in listOf(0, 90, 180, 270)) {
            val t = PageTransform(visible, rot, 1.7f)
            val (a, b, c, d, e) = t.affine()
            val f = t.affine()[5]
            for (p in samples) {
                val q = t.pageToContent(p)
                near(q.x, a * p.x + c * p.y + e)
                near(q.y, b * p.x + d * p.y + f)
            }
        }
    }

    @Test
    fun visibleRectMapsExactlyOntoContentArea() {
        for (rot in listOf(0, 90, 180, 270)) {
            val t = PageTransform(crop, rot, 2f)
            val corners = listOf(
                PPoint(crop.x0, crop.y0), PPoint(crop.x1, crop.y0), PPoint(crop.x0, crop.y1), PPoint(crop.x1, crop.y1)
            ).map { t.pageToContent(it) }
            near(0f, corners.minOf { it.x })
            near(0f, corners.minOf { it.y })
            near(t.contentWidth, corners.maxOf { it.x })
            near(t.contentHeight, corners.maxOf { it.y })
        }
    }

    @Test
    fun quarterTurnsSwapDimensionsAndRotateClockwise() {
        val t = PageTransform(page, 90, 1f)
        near(792f, t.contentWidth)
        near(612f, t.contentHeight)
        // The page's top-left corner ends up at the top-right after a clockwise turn.
        val tl = t.pageToContent(PPoint(0f, 0f))
        near(792f, tl.x)
        near(0f, tl.y)
    }

    @Test
    fun rotationNormalizes() {
        assertEquals(270, PageTransform.normalizeRotation(-90))
        assertEquals(0, PageTransform.normalizeRotation(360))
        assertEquals(90, PageTransform.normalizeRotation(450))
    }

    @Test
    fun cropClampKeepsSomethingVisible() {
        val m = CropMargins(left = 0.8f, right = 0.8f, top = -1f, bottom = 2f).clamped()
        assertEquals(0.8f, m.left, 1e-6f)
        assertEquals(0.1f, m.right, 1e-6f)
        assertEquals(0f, m.top, 1e-6f)
        assertEquals(0.9f, m.bottom, 1e-6f)
    }
}

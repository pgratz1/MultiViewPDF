package com.pgratz.multiviewpdf.model

/**
 * Maps between PDF page space and "content" pixels: the cropped, rotated, scaled page
 * image with its top-left corner at (0, 0). Rendering, hit-testing and overlays all go
 * through this one class so crop and rotation can never disagree between them.
 *
 * @param visible the part of the page shown (the page bounds, or the crop rect)
 * @param rotation view rotation, clockwise degrees (multiples of 90)
 * @param scale content pixels per PDF point
 */
class PageTransform(val visible: PRect, rotation: Int, val scale: Float) {
    val rotation: Int = normalizeRotation(rotation)
    private val vw = visible.width
    private val vh = visible.height

    val contentWidth: Float get() = (if (rotation % 180 == 0) vw else vh) * scale
    val contentHeight: Float get() = (if (rotation % 180 == 0) vh else vw) * scale

    fun pageToContent(p: PPoint): PPoint {
        val lx = p.x - visible.x0
        val ly = p.y - visible.y0
        return when (rotation) {
            90 -> PPoint((vh - ly) * scale, lx * scale)
            180 -> PPoint((vw - lx) * scale, (vh - ly) * scale)
            270 -> PPoint(ly * scale, (vw - lx) * scale)
            else -> PPoint(lx * scale, ly * scale)
        }
    }

    fun contentToPage(c: PPoint): PPoint {
        val x = c.x / scale
        val y = c.y / scale
        return when (rotation) {
            90 -> PPoint(y + visible.x0, vh - x + visible.y0)
            180 -> PPoint(vw - x + visible.x0, vh - y + visible.y0)
            270 -> PPoint(vw - y + visible.x0, x + visible.y0)
            else -> PPoint(x + visible.x0, y + visible.y0)
        }
    }

    /**
     * The same mapping as [pageToContent] as an affine matrix [a, b, c, d, e, f] with
     * x' = a·x + c·y + e and y' = b·x + d·y + f — MuPDF's Matrix layout.
     */
    fun affine(): FloatArray {
        val s = scale
        val x0 = visible.x0
        val y0 = visible.y0
        return when (rotation) {
            90 -> floatArrayOf(0f, s, -s, 0f, s * (vh + y0), -s * x0)
            180 -> floatArrayOf(-s, 0f, 0f, -s, s * (vw + x0), s * (vh + y0))
            270 -> floatArrayOf(0f, -s, s, 0f, -s * y0, s * (vw + x0))
            else -> floatArrayOf(s, 0f, 0f, s, -s * x0, -s * y0)
        }
    }

    fun withScale(newScale: Float) = PageTransform(visible, rotation, newScale)

    companion object {
        fun normalizeRotation(degrees: Int): Int = (((degrees / 90) * 90) % 360 + 360) % 360

        /** Unscaled (scale = 1) content size for [visible] shown at [rotation]. */
        fun unscaledSize(visible: PRect, rotation: Int): Pair<Float, Float> =
            if (normalizeRotation(rotation) % 180 == 0) visible.width to visible.height
            else visible.height to visible.width
    }
}

package com.pgratz.multiviewpdf.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** A point in PDF page space (MuPDF coordinates: points, origin top-left, y down). */
data class PPoint(val x: Float, val y: Float)

data class PRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0

    fun contains(p: PPoint, slop: Float = 0f): Boolean =
        p.x >= x0 - slop && p.x <= x1 + slop && p.y >= y0 - slop && p.y <= y1 + slop

    companion object {
        fun spanning(a: PPoint, b: PPoint) =
            PRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }
}

/** Four corners in page space, in the same order as MuPDF's Quad / the PDF QuadPoints entry. */
data class PQuad(val ul: PPoint, val ur: PPoint, val ll: PPoint, val lr: PPoint) {
    val bounds: PRect
        get() = PRect(
            minOf(ul.x, ur.x, ll.x, lr.x), minOf(ul.y, ur.y, ll.y, lr.y),
            maxOf(ul.x, ur.x, ll.x, lr.x), maxOf(ul.y, ur.y, ll.y, lr.y),
        )

    companion object {
        fun of(r: PRect) = PQuad(
            PPoint(r.x0, r.y0), PPoint(r.x1, r.y0), PPoint(r.x0, r.y1), PPoint(r.x1, r.y1)
        )
    }
}

/**
 * View-only crop, as fractions of each page's width/height trimmed from each edge.
 * Fractions (rather than points) let one crop apply sensibly to pages of differing size.
 */
@Serializable
data class CropMargins(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    val isNone: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f

    /** Keeps at least [MIN_VISIBLE] of the page visible in each direction. */
    fun clamped(): CropMargins {
        val l = left.coerceIn(0f, 1f - MIN_VISIBLE)
        val t = top.coerceIn(0f, 1f - MIN_VISIBLE)
        val r = right.coerceIn(0f, 1f - MIN_VISIBLE - l)
        val b = bottom.coerceIn(0f, 1f - MIN_VISIBLE - t)
        return CropMargins(l, t, r, b)
    }

    fun applyTo(bounds: PRect): PRect = PRect(
        bounds.x0 + left * bounds.width,
        bounds.y0 + top * bounds.height,
        bounds.x1 - right * bounds.width,
        bounds.y1 - bottom * bounds.height,
    )

    companion object {
        const val MIN_VISIBLE = 0.1f
        val NONE = CropMargins()
    }
}

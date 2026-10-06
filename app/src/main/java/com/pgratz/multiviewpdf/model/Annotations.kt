package com.pgratz.multiviewpdf.model

enum class AnnotType { NOTE, HIGHLIGHT, OTHER }

/**
 * Snapshot of one PDF annotation. [index] is its position in the page's annotation list
 * at the time it was read, which is how edits find it again.
 */
data class AnnotInfo(
    val index: Int,
    val type: AnnotType,
    val rect: PRect,
    val quads: List<PQuad>,
    val contents: String,
    val color: Int,
)

/** Topmost annotation under [p]; [slop] is in page units. */
fun List<AnnotInfo>.hitTest(p: PPoint, slop: Float): AnnotInfo? = lastOrNull { a ->
    when (a.type) {
        AnnotType.NOTE -> a.rect.contains(p, slop)
        AnnotType.HIGHLIGHT ->
            if (a.quads.isEmpty()) a.rect.contains(p) else a.quads.any { it.bounds.contains(p, slop / 3) }
        AnnotType.OTHER -> a.rect.contains(p)
    }
}

/** Highlighter colors offered in the UI (opaque ARGB; MuPDF multiplies them over the text). */
object HighlightColors {
    val palette = listOf(
        0xFFFFFF00.toInt(), // yellow
        0xFF7CFC00.toInt(), // green
        0xFF00E5FF.toInt(), // cyan
        0xFFFF69B4.toInt(), // pink
        0xFFFFA500.toInt(), // orange
    )
    val noteDefault = 0xFFFFD54F.toInt()
}

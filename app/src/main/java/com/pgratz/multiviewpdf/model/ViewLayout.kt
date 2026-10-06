package com.pgratz.multiviewpdf.model

import kotlinx.serialization.Serializable

enum class ViewMode { SINGLE, DUAL }

enum class FitMode { PAGE, WIDTH }

enum class Tool { PAN, HIGHLIGHT, NOTE }

/** One viewing pane. [page] may equal the page count in a spread, meaning "blank". */
@Serializable
data class PaneState(
    val page: Int = 0,
    val rotation: Int = 0,
    val locked: Boolean = false,
    /** Multiplier on top of the [fit] scale. */
    val zoom: Float = 1f,
    val fit: FitMode = FitMode.PAGE,
)

/**
 * Everything about how a document is being viewed. Persisted per document, so reopening
 * a patent brings back the locked figure pane, rotations and crop.
 */
@Serializable
data class ViewLayout(
    val mode: ViewMode = ViewMode.DUAL,
    val panes: List<PaneState> = listOf(PaneState(page = 0), PaneState(page = 1)),
    /** Pane that keyboard commands target; the one shown in single mode. */
    val active: Int = 0,
    /** In dual mode with neither pane locked, keep the panes on consecutive pages. */
    val linked: Boolean = true,
    val crop: CropMargins = CropMargins.NONE,
    val cropEnabled: Boolean = false,
) {
    fun pane(i: Int) = panes[i]

    fun withPane(i: Int, f: (PaneState) -> PaneState): ViewLayout =
        copy(panes = panes.mapIndexed { idx, p -> if (idx == i) f(p) else p })

    /** Clamps restored state to a document that may have changed since it was saved. */
    fun sanitized(pageCount: Int): ViewLayout {
        val last = (pageCount - 1).coerceAtLeast(0)
        val fixed = (if (panes.size == 2) panes else listOf(PaneState(0), PaneState(1))).map {
            it.copy(
                page = it.page.coerceIn(0, last + 1),
                rotation = PageTransform.normalizeRotation(it.rotation),
                zoom = it.zoom.coerceIn(Navigator.MIN_ZOOM, Navigator.MAX_ZOOM),
            )
        }
        return copy(panes = fixed, active = active.coerceIn(0, 1), crop = crop.clamped())
    }
}

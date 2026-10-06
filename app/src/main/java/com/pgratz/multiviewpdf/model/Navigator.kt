package com.pgratz.multiviewpdf.model

import kotlin.math.max

/**
 * Page-navigation rules for the one/two-pane viewer, as pure functions so they can be
 * unit-tested without Android.
 *
 * - Single mode shows the active pane; navigation moves it one page (locks don't apply).
 * - Dual mode, linked, neither pane locked ("spread"): panes show pages p and p+1 and
 *   turn together by two pages (or by one with `fine`).
 * - Dual mode with one pane locked: only the other pane moves, one page at a time.
 * - Dual mode, unlinked, neither locked: only the active pane moves.
 */
object Navigator {
    const val MIN_ZOOM = 0.25f
    const val MAX_ZOOM = 10f

    fun visiblePanes(l: ViewLayout): List<Int> =
        if (l.mode == ViewMode.SINGLE) listOf(l.active) else listOf(0, 1)

    fun isSpread(l: ViewLayout): Boolean =
        l.mode == ViewMode.DUAL && l.linked && l.panes.none { it.locked }

    /** Global next/previous page (keyboard, toolbar). [dir] is +1 or -1. */
    fun turn(l: ViewLayout, dir: Int, pageCount: Int, fine: Boolean = false): ViewLayout = when {
        l.mode == ViewMode.SINGLE -> movePane(l, l.active, dir, pageCount, ignoreLock = true)
        isSpread(l) -> moveSpread(l, if (fine) dir else dir * 2, pageCount)
        else -> {
            val unlocked = (0..1).filter { !l.panes[it].locked }
            when (unlocked.size) {
                0 -> l
                1 -> movePane(l, unlocked[0], dir, pageCount)
                else -> movePane(l, l.active, dir, pageCount)
            }
        }
    }

    /** Next/previous initiated on a specific pane (swipe, wheel, pane footer buttons). */
    fun turnPane(l: ViewLayout, pane: Int, dir: Int, pageCount: Int): ViewLayout = when {
        l.mode == ViewMode.SINGLE -> movePane(l, pane, dir, pageCount, ignoreLock = true)
        isSpread(l) -> moveSpread(l, dir * 2, pageCount)
        else -> movePane(l, pane, dir, pageCount)
    }

    /** Jump [pane] to [page]; a spread keeps its partner page adjacent. */
    fun goTo(l: ViewLayout, pane: Int, page: Int, pageCount: Int): ViewLayout {
        val p = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (!isSpread(l)) return l.withPane(pane) { it.copy(page = p) }
        return spreadAt(l, if (pane == 0) p else p - 1, pageCount)
    }

    fun setLocked(l: ViewLayout, pane: Int, locked: Boolean, pageCount: Int): ViewLayout {
        val next = l.withPane(pane) { it.copy(locked = locked) }
        // Unlocking the last lock returns to a spread; re-attach the freed pane next to
        // the pane the user has been reading in.
        if (!locked && isSpread(next)) {
            val other = next.panes[1 - pane].page
            return spreadAt(next, if (pane == 1) other else other - 1, pageCount)
        }
        return next
    }

    fun toggleLock(l: ViewLayout, pane: Int, pageCount: Int) =
        setLocked(l, pane, !l.panes[pane].locked, pageCount)

    fun setLinked(l: ViewLayout, linked: Boolean, pageCount: Int): ViewLayout {
        val next = l.copy(linked = linked)
        return if (isSpread(next)) resyncToActive(next, pageCount) else next
    }

    fun setMode(l: ViewLayout, mode: ViewMode, pageCount: Int): ViewLayout {
        val next = l.copy(mode = mode)
        return if (mode == ViewMode.DUAL && isSpread(next)) resyncToActive(next, pageCount) else next
    }

    /** Shows [pane]'s page in the other pane and locks it there (e.g. pin a figure). */
    fun sendToOther(l: ViewLayout, pane: Int): ViewLayout {
        val page = l.panes[pane].page
        return l.withPane(1 - pane) { it.copy(page = page, locked = true) }
            .copy(mode = ViewMode.DUAL, active = pane)
    }

    fun swap(l: ViewLayout): ViewLayout =
        l.copy(panes = l.panes.reversed(), active = 1 - l.active)

    fun rotate(l: ViewLayout, pane: Int, delta: Int): ViewLayout =
        l.withPane(pane) { it.copy(rotation = PageTransform.normalizeRotation(it.rotation + delta)) }

    fun setZoom(l: ViewLayout, pane: Int, zoom: Float): ViewLayout =
        l.withPane(pane) { it.copy(zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)) }

    private fun movePane(
        l: ViewLayout, pane: Int, dir: Int, pageCount: Int, ignoreLock: Boolean = false,
    ): ViewLayout {
        val p = l.panes[pane]
        if (p.locked && !ignoreLock) return l
        val last = (pageCount - 1).coerceAtLeast(0)
        return l.withPane(pane) { it.copy(page = (it.page + dir).coerceIn(0, last)) }
    }

    private fun moveSpread(l: ViewLayout, delta: Int, pageCount: Int) =
        spreadAt(l, l.panes[0].page + delta, pageCount)

    /** Left pane on [left], right pane on left+1, never running past the last page pair. */
    private fun spreadAt(l: ViewLayout, left: Int, pageCount: Int): ViewLayout {
        val maxLeft = max(0, pageCount - 2)
        val p = left.coerceIn(0, maxLeft)
        return l.withPane(0) { it.copy(page = p) }.withPane(1) { it.copy(page = p + 1) }
    }

    private fun resyncToActive(l: ViewLayout, pageCount: Int): ViewLayout {
        val anchor = l.panes[l.active].page
        return spreadAt(l, if (l.active == 0) anchor else anchor - 1, pageCount)
    }
}

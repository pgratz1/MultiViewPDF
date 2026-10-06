package com.pgratz.multiviewpdf

import com.pgratz.multiviewpdf.model.Navigator
import com.pgratz.multiviewpdf.model.PaneState
import com.pgratz.multiviewpdf.model.ViewLayout
import com.pgratz.multiviewpdf.model.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    private val n = 20
    private fun spread(left: Int) = ViewLayout(panes = listOf(PaneState(left), PaneState(left + 1)))
    private fun ViewLayout.pages() = panes.map { it.page }

    @Test
    fun linkedSpreadTurnsByTwo() {
        val l = Navigator.turn(spread(0), +1, n)
        assertEquals(listOf(2, 3), l.pages())
        assertEquals(listOf(0, 1), Navigator.turn(l, -1, n).pages())
    }

    @Test
    fun fineTurnShiftsSpreadByOne() {
        assertEquals(listOf(1, 2), Navigator.turn(spread(0), +1, n, fine = true).pages())
    }

    @Test
    fun spreadStopsAtDocumentEnds() {
        assertEquals(listOf(18, 19), Navigator.turn(spread(18), +1, n).pages())
        assertEquals(listOf(18, 19), Navigator.turn(spread(17), +1, n).pages())
        assertEquals(listOf(0, 1), Navigator.turn(spread(0), -1, n).pages())
    }

    @Test
    fun lockedPaneStaysWhileOtherAdvancesByOne() {
        // Lock a figure on the right; read text on the left.
        var l = Navigator.setLocked(spread(4), 1, true, n)
        l = Navigator.turn(l, +1, n)
        assertEquals(listOf(5, 5), l.pages())
        l = Navigator.turn(l, +1, n)
        assertEquals(listOf(6, 5), l.pages())
        // Swiping on the locked pane does nothing.
        assertEquals(l, Navigator.turnPane(l, 1, +1, n))
        // Swiping on the free pane moves it by one.
        assertEquals(listOf(7, 5), Navigator.turnPane(l, 0, +1, n).pages())
    }

    @Test
    fun lockedLeftPaneLetsRightPaneRead() {
        var l = Navigator.setLocked(spread(0), 0, true, n)
        repeat(5) { l = Navigator.turn(l, +1, n) }
        assertEquals(listOf(0, 6), l.pages())
    }

    @Test
    fun bothLockedMeansNoMovement() {
        var l = Navigator.setLocked(spread(2), 0, true, n)
        l = Navigator.setLocked(l, 1, true, n)
        assertEquals(l, Navigator.turn(l, +1, n))
    }

    @Test
    fun unlockingReattachesNextToReadingPane() {
        var l = Navigator.setLocked(spread(2), 1, true, n)
        repeat(6) { l = Navigator.turn(l, +1, n) }
        assertEquals(listOf(8, 3), l.pages())
        l = Navigator.setLocked(l, 1, false, n)
        assertEquals(listOf(8, 9), l.pages())
        assertTrue(Navigator.isSpread(l))
    }

    @Test
    fun unlinkedPanesMoveOnlyTheActiveOne() {
        val l = Navigator.setLinked(spread(0), false, n).copy(active = 1)
        assertEquals(listOf(0, 2), Navigator.turn(l, +1, n).pages())
    }

    @Test
    fun singleModeMovesActivePaneByOneIgnoringLocks() {
        val l = Navigator.setLocked(spread(4), 1, true, n).copy(mode = ViewMode.SINGLE, active = 1)
        val next = Navigator.turn(l, +1, n)
        assertEquals(listOf(4, 6), next.pages())
        assertTrue(next.panes[1].locked)
    }

    @Test
    fun singlePageStaysWithinDocument() {
        val l = ViewLayout(mode = ViewMode.SINGLE, panes = listOf(PaneState(19), PaneState(0)))
        assertEquals(19, Navigator.turn(l, +1, n).panes[0].page)
    }

    @Test
    fun sendToOtherPinsAndLocksPage() {
        val l = Navigator.sendToOther(spread(6), 0)
        assertEquals(listOf(6, 6), l.pages())
        assertTrue(l.panes[1].locked)
        assertFalse(l.panes[0].locked)
        assertEquals(ViewMode.DUAL, l.mode)
    }

    @Test
    fun goToKeepsSpreadAdjacent() {
        assertEquals(listOf(9, 10), Navigator.goTo(spread(0), 0, 9, n).pages())
        assertEquals(listOf(8, 9), Navigator.goTo(spread(0), 1, 9, n).pages())
        val locked = Navigator.setLocked(spread(0), 1, true, n)
        assertEquals(listOf(12, 1), Navigator.goTo(locked, 0, 12, n).pages())
    }

    @Test
    fun returningToDualResyncsAroundActivePane() {
        var l = spread(0).copy(mode = ViewMode.SINGLE, active = 0)
        repeat(5) { l = Navigator.turn(l, +1, n) }
        l = Navigator.setMode(l, ViewMode.DUAL, n)
        assertEquals(listOf(5, 6), l.pages())
    }

    @Test
    fun swapExchangesPanesAndActive() {
        val l = Navigator.swap(Navigator.rotate(spread(2), 0, 90))
        assertEquals(listOf(3, 2), l.pages())
        assertEquals(90, l.panes[1].rotation)
        assertEquals(1, l.active)
    }

    @Test
    fun rotationIsPerPane() {
        val l = Navigator.rotate(Navigator.rotate(spread(0), 1, 90), 1, 90)
        assertEquals(0, l.panes[0].rotation)
        assertEquals(180, l.panes[1].rotation)
        assertEquals(90, Navigator.rotate(l, 1, -90).panes[1].rotation)
    }

    @Test
    fun sanitizedClampsRestoredState() {
        val l = ViewLayout(panes = listOf(PaneState(50, rotation = 45, zoom = 100f), PaneState(-3))).sanitized(10)
        assertEquals(10, l.panes[0].page)
        assertEquals(0, l.panes[1].page)
        assertEquals(Navigator.MAX_ZOOM, l.panes[0].zoom, 0f)
    }
}

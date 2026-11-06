package com.pgratz.multiviewpdf

import com.pgratz.multiviewpdf.model.LinkInfo
import com.pgratz.multiviewpdf.model.PPoint
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.hitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkInfoTest {
    private val web = LinkInfo(PRect(10f, 10f, 100f, 20f), "https://www.example.com/path", null)
    private val goto = LinkInfo(PRect(50f, 10f, 200f, 20f), "#page=3", 2)
    private val links = listOf(web, goto)

    @Test fun topmostLinkWins() = assertEquals(goto, links.hitTest(PPoint(60f, 15f), 0f))

    @Test fun hitTestUsesSlop() {
        assertNull(links.hitTest(PPoint(5f, 15f), 0f))
        assertEquals(web, links.hitTest(PPoint(5f, 15f), 6f))
        assertNull(links.hitTest(PPoint(300f, 15f), 6f))
    }

    @Test fun exactHitBeatsNearbyLinkWithinSlop() {
        val below = LinkInfo(PRect(10f, 22f, 100f, 32f), "#page=5", 4)
        val all = listOf(web, below)
        // Inside `web`, and within slop of `below`, which comes later in the list.
        assertEquals(web, all.hitTest(PPoint(20f, 18f), 30f))
        // In the gap between them: the nearer one wins.
        assertEquals(below, all.hitTest(PPoint(20f, 21.5f), 30f))
        assertEquals(web, all.hitTest(PPoint(20f, 20.4f), 30f))
    }

    @Test fun labelsForInternalAndExternalLinks() {
        assertEquals("Page 3", goto.label)
        assertTrue(!goto.isExternal)
        assertEquals("example.com/path", web.label)
        val long = LinkInfo(PRect(0f, 0f, 1f, 1f), "https://host.example.org/" + "a".repeat(80) + "/end.html", null)
        assertTrue(long.label.length <= LinkInfo.MAX_LABEL + 1)
        assertTrue(long.label.contains("…"))
        assertTrue(long.label.endsWith("end.html"))
    }
}

package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** An ink-free page keeps a 1x1 sentinel, not a page-sized transparent ARGB surface. */
class EmptyInkSurfaceTest {

    private val factory = FakeSurfaceFactory()

    private fun state(pageCount: Int = 2): CanvasState {
        val pages = (0 until pageCount).mapTo(mutableListOf()) { Page(200.0, 200.0) }
        return CanvasState(Document(pages), factory, Palette.DEFAULT).apply {
            viewportW = 800
            viewportH = 1000
            maxCachePx = 4096.0
            relayout()
        }
    }

    private fun dot(x: Double, y: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0)))

    @Test
    fun anInkFreePageCachesTheTinySentinelAndNoBytes() {
        val st = state()
        val page = st.document.pages[0]
        val entry = st.cacheFor(page)

        assertTrue(entry.empty)
        assertEquals(1, entry.surface.width)
        assertEquals(1, entry.surface.height)
        assertEquals(1, st.cacheSnapshot().inkPages)
        assertEquals("an empty ink layer holds no page bytes", 0L, st.cacheSnapshot().bytes)
    }

    @Test
    fun everyInkFreePageSharesOneSentinelAllocation() {
        val st = state(pageCount = 3)
        val entries = st.document.pages.map { st.cacheFor(it) }

        assertTrue(entries.all { it.empty })
        assertSame(entries[0].surface, entries[1].surface)
        assertSame(entries[1].surface, entries[2].surface)
        assertEquals("only the one 1x1 surface was allocated", 1, factory.created.size)
    }

    @Test
    fun anEmptyCacheIsReusedAcrossAZoomInsteadOfRebuilt() {
        val st = state()
        val page = st.document.pages[0]
        val first = st.cacheFor(page)
        val allocated = factory.created.size

        st.zoom = 2.0
        st.relayout()
        val second = st.cacheFor(page)

        assertSame("an empty layer has no resolution to invalidate", first, second)
        assertEquals(allocated, factory.created.size)
    }

    @Test
    fun drawingOnAnEmptyCacheRebuildsAtFullSize() {
        val st = state()
        val page = st.document.pages[0]
        assertTrue(st.cacheFor(page).empty)

        val stroke = dot(20.0, 20.0)
        page.items.add(stroke)
        st.appendToCache(page, stroke)

        assertNull("the sentinel must not be painted into; the cache is dropped", st.rawInkCache(page))
        val rebuilt = st.cacheFor(page)
        assertFalse(rebuilt.empty)
        assertTrue("the page now has a real surface", rebuilt.surface.width > 1)
        assertEquals(1, st.cacheSnapshot().inkPages)
    }

    @Test
    fun repairingAnEmptyCacheNeverPaintsIntoTheSentinel() {
        val st = state()
        val page = st.document.pages[0]
        val entry = st.cacheFor(page)
        val surface = entry.surface as FakeRasterSurface
        val ops = surface.painter.ops.size

        assertFalse(st.repairRegion(page, Rect(0.0, 0.0, 10.0, 10.0)))

        assertEquals("no drawing may reach the shared sentinel", ops, surface.painter.ops.size)
    }

    @Test
    fun aPageWithFlowContentStillGetsAFullInkSurface() {
        val st = state(pageCount = 1)
        val page = st.document.pages[0]
        st.paintFlow = { _, _, _ -> }
        st.hasFlowContent = { it === page }

        val entry = st.cacheFor(page)

        assertFalse(entry.empty)
        assertTrue(entry.surface.width > 1)
    }

    @Test
    fun aPageWithOnlyAHighlighterUsesTheSentinel() {
        val st = state(pageCount = 1)
        val page = st.document.pages[0]
        page.items.add(
            Stroke(
                Tool.HIGHLIGHTER,
                ToolDefaults.configFor(Tool.HIGHLIGHTER),
                mutableListOf(Sample(20.0, 20.0, 1.0), Sample(80.0, 20.0, 1.0)),
            ),
        )

        val entry = st.cacheFor(page)

        assertTrue("highlighters composite live, never into the ink cache", entry.empty)
    }
}

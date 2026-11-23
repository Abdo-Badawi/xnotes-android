package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory pressure drops rendered layers outside what is on screen. It must never recycle a cache
 * surface — a hardware Canvas may still hold it — so eviction is a dropped reference left to GC.
 */
class MemoryPressureCacheTest {

    private val factory = FakeSurfaceFactory()
    private val paints = ArrayList<Page>()

    private fun state(pageCount: Int = 4, vh: Int = 120): CanvasState {
        val pages = (0 until pageCount).mapTo(mutableListOf()) { Page(200.0, 200.0, pdfPage = it) }
        return CanvasState(Document(pages), factory, Palette.DEFAULT).apply {
            viewportW = 400
            viewportH = vh
            maxCachePx = 4096.0
            relayout()
            paintPageBackground = { page, _, _, _, _, _ -> paints += page }
        }
    }

    private fun paginatedState(pageCount: Int = 4): CanvasState {
        val pages = (0 until pageCount).mapTo(mutableListOf()) { Page(200.0, 200.0, pdfPage = it) }
        return CanvasState(Document(pages), factory, Palette.DEFAULT).apply {
            viewingMode = ViewingMode.SINGLE
            verticalScroll = false
            viewportW = 400
            viewportH = 300
            maxCachePx = 4096.0
            relayout()
            paintPageBackground = { page, _, _, _, _, _ -> paints += page }
        }
    }

    @Test
    fun trimKeepsTheVisiblePageAndDropsOffScreenLayers() {
        val st = state()
        val onScreen = st.document.pages[0]
        val offScreen = st.document.pages.subList(1, 4)
        st.updateCacheBand()
        st.cacheFor(onScreen)
        st.backgroundFor(onScreen)
        for (p in offScreen) {
            st.cacheFor(p)
            st.backgroundFor(p)
        }
        assertNotNull(st.rawInkCache(offScreen[0]))
        assertNotNull(st.rawBackgroundCache(offScreen[0]))

        st.trimMemoryCaches()

        assertNotNull("the page on screen keeps its ink layer", st.rawInkCache(onScreen))
        assertNotNull("and its background layer", st.rawBackgroundCache(onScreen))
        for (p in offScreen) {
            assertNull("off-screen ink is dropped", st.rawInkCache(p))
            assertNull("off-screen background is dropped", st.rawBackgroundCache(p))
        }
    }

    @Test
    fun trimCancelsQueuedWorkForPagesThatLeaveTheVisibleSet() {
        val st = state()
        val offScreen = st.document.pages[1]
        val bgQueue = ArrayDeque<() -> Unit>()
        st.runAsyncBackground = { bgQueue.addLast(it) }
        st.updateCacheBand()
        assertNull(st.backgroundForOrSchedule(offScreen)) // queued, not yet painted
        assertEquals(1, bgQueue.size)
        assertTrue(paints.isEmpty())

        st.trimMemoryCaches()
        while (bgQueue.isNotEmpty()) bgQueue.removeFirst().invoke()

        assertTrue("the queued off-screen build must not paint", paints.isEmpty())
        assertNull(st.rawBackgroundCache(offScreen))
        // Its pending mark was cleaned, so a fresh request is possible.
        assertNull(st.backgroundForOrSchedule(offScreen))
        assertEquals(1, bgQueue.size)
    }

    @Test
    fun trimDropsAnUndrawnSharpFrameWithoutRecyclingDrawnOnes() {
        val st = state(pageCount = 1)
        st.maxCachePx = 50.0 // past the cap
        st.updateCacheBand()
        st.requestSharpViewport()
        assertNotNull("the sharp frame is published by the synchronous test scheduler", st.sharpViewportBlit())
        val sharpSurfaces = factory.created.takeLast(2)

        st.trimMemoryCaches()

        assertNull("the frame is no longer published", st.sharpViewportBlit())
        assertFalse("a drawn frame is left to the renderer/GC, never recycled", sharpSurfaces.any { it.recycled })
    }

    @Test
    fun trimDoesNotRecyclePageCacheSurfaces() {
        val st = state()
        st.updateCacheBand()
        for (p in st.document.pages) st.cacheFor(p)
        val built = factory.created.toList()
        assertTrue(built.isNotEmpty())

        st.trimMemoryCaches()

        assertFalse("evicted page surfaces are not recycled", built.any { it.recycled })
    }
    @Test
    fun trimKeepsTheLinkedNeighbourDuringAPaginatedFlip() {
        val st = paginatedState()
        val current = st.document.pages[0]
        val neighbour = st.document.pages[1]
        val unrelated = st.document.pages.subList(2, 4)
        st.flipOffsetX = 40.0 // the adjacent row is being drawn with the current one
        for (p in st.document.pages) {
            st.cacheFor(p)
            st.backgroundFor(p)
        }
        assertNotNull(st.rawInkCache(neighbour))
        assertNotNull(st.rawBackgroundCache(neighbour))

        st.trimMemoryCaches()

        assertNotNull("the current page survives the trim", st.rawInkCache(current))
        assertNotNull("the drawn neighbour survives the trim", st.rawInkCache(neighbour))
        assertNotNull("and its background", st.rawBackgroundCache(neighbour))
        for (p in unrelated) {
            assertNull("unrelated pages are still evicted", st.rawInkCache(p))
            assertNull("and their backgrounds", st.rawBackgroundCache(p))
        }
    }

    @Test
    fun trimFallsBackToThePreviousBandWhenVisibleRangeIsNull() {
        val st = state(pageCount = 10, vh = 300)
        val inBand = st.document.pages[1]
        val far = st.document.pages[5]
        st.updateCacheBand()
        st.cacheFor(inBand)
        st.cacheFor(far)

        // Put the viewport wholly inside the gap between rows.
        st.viewportH = 20
        st.scrollY = 250.0
        assertNull("no page intersects this gap", st.visiblePageRange())

        st.trimMemoryCaches()

        assertNotNull("the previous band is a safe fallback", st.rawInkCache(inBand))
        assertNull("an unrelated page is still evicted", st.rawInkCache(far))
    }

    @Test
    fun trimWithNullVisibleRangeAndNoPreviousBandDoesNotCrash() {
        val st = state(pageCount = 2)
        val page = st.document.pages[1]
        st.cacheFor(page)
        st.viewportH = 20
        st.scrollY = 250.0
        assertNull(st.visiblePageRange())

        st.trimMemoryCaches() // bandKeep is still null: no fallback, no crash

        assertNull(st.rawInkCache(page))
    }

    @Test
    fun documentReplacementCannotRetainAnOldBandsPages() {
        val st = state(pageCount = 4, vh = 300)
        st.updateCacheBand()
        val oldPage = st.document.pages[1]
        st.cacheFor(oldPage)

        st.document = Document((0 until 4).mapTo(mutableListOf()) { Page(200.0, 200.0, pdfPage = it + 10) })
        st.invalidateAllCaches()
        st.relayout()
        assertNull("the old cache was cleared with the document", st.rawInkCache(oldPage))

        st.viewportH = 20
        st.scrollY = 250.0
        st.trimMemoryCaches()

        assertNull("a previous document's page is never retained by the fallback", st.rawInkCache(oldPage))
    }

}

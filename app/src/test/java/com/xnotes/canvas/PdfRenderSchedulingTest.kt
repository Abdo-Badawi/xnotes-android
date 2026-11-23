package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pdf.TextQuad
import com.xnotes.platform.CancelToken
import com.xnotes.platform.PdfPriority
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Phase 3 high-priority scheduling fixes at the [CanvasState] seam:
 *
 * - C1: a background PDF request carries a token that reaches the painter and can be cancelled.
 * - C2: a queued build whose page left the band or whose generation moved is skipped before any
 *   surface is allocated; pending bookkeeping is always cleaned up.
 * - C3: the visible page, prefetch, markup repair and sharp viewport run at their intended
 *   priorities.
 * - C4: ink-cache work and PDF background work can be queued on separate executors, so ink
 *   publishes while a PDF build is still blocked.
 */
class PdfRenderSchedulingTest {

    private class Paint(val page: Page, val priority: PdfPriority, val token: CancelToken?)

    private val inkQueue = ArrayDeque<() -> Unit>()
    private val bgQueue = ArrayDeque<() -> Unit>()
    private val paints = ArrayList<Paint>()
    private var cacheReady = 0
    private val factory = FakeSurfaceFactory()

    private fun state(pageCount: Int = 5, vw: Int = 800, vh: Int = 220): CanvasState {
        val pages = (0 until pageCount).mapTo(mutableListOf()) { Page(200.0, 200.0, pdfPage = it) }
        return CanvasState(Document(pages), factory, Palette.DEFAULT).apply {
            viewportW = vw
            viewportH = vh
            maxCachePx = 4096.0
            relayout()
            runAsync = { inkQueue.addLast(it) }
            runAsyncBackground = { bgQueue.addLast(it) }
            postToMain = { it() }
            onCacheReady = { cacheReady++ }
            paintPageBackground = { page, _, _, _, token, priority -> paints += Paint(page, priority, token) }
        }
    }

    private fun runBackground() {
        while (bgQueue.isNotEmpty()) bgQueue.removeFirst().invoke()
    }

    private fun runInk() {
        while (inkQueue.isNotEmpty()) inkQueue.removeFirst().invoke()
    }

    private fun markup() = TextMarkup(
        TextMarkup.newId(), MarkupType.HIGHLIGHT, Rgba(0, 230, 118), 0.5,
        listOf(TextQuad(10f, 20f, 40f, 26f, 0)), "x", null, 0L, 0L,
    )

    // --- C1: cancellation -------------------------------------------------

    @Test
    fun aVisibleBackgroundBuildCarriesAnUncancelledTokenAndPublishes() {
        val st = state()
        val page = st.document.pages[0]
        st.updateCacheBand()
        assertNull(st.backgroundForOrSchedule(page))
        assertEquals(1, bgQueue.size)
        runBackground()
        val paint = paints.single()
        assertEquals(page, paint.page)
        assertEquals(PdfPriority.VISIBLE_RENDER, paint.priority)
        assertNotNull(paint.token)
        assertFalse(paint.token!!.isCancelled)
        assertNotNull("the built layer is published", st.backgroundForOrSchedule(page))
        assertEquals(1, st.cacheSnapshot().bgPages)
    }

    @Test
    fun aPrefetchWhosePageLeftTheBandIsSkippedAndNeverPublished() {
        val st = state(pageCount = 6)
        val prefetched = st.document.pages[1]
        st.updateCacheBand()
        st.backgroundForOrSchedule(prefetched, PdfPriority.WARM)
        assertEquals(1, bgQueue.size)

        st.goToPage(5)
        st.updateCacheBand() // cancels the request for page 1
        runBackground()

        assertTrue("the obsolete page must not be painted", paints.isEmpty())
        assertEquals(0, st.cacheSnapshot().bgPages)
        // Its pending mark was cleaned up: asking again queues a fresh request.
        assertNull(st.backgroundForOrSchedule(prefetched, PdfPriority.WARM))
        assertEquals(1, bgQueue.size)
    }

    @Test
    fun aZoomInvalidationStopsAnInFlightBackgroundBeforeItPaints() {
        val st = state()
        val page = st.document.pages[0]
        st.updateCacheBand()
        st.backgroundForOrSchedule(page)
        st.invalidateCachesForZoom() // the zoom moved: this build is now obsolete
        runBackground()
        assertTrue("nothing may be painted for the discarded generation", paints.isEmpty())
        assertEquals(0, st.cacheSnapshot().bgPages)
        assertTrue("and it must not have allocated a surface", factory.created.isEmpty())
    }

    // --- C2: skipping obsolete queued work --------------------------------

    @Test
    fun anObsoleteInkBuildIsSkippedBeforeAllocating() {
        val st = state()
        val page = st.document.pages[0]
        st.updateCacheBand()
        st.cacheForOrSchedule(page) // queued
        assertEquals(1, inkQueue.size)
        st.invalidatePageGeometry() // generation moved before the build ran
        runInk()

        assertTrue("an obsolete ink build must not allocate a surface", factory.created.isEmpty())
        assertEquals(0, st.cacheSnapshot().inkPages)
        // Pending was cleared, so the next frame can schedule it again.
        assertNull(st.cacheForOrSchedule(page))
        assertEquals(1, inkQueue.size)
        runInk()
        assertEquals(1, st.cacheSnapshot().inkPages)
    }

    @Test
    fun anObsoleteBackgroundBuildIsSkippedBeforeAllocating() {
        val st = state()
        val page = st.document.pages[0]
        st.updateCacheBand()
        st.backgroundForOrSchedule(page)
        st.invalidateCachesForZoom()
        runBackground() // the queued build sees the moved generation and skips

        assertTrue(factory.created.isEmpty())
        assertEquals(0, st.cacheSnapshot().bgPages)
        assertNull(st.backgroundForOrSchedule(page)) // pending was cleaned
        assertEquals(1, bgQueue.size)
    }

    @Test
    fun aRejectedExecutorDoesNotLeaveThePagePending() {
        val st = state()
        val page = st.document.pages[0]
        st.updateCacheBand()
        st.runAsync = { throw IllegalStateException("queue closed") }
        runCatching { st.cacheForOrSchedule(page) }
        st.runAsync = { inkQueue.addLast(it) }
        assertNull("the page can be scheduled again", st.cacheForOrSchedule(page))
        assertEquals(1, inkQueue.size)
        runInk()
        assertEquals(1, st.cacheSnapshot().inkPages)
    }

    @Test
    fun aRejectedBackgroundExecutorDoesNotLeaveThePagePending() {
        val st = state()
        val page = st.document.pages[0]
        st.updateCacheBand()
        st.runAsyncBackground = { throw IllegalStateException("queue closed") }
        runCatching { st.backgroundForOrSchedule(page) }
        st.runAsyncBackground = { bgQueue.addLast(it) }
        assertNull("the page can be scheduled again", st.backgroundForOrSchedule(page))
        assertEquals(1, bgQueue.size)
    }

    @Test
    fun speculativePrefetchStopsWhenItsQueueIsAlreadyDeep() {
        val st = state(pageCount = 6)
        st.backgroundQueueDepth = { 99 }
        st.inkQueueDepth = { 99 }
        st.prefetchAndPrune()
        assertTrue("no speculative PDF work", bgQueue.isEmpty())
        assertTrue("no speculative ink work", inkQueue.isEmpty())
        assertTrue(paints.isEmpty())
    }

    // --- M2: input-aware deferral -----------------------------------------

    @Test
    fun inputStartCancelsSpeculativePrefetchButLeavesVisibleWorkAlone() {
        val st = state(pageCount = 6)
        val visible = st.document.pages[0]
        val prefetched = st.document.pages[1]
        st.updateCacheBand()
        st.backgroundForOrSchedule(visible) // the draw loop's visible build
        st.backgroundForOrSchedule(prefetched, PdfPriority.WARM) // speculative
        assertEquals(2, bgQueue.size)

        st.beginInput()
        runBackground()

        assertEquals("only the page on screen may paint", listOf(visible), paints.map { it.page })
        assertEquals(1, st.cacheSnapshot().bgPages)
        // The cancelled prefetch's pending mark was cleaned, so it can be queued again later.
        assertNull(st.backgroundForOrSchedule(prefetched, PdfPriority.WARM))
        assertEquals(1, bgQueue.size)
    }

    @Test
    fun prefetchIsSuspendedWhileAPointerIsDownAndResumesAfterItLifts() {
        val st = state(pageCount = 6)
        st.beginInput()
        st.prefetchAndPrune()
        assertTrue("no speculative PDF work while touching", bgQueue.isEmpty())
        assertTrue("no speculative ink work while touching", inkQueue.isEmpty())
        assertTrue(paints.isEmpty())

        st.endInput()
        st.prefetchAndPrune()
        assertTrue("prefetch resumes on the next frame after release", bgQueue.isNotEmpty())
    }

    @Test
    fun aPageThatBecomesVisiblePromotesItsPendingWarmBuild() {
        val st = state(pageCount = 6)
        val page = st.document.pages[0]
        st.updateCacheBand()
        st.backgroundForOrSchedule(page, PdfPriority.WARM) // speculative first
        assertEquals(1, bgQueue.size)

        // The draw loop now needs the same page. It must not wait for the WARM task.
        st.backgroundForOrSchedule(page, PdfPriority.VISIBLE_RENDER)
        assertEquals(2, bgQueue.size)
        runBackground()

        assertEquals(listOf(page), paints.map { it.page })
        assertEquals(PdfPriority.VISIBLE_RENDER, paints.single().priority)
        assertEquals(1, st.cacheSnapshot().bgPages)
    }

    @Test
    fun inputDoesNotCancelAMarkupRepair() {
        val st = state(pageCount = 1)
        val page = st.document.pages[0]
        st.backgroundFor(page) // synchronous first layer
        paints.clear()
        val m = markup()
        page.markups = listOf(m)
        st.rebakeBackground(page, listOf(m))
        st.beginInput()
        runBackground()
        assertEquals("background repair is not speculative prefetch", listOf(page), paints.map { it.page })
    }

    // --- C3: priorities ---------------------------------------------------

    @Test
    fun prefetchIsQueuedAtWarmBehindTheVisiblePage() {
        val st = state(pageCount = 6)
        val visiblePage = st.document.pages[0]
        val prefetchedPage = st.document.pages[1]
        st.backgroundForOrSchedule(visiblePage) // the draw loop's visible build
        st.prefetchAndPrune() // the same frame's speculative band

        assertEquals(2, bgQueue.size)
        runBackground()
        assertEquals(PdfPriority.VISIBLE_RENDER, paints.first { it.page === visiblePage }.priority)
        assertEquals(PdfPriority.WARM, paints.first { it.page === prefetchedPage }.priority)
    }

    @Test
    fun theSharpViewportRunsAtSharpPriority() {
        val st = state(pageCount = 1)
        st.maxCachePx = 100.0 // a 200 px page is past the cap at zoom 1
        st.updateCacheBand()
        st.requestSharpViewport()
        assertEquals(1, bgQueue.size)
        runBackground()
        assertEquals(PdfPriority.SHARP, paints.single().priority)
        assertNotNull(st.sharpViewportBlit())
    }

    @Test
    fun aMarkupRebakeRunsAtBackgroundRepairPriority() {
        val st = state(pageCount = 1)
        val page = st.document.pages[0]
        st.backgroundFor(page) // synchronous first layer
        paints.clear()
        val m = markup()
        page.markups = listOf(m)
        st.rebakeBackground(page, listOf(m))
        assertEquals(1, bgQueue.size)
        runBackground()
        assertEquals(PdfPriority.BG_REPAIR, paints.single().priority)
    }

    // --- C4: executor separation ------------------------------------------

    @Test
    fun inkRebuildPublishesWhileABackgroundBuildIsStillBlocked() {
        val st = state(pageCount = 1)
        val page = st.document.pages[0]
        st.updateCacheBand()
        st.backgroundForOrSchedule(page) // PDF worker: still queued / blocked
        st.cacheForOrSchedule(page) // ink worker
        assertEquals(1, bgQueue.size)
        assertEquals(1, inkQueue.size)

        runInk() // the ink worker finishes while the PDF build has not even started
        assertEquals(1, st.cacheSnapshot().inkPages)
        assertEquals("the PDF layer is still not published", 0, st.cacheSnapshot().bgPages)
        assertTrue("and the PDF worker still holds its task", paints.isEmpty())

        runBackground()
        assertEquals(1, st.cacheSnapshot().bgPages)
        assertEquals(1, st.cacheSnapshot().inkPages)
    }

    // --- Background-generation scope --------------------------------------

    @Test
    fun invalidatingOneBackgroundCancelsOnlyThatPagesRequest() {
        val st = state(pageCount = 2)
        val target = st.document.pages[0]
        val other = st.document.pages[1]
        st.updateCacheBand()
        st.backgroundForOrSchedule(target)
        st.backgroundForOrSchedule(other)
        assertEquals(2, bgQueue.size)

        st.invalidateBackground(target)
        runBackground()

        assertEquals("the cancelled page must not paint", listOf(other), paints.map { it.page })
        assertEquals("only the other page is published", 1, st.cacheSnapshot().bgPages)
        assertNull(st.rawBackgroundCache(target))
        assertNotNull(st.rawBackgroundCache(other))
        // Its pending mark was cleaned: a fresh request can be scheduled.
        assertNull(st.backgroundForOrSchedule(target))
        assertEquals(1, bgQueue.size)
    }

    @Test
    fun invalidatingAllBackgroundsCancelsEveryRequest() {
        val st = state(pageCount = 2)
        val a = st.document.pages[0]
        val b = st.document.pages[1]
        st.updateCacheBand()
        st.backgroundForOrSchedule(a)
        st.backgroundForOrSchedule(b)
        st.invalidateAllBackgrounds()
        runBackground()
        assertTrue(paints.isEmpty())
        assertEquals(0, st.cacheSnapshot().bgPages)
        assertNull(st.backgroundForOrSchedule(a)) // both pending marks were cleaned
        assertNull(st.backgroundForOrSchedule(b))
        assertEquals(2, bgQueue.size)
    }

    @Test
    fun anInkInvalidationLeavesPendingBackgroundWorkValid() {
        val st = state(pageCount = 1)
        val page = st.document.pages[0]
        st.updateCacheBand()
        st.backgroundForOrSchedule(page)
        st.refreshInk(emptyList()) // bumps only the ink generation
        runBackground()
        assertEquals(listOf(page), paints.map { it.page })
        assertEquals(1, st.cacheSnapshot().bgPages)
    }

    @Test
    fun anInkPageInvalidationLeavesPendingBackgroundWorkValid() {
        val st = state(pageCount = 1)
        val page = st.document.pages[0]
        st.updateCacheBand()
        st.backgroundForOrSchedule(page)
        st.invalidatePage(page) // ink-only
        runBackground()
        assertEquals(listOf(page), paints.map { it.page })
        assertEquals(1, st.cacheSnapshot().bgPages)
    }

    @Test
    fun aMarkupEditCancelsOnlyTheTargetsWholeLayerBuild() {
        val st = state(pageCount = 2)
        val target = st.document.pages[0]
        val other = st.document.pages[1]
        st.updateCacheBand()
        st.backgroundForOrSchedule(target) // a whole-layer build that read no markups yet
        st.backgroundForOrSchedule(other)
        val m = markup()
        target.markups = listOf(m)
        st.rebakeBackground(target, listOf(m)) // no old layer to rebake: cancels the stale build
        runBackground()

        assertEquals("the stale target build is gone; the other page still renders", listOf(other), paints.map { it.page })
        assertNull(st.rawBackgroundCache(target))
        assertNotNull(st.rawBackgroundCache(other))

        // The next draw rebuilds the target from the edited markups.
        st.backgroundForOrSchedule(target)
        runBackground()
        assertEquals(target, paints.last().page)
        assertEquals(2, st.cacheSnapshot().bgPages)
    }

    @Test
    fun relayoutCancelsPendingBackgroundWork() {
        val st = state(pageCount = 1)
        val page = st.document.pages[0]
        st.updateCacheBand()
        st.backgroundForOrSchedule(page)
        st.relayout()
        runBackground()
        assertTrue(paints.isEmpty())
        assertEquals(0, st.cacheSnapshot().bgPages)
    }
}

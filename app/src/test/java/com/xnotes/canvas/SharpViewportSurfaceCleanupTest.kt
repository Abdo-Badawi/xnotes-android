package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.platform.CancelToken
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the sharp viewport's surface ownership: every base/ink surface it allocates is either
 * published into [CanvasState.sharpViewportBlit] or recycled, never left for GC. Partial allocation
 * and cancellation are covered because `requestSharpViewport` allocates the two layers separately.
 */
class SharpViewportSurfaceCleanupTest {

    private val queued = ArrayDeque<() -> Unit>()
    private val factory = FakeSurfaceFactory()

    private fun state(
        surfaceFactory: SurfaceFactory = factory,
        hook: (page: Page, res: Double, cancel: CancelToken?) -> Unit = { _, _, _ -> },
    ): CanvasState {
        val page = Page(200.0, 200.0, pdfPage = 0)
        return CanvasState(Document(mutableListOf(page)), surfaceFactory, Palette.DEFAULT).apply {
            viewportW = 800
            viewportH = 1000
            maxCachePx = 100.0 // a 200 px page is past the cap at zoom 1
            relayout()
            runAsyncBackground = { queued.addLast(it) }
            postToMain = { it() }
            paintPageBackground = { p, _, res, _, cancel, _ -> hook(p, res, cancel) }
        }
    }

    private fun runPending() {
        while (queued.isNotEmpty()) queued.removeFirst().invoke()
    }

    private fun surfaces() = factory.created

    @Test
    fun cancellationBeforeAllocationCreatesNothing() {
        val st = state()
        st.requestSharpViewport()
        st.clearSharpViewport() // cancels the queued request
        runPending()
        assertTrue(surfaces().isEmpty())
        assertNull(st.sharpViewportBlit())
    }

    @Test
    fun cancellationDuringRenderingRecyclesBothSurfaces() {
        lateinit var st: CanvasState
        st = state(hook = { _, _, cancel ->
            cancel?.cancel() // the page render observes the view request being dropped
        })
        st.requestSharpViewport()
        runPending()

        assertEquals(2, surfaces().size)
        assertTrue("base must be released", surfaces()[0].recycled)
        assertTrue("ink must be released", surfaces()[1].recycled)
        assertNull(st.sharpViewportBlit())
    }

    @Test
    fun cancellationAfterBothAllocationsButBeforePublicationRecyclesBoth() {
        lateinit var st: CanvasState
        st = state(hook = { _, _, _ -> st.invalidatePaper() }) // bumps sharpGen and cancels the frame
        st.requestSharpViewport()
        runPending()

        assertEquals(2, surfaces().size)
        assertTrue(surfaces()[0].recycled)
        assertTrue(surfaces()[1].recycled)
        assertNull(st.sharpViewportBlit())
    }

    @Test
    fun aStaleViewRecyclesBothSurfacesEvenWithoutCancellation() {
        lateinit var st: CanvasState
        st = state(hook = { _, _, _ -> st.zoom = 2.0 }) // the view moved while the frame painted
        st.requestSharpViewport()
        runPending()

        assertEquals(2, surfaces().size)
        assertTrue(surfaces()[0].recycled)
        assertTrue(surfaces()[1].recycled)
        assertNull(st.sharpViewportBlit())
    }

    @Test
    fun cancellationAfterBaseAllocationRecyclesTheOnlySurfaceItMade() {
        lateinit var st: CanvasState
        val cancelOnInk = object : SurfaceFactory {
            val created = mutableListOf<FakeRasterSurface>()
            override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double): RasterSurface {
                if (created.isNotEmpty()) {
                    st.clearSharpViewport() // cancels the request between the two allocations
                    error("ink allocation cancelled")
                }
                return FakeRasterSurface(widthPx, heightPx, devicePixelRatio).also { created += it }
            }
        }
        st = state(surfaceFactory = cancelOnInk)
        st.requestSharpViewport()
        runCatching { runPending() }

        assertEquals(1, cancelOnInk.created.size)
        assertTrue("the base allocated before the cancel must be released", cancelOnInk.created.single().recycled)
        assertNull(st.sharpViewportBlit())
    }

    @Test
    fun partialAllocationRecyclesTheOnlySurfaceItMade() {
        val partial = object : SurfaceFactory {
            val created = mutableListOf<FakeRasterSurface>()
            override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double): RasterSurface {
                if (created.size == 1) error("ink allocation failed")
                return FakeRasterSurface(widthPx, heightPx, devicePixelRatio).also { created += it }
            }
        }
        val st = state(surfaceFactory = partial)
        st.requestSharpViewport()
        runCatching { runPending() }

        assertEquals(1, partial.created.size)
        assertTrue("the allocated base must be released", partial.created.single().recycled)
        assertNull(st.sharpViewportBlit())
    }

    @Test
    fun aFullInkRefreshCancelsAndReleasesAnInFlightSharpRequest() {
        val st = state()
        st.requestSharpViewport()
        st.refreshInk(emptyList()) // bumps sharpGen and cancels the in-flight frame
        runPending()
        assertTrue(surfaces().isEmpty())
        assertNull(st.sharpViewportBlit())
    }

    @Test
    fun successfulPublicationTransfersOwnershipAndDoesNotRecycle() {
        val st = state(hook = { _, _, _ -> })
        st.requestSharpViewport()
        runPending()

        assertEquals(2, surfaces().size)
        assertFalse("the published base belongs to the frame", surfaces()[0].recycled)
        assertFalse("the published ink belongs to the frame", surfaces()[1].recycled)
        assertNotNull(st.sharpViewportBlit())
    }

    // --- Published-frame lifecycle -----------------------------------------

    @Test
    fun replacingANeverDrawnPublishedFrameRecyclesTheOldSurfaces() {
        val st = state()
        st.requestSharpViewport()
        runPending()
        assertEquals(2, surfaces().size)

        st.requestSharpViewport() // replaces the frame before any Canvas saw it
        runPending()

        assertEquals(4, surfaces().size)
        assertTrue("old base recycled", surfaces()[0].recycled)
        assertTrue("old ink recycled", surfaces()[1].recycled)
        assertFalse("new base kept", surfaces()[2].recycled)
        assertFalse("new ink kept", surfaces()[3].recycled)
        assertNotNull(st.sharpViewportBlit())
    }

    @Test
    fun replacingADrawnPublishedFrameKeepsTheOldSurfacesForTheRenderer() {
        val st = state()
        st.requestSharpViewport()
        runPending()
        val first = st.sharpViewportBlit()!! // a Canvas may still hold these bitmaps

        st.requestSharpViewport()
        runPending()

        assertEquals(4, surfaces().size)
        assertFalse("a drawn base must not be recycled", surfaces()[0].recycled)
        assertFalse("a drawn ink must not be recycled", surfaces()[1].recycled)
        assertFalse(surfaces()[2].recycled)
        assertFalse(surfaces()[3].recycled)
        assertNotNull(st.sharpViewportBlit())
        assertNotSame(first.base, st.sharpViewportBlit()?.base)
    }

    @Test
    fun clearingANeverDrawnPublishedFrameRecyclesIt() {
        val st = state()
        st.requestSharpViewport()
        runPending()
        st.clearSharpViewport()
        assertTrue(surfaces()[0].recycled)
        assertTrue(surfaces()[1].recycled)
        assertNull(st.sharpViewportBlit())
    }

    @Test
    fun clearingADrawnPublishedFrameLeavesItForTheRenderer() {
        val st = state()
        st.requestSharpViewport()
        runPending()
        st.sharpViewportBlit()!! // drawn
        st.clearSharpViewport()
        assertFalse(surfaces()[0].recycled)
        assertFalse(surfaces()[1].recycled)
        assertNull(st.sharpViewportBlit())
    }

    @Test
    fun invalidatingANeverDrawnPublishedFrameRecyclesIt() {
        val st = state()
        st.requestSharpViewport()
        runPending()
        st.invalidatePaper()
        assertTrue(surfaces()[0].recycled)
        assertTrue(surfaces()[1].recycled)
        assertNull(st.sharpViewportBlit())
    }

    @Test
    fun invalidatingADrawnPublishedFrameLeavesItForTheRenderer() {
        val st = state()
        st.requestSharpViewport()
        runPending()
        st.sharpViewportBlit()!! // drawn
        st.invalidatePaper()
        assertFalse(surfaces()[0].recycled)
        assertFalse(surfaces()[1].recycled)
        assertNull(st.sharpViewportBlit())
    }

    @Test
    fun clearingTwiceRecyclesAnUnseenFrameOnlyOnce() {
        val counting = CountingFactory()
        val st = state(surfaceFactory = counting)
        st.requestSharpViewport()
        runPending()
        st.clearSharpViewport()
        st.clearSharpViewport()
        assertEquals(listOf(1, 1), counting.created.map { it.recycles })
    }

    private class CountingSurface(
        override val width: Int,
        override val height: Int,
        override val devicePixelRatio: Double = 1.0,
    ) : RasterSurface {
        val painter = FakeRenderer()
        var recycles = 0
        override fun fill(color: Rgba) {}
        override fun renderer(): Renderer = painter
        override fun recycle() { recycles++ }
    }

    private class CountingFactory : SurfaceFactory {
        val created = mutableListOf<CountingSurface>()
        override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double): RasterSurface =
            CountingSurface(widthPx, heightPx, devicePixelRatio).also { created += it }
    }
}

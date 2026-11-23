package com.xnotes.canvas

import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Pt
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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin

/**
 * The live wet surface must land on the same device pixel grid the sharp viewport uses, so its
 * filtered blit cannot soften an edge that later becomes a razor-sharp committed stroke. These
 * tests pin the transform, not a particular rasterizer: a bitmap test would need Android graphics,
 * which the JVM suite deliberately does not load.
 */
class LiveInkParityTest {

    private val factory = FakeSurfaceFactory()

    private fun state(page: Page = Page(400.0, 600.0)): CanvasState =
        CanvasState(Document(mutableListOf(page)), factory, Palette.DEFAULT).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
            scrollX = 37.3
            scrollY = 11.7
        }

    private fun liveStroke(count: Int = 140, spacing: Double = 0.7, curve: Double = 10.0): Stroke {
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN))
        stroke.finished = false
        for (i in 0 until count) {
            stroke.addSample(Sample(60.0 + i * spacing, 80.0 + sin(i * 0.17) * curve, 0.65, i * 4.0))
        }
        return stroke
    }

    private fun assertOnDeviceGrid(dest: Rect, grid: Pt, res: Double, what: String) {
        val l = (dest.left - grid.x) * res
        val t = (dest.top - grid.y) * res
        val r = (dest.right - grid.x) * res
        val b = (dest.bottom - grid.y) * res
        for ((name, v) in listOf("left" to l, "top" to t, "right" to r, "bottom" to b)) {
            assertEquals("$what: $name is off the device grid", round(v).toDouble(), v, 1e-6)
        }
    }

    @Test
    fun liveInkBlitLandsOnTheDevicePixelGridAtEveryZoom() {
        val st = state()
        st.maxCachePx = 100.0 // the sharp layer is active at every zoom in this test
        val stroke = liveStroke()
        for (zoom in listOf(1.0, 2.0, 4.0)) {
            st.zoom = zoom
            val screen = FakeRenderer()
            st.paintLiveStroke(screen, stroke, 0)
            val grid = st.liveInkGrid(0)
            assertTrue("a grid is available at $zoom", grid != null)
            val dest = screen.exactRasterDests.lastOrNull()
            assertTrue("the live surface used the exact blit at $zoom", dest != null)
            assertOnDeviceGrid(dest!!, grid!!, zoom, "zoom $zoom")
            val surface = factory.created.last()
            assertEquals("live raster width is one device pixel per surface pixel at $zoom",
                surface.width.toDouble(), dest.w * zoom, 1e-6)
            assertEquals(surface.height.toDouble(), dest.h * zoom, 1e-6)
        }
    }

    @Test
    fun belowTheCacheCapKeepsTheExistingFilteredBlit() {
        val st = state()
        st.maxCachePx = 4096.0
        st.zoom = 1.0
        val screen = FakeRenderer()
        st.paintLiveStroke(screen, liveStroke(), 0)
        assertFalse("this view is deliberately below the sharp cap", st.isPastResolutionCap())
        assertTrue("the filtered path changed below the cap", screen.exactRasterDests.isEmpty())
        assertTrue("the live stroke was not blitted at all", screen.rasterDests.isNotEmpty())
    }

    @Test
    fun deepZoomAboveTheCacheCapKeepsTheLiveGrid() {
        val st = state()
        st.maxCachePx = 100.0 // page long edge * zoom is well past the cap
        st.zoom = 6.0
        val screen = FakeRenderer()
        st.paintLiveStroke(screen, liveStroke(), 0)
        val grid = st.liveInkGrid(0)!!
        assertOnDeviceGrid(screen.exactRasterDests.single(), grid, st.zoom, "deep zoom")
        assertTrue("the sharp layer is the one being matched", st.isPastResolutionCap())
    }

    @Test
    fun growingTheLiveSurfaceStaysOnTheGrid() {
        val st = state()
        st.zoom = 4.0
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN))
        stroke.finished = false
        val grid = st.liveInkGrid(0)!!
        var blits = 0
        for (i in 0 until 900) {
            stroke.addSample(Sample(20.0 + i * 1.1, 200.0 + sin(i * 0.05) * 40.0, 0.6, i * 5.0))
            val screen = FakeRenderer()
            st.paintLiveStroke(screen, stroke, 0)
            for (dest in screen.exactRasterDests) {
                assertOnDeviceGrid(dest, grid, st.zoom, "growth frame $i")
                blits++
            }
        }
        assertTrue("the stroke never produced a live blit", blits > 0)
        assertTrue("the live surface never grew", factory.created.size > 1)
    }

    @Test
    fun anUnavailableDeviceGridKeepsTheExistingFilteredBlit() {
        val st = state()
        st.rotationDeg = 90
        st.relayout()
        val screen = FakeRenderer()
        st.paintLiveStroke(screen, liveStroke(), 0)
        assertTrue("a rotated view has no upright device grid", st.liveInkGrid(0) == null)
        assertTrue("the exact blit was used without a grid", screen.exactRasterDests.isEmpty())
        assertTrue("the ordinary filtered blit was skipped", screen.rasterDests.isNotEmpty())
    }

    @Test
    fun wholeStrokeAndLiveRunPaintTheSameRibbonAndSmoothing() {
        // Sparse, gently curved samples make RibbonSmoothing subdivide. Both the live run painter
        // and the whole-stroke painter must hand the same ribbon to the same smoothing path.
        val stroke = liveStroke(count = 14, spacing = 34.0, curve = 18.0)
        val ribbon = stroke.wetRibbon!!
        val live = FakeRenderer()
        stroke.paintRun(live, ribbon, 0, ribbon.pointCount, 0.0)
        val committedShape = FakeRenderer()
        stroke.paint(committedShape)
        assertEquals("live and committed emit different smoothed polygon counts",
            committedShape.polygonFills, live.polygonFills)
        assertEquals("live and committed emit different smoothed disc counts",
            committedShape.circleFills, live.circleFills)
        assertTrue("the sparse curve was not subdivided", live.polygonFills > ribbon.pointCount)
    }

    @Test
    fun straightLinesAndVariableWidthSurviveTheLivePath() {
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN))
        stroke.finished = false
        for (i in 0 until 120) {
            stroke.addSample(Sample(20.0 + i * 2.0, 150.0, 0.2 + 0.8 * i / 119.0, i * 4.0))
        }
        val ribbon = stroke.wetRibbon!!
        for (i in 0 until ribbon.pointCount) {
            assertTrue("a straight line bent at $i", abs(ribbon.cy(i) - 150.0) < 1e-6)
        }
        val widths = (0 until ribbon.pointCount).map { ribbon.hw(it) }
        assertTrue("pressure varying width was flattened", widths.max() > widths.min() + 0.2)
        assertFalse("the live ribbon disappeared", ribbon.pointCount < 100)
    }
}

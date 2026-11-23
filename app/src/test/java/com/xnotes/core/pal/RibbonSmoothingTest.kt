package com.xnotes.core.pal

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Render-time refinement is adaptive: it must not add work to dense or straight runs. */
class RibbonSmoothingTest {

    private class Recorded {
        val points = ArrayList<Triple<Double, Double, Double>>()
        val segments = ArrayList<DoubleArray>()
    }

    private fun smooth(centers: FloatArray, radii: FloatArray, from: Int = 0, count: Int = centers.size / 2): Recorded {
        val out = Recorded()
        RibbonSmoothing.forEach(
            centers, radii, from, count,
            point = { x, y, r -> out.points += Triple(x, y, r) },
            segment = { x0, y0, r0, x1, y1, r1 -> out.segments += doubleArrayOf(x0, y0, r0, x1, y1, r1) },
        )
        return out
    }

    private fun centers(vararg xy: Double): FloatArray = FloatArray(xy.size) { xy[it].toFloat() }

    private fun radii(vararg r: Double): FloatArray = FloatArray(r.size) { r[it].toFloat() }

    private fun circle(count: Int, radius: Double = 100.0): Pair<FloatArray, FloatArray> {
        val c = FloatArray(count * 2)
        for (i in 0 until count) {
            val a = 2.0 * Math.PI * i / count
            c[2 * i] = (Math.cos(a) * radius).toFloat()
            c[2 * i + 1] = (Math.sin(a) * radius).toFloat()
        }
        return c to FloatArray(count) { 1f }
    }

    @Test
    fun aStraightRunKeepsItsOriginalSegments() {
        val out = smooth(centers(0.0, 0.0, 10.0, 0.0, 20.0, 0.0), radii(1.0, 1.0, 1.0))
        assertEquals(2, out.segments.size)
        for (s in out.segments) {
            assertEquals(0.0, s[1], 1e-6)
            assertEquals(0.0, s[4], 1e-6)
        }
    }

    @Test
    fun verticalAndDiagonalRunsAlsoStayStraight() {
        for (path in listOf(
            centers(0.0, 0.0, 0.0, 10.0, 0.0, 20.0),
            centers(0.0, 0.0, 10.0, 10.0, 20.0, 20.0),
        )) {
            val out = smooth(path, radii(1.0, 1.0, 1.0))
            assertEquals(2, out.segments.size)
            val dx = (path[4] - path[0]).toDouble()
            val dy = (path[5] - path[1]).toDouble()
            for (s in out.segments) {
                assertEquals(0.0, (s[3] - s[0]) * dy - (s[4] - s[1]) * dx, 1e-5)
            }
        }
    }

    @Test
    fun aSparseCircleIsSubdividedAndStaysNearTheCircle() {
        val (c, r) = circle(8)
        val out = smooth(c, r)
        assertTrue("sparse curve must gain intermediate points", out.segments.size > 7)
        for (p in out.points) {
            val radius = hypot(p.first, p.second)
            assertTrue("interpolation must not leave the curve: $radius", abs(radius - 100.0) < 4.0)
        }
        for (s in out.segments) {
            assertTrue("a subdivided chord should be short", hypot(s[3] - s[0], s[4] - s[1]) < 30.0)
        }
    }

    @Test
    fun aDenseCircleAddsNoSuperfluousSegments() {
        val (c, r) = circle(64)
        val out = smooth(c, r)
        assertEquals("dense input is already smooth", 63, out.segments.size)
    }

    @Test
    fun aSharpCornerIsNotRoundedAway() {
        val out = smooth(
            centers(0.0, 0.0, 20.0, 0.0, 20.0, 20.0, 40.0, 20.0),
            radii(1.0, 1.0, 1.0, 1.0),
        )
        assertEquals("a right-angle corner keeps its straight legs", 3, out.segments.size)
    }

    @Test
    fun duplicateAndZeroLengthSamplesAreSafe() {
        val out = smooth(
            centers(0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 10.0, 0.0),
            radii(1.0, 1.0, 1.0, 1.0),
        )
        for (s in out.segments) for (v in s) assertTrue(v.isFinite())
    }

    @Test
    fun radiiAreInterpolatedAcrossASubdividedCurve() {
        val out = smooth(
            centers(0.0, 0.0, 10.0, 0.0, 20.0, 8.0, 30.0, 20.0),
            radii(1.0, 3.0, 5.0, 7.0),
        )
        assertTrue(out.segments.size > 3)
        for (s in out.segments) {
            assertTrue(s[2] >= 1.0 && s[2] <= 7.0)
            assertTrue(s[5] >= 1.0 && s[5] <= 7.0)
        }
    }

    @Test
    fun aSubrangeStaysInsideItsSourceRange() {
        val out = smooth(
            centers(0.0, 0.0, 10.0, 4.0, 20.0, 0.0, 30.0, 4.0, 40.0, 0.0),
            radii(1.0, 1.0, 1.0, 1.0, 1.0),
            from = 1,
            count = 3,
        )
        assertTrue(out.segments.isNotEmpty())
        for (s in out.segments) {
            assertTrue(s[0] >= 10.0 - 1e-6 && s[0] <= 30.0 + 1e-6)
            assertTrue(s[3] >= 10.0 - 1e-6 && s[3] <= 30.0 + 1e-6)
        }
    }
}

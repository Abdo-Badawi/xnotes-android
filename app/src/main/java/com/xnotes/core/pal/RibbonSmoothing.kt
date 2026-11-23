package com.xnotes.core.pal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Render-time refinement of a swept-disc ribbon. The stored stroke keeps one point per accepted
 * sample; this fills only visibly sparse segments with points from a centripetal Catmull-Rom curve
 * through the neighbouring centres. It changes no stored sample, width rule or hit geometry.
 *
 * Straight runs are left exactly as they are, as are segments touching a sharp turn (over 75° per
 * sample). A sparse, gently curved run is subdivided adaptively by both turn angle and chord
 * length, capped so a burst of missing input cannot flood a frame. Radii are interpolated linearly
 * between the two source discs; a zero-length segment is emitted unchanged.
 */
object RibbonSmoothing {
    private const val EPS = 1e-6
    private const val MAX_STEPS = 16
    private val MAX_ANGLE_PER_STEP = Math.toRadians(8.0)
    private const val MAX_CHORD_PER_STEP = 16.0
    private val SHARP_TURN = Math.toRadians(75.0)

    /**
     * Walks the ribbon segments without allocating. [point] is called for every emitted centre
     * (used by the PDF/default renderer to fill end discs and joins); [segment] is called for every
     * emitted capsule. Android's path renderer can pass a no-op point callback because a capsule
     * already includes both rims.
     */
    inline fun forEach(
        centers: FloatArray,
        radii: FloatArray,
        from: Int,
        count: Int,
        crossinline point: (Double, Double, Double) -> Unit,
        crossinline segment: (Double, Double, Double, Double, Double, Double) -> Unit,
    ) {
        if (count <= 0) return
        if (count == 1) {
            point(centers[2 * from].toDouble(), centers[2 * from + 1].toDouble(), radii[from].toDouble())
            return
        }
        val last = from + count - 1
        for (i in from until last) {
            val x1 = centers[2 * i].toDouble()
            val y1 = centers[2 * i + 1].toDouble()
            val x2 = centers[2 * i + 2].toDouble()
            val y2 = centers[2 * i + 3].toDouble()
            val r1 = radii[i].toDouble()
            val r2 = radii[i + 1].toDouble()
            val x0 = if (i > from) centers[2 * i - 2].toDouble() else x1
            val y0 = if (i > from) centers[2 * i - 1].toDouble() else y1
            val x3 = if (i + 2 <= last) centers[2 * i + 4].toDouble() else x2
            val y3 = if (i + 2 <= last) centers[2 * i + 5].toDouble() else y2
            val dx = x2 - x1
            val dy = y2 - y1
            val len = hypot(dx, dy)
            val steps = steps(x0, y0, x1, y1, x2, y2, x3, y3, len)
            if (steps <= 1) {
                point(x1, y1, r1)
                point(x2, y2, r2)
                segment(x1, y1, r1, x2, y2, r2)
                continue
            }
            val t0 = 0.0
            val t1 = t0 + sqrt(hypot(x1 - x0, y1 - y0))
            val t2 = t1 + sqrt(len)
            val t3 = t2 + sqrt(hypot(x3 - x2, y3 - y2))
            // Keep the curve inside the four source centres' box. Catmull-Rom can bulge a little
            // around a tight but not-quite-corner bend; clamping prevents the derived ink escaping
            // the stored stroke's bounds (and the highlighter's bounds-sized cache).
            val minX = minOf(x0, x1, x2, x3)
            val maxX = maxOf(x0, x1, x2, x3)
            val minY = minOf(y0, y1, y2, y3)
            val maxY = maxOf(y0, y1, y2, y3)
            var px = x1
            var py = y1
            var pr = r1
            point(px, py, pr)
            for (s in 1..steps) {
                val u = s.toDouble() / steps
                val knot = t1 + (t2 - t1) * u
                val x = cubic(x0, x1, x2, x3, t0, t1, t2, t3, knot).coerceIn(minX, maxX)
                val y = cubic(y0, y1, y2, y3, t0, t1, t2, t3, knot).coerceIn(minY, maxY)
                val rr = r1 + (r2 - r1) * u
                point(x, y, rr)
                segment(px, py, pr, x, y, rr)
                px = x
                py = y
                pr = rr
            }
        }
    }

    @PublishedApi
    internal fun steps(
        x0: Double, y0: Double, x1: Double, y1: Double,
        x2: Double, y2: Double, x3: Double, y3: Double, len: Double,
    ): Int {
        if (len <= EPS) return 1
        val turn1 = turn(x0, y0, x1, y1, x2, y2)
        val turn2 = turn(x1, y1, x2, y2, x3, y3)
        if (turn1 >= SHARP_TURN || turn2 >= SHARP_TURN) return 1
        if (collinear(x0, y0, x1, y1, x2, y2, x3, y3)) return 1
        val byAngle = ceil(maxOf(turn1, turn2) / MAX_ANGLE_PER_STEP).toInt()
        val byLength = ceil(len / MAX_CHORD_PER_STEP).toInt()
        return maxOf(1, byAngle, byLength).coerceAtMost(MAX_STEPS)
    }

    /** Turn at ([x1], [y1]) between the incoming and outgoing chords; zero for a degenerate leg. */
    private fun turn(x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val ax = x1 - x0
        val ay = y1 - y0
        val bx = x2 - x1
        val by = y2 - y1
        val la = hypot(ax, ay)
        val lb = hypot(bx, by)
        if (la <= EPS || lb <= EPS) return 0.0
        return kotlin.math.atan2(abs(ax * by - ay * bx), ax * bx + ay * by)
    }

    private fun collinear(
        x0: Double, y0: Double, x1: Double, y1: Double,
        x2: Double, y2: Double, x3: Double, y3: Double,
    ): Boolean {
        fun cross(ax: Double, ay: Double, bx: Double, by: Double): Double = abs(ax * by - ay * bx)
        val aX = x1 - x0
        val aY = y1 - y0
        val bX = x2 - x1
        val bY = y2 - y1
        val cX = x3 - x2
        val cY = y3 - y2
        val ab = cross(aX, aY, bX, bY) <= EPS * (hypot(aX, aY) * hypot(bX, bY) + 1.0)
        val bc = cross(bX, bY, cX, cY) <= EPS * (hypot(bX, bY) * hypot(cX, cY) + 1.0)
        return ab && bc
    }

    /**
     * One coordinate of a centripetal Catmull-Rom at [u], with [t0]..[t3] the knot sequence.
     * Degenerate knots (duplicate samples) fall back to the nearest control point.
     */
    @PublishedApi
    internal fun cubic(
        p0: Double, p1: Double, p2: Double, p3: Double,
        t0: Double, t1: Double, t2: Double, t3: Double, u: Double,
    ): Double {
        val a1 = lerp(p0, p1, t0, t1, u)
        val a2 = lerp(p1, p2, t1, t2, u)
        val a3 = lerp(p2, p3, t2, t3, u)
        val b1 = lerp(a1, a2, t0, t2, u)
        val b2 = lerp(a2, a3, t1, t3, u)
        return lerp(b1, b2, t1, t2, u)
    }

    private fun lerp(a: Double, b: Double, ta: Double, tb: Double, u: Double): Double {
        val span = tb - ta
        if (span <= EPS) return b
        // The Barry-Goldman pyramid extrapolates beyond the nearest pair at the ends of the
        // centre segment; clamping here would flatten the curve into the source points.
        return a + (b - a) * ((u - ta) / span)
    }
}

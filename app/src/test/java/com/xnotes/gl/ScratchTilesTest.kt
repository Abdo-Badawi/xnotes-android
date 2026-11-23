package com.xnotes.gl

import com.xnotes.core.infinite.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wet pad's bounded scratch is fed by non-overlapping whole-pixel regions that tile the damage. */
class ScratchTilesTest {

    private fun tiles(box: PixelRect, w: Int, h: Int): List<PixelRect> {
        val out = ArrayList<PixelRect>()
        ScratchTiles.forEach(box, w, h) { out += PixelRect(it.left, it.top, it.right, it.bottom) }
        return out
    }

    @Test
    fun aSmallBoxIsOneRegion() {
        val out = tiles(PixelRect(10, 20, 13, 24), 1024, 1024)
        assertEquals(1, out.size)
        assertEquals(PixelRect(10, 20, 13, 24).toString(), out[0].toString())
    }

    @Test
    fun anOversizedBoxIsCutIntoBoundedRegionsThatTileItExactly() {
        val box = PixelRect(-120, 30, 2500, 2100)
        val out = tiles(box, 1024, 1024)
        assertTrue(out.isNotEmpty())
        var area = 0L
        for (t in out) {
            assertTrue("${t.width}x${t.height} exceeds the scratch", t.width <= 1024 && t.height <= 1024)
            assertTrue(!t.isEmpty)
            area += t.width.toLong() * t.height
        }
        assertEquals(box.width.toLong() * box.height, area)
        // No overlap: pairwise rectangles may only share an edge.
        for (i in out.indices) for (j in i + 1 until out.size) {
            val a = out[i]
            val b = out[j]
            val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            assertTrue("regions $i and $j overlap", overlapW <= 0 || overlapH <= 0)
        }
        // Every region is inside the damage and they all add up to it.
        for (t in out) {
            assertTrue(t.left >= box.left && t.top >= box.top)
            assertTrue(t.right <= box.right && t.bottom <= box.bottom)
        }
    }

    @Test
    fun aZeroOrEmptyBoxDrawsNothing() {
        assertTrue(tiles(PixelRect(5, 5, 5, 9), 1024, 1024).isEmpty())
        assertTrue(tiles(PixelRect(5, 5, 9, 5), 1024, 1024).isEmpty())
        assertTrue(tiles(PixelRect(5, 5, 9, 9), 0, 1024).isEmpty())
        assertTrue(tiles(PixelRect(5, 5, 9, 9), 1024, 0).isEmpty())
    }

    @Test
    fun jaggedRemaindersStayInsideTheRequestedBox() {
        val box = PixelRect(3, 7, 1028, 1032)
        val out = tiles(box, 1024, 1024)
        assertEquals(4, out.size)
        for (t in out) {
            assertTrue(t.left >= box.left && t.top >= box.top)
            assertTrue(t.right <= box.right && t.bottom <= box.bottom)
        }
    }
}

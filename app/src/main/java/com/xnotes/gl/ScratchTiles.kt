package com.xnotes.gl

import com.xnotes.core.infinite.PixelRect
import kotlin.math.min

/**
 * Cuts one damaged box into non-overlapping regions no larger than a scratch target.
 *
 * The wet pad draws each present into a bounded multisampled buffer, so a damage box bigger than
 * that buffer has to be worked through in several passes. Every emitted region is a whole-pixel
 * rectangle, the regions tile [box] exactly, and none is empty, so a caller can clear, draw and
 * blit each one without moving a pixel or leaving a seam.
 */
object ScratchTiles {

    inline fun forEach(box: PixelRect, maxW: Int, maxH: Int, emit: (PixelRect) -> Unit) {
        if (box.isEmpty || maxW <= 0 || maxH <= 0) return
        var y = box.top
        while (y < box.bottom) {
            val h = min(maxH, box.bottom - y)
            var x = box.left
            while (x < box.right) {
                val w = min(maxW, box.right - x)
                emit(PixelRect(x, y, x + w, y + h))
                x += w
            }
            y += h
        }
    }
}

package com.xnotes.canvas

import android.view.MotionEvent
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.InputCustomization
import com.xnotes.core.tools.InputFunction
import com.xnotes.core.tools.InputGestureLatch

/**
 * Turns the Input Customization recordings into a rewritten MotionEvent.
 *
 * Only pointers the platform reports as [MotionEvent.TOOL_TYPE_FINGER] are touched: a pointer
 * classified as Write becomes a stylus, Erase an eraser, and Pan stays a finger. Real stylus and
 * eraser pointers pass through exactly as the platform reported them. Historical samples are
 * replayed with [MotionEvent.addBatch] so a fast stroke never loses its intermediate points.
 */
class InputTouchRewriter {

    private val latch = InputGestureLatch()

    fun reset() = latch.clear()

    /**
     * Classifies new FINGER pointers and returns a rewritten event only when a tool type actually
     * changes. The callbacks report side effects the canvases need: the alt-write colour for this
     * touch, whether the pointer that just landed is a Pan, and whether that Pan is a multi-finger
     * one that must keep panning from the centroid. When nothing is latched for a pointer it keeps
     * the platform's tool type.
     */
    fun rewrite(
        event: MotionEvent,
        customization: InputCustomization,
        onAltColor: (Rgba?) -> Unit,
        onPan: (Boolean) -> Unit,
        onMultiPan: (Boolean) -> Unit,
        classifySize: Float? = null,
    ): MotionEvent? {
        if (!customization.enabled) {
            latch.clear()
            onPan(false)
            onMultiPan(false)
            return null
        }
        val masked = event.actionMasked
        val down = masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_POINTER_DOWN
        if (down) {
            // A pointer keeps the function it was first given; only a pointer seen for the first
            // time is classified. On POINTER_DOWN the earlier fingers are already latched (or were
            // already treated as plain fingers), so this never reclassifies a live pointer.
            val size = classifySize ?: gestureSize(event)
            for (i in 0 until event.pointerCount) {
                if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
                val id = event.getPointerId(i)
                if (latch.functionFor(id) != null) continue
                customization.match(event.pointerCount, size)?.let { match ->
                    latch.latch(id, match.function)
                }
            }
            val index = if (masked == MotionEvent.ACTION_DOWN) 0 else event.actionIndex
            val function = latch.functionFor(event.getPointerId(index))
            val finger = event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
            onAltColor(if (finger && function == InputFunction.ALT_WRITE) customization.altColor else null)
            // The pointer that just landed decides the gesture: a recorded Pan should pan even if
            // finger-draw would otherwise ink, and a multi-finger Pan keeps panning from the
            // centroid rather than pinching.
            val pan = finger && function == InputFunction.PAN
            onPan(pan)
            onMultiPan(pan && event.pointerCount >= 2)
        }

        // Build the replacement only if a latched finger's tool type must change.
        var changed = false
        val properties = Array(event.pointerCount) { MotionEvent.PointerProperties() }
        val coords = Array(event.pointerCount) { MotionEvent.PointerCoords() }
        for (i in 0 until event.pointerCount) {
            event.getPointerProperties(i, properties[i])
            event.getPointerCoords(i, coords[i])
            if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
            val desired = when (latch.functionFor(event.getPointerId(i))) {
                InputFunction.WRITE, InputFunction.ALT_WRITE -> MotionEvent.TOOL_TYPE_STYLUS
                InputFunction.ERASE -> MotionEvent.TOOL_TYPE_ERASER
                InputFunction.PAN, null -> MotionEvent.TOOL_TYPE_FINGER
            }
            if (desired != properties[i].toolType) {
                properties[i].toolType = desired
                changed = true
            }
        }
        val out = if (changed) build(event, properties, coords) else null

        when (masked) {
            MotionEvent.ACTION_POINTER_UP -> latch.release(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                latch.clear()
                onPan(false)
                onMultiPan(false)
            }
        }
        return out
    }

    /**
     * The contact size the recording rules use: the largest touchMinor (or touchMajor where minor
     * is absent) across the fingers in this event. The recorder built its samples the same way, so
     * a multi-finger gesture is judged by its biggest contact, not by whichever finger happened to
     * be the action pointer.
     */
    private fun gestureSize(event: MotionEvent): Float = contactSize(event)

    companion object {
        /**
         * The contact size the recording rules use: the largest touchMinor (or touchMajor where
         * minor is absent) across the fingers in this event.
         */
        fun contactSize(event: MotionEvent): Float {
            var size = 0f
            for (i in 0 until event.pointerCount) {
                if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
                val minor = event.getTouchMinor(i)
                size = maxOf(size, if (minor > 0f) minor else event.getTouchMajor(i))
            }
            return size
        }
    }

    @Suppress("DEPRECATION") // the historical size/tool/axis getters have no non-deprecated twin
    private fun build(
        event: MotionEvent,
        properties: Array<MotionEvent.PointerProperties>,
        coords: Array<MotionEvent.PointerCoords>,
    ): MotionEvent {
        val out = MotionEvent.obtain(
            event.downTime, event.eventTime, event.action, event.pointerCount,
            properties, coords, event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags,
            event.source, event.flags,
        )
        // MotionEvent.obtain(...) starts with no history; replay every batched sample so ink keeps
        // its full resolution instead of being resampled into chords.
        for (h in 0 until event.historySize) {
            val batch = Array(event.pointerCount) { i ->
                MotionEvent.PointerCoords().also { c ->
                    c.x = event.getHistoricalX(i, h)
                    c.y = event.getHistoricalY(i, h)
                    c.pressure = event.getHistoricalPressure(i, h)
                    c.size = event.getHistoricalSize(i, h)
                    c.touchMajor = event.getHistoricalTouchMajor(i, h)
                    c.touchMinor = event.getHistoricalTouchMinor(i, h)
                    c.toolMajor = event.getHistoricalToolMajor(i, h)
                    c.toolMinor = event.getHistoricalToolMinor(i, h)
                    c.orientation = event.getHistoricalOrientation(i, h)
                }
            }
            out.addBatch(event.getHistoricalEventTime(h), batch, event.metaState)
        }
        return out
    }
}

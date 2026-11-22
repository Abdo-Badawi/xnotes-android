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
 *
 * [forceFirstPointerFunction] lets the caller start a provisional stroke (Write) before the
 * contact size has settled, and later replay the held events with the function classification
 * finally resolved. It applies only to the first pointer, only when that pointer is not already
 * latched, so a live pointer is never reclassified twice.
 */
class InputTouchRewriter {

    private val latch = InputGestureLatch()

    // Scratch arrays reused across events: MotionEvent.obtain and addBatch copy their contents
    // into the event, so the arrays are free to be refilled for the next one. Sized generously
    // (Android devices report far fewer pointers); an unusually wide event falls back to fresh
    // arrays below.
    private val props = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
    private val coords = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }
    private val batchCoords = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }

    fun reset() = latch.clear()

    /**
     * Classifies new FINGER pointers and returns a rewritten event only when a tool type actually
     * changes. The callbacks report side effects the canvases need: the alt-write colour for this
     * touch, whether the pointer that just landed is a Pan, and whether that Pan is a multi-finger
     * one that must keep panning from the centroid. When nothing is latched for a pointer it keeps
     * the platform's tool type.
     *
     * [forceFirstPointerFunction] is the provisional/authoritative function for the first pointer
     * when no recorded signature matches at down time. It is used only for the pointer at index 0
     * and only while that pointer is unlatched.
     */
    fun rewrite(
        event: MotionEvent,
        customization: InputCustomization,
        onAltColor: (Rgba?) -> Unit,
        onPan: (Boolean) -> Unit,
        onMultiPan: (Boolean) -> Unit,
        classifySize: Float? = null,
        forceFirstPointerFunction: InputFunction? = null,
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
            val force = forceFirstPointerFunction
            for (i in 0 until event.pointerCount) {
                if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
                val id = event.getPointerId(i)
                if (latch.functionFor(id) != null) continue
                if (i == 0 && force != null) {
                    // Provisional Write (or the replayed authoritative function). It overrides a
                    // one-finger signature the settled size may now match: a gesture already
                    // resolved to a multi-finger Pan must not have its first finger relatched as
                    // Write, or the pan would be swallowed by the stroke it started.
                    latch.latch(id, force)
                } else {
                    customization.match(event.pointerCount, size)?.let { latch.latch(id, it.function) }
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

        // Build the replacement only if a latched finger's tool type must change. The scan itself
        // allocates nothing, so the common Pan path (or a real stylus) pays no per-event garbage.
        var changed = false
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
            if (desiredToolType(latch.functionFor(event.getPointerId(i))) != event.getToolType(i)) {
                changed = true
                break
            }
        }
        val out = if (changed) build(event) else null

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

    private fun desiredToolType(function: InputFunction?): Int = when (function) {
        InputFunction.WRITE, InputFunction.ALT_WRITE -> MotionEvent.TOOL_TYPE_STYLUS
        InputFunction.ERASE -> MotionEvent.TOOL_TYPE_ERASER
        InputFunction.PAN, null -> MotionEvent.TOOL_TYPE_FINGER
    }

    /**
     * The contact size the recording rules use: the largest touchMinor (or touchMajor where minor
     * is absent) across the fingers in this event. The recorder built its samples the same way, so
     * a multi-finger gesture is judged by its biggest contact, not by whichever finger happened to
     * be the action pointer.
     */
    private fun gestureSize(event: MotionEvent): Float = contactSize(event)

    companion object {
        /** Arrays are reused for anything up to this many pointers; wider events allocate. */
        private const val MAX_POINTERS = 20

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
    private fun build(event: MotionEvent): MotionEvent {
        val n = event.pointerCount
        val p = if (n <= props.size) props else Array(n) { MotionEvent.PointerProperties() }
        val c = if (n <= coords.size) coords else Array(n) { MotionEvent.PointerCoords() }
        for (i in 0 until n) {
            event.getPointerProperties(i, p[i])
            event.getPointerCoords(i, c[i])
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) {
                p[i].toolType = desiredToolType(latch.functionFor(event.getPointerId(i)))
            }
        }
        // obtain() copies the arrays into the event it returns, so the scratch arrays can be
        // refilled by the next call (and the historical batch below) without touching this one.
        val out = MotionEvent.obtain(
            event.downTime, event.eventTime, event.action, n,
            p, c, event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags,
            event.source, event.flags,
        )
        // MotionEvent.obtain(...) starts with no history; replay every batched sample so ink keeps
        // its full resolution instead of being resampled into chords.
        val b = if (n <= batchCoords.size) batchCoords else Array(n) { MotionEvent.PointerCoords() }
        for (h in 0 until event.historySize) {
            for (i in 0 until n) {
                val hc = b[i]
                hc.x = event.getHistoricalX(i, h)
                hc.y = event.getHistoricalY(i, h)
                hc.pressure = event.getHistoricalPressure(i, h)
                hc.size = event.getHistoricalSize(i, h)
                hc.touchMajor = event.getHistoricalTouchMajor(i, h)
                hc.touchMinor = event.getHistoricalTouchMinor(i, h)
                hc.toolMajor = event.getHistoricalToolMajor(i, h)
                hc.toolMinor = event.getHistoricalToolMinor(i, h)
                hc.orientation = event.getHistoricalOrientation(i, h)
            }
            out.addBatch(event.getHistoricalEventTime(h), b, event.metaState)
        }
        return out
    }
}

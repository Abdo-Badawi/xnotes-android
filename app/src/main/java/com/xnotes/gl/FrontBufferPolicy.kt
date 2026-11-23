package com.xnotes.gl

/**
 * Chooses between the low-latency front-buffer path and the ordinary CanvasView path.
 *
 * The front buffer is only useful when the platform can also deliver stylus samples at the
 * driver's cadence (`requestUnbufferedDispatch`, API 30+). Without that, the pad can add a
 * composition step while input is still batched to the view frame. The EGL capability is checked
 * separately because a device can expose the API without a mutable-render-buffer config.
 *
 * Pure so the decision can be tested on the JVM. The caller reads the actual device capabilities;
 * this object does not inspect the model, manufacturer or Build version.
 */
object FrontBufferPolicy {
    fun useFrontBuffer(
        userDisabled: Boolean,
        unbufferedDispatchSupported: Boolean,
        mutableRenderBufferSupported: Boolean?,
    ): Boolean = !userDisabled && unbufferedDispatchSupported && mutableRenderBufferSupported != false
}

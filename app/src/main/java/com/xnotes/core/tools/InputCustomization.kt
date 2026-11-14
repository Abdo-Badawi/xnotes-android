package com.xnotes.core.tools

import com.xnotes.core.model.Rgba
import org.json.JSONObject

/**
 * The functions an interactive board's touches can be sorted into. The board reports every pointer
 * as [android.view.MotionEvent.TOOL_TYPE_FINGER]; the user records what each function's contact
 * looks like on this device so the app can tell them apart.
 */
enum class InputFunction(val id: String) {
    WRITE("write"),
    PAN("pan"),
    ERASE("erase"),
    ALT_WRITE("alt_write");

    companion object {
        fun fromId(id: String?): InputFunction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What one function's touch looked like when the user recorded it: the contact-size range the
 * platform reported (touchMinor, or touchMajor where minor is unsupported) and the pointer count.
 * [openMax] gives the eraser its palm rule: everything at or above [minSize] matches, whatever
 * the count, while the pointer count still has to be the recorded one.
 */
data class TouchSignature(
    val minSize: Float,
    val maxSize: Float,
    val pointerCount: Int,
    val openMax: Boolean = false,
) {
    fun contains(size: Float): Boolean = size >= minSize && (openMax || size <= maxSize)

    /** 0 inside the range, else how far outside it [size] sits. */
    fun distanceTo(size: Float): Float = when {
        size < minSize -> minSize - size
        !openMax && size > maxSize -> size - maxSize
        else -> 0f
    }

    companion object {
        fun fromJson(o: JSONObject?): TouchSignature? {
            if (o == null || o.isNull("min")) return null
            val open = o.optBoolean("open", false)
            val min = o.optDouble("min", Double.NaN)
            if (min.isNaN()) return null
            val max = if (open) Float.MAX_VALUE else o.optDouble("max", min).toFloat()
            val count = o.optInt("count", 1).coerceAtLeast(1)
            return TouchSignature(min.toFloat(), max, count, open)
        }
    }
}

fun TouchSignature.toJson(): JSONObject = JSONObject()
    .put("min", minSize.toDouble())
    .put("max", maxSize.toDouble())
    .put("count", pointerCount)
    .put("open", openMax)

/** One function's closest signature for a touch, and how far the touch sits outside it. */
data class InputMatch(val function: InputFunction, val signature: TouchSignature, val distance: Float)

/**
 * Per-device touch recordings. Nothing here is a built-in threshold: with no signatures the
 * matcher returns null for every touch, so the feature does nothing until the user records.
 */
data class InputCustomization(
    val enabled: Boolean = false,
    val write: TouchSignature? = null,
    val pan: TouchSignature? = null,
    val erase: TouchSignature? = null,
    val altWrite: TouchSignature? = null,
    val altColor: Rgba = InkPalette.DEFAULT,
) {
    fun signatureFor(function: InputFunction): TouchSignature? = when (function) {
        InputFunction.WRITE -> write
        InputFunction.PAN -> pan
        InputFunction.ERASE -> erase
        InputFunction.ALT_WRITE -> altWrite
    }

    fun withSignature(function: InputFunction, signature: TouchSignature?): InputCustomization = when (function) {
        InputFunction.WRITE -> copy(write = signature)
        InputFunction.PAN -> copy(pan = signature)
        InputFunction.ERASE -> copy(erase = signature)
        InputFunction.ALT_WRITE -> copy(altWrite = signature)
    }

    val hasAnySignature: Boolean get() = write != null || pan != null || erase != null || altWrite != null

    /** The closest function whose recording accepts this touch, or null when nothing matches. */
    fun match(pointerCount: Int, size: Float): InputMatch? {
        if (!enabled) return null
        var best: InputMatch? = null
        for (function in InputFunction.entries) {
            val signature = signatureFor(function) ?: continue
            // Count and size together identify a touch: the same contact at a different finger
            // count is a different gesture, eraser included.
            if (signature.pointerCount != pointerCount) continue
            if (!signature.contains(size)) continue
            val match = InputMatch(function, signature, signature.distanceTo(size))
            if (best == null || match.distance < best.distance) best = match
        }
        return best
    }

    /**
     * Whether [candidate] would be ambiguous against anything already recorded for another
     * function, so the recorder can warn and ask for a re-record.
     */
    fun overlaps(candidate: TouchSignature, forFunction: InputFunction): Boolean =
        InputFunction.entries.any { other ->
            if (other == forFunction) return@any false
            val existing = signatureFor(other) ?: return@any false
            signaturesOverlap(candidate, existing, forFunction, other)
        }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("alt_color", Rgba.toHex(altColor))
        .put("write", write?.toJson() ?: JSONObject.NULL)
        .put("pan", pan?.toJson() ?: JSONObject.NULL)
        .put("erase", erase?.toJson() ?: JSONObject.NULL)
        .put("alt_write", altWrite?.toJson() ?: JSONObject.NULL)

    companion object {
        /** Relative safety margin around a recorded range; a fraction, never a device size. */
        const val MARGIN_FRACTION = 0.10f

        /**
         * Whether two recordings describe the same touch. Every function, the eraser included,
         * needs the same pointer count and a size range that intersects; the eraser's open upper
         * bound only widens its range.
         */
        fun signaturesOverlap(
            a: TouchSignature,
            b: TouchSignature,
            aFunction: InputFunction,
            bFunction: InputFunction,
        ): Boolean {
            if (a.pointerCount != b.pointerCount) return false
            val lo = maxOf(a.minSize, b.minSize)
            val hiA = if (a.openMax) Float.MAX_VALUE else a.maxSize
            val hiB = if (b.openMax) Float.MAX_VALUE else b.maxSize
            return lo <= minOf(hiA, hiB)
        }

        fun fromJson(o: JSONObject?): InputCustomization {
            if (o == null) return InputCustomization()
            return InputCustomization(
                enabled = o.optBoolean("enabled", false),
                write = TouchSignature.fromJson(o.optJSONObject("write")),
                pan = TouchSignature.fromJson(o.optJSONObject("pan")),
                erase = TouchSignature.fromJson(o.optJSONObject("erase")),
                altWrite = TouchSignature.fromJson(o.optJSONObject("alt_write")),
                altColor = Rgba.fromHex(o.optString("alt_color")) ?: InkPalette.DEFAULT,
            )
        }
    }
}

/** Builds one range out of the contacts the user repeated while recording. */
object InputSignatureBuilder {
    /** How many repeat touches the recorder asks for. */
    const val REQUIRED_SAMPLES = 3

    data class Sample(val size: Float, val pointerCount: Int)

    fun build(samples: List<Sample>, openMax: Boolean): TouchSignature? {
        if (samples.isEmpty()) return null
        val lo = samples.minOf { it.size }
        val hi = samples.maxOf { it.size }
        val margin = maxOf(lo, hi) * InputCustomization.MARGIN_FRACTION
        // The count a repeated gesture usually came in; ties keep the first-seen.
        val count = samples.groupingBy { it.pointerCount }.eachCount()
            .maxByOrNull { it.value }?.key ?: 1
        return TouchSignature(
            minSize = (lo - margin).coerceAtLeast(0f),
            maxSize = if (openMax) Float.MAX_VALUE else hi + margin,
            pointerCount = count.coerceAtLeast(1),
            openMax = openMax,
        )
    }
}

/**
 * Remembers which function each pointer was classified as, from its down until its up, so a
 * gesture is never re-classified while it is in flight.
 */
class InputGestureLatch {
    private val latched = HashMap<Int, InputFunction>()

    fun functionFor(pointerId: Int): InputFunction? = latched[pointerId]

    /** Keeps the first classification for a pointer: a live pointer never changes mid-gesture. */
    fun latch(pointerId: Int, function: InputFunction) {
        if (!latched.containsKey(pointerId)) latched[pointerId] = function
    }

    fun release(pointerId: Int) {
        latched.remove(pointerId)
    }

    fun clear() = latched.clear()

    val isEmpty: Boolean get() = latched.isEmpty()
}

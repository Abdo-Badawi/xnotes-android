package com.xnotes.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure matching, overlap and latching rules behind Input Customization. */
class InputCustomizationTest {

    private fun signature(min: Float, max: Float, count: Int = 1, open: Boolean = false) =
        TouchSignature(min, max, count, open)

    @Test
    fun overlapNeedsTheSamePointerCount() {
        assertTrue(
            InputCustomization.signaturesOverlap(
                signature(10f, 20f, 1), signature(15f, 25f, 1),
                InputFunction.WRITE, InputFunction.ALT_WRITE,
            ),
        )
        assertFalse(
            InputCustomization.signaturesOverlap(
                signature(10f, 20f, 1), signature(15f, 25f, 2),
                InputFunction.WRITE, InputFunction.PAN,
            ),
        )
    }

    @Test
    fun disjointRangesDoNotOverlap() {
        assertFalse(
            InputCustomization.signaturesOverlap(
                signature(10f, 20f, 1), signature(21f, 30f, 1),
                InputFunction.WRITE, InputFunction.ALT_WRITE,
            ),
        )
    }

    @Test
    fun palmIsOpenEndedSoAnythingLargerOverlaps() {
        val palm = signature(50f, Float.MAX_VALUE, 1, open = true)
        assertTrue(
            InputCustomization.signaturesOverlap(
                palm, signature(80f, 120f, 1),
                InputFunction.ERASE, InputFunction.WRITE,
            ),
        )
        assertFalse(
            InputCustomization.signaturesOverlap(
                palm, signature(10f, 20f, 1),
                InputFunction.ERASE, InputFunction.WRITE,
            ),
        )
        // Different pointer counts never overlap, even with open upper bound.
        assertFalse(
            InputCustomization.signaturesOverlap(
                signature(50f, Float.MAX_VALUE, 2, open = true), signature(80f, 120f, 1),
                InputFunction.ERASE, InputFunction.WRITE,
            ),
        )
    }

    @Test
    fun eraseMatchesItsSizeAndEverythingLarger() {
        val custom = InputCustomization(enabled = true, erase = signature(50f, 0f, 1, open = true))
        assertEquals(InputFunction.ERASE, custom.match(1, 50f)?.function)
        assertEquals(InputFunction.ERASE, custom.match(1, 500f)?.function)
        assertNull(custom.match(1, 49f))
        // Erase requires exact pointer count.
        assertNull(custom.match(2, 500f))
    }

    @Test
    fun aDisabledFeatureNeverMatches() {
        val custom = InputCustomization(enabled = false, write = signature(10f, 20f))
        assertNull(custom.match(1, 15f))
    }

    @Test
    fun noRecordingsNeverMatch() {
        assertNull(InputCustomization(enabled = true).match(1, 15f))
    }

    @Test
    fun pointerCountIsPartOfTheSignature() {
        val custom = InputCustomization(
            enabled = true,
            write = signature(10f, 20f, 1),
            pan = signature(10f, 20f, 2),
        )
        assertEquals(InputFunction.WRITE, custom.match(1, 15f)?.function)
        assertEquals(InputFunction.PAN, custom.match(2, 15f)?.function)
    }

    @Test
    fun closestSignatureWinsWhenRangesOverlap() {
        val custom = InputCustomization(
            enabled = true,
            write = signature(10f, 30f, 1),
            altWrite = signature(24f, 40f, 1),
        )
        assertEquals(InputFunction.WRITE, custom.match(1, 15f)?.function)
        assertEquals(InputFunction.ALT_WRITE, custom.match(1, 35f)?.function)
    }

    @Test
    fun latchKeepsTheFirstFunctionUntilReleased() {
        val latch = InputGestureLatch()
        latch.latch(3, InputFunction.WRITE)
        // A second classification mid-gesture must not swap a live pointer.
        latch.latch(3, InputFunction.ERASE)
        assertEquals(InputFunction.WRITE, latch.functionFor(3))
        latch.release(3)
        assertNull(latch.functionFor(3))
    }

    @Test
    fun latchTracksEachPointerSeparatelyThroughDownAndUpOrder() {
        val latch = InputGestureLatch()
        latch.latch(0, InputFunction.WRITE)
        latch.latch(1, InputFunction.PAN)
        assertEquals(InputFunction.WRITE, latch.functionFor(0))
        assertEquals(InputFunction.PAN, latch.functionFor(1))
        latch.release(0)
        assertNull(latch.functionFor(0))
        assertEquals(InputFunction.PAN, latch.functionFor(1))
        latch.clear()
        assertTrue(latch.isEmpty)
    }

    @Test
    fun builderAddsAMarginAndKeepsTheCommonPointerCount() {
        val built = InputSignatureBuilder.build(
            listOf(
                InputSignatureBuilder.Sample(100f, 2),
                InputSignatureBuilder.Sample(120f, 2),
                InputSignatureBuilder.Sample(110f, 2),
            ),
            openMax = false,
        )!!
        assertTrue(built.minSize < 100f)
        assertTrue(built.maxSize > 120f)
        assertEquals(2, built.pointerCount)
        assertFalse(built.openMax)
    }

    @Test
    fun builderLeavesThePalmUpperBoundOpen() {
        val built = InputSignatureBuilder.build(
            listOf(
                InputSignatureBuilder.Sample(200f, 1),
                InputSignatureBuilder.Sample(210f, 1),
            ),
            openMax = true,
        )!!
        assertEquals(Float.MAX_VALUE, built.maxSize, 0f)
        assertTrue(built.openMax)
    }

    @Test
    fun eraseRequiresExactPointerCount() {
        // Erase recorded with 2 fingers should not match 1 finger, even at large size.
        val custom = InputCustomization(enabled = true, erase = signature(50f, 0f, 2, open = true))
        assertNull(custom.match(1, 500f))
        assertEquals(InputFunction.ERASE, custom.match(2, 500f)?.function)
    }

    @Test
    fun sizeAndCountTogetherDistinguishGestures() {
        val custom = InputCustomization(
            enabled = true,
            write = signature(20f, 30f, 1),
            pan = signature(20f, 30f, 2),
        )
        // Same size, different counts -> different functions.
        assertEquals(InputFunction.WRITE, custom.match(1, 25f)?.function)
        assertEquals(InputFunction.PAN, custom.match(2, 25f)?.function)
        // A count not recorded yields no match.
        assertNull(custom.match(3, 25f))
    }

    @Test
    fun jsonRoundTripKeepsEverySignature() {
        val original = InputCustomization(
            enabled = true,
            write = signature(10f, 20f, 1),
            pan = signature(30f, 40f, 3),
            erase = signature(50f, Float.MAX_VALUE, 2, open = true),
            altWrite = signature(11f, 19f, 1),
        )
        val loaded = InputCustomization.fromJson(original.toJson())
        assertEquals(original, loaded)
    }
}

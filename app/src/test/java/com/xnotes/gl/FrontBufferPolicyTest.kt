package com.xnotes.gl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontBufferPolicyTest {
    @Test fun theUserDisableAlwaysWins() {
        assertFalse(FrontBufferPolicy.useFrontBuffer(true, true, true))
        assertFalse(FrontBufferPolicy.useFrontBuffer(true, true, null))
    }

    @Test fun frontBufferingNeedsDriverCadenceInput() {
        assertFalse(FrontBufferPolicy.useFrontBuffer(false, false, true))
    }

    @Test fun anUnavailableMutableBufferFallsBack() {
        assertFalse(FrontBufferPolicy.useFrontBuffer(false, true, false))
    }

    @Test fun unknownEglCapabilityDefersToThePad() {
        assertTrue(FrontBufferPolicy.useFrontBuffer(false, true, null))
    }

    @Test fun aFullyCapableDeviceUsesTheFrontBuffer() {
        assertTrue(FrontBufferPolicy.useFrontBuffer(false, true, true))
    }
}

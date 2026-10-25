package com.simplerc.app.webrtc
import org.junit.Assert.*
import org.junit.Test
class DecodedFrameWitnessTest {
    @Test fun onlyRealDecodedFramesAdvanceTheReceipt() {
        val w=DecodedFrameWitness();w.frame(1080,2400);w.frame(1080,2400)
        assertEquals(DecodedFrameWitness.Reading(2,1080,2400),w.read())
    }
    @Test fun invalidDimensionsCannotInventProgress() {
        val w=DecodedFrameWitness();w.frame(0,0);w.frame(-1,200)
        assertEquals(0L,w.read().frames)
    }
    @Test fun resetAndRotationCannotKeepOldDimensions() {
        val w=DecodedFrameWitness();w.frame(1080,2400);w.reset();w.frame(2400,1080)
        assertEquals(DecodedFrameWitness.Reading(1,2400,1080),w.read())
    }
}

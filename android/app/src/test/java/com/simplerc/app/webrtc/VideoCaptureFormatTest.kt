package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class VideoCaptureFormatTest {
    @Test fun lowBandwidthReducesCapturedPixelsWithoutChangingCoordinateAspect() {
        val full=VideoCaptureFormat.forScreen(1080,2400,1280,1.0,30)
        val small=VideoCaptureFormat.forScreen(1080,2400,1280,3.0,20)
        assertEquals(576,full.width);assertEquals(1280,full.height)
        assertEquals(192,small.width);assertEquals(426,small.height)
        assertTrue(small.width*small.height<full.width*full.height/8)
        assertEquals(full.width.toDouble()/full.height,small.width.toDouble()/small.height,0.01)
        assertEquals(20,small.fps)
    }
    @Test fun rotationSwapsDimensionsAndReturningToFullSizeIsReversible() {
        val portrait=VideoCaptureFormat.forScreen(1080,2400,1280,1.5,30)
        val landscape=VideoCaptureFormat.forScreen(2400,1080,1280,1.5,30)
        assertEquals(portrait.width,landscape.height);assertEquals(portrait.height,landscape.width)
        assertEquals(0,portrait.width%2);assertEquals(0,portrait.height%2)
        assertEquals(1280,VideoCaptureFormat.forScreen(1080,2400,1280,1.0,60).height)
    }
}

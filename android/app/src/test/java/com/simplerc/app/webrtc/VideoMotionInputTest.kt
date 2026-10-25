package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class VideoMotionInputTest {
    @Test fun stationaryHoldAndHeartbeatsCannotCreateMotion() {
        val p=VideoMotionInput();p.begin(100.0,200.0)
        repeat(200){assertFalse(p.moved(100.0,200.0,it*40L))}
    }
    @Test fun jitterBelowTwoRemotePixelsCannotCreateMotion() {
        val p=VideoMotionInput();p.begin(100.0,200.0)
        repeat(50){assertFalse(p.moved(100.5,200.5,it*20L))}
    }
    @Test fun gradualMovementAccumulatesUntilThreshold() {
        val p=VideoMotionInput();p.begin(0.0,0.0)
        assertFalse(p.moved(1.0,0.0,1000));assertTrue(p.moved(2.0,0.0,1020))
    }
    @Test fun fastMotionIsBoundedWithoutLosingAccumulatedDisplacement() {
        val p=VideoMotionInput();p.begin(0.0,0.0)
        assertTrue(p.moved(10.0,0.0,1000));assertFalse(p.moved(20.0,0.0,1005))
        assertTrue(p.moved(20.0,0.0,1016))
    }
    @Test fun newGestureResetsCoordinatesAndThrottle() {
        val p=VideoMotionInput();p.begin(0.0,0.0);assertTrue(p.moved(10.0,0.0,1000))
        p.begin(500.0,500.0);assertFalse(p.moved(500.0,500.0,1001))
        assertTrue(p.moved(505.0,500.0,1002))
    }
}

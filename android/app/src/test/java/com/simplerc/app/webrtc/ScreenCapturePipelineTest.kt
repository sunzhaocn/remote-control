package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class ScreenCapturePipelineTest {
    private class Driver {
        val starts = mutableListOf<VideoCaptureFormat>()
        val resizes = mutableListOf<VideoCaptureFormat>()
        val outputs = mutableListOf<VideoCaptureFormat>()
        var failResize = false
        val pipeline = ScreenCapturePipeline(
            { w, h, fps -> starts += VideoCaptureFormat(w, h, fps) },
            { w, h, fps ->
                if (failResize) throw IllegalStateException("display failed")
                resizes += VideoCaptureFormat(w, h, fps)
            },
            { w, h, fps -> outputs += VideoCaptureFormat(w, h, fps) },
        )
    }

    @Test fun scrollingCongestionAndRecoveryNeverResizeTheSystemMirror() {
        val driver = Driver()
        repeat(4) {
            for (scale in listOf(1.0, 1.5, 2.0, 3.0, 2.0, 1.0)) {
                driver.pipeline.apply(1080, 2400, 1280, scale, if (scale == 3.0) 20 else 30)
            }
        }
        assertEquals(listOf(VideoCaptureFormat(576, 1280, 30)), driver.starts)
        assertTrue(driver.resizes.isEmpty())
        assertTrue(driver.outputs.contains(VideoCaptureFormat(192, 426, 20)))
        assertEquals(VideoCaptureFormat(576, 1280, 30), driver.outputs.last())
    }

    @Test fun rotationResizesOnceAndQualityChangesDoNotCompoundTheScale() {
        val driver = Driver()
        driver.pipeline.apply(1080, 2400, 1280, 3.0, 20)
        driver.pipeline.apply(2400, 1080, 1280, 3.0, 20)
        driver.pipeline.apply(2400, 1080, 1280, 1.5, 30)
        driver.pipeline.apply(2400, 1080, 1280, 1.0, 60)
        driver.pipeline.apply(1080, 2400, 1280, 1.0, 60)
        assertEquals(listOf(VideoCaptureFormat(576, 1280, 20)), driver.starts)
        assertEquals(listOf(VideoCaptureFormat(1280, 576, 20), VideoCaptureFormat(576, 1280, 60)), driver.resizes)
        assertEquals(VideoCaptureFormat(576, 1280, 60), driver.outputs.last())
    }

    @Test fun identicalUpdatesDoNotTouchTheCaptureOrOutput() {
        val driver = Driver()
        repeat(10) { driver.pipeline.apply(720, 1280, 1280, 1.0, 30) }
        assertEquals(1, driver.starts.size)
        assertEquals(1, driver.outputs.size)
        assertTrue(driver.resizes.isEmpty())
        driver.pipeline.apply(720, 1280, 1280, 1.0, 60)
        assertEquals(2, driver.outputs.size)
        assertTrue(driver.resizes.isEmpty())
    }

    @Test fun failedRotationIsNotCachedAsApplied() {
        val driver = Driver()
        driver.pipeline.apply(1080, 2400, 1280, 1.0, 30)
        driver.failResize = true
        assertThrows(IllegalStateException::class.java) { driver.pipeline.apply(2400, 1080, 1280, 2.0, 30) }
        assertEquals(1, driver.outputs.size)
        driver.failResize = false
        driver.pipeline.apply(2400, 1080, 1280, 2.0, 30)
        assertEquals(listOf(VideoCaptureFormat(1280, 576, 30)), driver.resizes)
        assertEquals(VideoCaptureFormat(640, 288, 30), driver.outputs.last())
    }
}

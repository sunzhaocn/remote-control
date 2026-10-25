package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.CapturerObserver
import org.webrtc.RtpParameters
import org.webrtc.VideoFrame

class NativeResolutionPipelineTest {
    private fun parameters(): RtpParameters =
        RtpParameters::class.java.declaredConstructors.single().apply { isAccessible = true }
            .newInstance("tx", RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE, null,
                emptyList<Any>(), listOf(RtpParameters.Encoding(null, true, 3.0)), emptyList<Any>()) as RtpParameters

    @Test fun compatibilityModePreservesLibraryBitrateWhileRemovingHiddenScaling() {
        val parameters = parameters()
        val encoding = parameters.encodings.single()
        encoding.minBitrateBps = 100_000
        encoding.maxBitrateBps = 2_000_000
        val plan = ScreenContentVideoPlan.create(1440, 3200, ScreenActivity.STILL, 6.0, 30, 150_000)
        assertTrue(VideoSenderProfile.apply(parameters, plan, tunedBitrate = false))
        assertEquals(100_000, encoding.minBitrateBps)
        assertEquals(2_000_000, encoding.maxBitrateBps)
        assertEquals(1.0, encoding.scaleResolutionDownBy!!, 0.0)
        assertEquals(30, encoding.maxFramerate)
        assertEquals(RtpParameters.DegradationPreference.DISABLED, parameters.degradationPreference)
    }

    @Test fun compatibilityModeKeepsUnsetLibraryBitrateUnset() {
        val parameters = parameters()
        val plan = ScreenContentVideoPlan.create(1080, 2400, ScreenActivity.MEDIA, 1.0, 30, 4_000_000)
        VideoSenderProfile.apply(parameters, plan, tunedBitrate = false)
        assertNull(parameters.encodings.single().minBitrateBps)
        assertNull(parameters.encodings.single().maxBitrateBps)
    }

    @Test fun bothSenderStrategiesCapTexturesBeforeEncodingDuringPressureAndRotation() {
        for (tuned in listOf(false, true)) {
            val sizes = mutableListOf<Pair<Int, Int>>()
            val sink = object : CapturerObserver {
                override fun onCapturerStarted(success: Boolean) = Unit
                override fun onCapturerStopped() = Unit
                override fun onFrameCaptured(frame: VideoFrame) { sizes += frame.rotatedWidth to frame.rotatedHeight }
            }
            val observer = CaptureActivityObserver(sink, ScreenActivityPolicy(), { 0 }, { 1 })
            val starts = mutableListOf<Pair<Int, Int>>()
            val resizes = mutableListOf<Pair<Int, Int>>()
            val pipeline = ScreenCapturePipeline({ w, h, _ -> starts += w to h },
                { w, h, _ -> resizes += w to h }, observer::setOutputFormat)
            for ((w, h) in listOf(2160 to 3840, 3840 to 2160, 2160 to 3840)) {
                for (activity in ScreenActivity.entries) for (pressure in listOf(1.0, 6.0)) {
                    val plan = ScreenContentVideoPlan.create(w, h, activity, pressure, 30, 150_000)
                    pipeline.apply(w, h, plan.captureLongEdge, plan.scale, plan.fps)
                    val parameters = parameters()
                    assertTrue(VideoSenderProfile.apply(parameters, plan, tuned))
                    val buffer = ExactCaptureOutputTest.Buffer(w, h)
                    val frame = VideoFrame(buffer, 0, 1)
                    observer.onFrameCaptured(frame)
                    assertEquals(w/2 to h/2, sizes.last())
                    assertEquals(1, buffer.children.size)
                    assertEquals(0, buffer.children.single().refs)
                    assertEquals(1, buffer.refs)
                    frame.release()
                    assertEquals(0, buffer.refs)
                }
            }
            assertEquals(listOf(2160 to 3840), starts)
            assertEquals(listOf(3840 to 2160, 2160 to 3840), resizes)
        }
    }

    @Test fun rotatedNativeBuffersAreReusedForBothQuarterTurns() {
        for (rotation in listOf(90, 270)) {
            var submitted: Pair<Int, Int>? = null
            val sink = object : CapturerObserver {
                override fun onCapturerStarted(success: Boolean) = Unit
                override fun onCapturerStopped() = Unit
                override fun onFrameCaptured(frame: VideoFrame) { submitted = frame.rotatedWidth to frame.rotatedHeight }
            }
            val observer = CaptureActivityObserver(sink, ScreenActivityPolicy(), { 0 }, { 1 })
            observer.setOutputFormat(1080, 2400, 30)
            val buffer = ExactCaptureOutputTest.Buffer(2400, 1080)
            val frame = VideoFrame(buffer, rotation, 1)
            observer.onFrameCaptured(frame)
            assertEquals(1080 to 2400, submitted)
            assertTrue(buffer.children.isEmpty())
            frame.release()
            assertEquals(0, buffer.refs)
        }
    }

    @Test fun oddDimensionsOnlyLoseTheSinglePixelNeededForEvenAlignment() {
        for ((w, h) in listOf(1079 to 2399, 2399 to 1079, 719 to 1281)) {
            val plan = ScreenContentVideoPlan.create(w, h, ScreenActivity.INTERACTION, 6.0, 30, 150_000)
            val format = VideoCaptureFormat.forScreen(w, h, plan.captureLongEdge, plan.scale, plan.fps)
            assertEquals(w - 1, format.width)
            assertEquals(h - 1, format.height)
        }
    }
}

package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.CapturerObserver
import org.webrtc.RtpParameters
import org.webrtc.VideoFrame

/** Exercises submitted texture dimensions and ownership, without a hardware encoder. */
class Fixed1080VideoPipelineTest {
    private fun plan(width: Int, height: Int, activity: ScreenActivity = ScreenActivity.MEDIA,
        resourceScale: Double = 6.0, ceilingBps: Int = 150_000, stillDetail: Boolean = false) =
        ScreenContentVideoPlan.create(width, height, activity, resourceScale, 30, ceilingBps, stillDetail)

    private fun format(width: Int, height: Int, targetPlan: ScreenContentVideoPlan = plan(width, height)) =
        VideoCaptureFormat.forScreen(width, height, targetPlan.captureLongEdge, targetPlan.scale, targetPlan.fps)

    private class Sink : CapturerObserver {
        val sizes = mutableListOf<Pair<Int, Int>>()
        val rotations = mutableListOf<Int>()
        var retainFrame = false
        var retained: VideoFrame? = null
        var fail = false
        override fun onCapturerStarted(success: Boolean) = Unit
        override fun onCapturerStopped() = Unit
        override fun onFrameCaptured(frame: VideoFrame) {
            if (fail) error("downstream stopped")
            sizes += frame.rotatedWidth to frame.rotatedHeight
            rotations += frame.rotation
            if (retainFrame) { frame.retain(); retained = frame }
        }
    }

    private fun parameters(): RtpParameters =
        RtpParameters::class.java.declaredConstructors.single().apply { isAccessible = true }
            .newInstance("tx", RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE, null,
                emptyList<Any>(), listOf(RtpParameters.Encoding("a", true, 1.5),
                    RtpParameters.Encoding("b", false, 6.0)), emptyList<Any>()) as RtpParameters

    @Test fun a1440By3200PhoneKeeps1080By2400DuringEveryActivityAndPressureTier() {
        for (activity in ScreenActivity.entries) for (pressure in listOf(1.0, 1.25, 6.0)) {
            val plan = plan(1440, 3200, activity, pressure)
            assertEquals(VideoCaptureFormat(1080, 2400, 30), format(1440, 3200, plan))
        }
    }

    @Test fun a1220By2712PhoneUsesEvenAligned2400PixelsWithoutCroppingItsAspect() {
        // 2712 * 1080 / 1220 = 2400.786885...; alignment must floor to 2400.
        assertEquals(VideoCaptureFormat(1080, 2400, 30), format(1220, 2712))
        assertEquals(VideoCaptureFormat(2400, 1080, 30), format(2712, 1220))
    }

    @Test fun a1240By2772PhoneKeepsItsTallAspectInsteadOfForcingSixteenByNine() {
        // 2772 * 1080 / 1240 = 2414.322580...; preserve the full screen at 2414.
        assertEquals(VideoCaptureFormat(1080, 2414, 30), format(1240, 2772))
        assertEquals(VideoCaptureFormat(2414, 1080, 30), format(2772, 1240))
    }

    @Test fun aFourKScreenIsCappedAt1080By1920InBothOrientations() {
        assertEquals(VideoCaptureFormat(1080, 1920, 30), format(2160, 3840))
        assertEquals(VideoCaptureFormat(1920, 1080, 30), format(3840, 2160))
    }

    @Test fun reciprocalScaleRoundoffCannotRemoveTwoPixelsFromAnExact1080Target() {
        for (shortEdge in listOf(1094, 1100, 1160)) {
            assertEquals("short edge $shortEdge", VideoCaptureFormat(1080, 2160, 30),
                format(shortEdge, shortEdge * 2))
            assertEquals("rotated short edge $shortEdge", VideoCaptureFormat(2160, 1080, 30),
                format(shortEdge * 2, shortEdge))
        }
    }

    @Test fun eachFrameRotationDeliversThePlannedDisplaySizeAndReleasesTheScaledTexture() {
        val plan = plan(1440, 3200)
        val target = format(1440, 3200, plan)
        for (rotation in listOf(0, 90, 180, 270)) {
            val quarterTurn = rotation % 180 != 0
            val sink = Sink()
            val observer = CaptureActivityObserver(sink, ScreenActivityPolicy(), { 0 }, { 1 })
            observer.setOutputFormat(target.width, target.height, target.fps)
            val buffer = ExactCaptureOutputTest.Buffer(if (quarterTurn) 3200 else 1440,
                if (quarterTurn) 1440 else 3200)
            val frame = VideoFrame(buffer, rotation, 1)
            observer.onFrameCaptured(frame)
            assertEquals(listOf(1080 to 2400), sink.sizes)
            assertEquals(listOf(rotation), sink.rotations)
            val scaled = buffer.children.single()
            assertEquals(if (quarterTurn) 2400 else 1080, scaled.getWidth())
            assertEquals(if (quarterTurn) 1080 else 2400, scaled.getHeight())
            assertEquals(0, scaled.refs)
            assertEquals(1, buffer.refs)
            frame.release()
            assertEquals(0, buffer.refs)
        }
    }

    @Test fun anExisting1080ScreenReusesTheOriginalTextureForBothOrientations() {
        for ((width, height) in listOf(1080 to 2400, 2400 to 1080, 1080 to 1920)) {
            val target = format(width, height)
            assertEquals(VideoCaptureFormat(width, height, 30), target)
            val sink = Sink()
            val observer = CaptureActivityObserver(sink, ScreenActivityPolicy(), { 0 }, { 1 })
            observer.setOutputFormat(target.width, target.height, target.fps)
            val buffer = ExactCaptureOutputTest.Buffer(width, height)
            val frame = VideoFrame(buffer, 0, 1)
            observer.onFrameCaptured(frame)
            assertEquals(listOf(width to height), sink.sizes)
            assertTrue(buffer.children.isEmpty())
            assertEquals(1, buffer.refs)
            frame.release()
            assertEquals(0, buffer.refs)
        }
    }

    @Test fun a720ScreenIsNeverUpscaledByThePlanOrTheActualFrameObserver() {
        for ((width, height) in listOf(720 to 1600, 1600 to 720, 720 to 1280)) {
            val target = format(width, height)
            assertEquals(VideoCaptureFormat(width, height, 30), target)
            val sink = Sink()
            val observer = CaptureActivityObserver(sink, ScreenActivityPolicy(), { 0 }, { 1 })
            observer.setOutputFormat(target.width, target.height, target.fps)
            val buffer = ExactCaptureOutputTest.Buffer(width, height)
            val frame = VideoFrame(buffer, 0, 1)
            observer.onFrameCaptured(frame)
            assertEquals(listOf(width to height), sink.sizes)
            assertTrue(buffer.children.isEmpty())
            frame.release()
            assertEquals(0, buffer.refs)
        }
    }

    @Test fun bothRtpStrategiesRemoveStaleExtraScalingFromEveryEncoding() {
        for (tuned in listOf(false, true)) {
            val parameters = parameters()
            parameters.encodings.forEach { it.minBitrateBps = 100_000; it.maxBitrateBps = 2_000_000 }
            val plan = plan(1440, 3200)
            assertEquals(VideoCaptureFormat(1080, 2400, 30), format(1440, 3200, plan))
            assertTrue(VideoSenderProfile.apply(parameters, plan, tuned))
            parameters.encodings.forEach {
                assertEquals(1.0, it.scaleResolutionDownBy!!, 0.0)
                assertEquals(30, it.maxFramerate)
                assertEquals(if (tuned) 0 else 100_000, it.minBitrateBps)
                assertEquals(if (tuned) plan.ceilingBps else 2_000_000, it.maxBitrateBps)
            }
            assertFalse(parameters.encodings.last().active)
            assertEquals(RtpParameters.DegradationPreference.DISABLED, parameters.degradationPreference)
        }
    }

    @Test fun onlyRealRotationResizesTheMirrorWhilePressureKeepsTheSameSubmittedPixels() {
        val sink = Sink()
        val observer = CaptureActivityObserver(sink, ScreenActivityPolicy(), { 0 }, { 1 })
        val starts = mutableListOf<Pair<Int, Int>>()
        val resizes = mutableListOf<Pair<Int, Int>>()
        val outputs = mutableListOf<Pair<Int, Int>>()
        val pipeline = ScreenCapturePipeline({ width, height, _ -> starts += width to height },
            { width, height, _ -> resizes += width to height }, { width, height, fps ->
                outputs += width to height
                observer.setOutputFormat(width, height, fps)
            })
        for ((width, height) in listOf(1440 to 3200, 3200 to 1440, 1440 to 3200)) {
            val expected = if (width < height) 1080 to 2400 else 2400 to 1080
            for (activity in ScreenActivity.entries) for (pressure in listOf(1.0, 6.0)) {
                val plan = plan(width, height, activity, pressure)
                pipeline.apply(width, height, plan.captureLongEdge, plan.scale, plan.fps)
                val buffer = ExactCaptureOutputTest.Buffer(width, height)
                val frame = VideoFrame(buffer, 0, 1)
                observer.onFrameCaptured(frame)
                assertEquals(expected, sink.sizes.last())
                assertEquals(0, buffer.children.single().refs)
                assertEquals(1, buffer.refs)
                frame.release()
                assertEquals(0, buffer.refs)
            }
        }
        assertEquals(listOf(1440 to 3200), starts)
        assertEquals(listOf(3200 to 1440, 1440 to 3200), resizes)
        assertEquals(listOf(1080 to 2400, 2400 to 1080, 1080 to 2400), outputs)
    }

    @Test fun downstreamMayRetainThe1080TextureUntilItFinishesUsingIt() {
        val sink = Sink().also { it.retainFrame = true }
        val observer = CaptureActivityObserver(sink, ScreenActivityPolicy(), { 0 }, { 1 })
        val target = format(1440, 3200)
        observer.setOutputFormat(target.width, target.height, target.fps)
        val buffer = ExactCaptureOutputTest.Buffer(1440, 3200)
        val frame = VideoFrame(buffer, 0, 1)
        observer.onFrameCaptured(frame)
        assertEquals(listOf(1080 to 2400), sink.sizes)
        val scaled = buffer.children.single()
        assertEquals(1, scaled.refs)
        assertEquals(2, buffer.refs)
        frame.release()
        assertEquals(1, buffer.refs)
        assertEquals(1080, sink.retained!!.rotatedWidth)
        assertEquals(2400, sink.retained!!.rotatedHeight)
        sink.retained!!.release()
        assertEquals(0, scaled.refs)
        assertEquals(0, buffer.refs)
    }

    @Test fun downstreamFailureReleasesThe1080ChildWithoutReleasingTheProducersReference() {
        val sink = Sink().also { it.fail = true }
        val observer = CaptureActivityObserver(sink, ScreenActivityPolicy(), { 0 }, { 1 })
        val target = format(1220, 2712)
        observer.setOutputFormat(target.width, target.height, target.fps)
        val buffer = ExactCaptureOutputTest.Buffer(1220, 2712)
        val frame = VideoFrame(buffer, 0, 1)
        assertThrows(IllegalStateException::class.java) { observer.onFrameCaptured(frame) }
        assertEquals(1080, buffer.children.single().getWidth())
        assertEquals(2400, buffer.children.single().getHeight())
        assertEquals(0, buffer.children.single().refs)
        assertEquals(1, buffer.refs)
        frame.release()
        assertEquals(0, buffer.refs)
    }

    @Test fun textRefinementAndVideoUseIdentical1080CappedDimensionsOnHighResolutionScreens() {
        for ((width, height) in listOf(1440 to 3200, 1220 to 2712, 1240 to 2772, 2160 to 3840)) {
            val media = plan(width, height, ScreenActivity.MEDIA, 6.0, 150_000)
            val text = plan(width, height, ScreenActivity.STILL, 1.0, 8_000_000, stillDetail = true)
            assertEquals(format(width, height, media), format(width, height, text))
            assertEquals(1080, minOf(format(width, height, text).width, format(width, height, text).height))
            assertEquals(150_000, media.ceilingBps)
            assertEquals(8_000_000, text.ceilingBps)
            assertTrue(text.stillDetail)
        }
    }
}

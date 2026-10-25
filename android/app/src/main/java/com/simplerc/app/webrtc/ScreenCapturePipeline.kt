package com.simplerc.app.webrtc

/** Keep the system's mirrored screen stable while adapting only WebRTC output pixels. */
class ScreenCapturePipeline(
    private val startCapture: (Int, Int, Int) -> Unit,
    private val resizeCapture: (Int, Int, Int) -> Unit,
    private val adaptOutput: (Int, Int, Int) -> Unit,
) {
    private var capture: VideoCaptureFormat? = null
    private var output: VideoCaptureFormat? = null

    fun apply(screenWidth: Int, screenHeight: Int, longEdge: Int, downscale: Double, fps: Int): VideoCaptureFormat {
        val stableCapture = VideoCaptureFormat.forScreen(screenWidth, screenHeight, longEdge, 1.0, fps)
        val desiredOutput = VideoCaptureFormat.forScreen(screenWidth, screenHeight, longEdge, downscale, fps)
        val previous = capture
        if (previous == null) {
            adaptOutput(desiredOutput.width, desiredOutput.height, fps)
            startCapture(stableCapture.width, stableCapture.height, fps)
        } else {
            // ScreenCapturerAndroid ignores FPS. Changing bandwidth or FPS must never
            // resize its VirtualDisplay/SurfaceTexture and risk a cropped mirror.
            if (previous.width != stableCapture.width || previous.height != stableCapture.height) {
                resizeCapture(stableCapture.width, stableCapture.height, fps)
            }
            if (desiredOutput != output) adaptOutput(desiredOutput.width, desiredOutput.height, fps)
        }
        capture = stableCapture
        output = desiredOutput
        return desiredOutput
    }
}

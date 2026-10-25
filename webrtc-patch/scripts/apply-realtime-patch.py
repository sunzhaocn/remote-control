from pathlib import Path
import sys
import subprocess

root = Path(sys.argv[1]).resolve()
project = Path(__file__).resolve().parents[1]
source = root / 'stream-webrtc-android/src/main/java/org/webrtc'
assert (root / '.git').exists() and source.is_dir()
assert subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip() == '5d388e533d87f857e7de4a729630200cf9766369', 'Unexpected upstream revision'

def edit(name, changes):
    file = source / name
    text = file.read_text()
    assert 'SimpleRcVideoTuning' not in text, 'Already patched: ' + name
    for old, new in changes:
        assert text.count(old) == 1, (name, old[:100], text.count(old))
        text = text.replace(old, new, 1)
    file.write_text(text)

edit('HardwareVideoEncoder.java', [
    ('private static final int MAX_ENCODER_Q_SIZE = 2;', 'private static final int MAX_ENCODER_Q_SIZE = 2;\n  private final SimpleRcVideoTuning.Session simpleRcTuning = SimpleRcVideoTuning.current();\n  private final SimpleRcEncoderQueue simpleRcEncoderQueue = new SimpleRcEncoderQueue(simpleRcTuning.interactive, simpleRcTuning.maxFps);\n  private final java.util.concurrent.atomic.AtomicBoolean simpleRcRecoveryKeyFrame = new java.util.concurrent.atomic.AtomicBoolean();'),
    ('  private VideoCodecStatus initEncodeInternal() {', '  private VideoCodecStatus initEncodeInternal() {\n    simpleRcTuning.encoderName = null;\n    simpleRcTuning.encoderOptionsRequested = false;\n    simpleRcTuning.encoderLatencyFrames = -1;\n    simpleRcEncoderQueue.reset();\n    simpleRcTuning.encoderQueueLimit = simpleRcEncoderQueue.limit();\n    simpleRcTuning.encoderQueue(0, false);'),
    ('      Logging.d(TAG, "Format: " + format);\n      codec.configure(\n        format, null /* surface */, null /* crypto */, MediaCodec.CONFIGURE_FLAG_ENCODE);', '''      simpleRcTuning.encoderName = codecName;
      simpleRcTuning.encoderSurface = useSurfaceMode;
      boolean interactiveOptions = simpleRcTuning.interactive && Build.VERSION.SDK_INT >= 29;
      if (interactiveOptions) {
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        format.setInteger(MediaFormat.KEY_LATENCY, 1);
        if (codecType == VideoCodecMimeType.H264 || codecType == VideoCodecMimeType.H265) {
          format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        }
        // Limit raw frames upstream. MediaCodec's max-fps-to-encoder silently discards
        // Surface inputs, breaking WebRTC's one-input/one-output metadata queue.
      }
      try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        simpleRcTuning.encoderOptionsRequested = interactiveOptions;
      } catch (IllegalArgumentException | IllegalStateException optionFailure) {
        if (!interactiveOptions) throw optionFailure;
        codec.release();
        codec = mediaCodecWrapperFactory.createByCodecName(codecName);
        format.removeKey(MediaFormat.KEY_PRIORITY);
        format.removeKey(MediaFormat.KEY_LATENCY);
        format.removeKey(MediaFormat.KEY_MAX_B_FRAMES);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        simpleRcTuning.encoderOptionsRequested = false;
        simpleRcTuning.codecOptionsFallback = true;
      }'''),
    ('    } catch (IllegalArgumentException | IllegalStateException e) {\n      Logging.e(TAG, "initEncodeInternal failed", e);', '    } catch (IllegalArgumentException | IllegalStateException | IOException e) {\n      Logging.e(TAG, "initEncodeInternal failed", e);\n      simpleRcTuning.encoderName = null;\n      if (codec != null && outputThread == null) {\n        try { codec.release(); } catch (RuntimeException ignored) {}\n      }'),
    ('    if (outputBuilders.size() > MAX_ENCODER_Q_SIZE) {', '    int queueDepth = outputBuilders.size();\n    boolean queueFull = simpleRcEncoderQueue.full(queueDepth, System.nanoTime());\n    simpleRcTuning.encoderQueueLimit = simpleRcEncoderQueue.limit();\n    simpleRcTuning.encoderQueue(queueDepth, queueFull);\n    if (queueFull) {'),
    ('      Logging.e(TAG, "Dropped frame, encoder queue full");', '      Logging.d(TAG, "Dropped frame, encoder queue full");'),
    ('    if (requestedKeyFrame || shouldForceKeyFrame(videoFrame.getTimestampNs())) {', '    requestedKeyFrame |= simpleRcRecoveryKeyFrame.getAndSet(false);\n    requestedKeyFrame |= simpleRcTuning.clearFrame.takeKeyFrameRequest(frameWidth, frameHeight, videoFrame.getTimestampNs());\n    if (requestedKeyFrame || shouldForceKeyFrame(videoFrame.getTimestampNs())) {'),
    ('      EncodedImage encodedImage = builder.createEncodedImage();', '      EncodedImage encodedImage = builder.createEncodedImage();\n      simpleRcTuning.clearFrame.encoded(encodedImage.encodedWidth, encodedImage.encodedHeight, encodedImage.captureTimeNs, isKeyFrame, encodedImage.qp);'),
    ('    outputBuilders.offer(builder);', '    outputBuilders.offer(builder);\n    simpleRcTuning.encoderQueue(outputBuilders.size(), false);'),
    ('      outputBuilders.pollLast();', '      outputBuilders.pollLast();\n      simpleRcTuning.encoderQueue(outputBuilders.size(), true);'),
    ('    outputBuilders.clear();', '    outputBuilders.clear();\n    simpleRcTuning.encoderQueue(0, false);'),
    ('    if (framerate > MAX_VIDEO_FRAMERATE) {\n      framerate = MAX_VIDEO_FRAMERATE;\n    }', '    int fpsLimit = simpleRcTuning.interactive ? simpleRcTuning.maxFps : MAX_VIDEO_FRAMERATE;\n    if (framerate > fpsLimit) framerate = fpsLimit;'),
    ('      if (index < 0) {\n        if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {', '''      if (index < 0) {
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat actual = codec.getOutputFormat();
          if (actual.containsKey(MediaFormat.KEY_LATENCY)) simpleRcTuning.encoderLatencyFrames = actual.getInteger(MediaFormat.KEY_LATENCY);
        }
        if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {'''),
    ('          configBuffer.put(outputBuffer);\n        }\n        return;', '          configBuffer.put(outputBuffer);\n        }\n        codec.releaseOutputBuffer(index, false);\n        return;'),
    ('      EncodedImage.Builder builder = outputBuilders.poll();\n      builder.setBuffer', '''      EncodedImage.Builder builder = outputBuilders.poll();
      simpleRcEncoderQueue.output();
      simpleRcTuning.encoderQueue(outputBuilders.size(), false);
      if (builder == null) {
        if (releaseCallback != null) releaseCallback.run();
        simpleRcRecoveryKeyFrame.set(true);
        simpleRcTuning.encoderDropped.incrementAndGet();
        Logging.w(TAG, "Encoded frame without matching input metadata");
        return;
      }
      builder.setBuffer'''),
])

edit('AndroidVideoDecoder.java', [
    ('import android.os.SystemClock;', 'import android.os.SystemClock;\nimport android.os.Build;'),
    ('  private final String codecName;', '  private final String codecName;\n  private final SimpleRcVideoTuning.Session simpleRcTuning = SimpleRcVideoTuning.current();'),
    ('  private VideoCodecStatus initDecodeInternal(int width, int height) {', '  private VideoCodecStatus initDecodeInternal(int width, int height) {\n    simpleRcTuning.decoderName = null;\n    simpleRcTuning.decoderLowLatencySupported = false;\n    simpleRcTuning.decoderLowLatencyRequested = false;'),
    ('      codec.configure(format, surface, null, 0);\n      codec.start();', '''      simpleRcTuning.decoderName = codecName;
      boolean interactiveOptions = simpleRcTuning.interactive && Build.VERSION.SDK_INT >= 29;
      boolean supportsLowLatency = false;
      if (interactiveOptions) {
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        if (Build.VERSION.SDK_INT >= 30) {
          try {
            supportsLowLatency = codec.getCodecInfo().getCapabilitiesForType(codecType.mimeType())
                .isFeatureSupported(CodecCapabilities.FEATURE_LowLatency);
          } catch (RuntimeException ignored) {}
          if (supportsLowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
      }
      simpleRcTuning.decoderLowLatencySupported = supportsLowLatency;
      try {
        codec.configure(format, surface, null, 0);
        simpleRcTuning.decoderLowLatencyRequested = supportsLowLatency;
      } catch (IllegalArgumentException | IllegalStateException optionFailure) {
        if (!interactiveOptions) throw optionFailure;
        codec.release();
        codec = mediaCodecWrapperFactory.createByCodecName(codecName);
        format.removeKey(MediaFormat.KEY_PRIORITY);
        if (Build.VERSION.SDK_INT >= 30) format.removeKey(MediaFormat.KEY_LOW_LATENCY);
        codec.configure(format, surface, null, 0);
        simpleRcTuning.decoderLowLatencyRequested = false;
        simpleRcTuning.codecOptionsFallback = true;
      }
      codec.start();'''),
    ('    } catch (IllegalStateException | IllegalArgumentException e) {\n      Logging.e(TAG, "initDecode failed", e);', '    } catch (IllegalStateException | IllegalArgumentException | IOException e) {\n      Logging.e(TAG, "initDecode failed", e);\n      simpleRcTuning.decoderName = null;\n      if (codec != null && outputThread == null) {\n        try { codec.release(); } catch (RuntimeException ignored) {}\n        codec = null;\n      }'),
    ('    frameInfos.offer(new FrameInfo(SystemClock.elapsedRealtime(), frame.rotation));', '    frameInfos.offer(new FrameInfo(SystemClock.elapsedRealtime(), frame.rotation));\n    simpleRcTuning.decoderQueue(frameInfos.size());'),
])

edit('EglRenderer.java', [
    ('  @Nullable private VideoFrame pendingFrame;', '''  @Nullable private VideoFrame pendingFrame;
  private final SimpleRcRenderQueue simpleRcQueue = new SimpleRcRenderQueue();
  private SimpleRcVideoTuning.Session pendingFrameTuning;
  private SimpleRcVsyncScheduler simpleRcVsync;
  private final Runnable renderFrameRunnable = this::scheduleSimpleRcRender;

  private void scheduleSimpleRcRender() {
    synchronized (threadLock) {
      if (eglThread == null || eglThread.getHandler().getLooper().getThread() != Thread.currentThread()) return;
    }
    if (eglBase == null || !eglBase.hasSurface()) return;
    if (!SimpleRcVideoTuning.current().interactive) { renderFrameOnRenderThread(); return; }
    if (simpleRcVsync == null) {
      simpleRcVsync = new SimpleRcVsyncScheduler(SimpleRcVideoTuning.current().maxFps, () -> {
        // Surface destruction can overtake a queued VSync. Preserve the newest pending
        // frame for surface recreation; don't spin while the app has no surface.
        if (eglBase != null && eglBase.hasSurface()) renderFrameOnRenderThread();
      });
    }
    simpleRcVsync.request();
  }

  private void closeSimpleRcVsync() {
    if (simpleRcVsync != null) { simpleRcVsync.close(); simpleRcVsync = null; }
  }'''),
    ('        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);', '''        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        boolean simpleRcHasFrame;
        synchronized (frameLock) {
          simpleRcQueue.surfaceReady(pendingFrame != null);
          simpleRcHasFrame = pendingFrame != null;
        }
        if (simpleRcHasFrame) scheduleSimpleRcRender();'''),
    ('        // Detach current shader program.', '        closeSimpleRcVsync();\n        // Detach current shader program.'),
    ('          if (eglBase != null) {\n            eglBase.detachCurrent();', '          closeSimpleRcVsync();\n          if (eglBase != null) {\n            eglBase.detachCurrent();'),
    ('      synchronized (frameLock) {\n        dropOldFrame = (pendingFrame != null);', '      synchronized (frameLock) {\n        pendingFrameTuning = SimpleRcVideoTuning.current();\n        pendingFrameTuning.rendererReceived.incrementAndGet();\n        dropOldFrame = (pendingFrame != null);'),
    ('        if (dropOldFrame) {\n          pendingFrame.release();\n        }', '        if (dropOldFrame) {\n          pendingFrame.release();\n          pendingFrameTuning.rendererReplaced.incrementAndGet();\n        }'),
    ('        eglThread.getHandler().post(this::renderFrameOnRenderThread);', '        boolean shouldPost = simpleRcQueue.offer(System.nanoTime());\n        if (!pendingFrameTuning.interactive || shouldPost) eglThread.getHandler().post(renderFrameRunnable);'),
    ('      eglThread.getHandler().postAtFrontOfQueue(() -> clearSurfaceOnRenderThread(r, g, b, a));', '''      synchronized (frameLock) {
        if (pendingFrame != null) { pendingFrame.release(); pendingFrame = null; }
        simpleRcQueue.clearFrame();
      }
      eglThread.getHandler().postAtFrontOfQueue(() -> clearSurfaceOnRenderThread(r, g, b, a));'''),
    ('    logD("Releasing done.");', '    synchronized (frameLock) { simpleRcQueue.reset(); pendingFrameTuning = null; }\n    logD("Releasing done.");'),
    ('    final VideoFrame frame;\n    synchronized (frameLock) {\n      if (pendingFrame == null) {', '''    final VideoFrame frame;
    final long arrivalNs;
    final SimpleRcVideoTuning.Session tuning;
    synchronized (frameLock) {
      arrivalNs = simpleRcQueue.take();
      tuning = pendingFrameTuning;
      if (pendingFrame == null) {'''),
    ('    if (eglBase == null || !eglBase.hasSurface()) {\n      logD("Dropping frame - No surface");', '''    // pendingFrame already contains the newest decoded frame. Never discard the
    // final screen update merely because the renderer was busy for 150 ms.
    // Replacement in onFrame bounds the queue; arrivalNs remains useful telemetry.
    if (eglBase == null || !eglBase.hasSurface()) {
      logD("Dropping frame - No surface");'''),
    ('        final long swapBuffersStartTimeNs = System.nanoTime();\n        swapBuffersOnRenderThread(frame, swapBuffersStartTimeNs);', '''        final long swapBuffersStartTimeNs = System.nanoTime();
        tuning.rendered(startTimeNs - arrivalNs, swapBuffersStartTimeNs - startTimeNs,
          simpleRcVsync == null ? 0 : simpleRcVsync.pacedNs(arrivalNs));
        swapBuffersOnRenderThread(frame, swapBuffersStartTimeNs, tuning);'''),
    ('  private void swapBuffersOnRenderThread(final VideoFrame frame, long swapBuffersStartTimeNs) {', '  private void swapBuffersOnRenderThread(final VideoFrame frame, long swapBuffersStartTimeNs, SimpleRcVideoTuning.Session tuning) {'),
    ('            synchronized (statisticsLock) {\n              renderSwapBufferTimeNs += (System.nanoTime() - swapBuffersStartTimeNs);', '            tuning.swapped(System.nanoTime() - swapBuffersStartTimeNs);\n            synchronized (statisticsLock) {\n              renderSwapBufferTimeNs += (System.nanoTime() - swapBuffersStartTimeNs);'),
])

edit('SurfaceTextureHelper.java', [
    ('  private boolean hasPendingTexture;', '''  private boolean hasPendingTexture;
  private final SimpleRcCaptureScheduler simpleRcCaptureScheduler;

  /** Opt in only for screen capture; decoder textures retain their original delivery behavior. */
  public void setCaptureFrameRate(int maxFps) {
    handler.post(() -> {
      simpleRcCaptureScheduler.configure(maxFps);
      tryDeliverTextureFrame();
    });
  }'''),
    ('    this.handler = handler;', '    this.handler = handler;\n    simpleRcCaptureScheduler = new SimpleRcCaptureScheduler(handler, this::tryDeliverTextureFrame);'),
    ('      listener = null;\n      pendingListener = null;', '''      listener = null;
      pendingListener = null;
      simpleRcCaptureScheduler.reset();'''),
    ('      isQuitting = true;', '''      isQuitting = true;
      simpleRcCaptureScheduler.reset();'''),
    ('    isTextureInUse = true;\n    hasPendingTexture = false;', '''    if (!simpleRcCaptureScheduler.ready()) return;
    isTextureInUse = true;
    hasPendingTexture = false;'''),
])
for file in (project / 'library/org/webrtc').glob('*.java'):
    (source / file.name).write_bytes(file.read_bytes())
print('PATCHED_ENCODER_DECODER_RENDERER')

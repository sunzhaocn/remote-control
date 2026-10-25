package org.webrtc;

import android.view.Choreographer;

/** All methods run on the EGL looper. One callback, one newest pending frame, no idle loop. */
public final class SimpleRcVsyncScheduler implements Choreographer.FrameCallback {
  private final Choreographer choreographer;
  private final SimpleRcFramePacer pacer;
  private final Runnable render;
  private boolean scheduled;
  private boolean closed;
  private long requestNs, lastRequestNs, lastVsyncNs;
  public SimpleRcVsyncScheduler(int fps, Runnable render) {
    this.choreographer = Choreographer.getInstance();
    this.pacer = new SimpleRcFramePacer(fps);
    this.render = render;
  }
  public void request() {
    if (closed || scheduled) return;
    requestNs = System.nanoTime();
    scheduled = true;
    choreographer.postFrameCallback(this);
  }
  @Override public void doFrame(long frameTimeNs) {
    scheduled = false;
    if (closed) return;
    if (!pacer.vsyncDue(frameTimeNs, SimpleRcVideoTuning.current().displayRefreshRateHz)) {
      scheduled = true;
      choreographer.postFrameCallback(this);
      return;
    }
    lastRequestNs = requestNs; lastVsyncNs = frameTimeNs;
    pacer.emitted(frameTimeNs);
    render.run();
  }
  public long pacedNs(long arrivalNs) {
    return Math.max(0, lastVsyncNs - Math.max(arrivalNs, lastRequestNs));
  }
  public void close() {
    closed = true;
    choreographer.removeFrameCallback(this);
    scheduled = false;
    pacer.reset();
  }
}

package org.webrtc;

/** One pending wakeup; preserve the latest retained frame even after a long UI stall. */
public final class SimpleRcRenderQueue {
  private boolean scheduled;
  private long arrivalNs;
  public synchronized boolean offer(long nowNs) {
    arrivalNs = nowNs;
    if (scheduled) return false;
    scheduled = true;
    return true;
  }
  public synchronized long take() {
    scheduled = false;
    long value = arrivalNs;
    arrivalNs = 0;
    return value;
  }
  // Leave an already-posted wakeup in place so a new frame can use it.
  public synchronized void clearFrame() { arrivalNs = 0; }
  // Surface destruction cancels its VSync callback. Rebind the wakeup to a new surface,
  // including the no-frame case, so clearImage cannot leave a phantom scheduled callback.
  public synchronized void surfaceReady(boolean hasFrame) {
    scheduled = hasFrame;
    if (!hasFrame) arrivalNs = 0;
  }
  // Release destroys the old Handler and its wakeup; reinitialization must post anew.
  public synchronized void reset() { scheduled = false; arrivalNs = 0; }
}

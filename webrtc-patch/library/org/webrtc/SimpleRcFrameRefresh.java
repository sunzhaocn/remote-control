package org.webrtc;

/** Session-scoped, bounded clear-frame request. No codec calls from application threads. */
public final class SimpleRcFrameRefresh {
  private long epoch, completed, requested;
  private int width, height;
  private long notBeforeNs;
  private boolean pending;
  private boolean keySeen;
  private int detailFrames;

  public synchronized long request(int width, int height, long notBeforeNs) {
    this.width = width; this.height = height; this.notBeforeNs = notBeforeNs;
    pending = true;
    keySeen = false; detailFrames = 0;
    return ++epoch;
  }
  public synchronized boolean takeKeyFrameRequest(int width, int height, long timestampNs) {
    if (!pending || requested == epoch || !matches(width, height) || timestampNs < notBeforeNs) return false;
    requested = epoch;
    return true;
  }
  public synchronized void encoded(int width, int height, long timestampNs, boolean keyFrame) {
    encoded(width, height, timestampNs, keyFrame, null);
  }
  private boolean matches(int width, int height) {
    // Native adapters honor codec alignment (commonly 16 pixels). Accept that rounding,
    // never a lower resolution tier, when verifying a requested full-size refresh.
    return width >= Math.max(16, this.width - 16) && width <= this.width + 16 &&
        height >= Math.max(16, this.height - 16) && height <= this.height + 16;
  }
  public synchronized void encoded(int width, int height, long timestampNs, boolean keyFrame, Integer qp) {
    if (!pending || !matches(width, height) || timestampNs < notBeforeNs) return;
    if (keyFrame) keySeen = true;
    if (!keySeen) return;
    detailFrames++;
    boolean qualityKnown = qp != null && qp >= 0;
    // A coarse first keyframe is not proof of readable text. Allow static delta-frame
    // refinement, or a bounded eight-frame fallback when the encoder provides no QP.
    if ((detailFrames >= 4 && qualityKnown && qp <= 28) || (detailFrames >= 8 && !qualityKnown)) {
      completed = epoch; pending = false;
    }
  }
  public synchronized long completedEpoch() { return completed; }
  public synchronized void cancel() { pending = false; }
}

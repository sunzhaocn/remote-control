package org.webrtc;

/** Phase-based deadlines: one fresh frame per slot, no catch-up burst after a stall. */
public final class SimpleRcFramePacer {
  private long periodNs;
  private long nextNs = Long.MIN_VALUE;
  private long lastEmissionNs = Long.MIN_VALUE;
  public SimpleRcFramePacer(int fps) { setFps(fps); }
  public void setFps(int fps) {
    periodNs = fps > 0 ? 1_000_000_000L / Math.min(240, fps) : 0;
    reset();
  }
  public void reset() { nextNs = Long.MIN_VALUE; lastEmissionNs = Long.MIN_VALUE; }
  public long delayNs(long nowNs) {
    return periodNs == 0 || nextNs == Long.MIN_VALUE ? 0 : Math.max(0, nextNs - nowNs);
  }
  public boolean vsyncDue(long frameTimeNs) {
    // Account for sub-millisecond clock/refresh rounding without moving the phase each frame.
    return delayNs(frameTimeNs) <= Math.min(1_000_000L, periodNs / 8);
  }
  public boolean vsyncDue(long frameTimeNs, double displayHz) {
    if (!vsyncDue(frameTimeNs)) return false;
    if (lastEmissionNs == Long.MIN_VALUE || periodNs == 0 || displayHz < 20 || displayHz > 360) return true;
    double refreshNs = 1_000_000_000.0 / displayHz;
    long ticks = Math.round(periodNs / refreshNs);
    // Integer-multiple displays can enforce equal spacing after a late frame. Non-integer
    // displays still need their inherent alternating refresh counts (e.g. 4/5 on 144Hz).
    if (ticks < 1 || Math.abs(ticks * refreshNs - periodNs) > periodNs * 0.02) return true;
    long minimumNs = Math.round(ticks * refreshNs) - Math.min(1_000_000L, (long) refreshNs / 8);
    return frameTimeNs - lastEmissionNs >= minimumNs;
  }
  public void emitted(long nowNs) {
    lastEmissionNs = nowNs;
    if (periodNs == 0) return;
    if (nextNs == Long.MIN_VALUE || nowNs - nextNs >= periodNs) {
      nextNs = nowNs + periodNs;
    } else {
      nextNs += periodNs;
    }
  }
}

package org.webrtc;

/** Match upstream's three outstanding raw inputs, including pipelined codec latency. */
public final class SimpleRcEncoderQueue {
  private static final int LIMIT = 3;
  public SimpleRcEncoderQueue(boolean interactive) { this(interactive, 30); }
  // Keep the public constructor and lifecycle methods compatible with existing clients.
  // A two-input limit starves codecs with normal pipelined latency even when they
  // can sustain the requested FPS. Sporadic output also defeats a stall timer.
  public SimpleRcEncoderQueue(boolean interactive, int maxFps) {}
  public boolean full(int depth, long nowNs) { return depth >= LIMIT; }
  public void output() {}
  public void reset() {}
  public int limit() { return LIMIT; }
}

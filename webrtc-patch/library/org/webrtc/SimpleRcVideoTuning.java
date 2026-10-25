package org.webrtc;

import java.util.concurrent.atomic.AtomicLong;

/** Aggregate local-stage telemetry only. No frame pixels, peer addresses or credentials. */
public final class SimpleRcVideoTuning {
  private static volatile Session current = new Session(false, 30);
  private SimpleRcVideoTuning() {}
  public static Session current() { return current; }
  public static void configure(boolean interactive, int maxFps) {
    current = new Session(interactive, Math.max(15, Math.min(60, maxFps)));
  }
  public static Snapshot snapshot() { return new Snapshot(current); }
  public static final class Session {
    public final boolean interactive;
    public final int maxFps;
    public volatile double displayRefreshRateHz;
    public final SimpleRcFrameRefresh clearFrame = new SimpleRcFrameRefresh();
    public volatile String encoderName;
    public volatile String decoderName;
    public volatile boolean encoderSurface;
    public volatile boolean encoderOptionsRequested;
    public volatile int encoderLatencyFrames = -1;
    public volatile int encoderQueueLimit;
    public volatile boolean decoderLowLatencySupported;
    public volatile boolean decoderLowLatencyRequested;
    public volatile boolean codecOptionsFallback;
    public final AtomicLong encoderDepth = new AtomicLong();
    public final AtomicLong encoderPeak = new AtomicLong();
    public final AtomicLong encoderDropped = new AtomicLong();
    public final AtomicLong decoderPeak = new AtomicLong();
    public final AtomicLong rendererReceived = new AtomicLong();
    public final AtomicLong rendererReplaced = new AtomicLong();
    public final AtomicLong rendererStale = new AtomicLong();
    public final AtomicLong rendererRendered = new AtomicLong();
    public final AtomicLong rendererQueueNs = new AtomicLong();
    public final AtomicLong rendererPacingNs = new AtomicLong();
    public final AtomicLong rendererDrawNs = new AtomicLong();
    public final AtomicLong rendererSwaps = new AtomicLong();
    public final AtomicLong rendererSwapNs = new AtomicLong();
    Session(boolean interactive, int maxFps) { this.interactive = interactive; this.maxFps = maxFps; encoderQueueLimit = 3; }
    public void encoderQueue(int depth, boolean dropped) {
      encoderDepth.set(depth); encoderPeak.accumulateAndGet(depth, Math::max);
      if (dropped) encoderDropped.incrementAndGet();
    }
    public void decoderQueue(int depth) { decoderPeak.accumulateAndGet(depth, Math::max); }
    public synchronized void rendered(long queuedNs, long drawNs) {
      rendered(queuedNs, drawNs, 0);
    }
    public synchronized void rendered(long queuedNs, long drawNs, long pacingNs) {
      rendererRendered.incrementAndGet();
      rendererQueueNs.addAndGet(Math.max(0, queuedNs)); rendererDrawNs.addAndGet(Math.max(0, drawNs));
      rendererPacingNs.addAndGet(Math.min(Math.max(0, queuedNs), Math.max(0, pacingNs)));
    }
    public synchronized void swapped(long ns) { rendererSwaps.incrementAndGet(); rendererSwapNs.addAndGet(Math.max(0, ns)); }
  }
  public static final class Snapshot {
    public final Session identity;
    public final String encoderName, decoderName;
    public final boolean encoderSurface, encoderOptionsRequested, decoderLowLatencySupported,
        decoderLowLatencyRequested, codecOptionsFallback;
    public final int encoderLatencyFrames, encoderQueueLimit;
    public final long encoderDepth, encoderPeak, encoderDropped, decoderPeak, rendererReceived,
        rendererReplaced, rendererStale, rendererRendered, rendererQueueNs, rendererDrawNs,
        rendererSwaps, rendererSwapNs, rendererPacingNs;
    Snapshot(Session s) {
      identity=s; encoderName=s.encoderName; decoderName=s.decoderName; encoderSurface=s.encoderSurface;
      encoderOptionsRequested=s.encoderOptionsRequested; encoderLatencyFrames=s.encoderLatencyFrames;
      encoderQueueLimit=s.encoderQueueLimit;
      decoderLowLatencySupported=s.decoderLowLatencySupported; decoderLowLatencyRequested=s.decoderLowLatencyRequested;
      codecOptionsFallback=s.codecOptionsFallback; encoderDepth=s.encoderDepth.get(); encoderPeak=s.encoderPeak.get();
      encoderDropped=s.encoderDropped.get(); decoderPeak=s.decoderPeak.get(); rendererReceived=s.rendererReceived.get();
      rendererReplaced=s.rendererReplaced.get(); rendererStale=s.rendererStale.get();
      // Sample each duration sum with its matching count. Partial updates produce false
      // pressure spikes when the policy thread samples while the render thread is running.
      synchronized (s) {
        rendererRendered=s.rendererRendered.get(); rendererQueueNs=s.rendererQueueNs.get();
        rendererDrawNs=s.rendererDrawNs.get(); rendererSwaps=s.rendererSwaps.get();
        rendererSwapNs=s.rendererSwapNs.get();
        rendererPacingNs=s.rendererPacingNs.get();
      }
    }
  }
}

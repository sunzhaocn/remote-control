package org.webrtc;

import android.os.Handler;
import java.util.function.LongSupplier;

/** Capture-looper gate. Wait before updateTexImage, so the deadline samples the latest texture. */
public final class SimpleRcCaptureScheduler {
  private final Handler handler;
  private final Runnable deliver;
  private final LongSupplier clock;
  private final SimpleRcFramePacer pacer = new SimpleRcFramePacer(0);
  private boolean scheduled;
  private final Runnable tick;
  public SimpleRcCaptureScheduler(Handler handler, Runnable deliver) {
    this(handler, deliver, System::nanoTime);
  }
  public SimpleRcCaptureScheduler(Handler handler, Runnable deliver, LongSupplier clock) {
    this.handler = handler; this.deliver = deliver; this.clock = clock;
    this.tick = () -> { scheduled = false; this.deliver.run(); };
  }
  public void configure(int fps) { reset(); pacer.setFps(fps); }
  public boolean ready() {
    long now = clock.getAsLong();
    long delay = pacer.delayNs(now);
    if (delay > 0) {
      if (!scheduled) {
        scheduled = true;
        handler.postDelayed(tick, (delay + 999_999L) / 1_000_000L);
      }
      return false;
    }
    handler.removeCallbacks(tick);
    scheduled = false;
    pacer.emitted(now);
    return true;
  }
  public void reset() {
    handler.removeCallbacks(tick);
    scheduled = false;
    pacer.reset();
  }
}

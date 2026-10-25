"""Exercise the built AAR schedulers with deterministic Handler/Choreographer queues, no GPU."""
from pathlib import Path
from zipfile import ZipFile
from io import BytesIO
import json
import os
import subprocess
import sys
import tempfile

aar, receipt = map(Path, sys.argv[1:3])
handler = r'''package android.os;
import java.util.*;
public class Handler {
  public static long now;
  private final Map<Runnable,Long> tasks = new LinkedHashMap<>();
  public boolean postDelayed(Runnable r,long ms) { tasks.put(r,now+ms*1000000);return true; }
  public void removeCallbacks(Runnable r) { tasks.remove(r); }
  public int size() {return tasks.size();}
  public void advance(long target) {
    while(true) {
      Runnable best=null;long due=Long.MAX_VALUE;
      for(Map.Entry<Runnable,Long> e:tasks.entrySet())if(e.getValue()<due){best=e.getKey();due=e.getValue();}
      if(best==null || due>target)break;
      now=due;tasks.remove(best);best.run();
    }
    now=target;
  }
  public void stall(long target) {
    now=target;
    List<Runnable> old=new ArrayList<>(tasks.keySet());tasks.clear();
    for(Runnable r:old)r.run();
  }
}'''
choreographer = r'''package android.view;
import java.util.*;
public class Choreographer {
  public interface FrameCallback {void doFrame(long ns);}
  private static final Choreographer INSTANCE=new Choreographer();
  private final Set<FrameCallback> pending=new LinkedHashSet<>();
  public static Choreographer getInstance(){return INSTANCE;}
  public void postFrameCallback(FrameCallback cb){pending.add(cb);}
  public void removeFrameCallback(FrameCallback cb){pending.remove(cb);}
  public int size(){return pending.size();}
  public void reset(){pending.clear();}
  public void tick(long t){List<FrameCallback> list=new ArrayList<>(pending);pending.clear();for(FrameCallback cb:list)cb.doFrame(t);}
}'''
harness = r'''import java.util.*;
import android.os.Handler;
import android.view.Choreographer;
import org.webrtc.*;
public class FrameSchedulersTest {
  static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
  static final List<String> passed=new ArrayList<>();
  static class Capture {
    final Handler handler=new Handler(); final SimpleRcCaptureScheduler scheduler;
    final List<Long> times=new ArrayList<>();final List<Integer> frames=new ArrayList<>();
    boolean pending,busy,closed;int latest;
    Capture(){Handler.now=0;scheduler=new SimpleRcCaptureScheduler(handler,this::deliver,()->Handler.now);scheduler.configure(30);}
    void deliver(){if(closed || busy || !pending)return;if(scheduler.ready()){pending=false;times.add(Handler.now);frames.add(latest);}}
    void frame(long t,int id){handler.advance(t);latest=id;pending=true;deliver();}
  }
  static void captureCadence(int hz) {
    Capture c=new Capture();
    for(int i=0;i<hz*2;i++)c.frame(i*1000000000L/hz,i);
    c.handler.advance(2100000000L);
    check(c.times.size()>=59 && c.times.size()<=61,"capture count "+hz+" "+c.times.size());
    for(int i=1;i<c.times.size();i++)check(c.times.get(i)-c.times.get(i-1)>=32000000L,"capture burst");
    check(c.frames.get(c.frames.size()-1)==hz*2-1,"final source frame lost");
    check(c.handler.size()==0,"idle capture loop");passed.add("capture_"+hz+"hz_to_thirty_latest_and_no_idle_loop");
  }
  static void latestTexture() {
    Capture c=new Capture();c.frame(0,0);c.frame(1000000,1);c.frame(2000000,2);c.frame(3000000,3);
    check(c.handler.size()==1,"multiple capture wakeups");c.handler.advance(34000000);
    check(c.frames.equals(Arrays.asList(0,3)),"retained old frame instead of latest texture");
    passed.add("capture_samples_latest_texture_at_deadline");
  }
  static void captureStall() {
    Capture c=new Capture();c.frame(0,0);c.frame(1000000,1);c.handler.stall(500000000);
    check(c.frames.size()==2 && c.handler.size()==0,"capture catch-up burst");
    c.frame(501000000,2);check(c.frames.size()==2,"post-stall burst");c.handler.advance(534000000);
    check(c.frames.size()==3,"capture failed to recover");passed.add("capture_stall_skips_old_slots");
  }
  static void captureLifecycle() {
    Capture c=new Capture();c.frame(0,0);c.frame(1000000,1);c.scheduler.reset();c.closed=true;
    c.handler.advance(500000000);check(c.frames.size()==1 && c.handler.size()==0,"late capture callback after stop");
    c.closed=false;c.frame(501000000,2);check(c.frames.size()==2,"restart kept old deadline");
    passed.add("capture_stop_cancels_wakeup_and_restart_resets_phase");
  }
  static void disabledCapture() {
    Capture c=new Capture();c.scheduler.configure(0);for(int i=0;i<100;i++)c.frame(i*1000000L,i);
    check(c.frames.size()==100 && c.handler.size()==0,"decoder/default path unexpectedly paced");
    passed.add("capture_disabled_leaves_decoder_delivery_unchanged");
  }
  static void captureBusy() {
    Capture c=new Capture();c.frame(0,0);c.busy=true;c.frame(100000000,1);c.frame(200000000,2);
    check(c.handler.size()==0,"busy texture spun on timer");c.busy=false;c.deliver();
    check(c.frames.equals(Arrays.asList(0,2)),"busy texture failed to keep latest");
    passed.add("capture_busy_texture_waits_for_return_without_spinning");
  }
  static void vsyncCadence(int hz) {
    SimpleRcVideoTuning.current().displayRefreshRateHz=hz;
    Choreographer ch=Choreographer.getInstance();ch.reset();final int[] n={0};
    SimpleRcVsyncScheduler s=new SimpleRcVsyncScheduler(30,()->n[0]++);
    for(int i=0;i<=hz*2;i++){s.request();ch.tick(i*1000000000L/hz);check(ch.size()<=1,"multiple VSync callbacks");}
    check(n[0]==61,"VSync cadence "+hz+" "+n[0]);s.close();check(ch.size()==0,"VSync release callback");
    passed.add("vsync_"+hz+"hz_to_even_thirty");
  }
  static void vsyncLatest() {
    Choreographer ch=Choreographer.getInstance();ch.reset();SimpleRcRenderQueue q=new SimpleRcRenderQueue();
    final int[] latest={0},shown={0};
    SimpleRcVsyncScheduler s=new SimpleRcVsyncScheduler(30,()->{q.take();shown[0]=latest[0];});
    for(int i=1;i<=100000;i++){latest[0]=i;if(q.offer(i))s.request();}
    check(ch.size()==1,"burst posted multiple VSync callbacks");ch.tick(0);
    check(shown[0]==100000 && ch.size()==0,"VSync did not show newest once");
    ch.tick(1000000000);check(ch.size()==0,"VSync idle loop");s.close();
    passed.add("vsync_hundred_thousand_frames_coalesce_and_idle_stops");
  }
  static void vsyncClose() {
    Choreographer ch=Choreographer.getInstance();ch.reset();final int[] n={0};
    SimpleRcVsyncScheduler s=new SimpleRcVsyncScheduler(30,()->n[0]++);s.request();s.close();s.doFrame(0);s.request();
    check(n[0]==0 && ch.size()==0,"closed VSync revived");
    SimpleRcVsyncScheduler newer=new SimpleRcVsyncScheduler(30,()->n[0]++);newer.request();ch.tick(1);
    check(n[0]==1,"recreated surface retained old pacing");newer.close();
    passed.add("vsync_destroy_drops_late_callback_and_recreate_is_fresh");
  }
  static void vsyncStall() {
    SimpleRcVideoTuning.current().displayRefreshRateHz=60;
    Choreographer ch=Choreographer.getInstance();ch.reset();final int[] n={0};
    SimpleRcVsyncScheduler s=new SimpleRcVsyncScheduler(30,()->n[0]++);
    s.request();ch.tick(0);s.request();ch.tick(500000000);
    check(n[0]==2 && ch.size()==0,"VSync catch-up burst");s.request();ch.tick(516666666);
    check(n[0]==2 && ch.size()==1,"VSync rendered too soon after stall");ch.tick(533333333);
    check(n[0]==3,"VSync did not recover");s.close();passed.add("vsync_stall_renders_once_and_skips_missed_slots");
  }
  static void pacingTelemetry() {
    Choreographer ch=Choreographer.getInstance();ch.reset();final long[] wait={-1};
    final SimpleRcVsyncScheduler[] s={null};long before=System.nanoTime();
    s[0]=new SimpleRcVsyncScheduler(30,()->wait[0]=s[0].pacedNs(before));s[0].request();long after=System.nanoTime();
    ch.tick(after+10000000);check(wait[0]>=10000000 && wait[0]<=after-before+10000000,"planned VSync wait not bounded");
    s[0].close();passed.add("vsync_wait_telemetry_excludes_post_vsync_handler_delay");
  }
  static void vsyncSlightlyLate() {
    Choreographer ch=Choreographer.getInstance();ch.reset();final int[] n={0};
    SimpleRcVideoTuning.current().displayRefreshRateHz=60;
    SimpleRcVsyncScheduler s=new SimpleRcVsyncScheduler(30,()->n[0]++);
    s.request();ch.tick(0);s.request();ch.tick(50000000);check(n[0]==2,"late frame missing");
    s.request();ch.tick(66666666);check(n[0]==2,"late frame caused one-refresh catch-up");
    ch.tick(83333333);check(n[0]==3,"even cadence did not resume");s.close();
    passed.add("vsync_slight_lateness_cannot_create_short_catch_up_interval");
  }
  public static void main(String[] args){
    captureCadence(60);captureCadence(90);captureCadence(120);latestTexture();captureStall();captureLifecycle();disabledCapture();captureBusy();
    vsyncCadence(60);vsyncCadence(90);vsyncCadence(120);vsyncLatest();vsyncClose();vsyncStall();pacingTelemetry();vsyncSlightlyLate();
    for(String name:passed)System.out.println(name);
  }
}'''
with tempfile.TemporaryDirectory(prefix='simplerc-frame-schedulers-') as folder:
    root = Path(folder)
    with ZipFile(aar) as z:
        jar = z.read('classes.jar')
    (root / 'production.jar').write_bytes(jar)
    with ZipFile(BytesIO(jar)) as classes:
        assert b'SimpleRcCaptureScheduler' in classes.read('org/webrtc/SurfaceTextureHelper.class')
        renderer = classes.read('org/webrtc/EglRenderer.class')
        assert b'SimpleRcVsyncScheduler' in renderer and b'closeSimpleRcVsync' in renderer
    for name, source in [('android/os/Handler.java', handler), ('android/view/Choreographer.java', choreographer), ('FrameSchedulersTest.java', harness)]:
        file = root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(source, encoding='utf-8')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', str(root / 'production.jar'), '-d', str(root),
        str(root / 'android/os/Handler.java'), str(root / 'android/view/Choreographer.java'), str(root / 'FrameSchedulersTest.java')], check=True)
    result = subprocess.check_output(['java', '-cp', os.pathsep.join([str(root), str(root / 'production.jar')]), 'FrameSchedulersTest'], text=True)
    passed = result.strip().splitlines()
    assert len(passed) == 16
    record = {'tests': len(passed), 'passed': passed,
        'production_capture_and_renderer_scheduler_hooks_present': True,
        'boundary': 'Built AAR scheduler bytecode with deterministic Android queue stubs; no phone/GPU/presentation timing measured.'}
    receipt.write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(record, indent=2))

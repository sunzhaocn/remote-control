"""Exercise queue helpers from the built AAR and reject the previous starvation bug."""
from pathlib import Path
from io import BytesIO
from zipfile import ZipFile
import json
import os
import subprocess
import sys
import tempfile

previous, built, receipt = map(Path, sys.argv[1:4])
harness = '''import java.util.*;
import org.webrtc.SimpleRcEncoderQueue;
import org.webrtc.SimpleRcRenderQueue;
public class VideoQueuesTest {
  static void check(boolean ok,String reason) { if(!ok)throw new AssertionError(reason); }
  static void pipeline(int fps,int latencyMs) {
    SimpleRcEncoderQueue queue=new SimpleRcEncoderQueue(true,60);
    ArrayDeque<Long> outputs=new ArrayDeque<>(); int accepted=0,dropped=0,peak=0; long next=0;
    for(long now=0;now<2_000_000_000L;now+=1_000_000L) {
      while(!outputs.isEmpty()&&outputs.peekFirst()<=now) { outputs.removeFirst(); queue.output(); }
      if(now>=next) {
        next=(accepted+dropped+1L)*1_000_000_000L/fps;
        if(queue.full(outputs.size(),now))dropped++;
        else { accepted++;outputs.addLast(now+latencyMs*1_000_000L);peak=Math.max(peak,outputs.size()); }
      }
    }
    check(accepted==fps*2&&dropped==0,"Pipelined codec starved: "+accepted+" accepted, "+dropped+" dropped");
    check(peak==3&&queue.limit()==3,"Unexpected pipeline bound");
    System.out.println(fps+"fps_"+latencyMs+"ms_pipeline_full_throughput");
  }
  static void stalled(boolean interactive) {
    SimpleRcEncoderQueue queue=new SimpleRcEncoderQueue(interactive,60); int depth=0;
    for(int i=0;i<100_000;i++)if(!queue.full(depth,i*1_000_000_000L))depth++;
    check(depth==3&&queue.limit()==3,"Stall changed bound");
    queue.output();check(queue.full(3,Long.MAX_VALUE),"Output notice expanded queue without depth change");
    check(!queue.full(2,Long.MAX_VALUE),"Freed slot not reusable");
    queue.reset();check(queue.limit()==3&&!queue.full(2,0)&&queue.full(3,0),"Reset changed bound");
    System.out.println((interactive?"interactive":"default")+"_permanent_stall_bounded_to_three_and_reset_safe");
  }
  static void clockAndRate() {
    for(int fps:new int[]{0,20,30,60,Integer.MAX_VALUE}) {
      SimpleRcEncoderQueue queue=new SimpleRcEncoderQueue(true,fps);
      check(queue.limit()==3&&!queue.full(2,Long.MAX_VALUE)&&queue.full(3,Long.MIN_VALUE),"Clock/rate changed limit");
    }
    System.out.println("clock_and_frame_rate_cannot_expand_queue");
  }
  static void renderer() {
    SimpleRcRenderQueue queue=new SimpleRcRenderQueue();
    check(queue.offer(1_000_000),"First frame did not post");check(!queue.offer(2_000_000),"Replacement posted duplicate");
    long afterPause=502_000_000L,arrival=queue.take();
    check(arrival==2_000_000L&&afterPause-arrival==500_000_000L,"Final update lost after pause");
    check(queue.take()==0,"Duplicate retained frame");check(queue.offer(afterPause+1),"Next frame cannot post");
    System.out.println("final_frame_retained_after_500ms_stall_without_new_frame");
    queue.reset();int jobs=0;for(int i=1;i<=100_000;i++)if(queue.offer(i))jobs++;
    check(jobs==1&&queue.take()==100_000,"Burst not coalesced to newest frame");
    System.out.println("hundred_thousand_frames_coalesce_to_one_latest_wakeup");
    check(queue.offer(10),"New frame missing");queue.clearFrame();check(!queue.offer(20)&&queue.take()==20,"Clear lost scheduled wakeup");
    queue.reset();check(queue.offer(30)&&queue.take()==30,"Release/reset lost new wakeup");
    System.out.println("clear_and_release_preserve_future_wakeups");
  }
  public static void main(String[] args) { pipeline(30,90);pipeline(60,45);stalled(true);stalled(false);clockAndRate();renderer(); }
}'''
with tempfile.TemporaryDirectory(prefix='simplerc-video-queues-') as folder:
    root = Path(folder)
    with ZipFile(built) as aar:
        current_classes = aar.read('classes.jar')
    with ZipFile(BytesIO(current_classes)) as classes:
        # Verify the production consumer cannot discard the last frame using the removed age test.
        assert b'stale' not in classes.read('org/webrtc/EglRenderer.class')
    (root / 'current.jar').write_bytes(current_classes)
    with ZipFile(previous) as aar:
        (root / 'previous.jar').write_bytes(aar.read('classes.jar'))
    (root / 'VideoQueuesTest.java').write_text(harness, encoding='utf-8')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', str(root / 'current.jar'), '-d', str(root),
                    str(root / 'VideoQueuesTest.java')], check=True)
    result = subprocess.check_output(['java', '-cp', os.pathsep.join([str(root), str(root / 'current.jar')]),
                                      'VideoQueuesTest'], text=True)
    regression = subprocess.run(['java', '-cp', os.pathsep.join([str(root), str(root / 'previous.jar')]),
                                 'VideoQueuesTest'], text=True, capture_output=True)
    assert regression.returncode != 0 and 'Pipelined codec starved' in regression.stderr, 'Previous starvation bug was not rejected'
    record = {'tests': len(result.strip().splitlines()), 'passed': result.strip().splitlines(),
              'previous_aar_starvation_rejected': True, 'production_renderer_age_drop_absent': True,
              'boundary': 'Actual built AAR Java helpers and renderer bytecode; no phone codec/GPU measured.'}
    receipt.write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(record, indent=2))

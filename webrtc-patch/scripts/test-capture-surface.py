"""Compile the actual capturer against behavioral Android stubs; no device is used.

VirtualDisplay.setSurface preserves AOSP's object-identity short circuit, so the
pre-0.3.13 same-Surface regression fails this test. This does not test real pixels.
"""
from pathlib import Path
import json
import shutil
import subprocess
import sys
import tempfile

source = Path(sys.argv[1]).resolve()
if source.is_dir():
    source /= 'stream-webrtc-android/src/main/java/org/webrtc/ScreenCapturerAndroid.java'
stubs = {
    'android/app/Activity.java': 'package android.app; public class Activity { public static final int RESULT_OK=-1; }',
    'android/content/Intent.java': 'package android.content; public class Intent {}',
    'android/content/Context.java': '''package android.content;
public class Context { public static final String MEDIA_PROJECTION_SERVICE="projection";
public Object getSystemService(String service) { return new android.media.projection.MediaProjectionManager(); } }''',
    'android/os/Build.java': '''package android.os; public class Build {
public static class VERSION { public static int SDK_INT=31; }
public static class VERSION_CODES { public static final int S=31; } }''',
    'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
    'android/hardware/display/DisplayManager.java': '''package android.hardware.display;
public class DisplayManager { public static final int VIRTUAL_DISPLAY_FLAG_PUBLIC=1, VIRTUAL_DISPLAY_FLAG_PRESENTATION=2; }''',
    'org/webrtc/TestLog.java': '''package org.webrtc; import java.util.*;
public class TestLog { public static final List<String> events=new ArrayList<>();
public static void add(String e) { events.add(e); } }''',
    'android/view/Surface.java': '''package android.view; import java.util.*; import org.webrtc.TestLog;
public class Surface { public static final List<Surface> all=new ArrayList<>(); public int releases;
public Surface(Object texture) { all.add(this); TestLog.add("surface-new"); }
public void release() { releases++; TestLog.add("surface-release"); } }''',
    'android/hardware/display/VirtualDisplay.java': '''package android.hardware.display;
import android.view.Surface; import org.webrtc.TestLog;
public class VirtualDisplay { public Surface surface; public int releases,bindings,resizes;
public boolean failBind,failResize;
public VirtualDisplay(Surface s) { surface=s; }
public void resize(int w,int h,int dpi) { TestLog.add("resize"); if(failResize)throw new IllegalStateException("resize failed"); resizes++; }
public void setSurface(Surface s) { if(surface!=s) { TestLog.add("bind"); if(failBind)throw new IllegalStateException("bind failed"); surface=s; bindings++; } }
public void release() { releases++; TestLog.add("display-release"); } }''',
    'android/media/projection/MediaProjection.java': '''package android.media.projection;
import android.hardware.display.VirtualDisplay; import android.view.Surface; import org.webrtc.TestLog;
public class MediaProjection { public static class Callback {} public VirtualDisplay display;
public int creates,stops; public boolean failCreate;
public void registerCallback(Callback c,Object h) {} public void unregisterCallback(Callback c) {}
public void stop() { stops++; }
public VirtualDisplay createVirtualDisplay(String name,int w,int h,int dpi,int flags,Surface s,Object c,Object handler) {
TestLog.add("create"); if(failCreate)throw new IllegalStateException("create failed"); creates++; return display=new VirtualDisplay(s); } }''',
    'android/media/projection/MediaProjectionManager.java': '''package android.media.projection;
import android.content.Intent; public class MediaProjectionManager { public static MediaProjection current;
public MediaProjection getMediaProjection(int result,Intent data) { return current; } }''',
    'org/webrtc/VideoCapturer.java': '''package org.webrtc; import android.content.Context;
public interface VideoCapturer { void initialize(SurfaceTextureHelper h,Context c,CapturerObserver o);
void startCapture(int w,int h,int fps); void stopCapture(); void dispose(); void changeCaptureFormat(int w,int h,int fps); boolean isScreencast(); }''',
    'org/webrtc/VideoSink.java': 'package org.webrtc; public interface VideoSink { void onFrame(VideoFrame f); }',
    'org/webrtc/VideoFrame.java': 'package org.webrtc; public class VideoFrame {}',
    'org/webrtc/CapturerObserver.java': '''package org.webrtc; public interface CapturerObserver {
void onCapturerStarted(boolean ok); void onCapturerStopped(); void onFrameCaptured(VideoFrame f); }''',
    'org/webrtc/SurfaceTextureHelper.java': '''package org.webrtc; public class SurfaceTextureHelper {
public Object getHandler() {return this;} public Object getSurfaceTexture() {return this;}
public void setTextureSize(int w,int h) {TestLog.add("texture");} public void startListening(VideoSink s) {} public void stopListening() {} }''',
    'org/webrtc/ThreadUtils.java': '''package org.webrtc; public class ThreadUtils {
public static void invokeAtFrontUninterruptibly(Object h,Runnable r) { r.run(); } }''',
    'org/webrtc/CaptureSurfaceTest.java': '''package org.webrtc;
import android.hardware.display.VirtualDisplay; import android.media.projection.*;
import android.view.Surface; import java.lang.reflect.Field; import java.util.*;
public class CaptureSurfaceTest {
static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
static void pass(String name) { System.out.println(name); }
static ScreenCapturerAndroid create(int api) {
  android.os.Build.VERSION.SDK_INT=api; Surface.all.clear(); TestLog.events.clear();
  MediaProjectionManager.current=new MediaProjection();
  ScreenCapturerAndroid c=new ScreenCapturerAndroid(new android.content.Intent(),new MediaProjection.Callback());
  c.initialize(new SurfaceTextureHelper(),new android.content.Context(),new CapturerObserver() {
    public void onCapturerStarted(boolean ok) {} public void onCapturerStopped() {} public void onFrameCaptured(VideoFrame f) {} }); return c;
}
static Surface owned(ScreenCapturerAndroid c) throws Exception {
  Field f=ScreenCapturerAndroid.class.getDeclaredField("captureSurface"); f.setAccessible(true); return (Surface)f.get(c);
}
static void expectFailure(Runnable action) { try { action.run(); } catch(IllegalStateException expected) { return; } throw new AssertionError("Expected failure"); }
static void allReleasedOnce() { for(Surface s:Surface.all)check(s.releases==1,"Surface leak or double release"); }
static void testModern(int api) throws Exception {
  ScreenCapturerAndroid c=create(api); c.startCapture(576,1280,30);
  MediaProjection p=MediaProjectionManager.current; VirtualDisplay d=p.display;
  for(int i=0;i<12;i++) {
    Surface previous=d.surface; TestLog.events.clear(); c.changeCaptureFormat(i%2==0?1280:576,i%2==0?576:1280,30);
    check(p.creates==1,"Modern Android must not recreate display"); check(d.surface!=previous,"Surface must change identity");
    check(d.surface==owned(c),"New Surface ownership lost"); check(previous.releases==1,"Old Surface not released exactly once");
    check(d.surface.releases==0,"Active Surface released");
    check(TestLog.events.equals(Arrays.asList("texture","resize","surface-new","bind","surface-release")),"Wrong resize order: "+TestLog.events);
  }
  check(d.bindings==12&&d.resizes==12,"Missing resize or rebind"); c.stopCapture(); allReleasedOnce();
  check(d.releases==1&&p.stops==1,"Display/projection cleanup"); c.stopCapture(); allReleasedOnce();
  pass("api_"+api+"_rebind_rotation_and_cleanup");
}
static void testLegacy(int api) {
  ScreenCapturerAndroid c=create(api); c.startCapture(576,1280,30); MediaProjection p=MediaProjectionManager.current;
  for(int i=0;i<4;i++) {
    VirtualDisplay old=p.display; Surface previous=old.surface; TestLog.events.clear(); c.changeCaptureFormat(1280,576,30);
    check(old.releases==1&&previous.releases==1,"Legacy cleanup"); check(old.resizes==0&&old.bindings==0,"Legacy must recreate");
    check(p.display!=old&&p.display.surface!=previous,"Legacy reused resources");
    check(TestLog.events.equals(Arrays.asList("texture","display-release","surface-release","surface-new","create")),"Legacy order");
  }
  check(p.creates==5,"Legacy create count"); c.stopCapture(); allReleasedOnce(); pass("api_"+api+"_recreate_and_cleanup");
}
static void testBindFailure() throws Exception {
  ScreenCapturerAndroid c=create(34); c.startCapture(576,1280,30); VirtualDisplay d=MediaProjectionManager.current.display;
  Surface previous=d.surface; d.failBind=true; expectFailure(()->c.changeCaptureFormat(1280,576,30));
  check(d.surface==previous&&owned(c)==previous&&previous.releases==0,"Failed bind lost old Surface");
  check(Surface.all.size()==2&&Surface.all.get(1).releases==1,"Failed bind leaked replacement");
  d.failBind=false; c.changeCaptureFormat(1280,576,30); check(d.surface!=previous&&previous.releases==1,"Retry failed");
  c.stopCapture(); allReleasedOnce(); pass("failed_bind_preserves_owner_releases_replacement_and_can_retry");
}
static void testResizeFailure() throws Exception {
  ScreenCapturerAndroid c=create(34); c.startCapture(576,1280,30); VirtualDisplay d=MediaProjectionManager.current.display;
  Surface previous=d.surface; d.failResize=true; expectFailure(()->c.changeCaptureFormat(1280,576,30));
  check(Surface.all.size()==1&&owned(c)==previous&&previous.releases==0,"Resize failure changed ownership");
  c.stopCapture(); allReleasedOnce(); pass("failed_resize_preserves_owner");
}
static void testCreateFailure(int api,boolean initial) throws Exception {
  ScreenCapturerAndroid c=create(api); MediaProjection p=MediaProjectionManager.current;
  if(!initial)c.startCapture(576,1280,30); p.failCreate=true;
  if(initial)expectFailure(()->c.startCapture(576,1280,30)); else expectFailure(()->c.changeCaptureFormat(1280,576,30));
  check(owned(c)==null,"Create failure retains Surface"); allReleasedOnce(); c.stopCapture(); allReleasedOnce();
  pass("api_"+api+"_"+(initial?"initial":"replacement")+"_create_failure_cleanup");
}
public static void main(String[] args) throws Exception {
  for(int api:new int[]{31,32,34,35})testModern(api);
  for(int api:new int[]{29,30})testLegacy(api);
  testBindFailure(); testResizeFailure(); testCreateFailure(34,true); testCreateFailure(29,false);
}
}''',
}
with tempfile.TemporaryDirectory(prefix='simplerc-capture-test-') as tmp:
    root = Path(tmp)
    for name, text in stubs.items():
        file = root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding='utf-8')
    shutil.copyfile(source, root / 'org/webrtc/ScreenCapturerAndroid.java')
    classes = root / 'classes'
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(classes),
                    *map(str, root.rglob('*.java'))], check=True)
    result = subprocess.check_output(['java', '-cp', str(classes), 'org.webrtc.CaptureSurfaceTest'], text=True)
    cases = result.strip().splitlines()
    # Verify that this harness rejects the exact previous same-Surface regression.
    actual = source.read_text(encoding='utf-8')
    assert actual.count('      replaceCaptureSurface();') == 1
    (root / 'org/webrtc/ScreenCapturerAndroid.java').write_text(
        actual.replace('      replaceCaptureSurface();', '      virtualDisplay.setSurface(captureSurface);'), encoding='utf-8')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(classes),
                    *map(str, root.rglob('*.java'))], check=True)
    regression = subprocess.run(['java', '-cp', str(classes), 'org.webrtc.CaptureSurfaceTest'], capture_output=True, text=True)
    assert regression.returncode != 0 and 'Surface must change identity' in regression.stderr, 'Harness did not reject the previous regression'
    record = {'tests': len(cases), 'passed': cases, 'previous_same_surface_regression_rejected': True,
              'boundary': 'Actual capturer Java with behavioral Android stubs; no device pixels tested.'}
    if len(sys.argv) > 2:
        Path(sys.argv[2]).write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(record, indent=2))

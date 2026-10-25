"""Resize with a fresh Surface on Android 12+, recreate on older devices."""
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
assert subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip() == '5d388e533d87f857e7de4a729630200cf9766369'
file = root / 'stream-webrtc-android/src/main/java/org/webrtc/ScreenCapturerAndroid.java'
text = file.read_text()
assert 'captureSurface' not in text, 'Already patched'
changes = [
    ('import android.view.Surface;', 'import android.view.Surface;\nimport android.os.Build.VERSION;\nimport android.os.Build.VERSION_CODES;'),
    ('  @Nullable private VirtualDisplay virtualDisplay;', '  @Nullable private VirtualDisplay virtualDisplay;\n  @Nullable private Surface captureSurface;'),
    ('        if (mediaProjection != null) {', '        if (captureSurface != null) {\n          captureSurface.release();\n          captureSurface = null;\n        }\n\n        if (mediaProjection != null) {'),
    ('    createVirtualDisplay();\n    capturerObserver.onCapturerStarted(true);', '    updateVirtualDisplay();\n    capturerObserver.onCapturerStarted(true);'),
    ('        virtualDisplay.release();\n        createVirtualDisplay();', '        updateVirtualDisplay();'),
    ('''  private void createVirtualDisplay() {
    surfaceTextureHelper.setTextureSize(width, height);''', '''  private void updateVirtualDisplay() {
    surfaceTextureHelper.setTextureSize(width, height);
    // Match upstream: resize can scale incorrectly before Android 12. Android 14+
    // must retain the display because one projection grant permits one creation.
    if (virtualDisplay == null || VERSION.SDK_INT < VERSION_CODES.S) {
      createVirtualDisplay();
    } else {
      virtualDisplay.resize(width, height, VIRTUAL_DISPLAY_DPI);
      // VirtualDisplay ignores setSurface when given the same Java Surface object.
      // Follow upstream with a new wrapper so the resized producer is rebound.
      replaceCaptureSurface();
    }
  }

  private void replaceCaptureSurface() {
    final Surface nextSurface = new Surface(surfaceTextureHelper.getSurfaceTexture());
    try {
      virtualDisplay.setSurface(nextSurface);
    } catch (RuntimeException failure) {
      nextSurface.release();
      throw failure;
    }
    final Surface previousSurface = captureSurface;
    captureSurface = nextSurface;
    if (previousSurface != null) {
      previousSurface.release();
    }
  }

  private void createVirtualDisplay() {
    if (virtualDisplay != null) {
      virtualDisplay.release();
      virtualDisplay = null;
    }
    if (captureSurface != null) {
      captureSurface.release();
      captureSurface = null;
    }'''),
    ('''    virtualDisplay = mediaProjection.createVirtualDisplay("WebRTC_ScreenCapture", width, height,
        VIRTUAL_DISPLAY_DPI, DISPLAY_FLAGS, new Surface(surfaceTextureHelper.getSurfaceTexture()),
        null /* callback */, null /* callback handler */);''', '''    captureSurface = new Surface(surfaceTextureHelper.getSurfaceTexture());
    try {
      virtualDisplay = mediaProjection.createVirtualDisplay("WebRTC_ScreenCapture", width, height,
          VIRTUAL_DISPLAY_DPI, DISPLAY_FLAGS, captureSurface,
          null /* callback */, null /* callback handler */);
    } catch (RuntimeException failure) {
      captureSurface.release();
      captureSurface = null;
      throw failure;
    }'''),
]
for old, new in changes:
    assert text.count(old) == 1, (old[:80], text.count(old))
    text = text.replace(old, new, 1)
file.write_text(text)
print('PATCHED_CAPTURE_ANDROID_12_RESIZE_AND_OLDER_RECREATE_WITH_SURFACE_OWNERSHIP')

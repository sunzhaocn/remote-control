"""Regenerate the minimal video diff from the pinned upstream, without resetting the audio patch."""
from pathlib import Path
import subprocess
import sys
import tempfile
import shutil

sdk = Path(sys.argv[1]).resolve()
project = Path(__file__).resolve().parents[1]
prefix = 'stream-webrtc-android/src/main/java/org/webrtc'
names = ['HardwareVideoEncoder.java', 'AndroidVideoDecoder.java', 'EglRenderer.java', 'SurfaceTextureHelper.java']
paths = [prefix + '/' + name for name in names]
capture_path = prefix + '/ScreenCapturerAndroid.java'
assert subprocess.check_output(['git', '-C', str(sdk), 'rev-parse', 'HEAD'], text=True).strip() == '5d388e533d87f857e7de4a729630200cf9766369'
with tempfile.TemporaryDirectory(prefix='simplerc-video-patch-') as folder:
    clean = Path(folder) / 'upstream'
    subprocess.run(['git', 'clone', '--quiet', '--shared', '--no-checkout', str(sdk), str(clean)], check=True)
    subprocess.run(['git', '-C', str(clean), 'checkout', 'HEAD', '--', *paths, capture_path], check=True)
    subprocess.run([sys.executable, str(project / 'scripts/apply-realtime-patch.py'), str(clean)], check=True)
    subprocess.run([sys.executable, str(project / 'scripts/apply-capture-resize-patch.py'), str(clean)], check=True)
    subprocess.run(['git', '-C', str(clean), 'diff', '--check'], check=True)
    patch = subprocess.check_output(['git', '-C', str(clean), 'diff', '--', *paths])
    (project / 'patches/0002-low-latency-video.patch').write_bytes(patch)
    capture_patch = subprocess.check_output(['git', '-C', str(clean), 'diff', '--', capture_path])
    (project / 'patches/0003-rotation-safe-capture.patch').write_bytes(capture_patch)
    for name in names + ['ScreenCapturerAndroid.java'] + [file.name for file in (project / 'library/org/webrtc').glob('*.java')]:
        shutil.copyfile(clean / prefix / name, sdk / prefix / name)
    # The generated diff must match the applied SDK source exactly, with no audio changes.
    actual = subprocess.check_output(['git', '-C', str(sdk), 'diff', '--', *paths])
    assert actual == patch
    assert subprocess.check_output(['git', '-C', str(sdk), 'diff', '--', capture_path]) == capture_patch
print('PINNED_REVISION_PATCH_REPRODUCED_AND_VIDEO_SOURCE_MATCHED')

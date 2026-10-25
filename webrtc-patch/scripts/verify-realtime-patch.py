"""Replay all three patches on pinned sources and compare the complete patched Java set."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

sdk = Path(sys.argv[1]).resolve()
project = Path(__file__).resolve().parents[1]
prefix = 'stream-webrtc-android/src/main/java/org/webrtc'
revision = '5d388e533d87f857e7de4a729630200cf9766369'
sources = [
    'HardwareVideoEncoder.java', 'AndroidVideoDecoder.java', 'EglRenderer.java',
    'ScreenCapturerAndroid.java', 'SurfaceTextureHelper.java', 'audio/JavaAudioDeviceModule.java',
    'audio/WebRtcAudioRecord.java',
]
assert subprocess.check_output(['git', '-C', str(sdk), 'rev-parse', 'HEAD'], text=True).strip() == revision
with tempfile.TemporaryDirectory(prefix='simplerc-all-patches-') as folder:
    clean = Path(folder) / 'upstream'
    subprocess.run(['git', 'clone', '--quiet', '--shared', '--no-checkout', str(sdk), str(clean)], check=True)
    subprocess.run(['git', '-C', str(clean), 'checkout', 'HEAD', '--',
                    *[prefix + '/' + name for name in sources]], check=True)
    for name in ['0001-audioplaybackcapture-factory.patch', '0002-low-latency-video.patch',
                 '0003-rotation-safe-capture.patch']:
        patch = str(project / 'patches' / name)
        subprocess.run(['git', '-C', str(clean), 'apply', '--check', patch], check=True)
        subprocess.run(['git', '-C', str(clean), 'apply', patch], check=True)
    helpers = list((project / 'library/org/webrtc').glob('*.java'))
    for file in helpers:
        shutil.copyfile(file, clean / prefix / file.name)
    subprocess.run(['git', '-C', str(clean), 'diff', '--check'], check=True)
    for name in sources + [file.name for file in helpers]:
        expected = clean / prefix / name
        actual = sdk / prefix / name
        assert expected.read_bytes() == actual.read_bytes(), 'SDK source differs: ' + name
print('AUDIO_VIDEO_CAPTURE_PATCHES_REPLAYED_AND_ALL_JAVA_SOURCES_MATCHED')

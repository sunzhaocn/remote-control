"""Compare native libraries and required Java patches before replacing the application's AAR."""
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import sys
from zipfile import ZipFile

previous, built, receipt = map(Path, sys.argv[1:4])
with ZipFile(previous) as old, ZipFile(built) as new:
    native_names = sorted(n for n in new.namelist() if n.startswith('jni/') and n.endswith('.so'))
    assert native_names == sorted(n for n in old.namelist() if n.startswith('jni/') and n.endswith('.so'))
    assert len(native_names) == 4
    native_hashes = {}
    for name in native_names:
        assert old.read(name) == new.read(name), 'Native library changed: ' + name
        native_hashes[name] = sha256(new.read(name)).hexdigest()
    with ZipFile(BytesIO(new.read('classes.jar'))) as classes, ZipFile(BytesIO(old.read('classes.jar'))) as old_classes:
        audio_names = sorted(n for n in classes.namelist() if n.startswith('org/webrtc/audio/') and n.endswith('.class'))
        assert audio_names == sorted(n for n in old_classes.namelist() if n.startswith('org/webrtc/audio/') and n.endswith('.class'))
        for name in audio_names:
            assert old_classes.read(name) == classes.read(name), 'Audio Java implementation changed: ' + name
        for name in ['org/webrtc/audio/JavaAudioDeviceModule$AudioRecordFactory.class',
                     'org/webrtc/SimpleRcEncoderQueue.class', 'org/webrtc/SimpleRcRenderQueue.class',
                     'org/webrtc/SimpleRcVideoTuning.class', 'org/webrtc/ScreenCapturerAndroid.class',
                     'org/webrtc/SimpleRcFrameRefresh.class']:
            assert name in classes.namelist(), 'Missing Java patch: ' + name
        encoder = classes.read('org/webrtc/HardwareVideoEncoder.class')
        assert b'max-fps-to-encoder' not in encoder, 'MediaCodec input dropping is still configured'
        assert b'SimpleRcEncoderQueue' in encoder
        assert b'takeKeyFrameRequest' in encoder, 'Missing clear-frame key request consumer'
        assert b'org/webrtc/SimpleRcFrameRefresh' in encoder, 'Missing clear-frame completion hook'
        texture_helper = classes.read('org/webrtc/SurfaceTextureHelper.class')
        assert b'SimpleRcCaptureScheduler' in texture_helper and b'setCaptureFrameRate' in texture_helper
        capture = classes.read('org/webrtc/ScreenCapturerAndroid.class')
        assert b'android/os/Build$VERSION' in capture and b'SDK_INT' in capture, 'Missing capture API guard'
        assert b'replaceCaptureSurface' in capture, 'Missing capture Surface rebind fix'
        renderer = classes.read('org/webrtc/EglRenderer.class')
        assert b'stale' not in renderer, 'Latest decoded frame is still discarded solely by age'
        assert b'SimpleRcVsyncScheduler' in renderer and b'closeSimpleRcVsync' in renderer
        surface_creation = classes.read('org/webrtc/EglRenderer$EglSurfaceCreation.class')
        assert b'surfaceReady' in surface_creation, 'Surface recreation can leave a phantom render wakeup'
record = {
    'previous_aar_sha256': sha256(previous.read_bytes()).hexdigest(),
    'aar_bytes': built.stat().st_size,
    'aar_sha256': sha256(built.read_bytes()).hexdigest(),
    'native_libraries_unchanged': native_hashes,
    'audio_capture_patch_present': True,
    'video_and_rotation_capture_patches_present': True,
    'codec_surface_input_dropping_disabled': True,
    'capture_resize_version_guard_present': True,
    'capture_surface_rebind_fix_present': True,
    'audio_java_classes_unchanged': len(audio_names),
    'renderer_latest_frame_age_drop_removed': True,
    'clear_frame_request_and_completion_hooks_present': True,
    'capture_deadline_and_vsync_render_schedulers_present': True,
}
receipt.write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
print(json.dumps(record, indent=2))

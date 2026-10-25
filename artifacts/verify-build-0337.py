"""Verify the frozen 0.3.37 release, then publish its reviewed APK and receipt."""
from collections import Counter
from pathlib import Path
import datetime
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def wsl(path):
    return '/mnt/' + path.drive[0].lower() + path.as_posix()[2:]


def sdk(name, *args):
    return subprocess.check_output([
        'wsl', '-d', 'SimpleRC-WebRTC', '--', '/usr/bin/env',
        'JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64',
        '/opt/android-sdk/build-tools/35.0.0/' + name, *args,
    ], text=True, encoding='utf-8')


def permissions(path):
    return set(re.findall(r"uses-permission: name='([^']+)'", sdk('aapt2', 'dump', 'permissions', wsl(path))))


frozen = json.loads((ROOT / 'artifacts/INPUTS_0.3.37.json').read_text(encoding='utf-8'))
assert all(digest(ROOT / name) == value for name, value in frozen.items()), 'Build inputs changed'
log = (ROOT / 'artifacts/build-0.3.37-final.log').read_text(encoding='utf-8')
assert 'BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log

tests = {}
for variant in ('Release', 'Debug', 'Compare80', 'Compare88'):
    rows = [ET.parse(p).getroot() for p in (ROOT / f'android/app/build/test-results/test{variant}UnitTest').glob('TEST-*.xml')]
    total = sum(int(row.attrib['tests']) for row in rows)
    assert total == 650 and len(rows) == 58, (variant, total, len(rows))
    assert all(int(row.attrib[key]) == 0 for row in rows for key in ('failures', 'errors', 'skipped'))
    for name, count in [('NativeResolutionPipelineTest', 5), ('SignalClientTest', 8), ('Fixed1080VideoPipelineTest', 13), ('GeometryMapperTest', 6), ('IcePathTest', 15), ('IcePairSelectionTest', 20), ('RouteDiagnosticsTest', 9), ('PeerClockEstimateTest', 9), ('VideoCadenceProtectionTest', 17), ('VideoSenderProfileTest', 6)]:
        assert any(row.attrib['name'].endswith('.' + name) and int(row.attrib['tests']) == count for row in rows)
    tests[variant] = {'total': total, 'suite_count': len(rows), 'failures': 0, 'errors': 0, 'skipped': 0}

lint = {}
for variant in ('release', 'debug'):
    report = ET.parse(ROOT / f'android/app/build/reports/lint-results-{variant}.xml').getroot()
    issues = report.findall('issue')
    assert not any(issue.attrib['severity'] in ('Error', 'Fatal') for issue in issues)
    lint[variant] = {'errors': 0, 'warnings': dict(Counter(issue.attrib['id'] for issue in issues if issue.attrib['severity'] == 'Warning'))}

previous = json.loads((ROOT / 'artifacts/BUILD_0.3.33.json').read_text(encoding='utf-8'))
old_apks = {}
for package in previous['packages'].values():
    path = ROOT / 'artifacts' / package['apk']
    assert digest(path) == package['sha256'], 'Previous delivery changed'
    old_apks[path.name] = package['sha256']
latest_previous = json.loads((ROOT / 'artifacts/BUILD_0.3.34.json').read_text(encoding='utf-8'))
latest_apk = ROOT / 'artifacts' / latest_previous['apk']
assert digest(latest_apk) == latest_previous['sha256'], 'Previous 0.3.34 delivery changed'
old_apks[latest_apk.name] = latest_previous['sha256']
previous_fhd = json.loads((ROOT / 'artifacts/BUILD_0.3.35.json').read_text(encoding='utf-8'))
previous_fhd_apk = ROOT / 'artifacts' / previous_fhd['apk']
assert digest(previous_fhd_apk) == previous_fhd['sha256'], 'Previous 0.3.35 delivery changed'
old_apks[previous_fhd_apk.name] = previous_fhd['sha256']
policy_unchanged = ['config/AppConfig.kt', 'webrtc/ScreenContentVideoPlan.kt',
    'webrtc/VideoCaptureFormat.kt', 'webrtc/VideoSenderProfile.kt']
for item in policy_unchanged:
    name = 'android/app/src/main/java/com/simplerc/app/' + item
    assert frozen[name] == previous_fhd['source_sha256'][name], 'Video policy unexpectedly changed: ' + item
previous_route = json.loads((ROOT / 'artifacts/BUILD_0.3.36.json').read_text(encoding='utf-8'))
previous_route_apk = ROOT / 'artifacts' / previous_route['apk']
assert digest(previous_route_apk) == previous_route['sha256'], 'Previous 0.3.36 delivery changed'
old_apks[previous_route_apk.name] = previous_route['sha256']
old_apk = previous_route_apk
build_apk = ROOT / 'android/app/build/outputs/apk/release/app-release.apk'
signature = sdk('apksigner', 'verify', '--verbose', '--print-certs', wsl(build_apk))
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
certificate = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', signature).group(1)
assert certificate == previous['signer_certificate_sha256']
badge = sdk('aapt2', 'dump', 'badging', wsl(build_apk))
assert "package: name='com.simplerc.app' versionCode='40' versionName='0.3.37'" in badge
assert "application-label:'SimpleRC'" in badge
assert "launchable-activity: name='com.simplerc.app.MainActivity'" in badge
manifest = sdk('aapt2', 'dump', 'xmltree', wsl(build_apk), '--file', 'AndroidManifest.xml')
assert 'android:debuggable' not in manifest or not re.search(r'android:debuggable[^\n]*0xffffffff', manifest)
assert manifest.count('android:foregroundServiceType') == 3
assert permissions(build_apk) == permissions(old_apk)
build_config = (ROOT / 'android/app/build/generated/source/buildConfig/release/com/simplerc/app/BuildConfig.java').read_text(encoding='utf-8')
assert 'boolean DEBUG = false' in build_config
assert 'boolean FIXED_80_COMPARE = false' in build_config
assert 'boolean FIXED_88_COMPARE = false' in build_config

native = {}
with zipfile.ZipFile(build_apk) as new, zipfile.ZipFile(old_apk) as old:
    assert new.testzip() is None
    paths = [name for name in new.namelist() if name.endswith('/libjingle_peerconnection_so.so')]
    assert len(paths) == 4
    for name in paths:
        assert new.read(name) == old.read(name), 'Native library unexpectedly changed'
        native[name] = hashlib.sha256(new.read(name)).hexdigest()
    dex = [new.read(name) for name in new.namelist() if re.fullmatch(r'classes\d*\.dex', name)]
    for value in ('全程固定1080p', '1080p发送参数未应用', '媒体路径（视频/音频）', '独立控制路径', '媒体候选对', 'candidate-pair.selected', '对端报告', '路径未知', '24fps设备保护', '编码队列积压'):
        assert any(value.encode('utf-8') in data for data in dex), value
    assert any(b'Lcom/simplerc/app/signaling/SignalClient$Connection;' in data for data in dex)

aar = ROOT / 'android/app/libs/stream-webrtc-android-simplerc-1.3.10.aar'
assert digest(aar) == previous['aar_sha256']
assert all(digest(ROOT / name) == value for name, value in frozen.items()), 'Inputs changed during verification'
apk = ROOT / 'artifacts/SimpleRC-0.3.37-1080p-release.apk'
shutil.copy2(build_apk, apk)
assert digest(apk) == digest(build_apk)
receipt = {
    'version': '0.3.37', 'version_code': 40,
    'verified_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'apk': apk.name, 'bytes': apk.stat().st_size, 'sha256': digest(apk),
    'application_id': 'com.simplerc.app', 'build_type': 'release', 'debuggable': False,
    'signature_v2_verified': True, 'signer_certificate_sha256': certificate,
    'signing_note': 'Existing development certificate retained for same-package updates.',
    'android_tests': tests, 'lint': lint, 'server_changes_required': False,
    'source_sha256': frozen, 'frozen_input_count': len(frozen),
    'previous_delivered_apks': old_apks,
    'aar_sha256': digest(aar), 'webrtc_native_sha256': native,
    'android_permissions_unchanged': True,
    'resolution': {
        'default_strategy': '1080 short-edge cap', 'tuned_strategy': '1080 short-edge cap',
        'short_edge_limit': 1080, 'preserve_aspect_ratio': True, 'upscale_small_screens': False,
        'examples': {'1440x3200': '1080x2400', '1220x2712': '1080x2400', '1240x2772': '1080x2414', '2160x3840': '1080x1920'},
        'physical_screen_coordinates_unchanged': True, 'downscale_before_encoding': True,
        'all_activities': True, 'capture_mirror_stays_native_and_stable': True,
        'rtp_scale_resolution_down_by': 1.0, 'degradation_preference': 'DISABLED',
        'dimension_alignment': 'Scaled dimensions round down to even pixels; inverse-scale floating-point error is corrected.',
        'fps_target': 30, 'device_protection_fps': 24, 'fps_adaptive_in_tuned_strategy': True, 'video_compression': 'Existing lossy video encoding and congestion control remain.',
    },
    'route_diagnostics': {
        'unknown_is_never_p2p': True, 'transport_selected_pair_id_preferred': True,
        'legacy_selected_only_when_unique_and_unambiguous': True,
        'no_nominated_pair_guessing': True, 'missing_selection_clears_old_path': True,
        'media_and_control_diagnostics_separate': True,
        'candidate_types_pair_id_and_selection_source_visible': True,
        'peer_local_candidate_self_report': 'Media only; explicitly labeled when it reveals relay.',
        'remote_prflx_requires_peer_candidate_evidence_for_direct': True,
        'feedback_original_lifetime_ms': 1500,
        'video_policy_files_unchanged_from_0_3_35': policy_unchanged,
        'does_not_force_direct_connectivity': True,
    },
    'device_cadence_protection': {
        'normal_fps': 30, 'protected_fps': 24,
        'persistent_measured_device_pressure_samples': 2, 'severe_pressure_can_act_immediately': True,
        'recovery_continuous_healthy_duration_ms': 8000, 'minimum_valid_sample_spacing_ms': 750,
        'maximum_continuous_sample_gap_ms': 1500, 'post_recovery_minimum_residence_ms': 3000,
        'network_congestion_alone_does_not_lower_fps': True,
        'unknown_or_sparse_activity_does_not_prove_pressure': True,
        'stale_cpu_flag_alone_does_not_block_recovery': True,
        'texture_capture_rate_applied_on_successful_fps_change': True,
        'capture_surface_does_not_resize_for_fps': True,
        'default_strategy_unchanged': True,
        'storage_permission_or_video_disk_cache_added': False,
        'real_device_performance_improvement_verified': False,
    },
    'signal_connection_ownership_regressions': 8,
    'phone_installation_or_hardware_testing_performed': False,
    'server_deployed': False,
    'known_boundary': 'Vendor-enforced single-app capture scope remains a pre-existing unverified limitation.',
}
(ROOT / 'artifacts/BUILD_0.3.37.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({key: receipt[key] for key in ('apk', 'bytes', 'sha256', 'debuggable', 'signature_v2_verified', 'android_tests', 'lint', 'frozen_input_count')}, ensure_ascii=False, indent=2))

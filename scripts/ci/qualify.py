#!/usr/bin/env python3
"""Sign/inspect an unsigned APK in an isolated job; never run source build code here."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile
from envelope import validate, group


def run(args):
    return subprocess.check_output([str(x) for x in args], text=True, stderr=subprocess.STDOUT, timeout=90)


def check_badging(text, info):
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", text, re.M)
    if not package or package.groups() != (info['package'], str(info['version_code']), info['version_name']):
        raise ValueError('APK package/version does not match the requested build')
    minimum = re.search(r"^sdkVersion:'([0-9]+)'", text, re.M)
    if not minimum or int(minimum.group(1)) != 26:
        raise ValueError('Expected API 26 minimum, compatible with A4 API 28')


def backend_protocol(dex):
    # Identify the embedded implementation, including supported older diagnostic builds.
    # An unknown/mixed artifact must not be labelled using this script's own source version.
    known = ('apk-jni-v2', 'apk-jni-v3/root-pipe-v1', 'apk-jni-v4/root-pipe-v1')
    matches = [protocol for protocol in known if protocol.encode('ascii') + b'\0' in dex]
    if len(matches) != 1:
        raise ValueError('Missing or ambiguous embedded backend protocol')
    return matches[0]


def qualify(incoming, destination, tools, expected_sha, run_id):
    if sorted(x.name for x in incoming.iterdir()) != ['BUILD_INFO.json', 'app-unsigned.apk']:
        raise ValueError('Unexpected build hand-off files')
    for item in incoming.iterdir():
        if item.is_symlink() or not item.is_file(): raise ValueError('Non-regular hand-off file')
    info = validate(json.loads((incoming / 'BUILD_INFO.json').read_text()))
    if info['source_sha'] != expected_sha:
        raise ValueError('Build metadata source does not match resolver source')
    check_badging(run([tools / 'aapt', 'dump', 'badging', incoming / 'app-unsigned.apk']), info)
    manifest = run([tools / 'aapt', 'dump', 'xmltree', incoming / 'app-unsigned.apk', 'AndroidManifest.xml'])
    if 'android.intent.category.LEANBACK_LAUNCHER' not in manifest:
        raise ValueError('Missing TV launcher entry')
    with zipfile.ZipFile(incoming / 'app-unsigned.apk') as archive:
        dex = b''.join(archive.read(name) for name in archive.namelist() if re.fullmatch(r'classes[0-9]*\.dex', name))
        if expected_sha.encode('ascii') not in dex: raise ValueError('APK does not embed the requested source identity')
        embedded_backend = backend_protocol(dex)
        for member in archive.namelist():
            if member.startswith('/') or '..' in Path(member).parts: raise ValueError('Unsafe APK member')
            if member.startswith('lib/') and not member.startswith('lib/armeabi-v7a/'):
                raise ValueError('Unexpected native ABI')
            if member.endswith('libcom_mediatek_twoworlds_tv_jni.so'):
                raise ValueError('Proprietary vendor JNI must not be redistributed')
    destination.mkdir(parents=True, exist_ok=False)
    suffix = group(info, run_id, os.environ.get('GITHUB_RUN_ATTEMPT', '1')) if info['channel'] == 'testing' else info['version_name']
    prefix = 'TESTING-' if info['channel'] == 'testing' else ''
    apk = destination / f'red-moon-tv-{prefix}{suffix}.apk'
    with tempfile.TemporaryDirectory(prefix='red-moon-signing-') as temp:
        key = Path(temp) / 'release.jks'
        key.write_bytes(base64.b64decode(os.environ['KEYSTORE_BASE64'], validate=True))
        key.chmod(0o600)
        aligned = Path(temp) / 'aligned.apk'
        run([tools / 'zipalign', '-f', '4', incoming / 'app-unsigned.apk', aligned])
        run([tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'env:KEYSTORE_PASSWORD',
             '--key-pass', 'env:KEY_PASSWORD', '--ks-key-alias', os.environ['KEY_ALIAS'], '--v4-signing-enabled', 'false', '--out', apk, aligned])
    signer = run([tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk])
    if 'certificate SHA-256 digest:' not in signer:
        raise ValueError('Missing verified signing certificate')
    check_badging(run([tools / 'aapt', 'dump', 'badging', apk]), info)
    run([tools / 'zipalign', '-c', '4', apk])
    certificate = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})', signer)
    if not certificate: raise ValueError('Missing signer identity')
    expected_signer = os.environ.get('EXPECTED_SIGNER_SHA256', '').lower()
    if expected_signer and not re.fullmatch(r'[0-9a-f]{64}', expected_signer): raise ValueError('Invalid expected signer digest')
    if expected_signer and certificate.group(1).lower() != expected_signer: raise ValueError('Signing certificate continuity mismatch')
    info['signer_sha256'] = certificate.group(1).lower()
    info['certificate_continuity'] = 'pinned' if expected_signer else 'first-use: explicitly accept this certificate before promotion'
    info['unsigned_apk_sha256'] = hashlib.sha256((incoming / 'app-unsigned.apk').read_bytes()).hexdigest()
    info['workflow_sha'] = os.environ['GITHUB_WORKFLOW_SHA']
    info['build_run_attempt'] = os.environ.get('GITHUB_RUN_ATTEMPT', '1')
    info['backend_protocol'] = embedded_backend
    info['build_run_id'] = str(run_id)
    info['apk_sha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
    info['physical_a4_validation'] = 'not established by CI'
    (destination / f'BUILD_INFO-{suffix}.json').write_text(json.dumps(info, indent=2) + '\n')
    (destination / f'SIGNER-{suffix}.txt').write_text(signer)
    sums = ''.join(f'{hashlib.sha256(x.read_bytes()).hexdigest()}  {x.name}\n' for x in sorted(destination.iterdir()))
    (destination / f'SHA256SUMS-{suffix}').write_text(sums)
    return info


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--incoming', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--tools', type=Path, required=True)
    p.add_argument('--expected-sha', required=True)
    a = p.parse_args()
    qualify(a.incoming, a.output, a.tools, a.expected_sha, os.environ['GITHUB_RUN_ID'])

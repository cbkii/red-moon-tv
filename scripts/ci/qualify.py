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


def qualify(incoming, destination, tools, expected_sha, run_id):
    if sorted(x.name for x in incoming.iterdir()) != ['BUILD_INFO.json', 'app-unsigned.apk']:
        raise ValueError('Unexpected build hand-off files')
    info = validate(json.loads((incoming / 'BUILD_INFO.json').read_text()))
    if info['source_sha'] != expected_sha:
        raise ValueError('Build metadata source does not match resolver source')
    check_badging(run([tools / 'aapt', 'dump', 'badging', incoming / 'app-unsigned.apk']), info)
    manifest = run([tools / 'aapt', 'dump', 'xmltree', incoming / 'app-unsigned.apk', 'AndroidManifest.xml'])
    if 'android.intent.category.LEANBACK_LAUNCHER' not in manifest:
        raise ValueError('Missing TV launcher entry')
    destination.mkdir(parents=True, exist_ok=False)
    suffix = group(info, run_id) if info['channel'] == 'testing' else info['version_name']
    prefix = 'TESTING-' if info['channel'] == 'testing' else ''
    apk = destination / f'red-moon-tv-{prefix}{suffix}.apk'
    with tempfile.TemporaryDirectory(prefix='red-moon-signing-') as temp:
        key = Path(temp) / 'release.jks'
        key.write_bytes(base64.b64decode(os.environ['KEYSTORE_BASE64'], validate=True))
        key.chmod(0o600)
        aligned = Path(temp) / 'aligned.apk'
        run([tools / 'zipalign', '-f', '4', incoming / 'app-unsigned.apk', aligned])
        run([tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'env:KEYSTORE_PASSWORD',
             '--key-pass', 'env:KEY_PASSWORD', '--ks-key-alias', os.environ['KEY_ALIAS'], '--out', apk, aligned])
    signer = run([tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk])
    if 'certificate SHA-256 digest:' not in signer:
        raise ValueError('Missing verified signing certificate')
    check_badging(run([tools / 'aapt', 'dump', 'badging', apk]), info)
    run([tools / 'zipalign', '-c', '4', apk])
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

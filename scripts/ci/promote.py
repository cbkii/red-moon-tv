#!/usr/bin/env python3
"""Promote an existing stable-package draft; never build or sign during promotion."""
import argparse
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path
from publish import api, gh
from envelope import validate

MARKER = '<!-- red-moon-tv-release-managed -->'
CHECKS = ('native_read', 'panel_write', 'restoration', 'scheduling', 'lifecycle', 'fades',
          'warmth', 'playback', 'stable_package_install', 'rollback')


def acceptance(record, info):
    if record.get('schema') != 1:
        raise ValueError('Unsupported acceptance schema')
    for name in ('source_sha', 'apk_sha256', 'package', 'signer_sha256'):
        if record.get(name) != info.get(name):
            raise ValueError(f'Acceptance {name} does not match this exact signed candidate')
    if not record.get('reviewer') or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', record.get('date', '')):
        raise ValueError('Acceptance requires reviewer and dated evidence')
    device = record.get('device', {})
    if device.get('model') != '50C350KP' or device.get('api') != 28 or device.get('abi') != 'armeabi-v7a' or not device.get('fingerprint'):
        raise ValueError('Acceptance must identify the physical A4 runtime')
    checks = record.get('checks', {})
    if any(checks.get(name, {}).get('status') != 'passed' or not checks[name].get('evidence') for name in CHECKS):
        raise ValueError('Every full-release gate needs passed evidence, not a boolean acknowledgement')
    return record


def promote(repo, release_id, record):
    release = api(f'repos/{repo}/releases/{release_id}')
    if not release['draft'] or MARKER not in (release.get('body') or ''):
        raise ValueError('Select a managed, unpublished stable-package draft')
    assets = release['assets']
    names = [a['name'] for a in assets]
    if len(names) != 4 or len(set(names)) != 4:
        raise ValueError('Expected exactly four closed candidate assets')
    metadata = [a for a in assets if a['name'].startswith('BUILD_INFO-') and a['name'].endswith('.json')]
    if len(metadata) != 1:
        raise ValueError('Missing unique candidate metadata')
    with tempfile.TemporaryDirectory(prefix='red-moon-promote-') as temp:
        root = Path(temp)
        for asset in assets:
            name = asset['name']
            if not re.fullmatch(r'[A-Za-z0-9.+_-]+', name):
                raise ValueError('Unsafe candidate asset name')
            target = root / name
            # Download by the verified release tag; no archive extraction or execution.
            gh('release', 'download', release['tag_name'], '--repo', repo, '--pattern', name, '--dir', str(root))
            actual = 'sha256:' + hashlib.sha256(target.read_bytes()).hexdigest()
            if actual != asset.get('digest'):
                raise ValueError('Remote candidate asset digest mismatch')
        info = validate(json.loads((root / metadata[0]['name']).read_text()))
        if info['channel'] != 'release' or info['package'] != 'com.cbkii.redmoontv':
            raise ValueError('TESTING/debug package cannot qualify stable')
        acceptance(record, info)
        apk = next(root.glob('*.apk'))
        if hashlib.sha256(apk.read_bytes()).hexdigest() != info['apk_sha256']:
            raise ValueError('Accepted APK was modified')
        sums = next(root.glob('SHA256SUMS-*'))
        expected = {p.name for p in root.iterdir() if p != sums}
        covered = set()
        for line in sums.read_text().splitlines():
            digest, name = line.split('  ', 1)
            if name not in expected or name in covered or hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
                raise ValueError('Invalid candidate checksum coverage')
            covered.add(name)
        if covered != expected:
            raise ValueError('Incomplete candidate checksum coverage')
    sha = info['source_sha']
    if release['target_commitish'] != sha or release['tag_name'] != 'v' + info['version_name']:
        raise ValueError('Candidate source/tag mismatch')
    tag = api(f'repos/{repo}/git/matching-refs/tags/{release["tag_name"]}')
    exact = [r for r in tag if r['ref'] == 'refs/tags/' + release['tag_name']]
    if exact and (exact[0]['object']['type'] != 'commit' or exact[0]['object']['sha'] != sha):
        raise ValueError('Existing tag is not the accepted source commit')
    default = api(f'repos/{repo}')['default_branch']
    if api(f'repos/{repo}/commits/{default}')['sha'] != sha:
        raise ValueError('Default branch changed; prepare/qualify a current candidate')
    # Re-read immediately before the sole external state transition.
    current = api(f'repos/{repo}/releases/{release_id}')
    if not current['draft'] or current['assets'] != assets:
        raise ValueError('Candidate changed during verification')
    notes = current['body'] + '\n\nPhysical acceptance (exact signed APK):\n```json\n' + json.dumps(record, indent=2) + '\n```\n'
    final = api(f'repos/{repo}/releases/{release_id}', 'PATCH', dict(draft=False, prerelease=False, body=notes, make_latest='true'))
    if final['draft'] or final['prerelease'] or final['tag_name'] != current['tag_name']:
        raise ValueError('Promotion state verification failed')
    print(final['html_url'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--release-id', required=True, type=int)
    parser.add_argument('--acceptance', required=True, type=Path)
    args = parser.parse_args()
    promote(os.environ['GITHUB_REPOSITORY'], args.release_id, json.loads(args.acceptance.read_text()))

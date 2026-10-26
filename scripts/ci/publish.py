#!/usr/bin/env python3
"""Draft-first publisher. Only manually authorised workflows call this module."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from envelope import validate, group, MARKER


def gh(*args, data=None):
    result = subprocess.run(['gh', *args], input=json.dumps(data) if data is not None else None,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    if result.returncode:
        raise RuntimeError(f'GitHub request failed ({result.returncode}): {result.stderr[-1200:]}')
    return result.stdout


def api(path, method='GET', data=None):
    args = ['api', path, '--method', method]
    if data is not None: args += ['--input', '-']
    result = gh(*args, data=data)
    return json.loads(result) if result.strip() else None


def publish(directory, repo, expected_sha, state, tag):
    files = sorted(directory.iterdir())
    infos = [p for p in files if p.name.startswith('BUILD_INFO-') and p.suffix == '.json']
    if len(infos) != 1 or any(not p.is_file() or p.is_symlink() for p in files):
        raise ValueError('Expected one closed qualified snapshot')
    info = validate(json.loads(infos[0].read_text()))
    if info['source_sha'] != expected_sha: raise ValueError('Wrong qualified source')
    apk = next(p for p in files if p.suffix == '.apk')
    if hashlib.sha256(apk.read_bytes()).hexdigest() != info['apk_sha256']: raise ValueError('APK hash mismatch')
    sumfile = next(p for p in files if p.name.startswith('SHA256SUMS-'))
    covered = set()
    for line in sumfile.read_text().splitlines():
        digest, name = line.split('  ', 1)
        if name not in {p.name for p in files} or name in covered: raise ValueError('Invalid checksum member')
        if hashlib.sha256((directory / name).read_bytes()).hexdigest() != digest: raise ValueError('Checksum mismatch')
        covered.add(name)
    if covered != {p.name for p in files if p != sumfile}: raise ValueError('Incomplete checksum coverage')
    testing = info['channel'] == 'testing'
    marker = MARKER if testing else '<!-- red-moon-tv-release-managed -->'
    if testing:
        tag = '000-testing-only'
        title = '000 Testing Only Version'
        state = 'draft'
    else:
        if tag != 'v' + info['version_name']: raise ValueError('Tag must equal v + APK versionName')
        title = 'Red Moon TV ' + tag
    releases = json.loads(gh('api', '--paginate', '--slurp', f'repos/{repo}/releases?per_page=100'))
    releases = [release for page in releases for release in page]
    matches = [r for r in releases if r['tag_name'] == tag]
    if len(matches) > 1: raise ValueError('Multiple releases use the requested tag')
    notes = marker + '\n\nNative A4 backlight controller with independent warmth scheduling.\n\n'
    notes += 'Physical A4 native-control, repeated-write and playback acceptance must be recorded separately from CI.\n'
    if matches:
        release = matches[0]
        if not release['draft'] or marker not in (release.get('body') or ''):
            raise ValueError('Existing published/unmanaged release is immutable')
        if not testing and release['target_commitish'] != expected_sha: raise ValueError('Existing draft targets a different source')
    else:
        # Never repoint an existing tag as a side effect of release creation.
        refs = api(f'repos/{repo}/git/matching-refs/tags/{tag}')
        exact = [r for r in refs if r['ref'] == 'refs/tags/' + tag]
        if exact and exact[0]['object']['sha'] != expected_sha: raise ValueError('Existing tag points elsewhere')
        release = api(f'repos/{repo}/releases', 'POST', dict(tag_name=tag, target_commitish=expected_sha,
                      name=title, body=notes, draft=True, prerelease=False))
    for p in files:
        current = api(f'repos/{repo}/releases/{release["id"]}')
        existing = [a for a in current['assets'] if a['name'] == p.name]
        digest = 'sha256:' + hashlib.sha256(p.read_bytes()).hexdigest()
        if existing:
            if existing[0].get('digest') != digest: raise ValueError('Existing asset differs; no overwrite performed')
        else:
            gh('release', 'upload', tag, str(p), '--repo', repo)
    current = api(f'repos/{repo}/releases/{release["id"]}')
    for p in files:
        asset = next(a for a in current['assets'] if a['name'] == p.name)
        if asset.get('digest') != 'sha256:' + hashlib.sha256(p.read_bytes()).hexdigest():
            raise ValueError('Remote asset digest does not match qualified bytes')
    if testing:
        groups = sorted([a for a in current['assets'] if re.fullmatch(r'BUILD_INFO-[0-9a-f]{12}-[0-9]+\.json', a['name'])], key=lambda a: a['created_at'], reverse=True)
        keep = {a['name'][11:-5] for a in groups[:2]}
        # Only remove this workflow's own older, complete snapshot groups.
        for old in groups[2:]:
            old_group = old['name'][11:-5]
            expected_names = {f'red-moon-tv-TESTING-{old_group}.apk', f'BUILD_INFO-{old_group}.json', f'SIGNER-{old_group}.txt', f'SHA256SUMS-{old_group}'}
            owned = [a for a in current['assets'] if a['name'] in expected_names]
            if {a['name'] for a in owned} != expected_names: continue
            for asset in owned: api(f'repos/{repo}/releases/assets/{asset["id"]}', 'DELETE')
        current = api(f'repos/{repo}/releases/{release["id"]}')
        notes += '\nNewest two TESTING snapshots (owner access required while draft):\n\n'
        for metadata in sorted([a for a in current['assets'] if a['name'].startswith('BUILD_INFO-')], key=lambda a: a['created_at'], reverse=True):
            snapshot = metadata['name'][11:-5]
            if snapshot not in keep: continue
            asset = next(a for a in current['assets'] if a['name'] == f'red-moon-tv-TESTING-{snapshot}.apk')
            notes += f'- [{snapshot}]({asset["browser_download_url"]}) · [metadata]({metadata["browser_download_url"]})\n'
    else:
        notes += f'\nSource: {expected_sha}\n\nAPK SHA-256: {info["apk_sha256"]}\n'
    final = api(f'repos/{repo}/releases/{release["id"]}', 'PATCH', dict(name=title, body=notes, draft=state == 'draft', prerelease=state == 'prerelease', make_latest=False))
    if final['draft'] != (state == 'draft') or final['tag_name'] != tag: raise ValueError('Release state verification failed')
    print(final['html_url'])


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--directory', type=Path, required=True)
    p.add_argument('--expected-sha', required=True)
    p.add_argument('--state', choices=('draft', 'prerelease', 'stable'), default='draft')
    p.add_argument('--tag', default='000-testing-only')
    a = p.parse_args()
    publish(a.directory, os.environ['GITHUB_REPOSITORY'], a.expected_sha, a.state, a.tag)

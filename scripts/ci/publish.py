#!/usr/bin/env python3
"""Draft-first publisher. Only manually authorised workflows call this module."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
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


def history(releases, repo):
    for previous in releases:
        if not any(mark in (previous.get('body') or '') for mark in (MARKER, '<!-- red-moon-tv-release-managed -->')): continue
        for asset in previous.get('assets', []):
            name = asset['name']
            if not re.fullmatch(r'BUILD_INFO-[A-Za-z0-9.+_-]+\.json', name): continue
            with tempfile.TemporaryDirectory(prefix='red-moon-history-') as temp:
                gh('release', 'download', previous['tag_name'], '--repo', repo, '--pattern', name, '--dir', temp)
                content = (Path(temp) / name).read_bytes()
                if asset.get('digest') != 'sha256:' + hashlib.sha256(content).hexdigest():
                    raise ValueError('Previous managed metadata digest mismatch')
                prior = validate(json.loads(content))
            yield previous, prior


def complete_testing_groups(assets):
    names = {a['name'] for a in assets}
    groups = []
    for asset in assets:
        if not re.fullmatch(r'BUILD_INFO-[0-9a-f]{12}-[0-9]+(?:-[0-9]+)?\.json', asset['name']): continue
        key = asset['name'][11:-5]
        required = {f'red-moon-tv-TESTING-{key}.apk', f'BUILD_INFO-{key}.json', f'SIGNER-{key}.txt', f'SHA256SUMS-{key}'}
        if required <= names: groups.append(asset)
    return sorted(groups, key=lambda a: a['created_at'], reverse=True)


def publish(directory, repo, expected_sha, state, tag):
    if state not in ('draft', 'prerelease'): raise ValueError('Stable publication requires promote.py and exact-artifact acceptance')
    files = sorted(directory.iterdir())
    infos = [p for p in files if p.name.startswith('BUILD_INFO-') and p.suffix == '.json']
    if len(infos) != 1 or any(not p.is_file() or p.is_symlink() for p in files):
        raise ValueError('Expected one closed qualified snapshot')
    info = validate(json.loads(infos[0].read_text()))
    if info['source_sha'] != expected_sha: raise ValueError('Wrong qualified source')
    suffix = infos[0].name[len('BUILD_INFO-'):-5]
    prefix = 'TESTING-' if info['channel'] == 'testing' else ''
    expected_names = {f'BUILD_INFO-{suffix}.json', f'red-moon-tv-{prefix}{suffix}.apk', f'SIGNER-{suffix}.txt', f'SHA256SUMS-{suffix}'}
    if {p.name for p in files} != expected_names: raise ValueError('Qualified snapshot must contain exactly its four managed files')
    signer = (directory / f'SIGNER-{suffix}.txt').read_text()
    cert = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})', signer)
    if not cert or cert.group(1).lower() != info.get('signer_sha256'): raise ValueError('Qualified signer identity mismatch')
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
    if testing and state != 'draft': raise ValueError('Permanent TESTING snapshots remain private drafts')
    if state == 'prerelease':
        default = api(f'repos/{repo}')['default_branch']
        from urllib.parse import quote
        if api(f'repos/{repo}/commits/{quote(default, safe="")}')['sha'] != expected_sha:
            raise ValueError('Prerelease source must be the current default-branch head')
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
    for previous, prior in history(releases, repo):
        if prior.get('signer_sha256') and prior['signer_sha256'] != info['signer_sha256']:
            raise ValueError('Signing certificate differs from previous managed snapshot')
        if info['channel'] == 'release' and prior['channel'] == 'release' and not previous['draft'] and info['version_code'] <= prior['version_code']:
            raise ValueError('Stable versionCode must increase over every published stable APK')
    matches = [r for r in releases if r['tag_name'] == tag]
    if testing and not matches:
        # GitHub may give an unpublished draft an untagged placeholder if a PATCH
        # omitted its intended identity. Recover only our uniquely managed draft.
        matches = [r for r in releases if r['draft'] and MARKER in (r.get('body') or '')]
    if len(matches) > 1: raise ValueError('Multiple releases use the requested tag')
    notes = marker + '\n\nNative A4 backlight controller with independent warmth scheduling.\n\n'
    notes += 'Physical A4 native-control, repeated-write and playback acceptance must be recorded separately from CI.\n'
    if state == 'prerelease':
        notes += ('\n## v' + info['version_name'] + ' prerelease\n\n'
            '- Native A4 backlight control, Day/Dusk/Dim caps and independently scheduled warmth.\n'
            '- Direct Boot, standby/process recovery, bounded effect checks and optional root transport.\n'
            '- Paired tvQuickActions shortcuts, temporary targets, snooze and conditional status.\n'
            '- ADB collectors and lifecycle/failure/automation validation scripts in the corresponding source.\n\n'
            '**Qualification:** this exact stable-package APK has not completed physical A4 acceptance. '
            'Both schedules start disabled; native writes require the restored probe acknowledgement, '
            'and repeated-write fades require a separate persistence check. Playback/HDMI/tuner/HDR '
            'warmth coverage, boot timing and runtime overhead remain device tests.\n\n'
            '**Install:** package `com.cbkii.redmoontv`, versionCode ' + str(info['version_code']) +
            '. TESTING/debug are separate packages; pause other variants before qualification. '
            'Grant overlay permission for warmth and select root explicitly only if needed.\n\n'
            '**Recovery:** Pause/Warmth Off, conditional Restore or native TV Picture Settings. '
            'Android downgrade restrictions apply; use a forward-version repair for an installed update.\n')
    if matches:
        release = matches[0]
        if not release['draft'] or marker not in (release.get('body') or ''):
            raise ValueError('Existing published/unmanaged release is immutable')
        if not testing and release['target_commitish'] != expected_sha: raise ValueError('Existing draft targets a different source')
        if testing and release['tag_name'] != tag:
            # Uploads resolve a release by tag. Repair identity before uploading any new group.
            release = api(f'repos/{repo}/releases/{release["id"]}', 'PATCH',
                          dict(tag_name=tag, target_commitish=expected_sha, name=title,
                               body=release.get('body') or marker, draft=True, prerelease=False, make_latest='false'))
            if release['tag_name'] != tag or not release['draft']:
                raise ValueError('Cannot recover permanent draft identity')
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
        groups = complete_testing_groups(current['assets'])
        keep = {a['name'][11:-5] for a in groups[:2]}
        # Only remove this workflow's own older, complete snapshot groups.
        for old in groups[2:]:
            old_group = old['name'][11:-5]
            expected_names = {f'red-moon-tv-TESTING-{old_group}.apk', f'BUILD_INFO-{old_group}.json', f'SIGNER-{old_group}.txt', f'SHA256SUMS-{old_group}'}
            owned = [a for a in current['assets'] if a['name'] in expected_names]
            if {a['name'] for a in owned} != expected_names: continue
            for asset in owned: api(f'repos/{repo}/releases/assets/{asset["id"]}', 'DELETE')
        current = api(f'repos/{repo}/releases/{release["id"]}')
        notes += '\nNewest two TESTING snapshots (owner access required; download the named files from Assets below):\n\n'
        for metadata in sorted([a for a in current['assets'] if a['name'].startswith('BUILD_INFO-')], key=lambda a: a['created_at'], reverse=True):
            snapshot = metadata['name'][11:-5]
            if snapshot not in keep: continue
            asset = next(a for a in current['assets'] if a['name'] == f'red-moon-tv-TESTING-{snapshot}.apk')
            # Draft browser URLs contain a temporary untagged ID which can change
            # during the final PATCH. File names remain stable across that update.
            notes += f'- {snapshot}: `{asset["name"]}` · `{metadata["name"]}`\n'
    else:
        notes += f'\nSource: {expected_sha}\n\nAPK SHA-256: {info["apk_sha256"]}\n'
        notes += f'\n[ADB validation](https://github.com/{repo}/blob/{expected_sha}/docs/A4-VALIDATION.md) · [Startup/continuity](https://github.com/{repo}/blob/{expected_sha}/docs/A4-STARTUP-CONTINUITY.md) · [tvQuickActions](https://github.com/{repo}/blob/{expected_sha}/docs/TVQUICKACTIONS.md)\n'
    if state == 'prerelease' and api(f'repos/{repo}/commits/{quote(default, safe="")}')['sha'] != expected_sha:
        raise ValueError('Default branch changed during qualification; candidate remains unpublished')
    final = api(f'repos/{repo}/releases/{release["id"]}', 'PATCH', dict(tag_name=tag, target_commitish=expected_sha, name=title, body=notes, draft=state == 'draft', prerelease=state == 'prerelease', make_latest='false'))
    if final['draft'] != (state == 'draft') or final.get('prerelease', False) != (state == 'prerelease') or final['tag_name'] != tag: raise ValueError('Release state verification failed')
    print(final['html_url'])


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--directory', type=Path, required=True)
    p.add_argument('--expected-sha', required=True)
    p.add_argument('--state', choices=('draft', 'prerelease'), default='draft')
    p.add_argument('--tag', default='000-testing-only')
    a = p.parse_args()
    publish(a.directory, os.environ['GITHUB_REPOSITORY'], a.expected_sha, a.state, a.tag)

#!/usr/bin/env python3
"""Resolve a maintainer's explicit release request against current default-branch source."""
import json
import os
import re
from pathlib import Path
from urllib.parse import quote
from envelope import validate
from resolve_testing import api


def resolve(event, name, repo, branch, workflow_sha):
    if event['repository']['full_name'] != repo or branch != event['repository']['default_branch']:
        raise ValueError('Releases must run from this repository default branch')
    if name == 'issue_comment':
        comment = event['comment']
        match = re.fullmatch(r'/prerelease v([1-9][0-9]{0,8})\s*', comment['body'])
        if not match or comment.get('author_association') not in ('OWNER', 'MEMBER', 'COLLABORATOR'):
            raise ValueError('An explicit maintainer /prerelease v<number> request is required')
        login = quote(comment['user']['login'], safe='')
        permission = api(f'repos/{repo}/collaborators/{login}/permission')['permission']
        if permission not in ('admin', 'maintain', 'write'):
            raise ValueError('Release requester must have repository write permission')
        version = match.group(1)
        code = int(version)
        operation = 'prerelease'
    elif name == 'workflow_dispatch':
        inputs = event['inputs']
        version = inputs['version_name']
        code = int(inputs['version_code'])
        operation = inputs['operation']
        if operation not in ('prepare', 'prerelease'):
            raise ValueError('Build operation must be prepare or prerelease')
    else:
        raise ValueError('Unsupported release trigger')
    default = quote(event['repository']['default_branch'], safe='')
    sha = api(f'repos/{repo}/commits/{default}')['sha']
    if sha != workflow_sha:
        raise ValueError('Default branch moved; request a new exact-source release')
    info = validate(dict(schema=1, source_sha=sha, channel='release', version_name=version,
                         version_code=code, package='com.cbkii.redmoontv'))
    return info, dict(source_sha=sha, version_name=version, version_code=str(code),
                      state='prerelease' if operation == 'prerelease' else 'draft')


if __name__ == '__main__':
    info, outputs = resolve(json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text()),
        os.environ['GITHUB_EVENT_NAME'], os.environ['GITHUB_REPOSITORY'],
        os.environ['GITHUB_REF_NAME'], os.environ['GITHUB_SHA'])
    Path('release-input').mkdir()
    Path('release-input/BUILD_INFO.json').write_text(json.dumps(info, indent=2) + '\n')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
        for key, value in outputs.items():
            out.write(f'{key}={value}\n')

#!/usr/bin/env python3
"""Resolve explicit requests to a same-repository immutable source commit."""
import json
import os
import re
import subprocess
from urllib.parse import quote


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True, timeout=45))


def resolve(event, name, repo, branch):
    source = ''
    number = None
    if name == 'workflow_dispatch':
        if branch != event['repository']['default_branch']:
            raise ValueError('Run workflow from the default branch; select APK source with source_to_build')
        source = event.get('inputs', {}).get('source_to_build', '').strip() or event['repository']['default_branch']
        if re.fullmatch(r'#?[1-9][0-9]*', source): number = int(source.lstrip('#'))
    elif name == 'issue_comment':
        comment = event['comment']
        if 'pull_request' not in event['issue'] or comment.get('author_association') not in ('OWNER', 'MEMBER', 'COLLABORATOR'):
            return None
        if not re.search(r'(^|\s)/testing-apk(\s|$)', comment['body']): return None
        number = event['issue']['number']
    else:
        return None
    if number is not None:
        pr = api(f'repos/{repo}/pulls/{number}')
        if pr['head']['repo']['full_name'] != repo: raise ValueError('Signed TESTING requests require a same-repository source')
        return dict(source_sha=pr['head']['sha'], source_ref=pr['head']['ref'], pr_number=number)
    commit = api(f'repos/{repo}/commits/{quote(source, safe="")}')
    return dict(source_sha=commit['sha'], source_ref=source, pr_number='')


if __name__ == '__main__':
    event = json.loads(open(os.environ['GITHUB_EVENT_PATH']).read())
    result = resolve(event, os.environ['GITHUB_EVENT_NAME'], os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_REF_NAME'])
    with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
        out.write('requested=' + ('true' if result else 'false') + '\n')
        if result:
            if not re.fullmatch(r'[0-9a-f]{40}', result['source_sha']): raise ValueError('Invalid resolved SHA')
            for key in ('source_sha', 'pr_number'): out.write(f'{key}={result[key]}\n')
